package com.eyue.reader.data.cloud

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

interface CloudTransport {
    fun ensureCollection(path: String)
    fun collections(path: String): List<String>
    fun metadata(path: String, maximum: Int = CLOUD_METADATA_LIMIT): ByteArray?
    fun putMetadata(path: String, bytes: ByteArray)
    fun versionedMetadata(path: String): CloudVersionedMetadata?
    /** Returns false only when another device changed the resource (HTTP 412). */
    fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean
    fun deleteCollection(path: String)
    fun cancel() {}
    fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit)
    fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long
}

data class CloudVersionedMetadata(val bytes: ByteArray, val etag: String)

/** Jianguoyun may return an unquoted opaque tag; preserve it for its If-Match comparison. */
internal fun cloudStrongETag(value: String?): String {
    val tag = requireNotNull(value) { "云端未提供版本校验，不能安全登记新书" }.trim(' ', '\t')
    require(!tag.startsWith("W/", true)) { "云端只提供弱版本校验，不能安全登记新书" }
    require(tag.isNotEmpty() && tag.length <= 512 && tag.all { it.code in 33..126 } && ',' !in tag) { "云端版本校验无效" }
    if (tag.startsWith('"')) require(tag.length >= 2 && tag.endsWith('"') && '"' !in tag.substring(1, tag.lastIndex)) { "云端版本校验无效" }
    else require('"' !in tag && tag != "*") { "云端版本校验无效" }
    return tag
}

class CloudHttpException(val code: Int, val retryable: Boolean, message: String) : IOException(message)

/** Only the configured origin receives the authorization header; redirects are never followed. */
class WebDavCloudTransport internal constructor(
    private val credentials: CloudCredentials,
    private val checkActive: () -> Unit,
    private val client: OkHttpClient,
    private val pause: (Long) -> Unit
) : CloudTransport {
    constructor(credentials: CloudCredentials, checkActive: () -> Unit = {}) : this(credentials, checkActive,
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
        { Thread.sleep(it) })
    private val authorization = Credentials.basic(credentials.account, credentials.password, Charsets.UTF_8)

    override fun ensureCollection(path: String) {
        execute(request(path, "MKCOL")).use { response ->
            if (response.code !in setOf(201, 200, 204, 405)) fail(response)
        }
    }

    override fun collections(path: String): List<String> {
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val bytes = execute(request(path, "PROPFIND", body).newBuilder().header("Depth", "1").build()).use { response ->
            if (response.code != 207) fail(response)
            readBounded(response, CLOUD_METADATA_LIMIT)
        }
        return parseCollections(bytes, path, credentials.rootUrl)
    }

    override fun metadata(path: String, maximum: Int): ByteArray? = execute(request(path, "GET")).use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) fail(response)
        readBounded(response, maximum)
    }

    override fun putMetadata(path: String, bytes: ByteArray) {
        require(bytes.size <= CLOUD_METADATA_LIMIT)
        execute(request(path, "PUT", bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))).use { response ->
            if (!response.isSuccessful) fail(response)
        }
    }

    override fun versionedMetadata(path: String): CloudVersionedMetadata? = execute(request(path, "GET")).use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) fail(response)
        val etag = cloudStrongETag(response.header("ETag"))
        CloudVersionedMetadata(readBounded(response, CLOUD_METADATA_LIMIT), etag)
    }

    override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean {
        require(bytes.size <= CLOUD_METADATA_LIMIT)
        val builder = request(path, "PUT", bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).newBuilder()
        if (etag == null) builder.header("If-None-Match", "*") else builder.header("If-Match", cloudStrongETag(etag))
        return execute(builder.build()).use { response ->
            if (response.code == 412) false else { if (!response.isSuccessful) fail(response); true }
        }
    }

    override fun deleteCollection(path: String) {
        require(path.matches(Regex("books/[A-Za-z0-9_-]{1,128}/"))) { "只能删除指定书籍的云端目录" }
        execute(request(path, "DELETE").newBuilder().header("Depth", "infinity").build()).use { response ->
            if (response.code == 207) throw CloudHttpException(207, false, "部分云端文件未能删除，请重试")
            if (response.code !in setOf(200, 204, 404)) fail(response)
        }
    }

    override fun cancel() { client.dispatcher.cancelAll() }

    override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) {
        require(offset >= 0 && bytes >= 0 && offset <= file.length() && bytes <= file.length() - offset)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (done < bytes) {
                        checkActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - done).toInt())
                        check(count > 0) { "待备份文件在上传时发生变化" }
                        sink.write(buffer, 0, count)
                        done += count
                        progress(done)
                    }
                }
            }
        }
        execute(request(path, "PUT", body)).use { response -> if (!response.isSuccessful) fail(response) }
    }

    override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long =
        execute(request(path, "GET")).use { response ->
            if (!response.isSuccessful) fail(response)
            val body = requireNotNull(response.body) { "云端备份内容为空" }
            require(body.contentLength() <= maximum) { "云端备份超过声明大小" }
            body.byteStream().use { input -> java.io.FileOutputStream(file, append).buffered().use { output ->
                CloudArchive.copy(input, output, maximum, checkActive, progress = progress)
            } }
        }

    private fun request(path: String, method: String, body: RequestBody? = null): Request {
        val relative = path.trimEnd('/')
        if (relative.isNotEmpty()) CloudArchive.safeName(relative)
        val url = credentials.rootUrl + relative + if (path.endsWith('/') && relative.isNotEmpty()) "/" else ""
        return Request.Builder().url(url).header("Authorization", authorization)
            .header("User-Agent", "ERead-Android-WebDAV").method(method, body).build()
    }

    private fun execute(request: Request): Response {
        // These requests read or replace the same resource. Retrying never appends file bytes.
        for (attempt in 0..2) {
            checkActive()
            val response = try { client.newCall(request).execute() }
            catch (error: IOException) {
                checkActive()
                if (error is CloudHttpException && !error.retryable) throw error
                if (attempt == 2) throw IOException("连接 WebDAV 网盘失败，请检查网络后重试", error)
                waitForRetry(1_000L shl attempt)
                continue
            }
            try { checkActive() } catch (error: Throwable) { response.close(); throw error }
            if (response.code !in RETRYABLE_CODES || attempt == 2) return response
            val delay = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1_000)
                ?: (1_000L shl attempt)
            response.close()
            waitForRetry(delay)
        }
        error("Unreachable retry state")
    }

    private fun waitForRetry(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            checkActive()
            val step = minOf(remaining, 250)
            pause(step)
            remaining -= step
        }
        checkActive()
    }

    private fun readBounded(response: Response, maximum: Int): ByteArray {
        val body = requireNotNull(response.body) { "云端响应为空" }
        require(body.contentLength() <= maximum) { "云端图书记录超过限制" }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            CloudArchive.copy(input, output, maximum.toLong(), checkActive)
            output.toByteArray()
        }
    }

    private fun fail(response: Response): Nothing = throw CloudHttpException(response.code,
        response.code in RETRYABLE_CODES, when (response.code) {
        401 -> "WebDAV 账户或密码错误"
        403 -> "WebDAV 网盘拒绝访问，请检查授权、流量或请求频率"
        404 -> "云端备份不存在"
        409 -> "云端备份目录不存在或冲突"
        429, 503 -> "WebDAV 网盘请求过于频繁或暂不可用，请稍后重试"
        507 -> "WebDAV 网盘可用空间不足"
        else -> "WebDAV 网盘请求失败（HTTP ${response.code}）"
    })

    companion object {
        const val ROOT = DEFAULT_WEBDAV_SERVER + "ERead/"
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)

        internal fun parseCollections(bytes: ByteArray, path: String, rootUrl: String = ROOT): List<String> {
            require(bytes.size <= CLOUD_METADATA_LIMIT)
            val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require('\u0000' !in xml && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "云端目录 XML 含有不支持的实体声明" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw org.xml.sax.SAXException("不允许外部 XML 实体") }
            }
            val document = builder.parse(org.xml.sax.InputSource(StringReader(xml)))
            val base = URI(rootUrl + path.trimEnd('/') + "/")
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            require(responses.length <= 10_001) { "云端图书数量超过限制" }
            return (0 until responses.length).mapNotNull { index ->
                val response = responses.item(index) as org.w3c.dom.Element
                if (response.getElementsByTagNameNS("DAV:", "collection").length == 0) return@mapNotNull null
                val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
                val resolved = try { base.resolve(href).normalize() } catch (_: IllegalArgumentException) { return@mapNotNull null }
                fun port(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
                if (!resolved.scheme.equals(base.scheme, true) || !resolved.host.equals(base.host, true) ||
                    port(resolved) != port(base) || !resolved.path.startsWith(base.path)) return@mapNotNull null
                val child = resolved.path.removePrefix(base.path).trimEnd('/')
                child.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            }.distinct()
        }
    }
}
