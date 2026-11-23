package com.eyue.reader.data.cloud

import com.eyue.reader.data.Annotation
import com.eyue.reader.data.Book
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ShelfGroup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CloudOperationProgress(val stage: String, val fraction: Float? = null)
data class CloudArchivePart(val name: String, val bytes: Long)
data class CloudBookInfo(
    val book: Book,
    val annotations: List<Annotation>,
    val settings: ReaderSettings,
    val group: ShelfGroup?,
    val backupAt: Long,
    val revision: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val parts: List<CloudArchivePart>,
    val readingSeconds: Long = 0,
    val contentIdentity: String? = null,
    val sourceSha256: String? = null,
    val cloudCover: String? = null,
    val parentGroup: ShelfGroup? = null
)
data class CloudRestoredBook(
    val book: Book,
    val annotations: List<Annotation>,
    val settings: ReaderSettings,
    val group: ShelfGroup?,
    val backupAt: Long,
    val readingSeconds: Long = 0,
    val parentGroup: ShelfGroup? = null
)

const val DEFAULT_WEBDAV_SERVER = "https://dav.jianguoyun.com/dav/"

internal fun normalizeWebDavServer(value: String): String {
    val input = value.trim()
    require(input.length <= 4096 && input.none { it.code < 32 || it == '\\' }) { "服务器地址无效" }
    val url = input.toHttpUrlOrNull()
    require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "请输入完整的 HTTP 或 HTTPS 服务器地址，不要包含账户、参数或片段"
    }
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString().also {
        require(it.length <= 4096) { "服务器地址过长" }
    }
}

data class S3Location(val bucket: String, val region: String, val prefix: String = "ERead", val pathStyle: Boolean = true,
    val cos: Boolean = false) {
    init {
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) { "存储桶名称无效" }
        require(region.matches(Regex("[a-z0-9-]{1,64}"))) { "区域名称无效" }
        require(prefix.length <= 512 && prefix.split('/').all { it != "." && it != ".." } &&
            prefix.none { it.isISOControl() || it == '\\' }) { "备份路径无效" }
    }
}

class CloudCredentials(val account: String, val password: String, serverUrl: String = DEFAULT_WEBDAV_SERVER,
    val s3: S3Location? = null) {
    val serverUrl: String = normalizeWebDavServer(serverUrl)
    // Keep the original provider's history and resumable parts without migrating sensitive files.
    val identity: String get() = s3?.let { "${if (it.cos) "cos" else "s3"}\n$serverUrl\n$account\n${it.bucket}\n${it.prefix.trim('/')}" }
        ?: if (serverUrl == DEFAULT_WEBDAV_SERVER) account else "$serverUrl\n$account"
    val rootUrl: String get() = serverUrl + "ERead/"
    init {
        require(account.isNotBlank() && account.length <= 320 && ':' !in account && '\r' !in account && '\n' !in account) { if (s3 == null) "请输入 WebDAV 账户" else "请输入 Access Key" }
        require(password.isNotBlank() && password.length <= 4096) { if (s3 == null) "请输入 WebDAV 密码" else "请输入 Secret Key" }
    }
    override fun toString(): String = "CloudCredentials(redacted)"
}
