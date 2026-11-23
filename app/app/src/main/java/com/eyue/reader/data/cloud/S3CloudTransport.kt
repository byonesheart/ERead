package com.eyue.reader.data.cloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** S3 Signature V4, using the existing resumable archive parts as individual objects. */
internal class S3CloudTransport(
    private val credentials: CloudCredentials,
    private val checkActive: () -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val uncachedReads: Boolean = false
) : CloudTransport {
    private val location = requireNotNull(credentials.s3)
    private val prefix = location.prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val bucketUrl = credentials.serverUrl.toHttpUrl().newBuilder().apply {
        if (location.pathStyle) addPathSegment(location.bucket)
        else host("${location.bucket}.${credentials.serverUrl.toHttpUrl().host}")
    }.build()

    private fun key(path: String): String {
        if (path.isNotEmpty()) CloudArchive.safeName(path.trimEnd('/'))
        return prefix + path
    }

    private fun url(path: String) = bucketUrl.newBuilder().apply {
        key(path).split('/').forEach { addPathSegment(it) }
    }.build()

    override fun ensureCollection(path: String) {
        if (path.isEmpty()) list("", delimiter = "/", limit = 1)
    }

    private data class Listing(val keys: List<String>, val folders: List<String>, val next: String?)
    private fun list(path: String, token: String? = null, delimiter: String? = null, limit: Int = 1000): Listing {
        val url = bucketUrl.newBuilder().addQueryParameter("list-type", "2")
            .addQueryParameter("prefix", key(path)).addQueryParameter("max-keys", limit.toString()).apply {
                delimiter?.let { addQueryParameter("delimiter", it) }
                token?.let { addQueryParameter("continuation-token", it) }
            }.build()
        return execute(signed("GET", url)).use { response ->
            requireSuccess(response)
            val xml = readBounded(response, CLOUD_METADATA_LIMIT).decodeToString()
            require('\u0000' !in xml && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) {
                "S3 目录 XML 含有不支持的实体声明"
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(xml)))
            fun items(parent: String, child: String): List<String> {
                val nodes = doc.getElementsByTagNameNS("*", parent)
                return (0 until nodes.length).map { index ->
                    (nodes.item(index) as Element).getElementsByTagNameNS("*", child).item(0)?.textContent
                        ?: throw IOException("S3 列表格式无效")
                }
            }
            val truncated = doc.getElementsByTagNameNS("*", "IsTruncated").item(0)?.textContent == "true"
            val next = doc.getElementsByTagNameNS("*", "NextContinuationToken").item(0)?.textContent
            require(!truncated || !next.isNullOrEmpty()) { "S3 分页令牌缺失" }
            Listing(items("Contents", "Key"), items("CommonPrefixes", "Prefix"), next.takeIf { truncated })
        }
    }

    override fun collections(path: String): List<String> {
        val result = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = list(path, token, "/")
            for (folder in page.folders) {
                require(folder.startsWith(key(path))) { "S3 返回了备份路径外的数据" }
                val name = folder.removePrefix(key(path)).trimEnd('/')
                if (name.isNotEmpty() && '/' !in name) result += name
            }
            token = page.next
            require(token == null || seen.add(token)) { "S3 返回了重复的分页令牌" }
        } while (token != null)
        return result.toList()
    }

    internal fun objectKeys(path: String, limit: Int = 1000): List<String> = list(path, limit = limit).keys.map {
        require(it.startsWith(key(path))) { "云端对象超出备份路径" }
        it.removePrefix(prefix)
    }

    internal fun createCosObject(path: String, bytes: ByteArray): Boolean =
        execute(signed("PUT", url(path), bytes.toRequestBody(), sha256(bytes),
            mapOf("x-cos-forbid-overwrite" to "true"))).use {
            if (it.code == 409) false else { requireSuccess(it); true }
        }

    internal fun testCosCreateOnly() {
        val path = ".connection-test-${UUID.randomUUID()}"
        val bytes = UUID.randomUUID().toString().toByteArray()
        var created = false
        var failure: Throwable? = null
        try {
            require(createCosObject(path, bytes)) { "COS 测试文件创建失败" }
            created = true
            require(!createCosObject(path, "changed".toByteArray())) {
                "COS 防覆盖未生效：请使用从未开启版本控制的专用存储桶，且不要给备份目录设置自动删除规则"
            }
            require(metadata(path, bytes.size)?.contentEquals(bytes) == true) { "COS 读写测试不一致" }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            if (created) try { deleteObject(path) } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    override fun metadata(path: String, maximum: Int): ByteArray? =
        execute(signed("GET", url(path))).use {
            if (it.code == 404) null else { requireSuccess(it); readBounded(it, maximum) }
        }

    override fun readMetadata(path: String, etag: String?): CloudMetadataResponse =
        execute(signed("GET", url(path), headers = etag?.let { mapOf("If-None-Match" to cloudStrongETag(it)) }.orEmpty())).use {
            when (it.code) {
                404 -> CloudMetadataResponse(null)
                304 -> CloudMetadataResponse(null, etag, true)
                else -> { requireSuccess(it); CloudMetadataResponse(readBounded(it, CLOUD_METADATA_LIMIT), it.header("ETag")) }
            }
        }

    override fun versionedMetadata(path: String): CloudVersionedMetadata? =
        execute(signed("GET", url(path))).use {
            if (it.code == 404) null else {
                requireSuccess(it)
                CloudVersionedMetadata(readBounded(it, CLOUD_METADATA_LIMIT), cloudStrongETag(it.header("ETag")))
            }
        }

    override fun putMetadata(path: String, bytes: ByteArray) {
        execute(signed("PUT", url(path), bytes.toRequestBody(), sha256(bytes))).use { requireSuccess(it) }
    }

    override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean =
        execute(signed("PUT", url(path), bytes.toRequestBody(), sha256(bytes),
            if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to cloudStrongETag(etag)))).use {
            if (it.code == 412 || it.code == 409) false else { requireSuccess(it); true }
        }

    override fun deleteCollection(path: String) {
        require(path.matches(Regex("books/[A-Za-z0-9_-]{1,128}/"))) { "只能删除指定书籍的云端目录" }
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = list(path, token)
            for (objectKey in page.keys) {
                require(objectKey.startsWith(key(path))) { "S3 删除范围无效" }
                delete(objectKey.removePrefix(prefix))
            }
            token = page.next
            require(token == null || seen.add(token)) { "S3 返回了重复的分页令牌" }
        } while (token != null)
    }

    private fun delete(path: String) = deleteObject(path)

    internal fun deleteObject(path: String) {
        execute(signed("DELETE", url(path))).use { if (it.code != 404) requireSuccess(it) }
    }

    fun testWriteAccess() {
        val path = ".connection-test-${UUID.randomUUID()}"
        val content = UUID.randomUUID().toString().toByteArray()
        var created = false
        var failure: Throwable? = null
        try {
            require(putVersionedMetadata(path, content, null)) { "S3 测试文件创建失败" }
            created = true
            require(!putVersionedMetadata(path, content, null)) { "此服务不支持 S3 条件写入，无法安全同步" }
            require(metadata(path, content.size)?.contentEquals(content) == true) { "S3 读写测试不一致" }
            val version = requireNotNull(versionedMetadata(path))
            require(putVersionedMetadata(path, "updated".toByteArray(), version.etag)) { "S3 条件更新失败" }
            require(!putVersionedMetadata(path, content, version.etag)) { "此服务不支持 S3 版本条件更新" }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (created) try { delete(path) } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) {
        require(offset >= 0 && bytes >= 0 && offset <= file.length() && bytes <= file.length() - offset)
        val digest = MessageDigest.getInstance("SHA-256")
        fun copy(consume: (ByteArray, Int) -> Unit) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (done < bytes) {
                    checkActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - done).toInt())
                    require(count > 0) { "待备份文件发生变化" }
                    consume(buffer, count)
                    done += count
                }
            }
        }
        copy { buffer, count -> digest.update(buffer, 0, count) }
        val body = object : RequestBody() {
            override fun contentLength() = bytes
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) {
                var done = 0L
                copy { buffer, count -> sink.write(buffer, 0, count); done += count; progress(done) }
            }
        }
        execute(signed("PUT", url(path), body, digest.digest().hex())).use { requireSuccess(it) }
    }

    override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long =
        execute(signed("GET", url(path))).use {
            requireSuccess(it)
            val body = requireNotNull(it.body) { "S3 返回空响应" }
            require(body.contentLength() <= maximum) { "S3 文件超过声明大小" }
            body.byteStream().use { input -> java.io.FileOutputStream(file, append).buffered().use { output ->
                CloudArchive.copy(input, output, maximum, checkActive, progress = progress)
            } }
        }

    override fun cancel() = client.dispatcher.cancelAll()

    private fun execute(request: Request): Response {
        for (attempt in 0..2) {
            checkActive()
            val response = try { client.newCall(request).execute() }
            catch (failure: IOException) {
                checkActive()
                if (attempt == 2) {
                    val reason = when (failure) {
                        is java.net.UnknownHostException -> "域名解析失败，请检查 Endpoint 与 Bucket 是否重复"
                        is javax.net.ssl.SSLException -> "TLS 连接失败，请检查服务器域名与证书"
                        is java.net.SocketTimeoutException -> "连接或传输超时"
                        is java.net.ConnectException -> "无法连接服务器"
                        else -> "网络连接中断"
                    }
                    throw IOException("连接 S3 失败：$reason", failure)
                }
                waitForRetry(attempt)
                continue
            }
            try { checkActive() } catch (failure: Throwable) { response.close(); throw failure }
            if (response.code !in setOf(429, 500, 502, 503, 504) || attempt == 2) return response
            response.close()
            waitForRetry(attempt)
        }
        error("S3 重试次数已用尽")
    }

    private fun waitForRetry(attempt: Int) {
        repeat(4 shl attempt) { checkActive(); Thread.sleep(250) }
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val details = runCatching {
            val xml = response.peekBody(16 * 1024L).string()
            require('\u0000' !in xml && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(org.xml.sax.helpers.DefaultHandler())
            }.parse(InputSource(StringReader(xml)))
            fun value(tag: String) = doc.getElementsByTagNameNS("*", tag).item(0)?.textContent.orEmpty()
            listOf(value("Code"), value("Message"), value("RequestId"))
        }.getOrDefault(listOf("", "", ""))
        fun safe(value: String, maximum: Int) = value.replace(credentials.password, "[已隐藏]")
            .replace(credentials.account, "[已隐藏]").filterNot { it.isISOControl() }.take(maximum)
        val code = safe(details[0], 100)
        val operation = when {
            response.request.url.queryParameter("list-type") != null -> "列举存储桶"
            response.request.method == "PUT" -> "写入备份"
            response.request.method == "DELETE" -> "删除备份或清理测试文件"
            else -> "读取备份"
        }
        val reason = if (code == "AccessDenied") when {
            details[1].contains("SignatureDoesNotMatch", ignoreCase = true) -> "SignatureDoesNotMatch"
            details[1].contains("Request has expired", ignoreCase = true) -> "RequestExpired"
            else -> code
        } else code
        val hint = when (reason) {
            "AccessDenied" -> "检查此子用户的桶权限、备份路径权限及拒绝策略"
            "InvalidAccessKeyId" -> "检查 Access Key 是否为有效的 SecretId"
            "SignatureDoesNotMatch" -> "核对 Secret Key、Endpoint 和 Region；也可能是服务签名兼容问题"
            "RequestTimeTooSkewed", "RequestExpired" -> "请开启手机的自动日期与时间后重试"
            "AuthorizationHeaderMalformed", "InvalidRegion" -> "检查 Region 是否与存储桶和服务要求一致"
            else -> "请检查地址、区域、密钥和存储桶权限"
        }
        val requestId = safe(details[2].ifBlank { response.header("x-cos-request-id") ?: response.header("x-amz-request-id").orEmpty() }, 160)
        val message = buildString {
            append("S3 请求失败（HTTP ${response.code}）\n操作：$operation")
            if (code.isNotBlank()) append("\n错误码：$code")
            if (details[1].isNotBlank()) append("\n服务端说明：${safe(details[1], 500)}")
            if (requestId.isNotBlank()) append("\n请求编号：$requestId")
            append("\n$hint")
        }
        throw CloudHttpException(response.code, response.code == 429 || response.code >= 500, message)
    }

    private fun readBounded(response: Response, maximum: Int): ByteArray {
        val body = requireNotNull(response.body) { "S3 返回空响应" }
        require(body.contentLength() <= maximum) { "S3 元数据过大" }
        return body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            CloudArchive.copy(input, output, maximum.toLong(), checkActive)
            output.toByteArray()
        }
    }

    private fun signed(method: String, url: HttpUrl, body: RequestBody? = null,
        hash: String = sha256(byteArrayOf()), headers: Map<String, String> = emptyMap()): Request =
        signS3Request(credentials, method, url, body, hash,
            if (uncachedReads && method == "GET") headers + ("Cache-Control" to "no-cache, no-store") else headers, Instant.now())
}

internal fun signS3Request(credentials: CloudCredentials, method: String, url: HttpUrl, body: RequestBody?,
    payloadHash: String, headers: Map<String, String>, now: Instant): Request {
    val region = requireNotNull(credentials.s3).region
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
    val date = stamp.take(8)
    val host = url.host + if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
    val canonicalHeaders = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to stamp)
    headers.forEach { (name, value) -> canonicalHeaders[name.lowercase(java.util.Locale.ROOT)] = value.trim() }
    val signedHeaders = canonicalHeaders.keys.joinToString(";")
    val query = (0 until url.querySize).map { s3Encode(url.queryParameterName(it)) + "=" +
        s3Encode(url.queryParameterValue(it).orEmpty()) }.sorted().joinToString("&")
    val path = url.pathSegments.joinToString("/", prefix = "/") { s3Encode(it) }
    val signedUrl = url.newBuilder().encodedPath(path).encodedQuery(query.takeIf(String::isNotEmpty)).build()
    val canonical = listOf(method, path, query,
        canonicalHeaders.entries.joinToString("") { "${it.key}:${it.value}\n" }, signedHeaders, payloadHash).joinToString("\n")
    val scope = "$date/$region/s3/aws4_request"
    val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha256(canonical.toByteArray())}"
    fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray())
    }
    val key = hmac(hmac(hmac(hmac(("AWS4" + credentials.password).toByteArray(), date), region), "s3"), "aws4_request")
    return Request.Builder().url(signedUrl).method(method, body).apply {
        canonicalHeaders.forEach { (name, value) -> header(name, value) }
        header("Authorization", "AWS4-HMAC-SHA256 Credential=${credentials.account}/$scope, SignedHeaders=$signedHeaders, Signature=${hmac(key, toSign).hex()}")
    }.build()
}

private fun s3Encode(value: String) = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 255
        if (code in 65..90 || code in 97..122 || code in 48..57 || code in listOf(45, 46, 95, 126)) append(code.toChar())
        else append("%%%02X".format(code))
    }
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
