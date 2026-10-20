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
    val sourceSha256: String? = null
)
data class CloudRestoredBook(
    val book: Book,
    val annotations: List<Annotation>,
    val settings: ReaderSettings,
    val group: ShelfGroup?,
    val backupAt: Long,
    val readingSeconds: Long = 0
)

const val DEFAULT_WEBDAV_SERVER = "https://dav.jianguoyun.com/dav/"

internal fun normalizeWebDavServer(value: String): String {
    val input = value.trim()
    require(input.length <= 4096 && input.none { it.code < 32 || it == '\\' }) { "服务器地址无效" }
    val url = input.toHttpUrlOrNull()
    require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "请输入完整的 HTTP 或 HTTPS WebDAV 服务器地址，不要包含账户、参数或片段"
    }
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString().also {
        require(it.length <= 4096) { "服务器地址过长" }
    }
}

class CloudCredentials(val account: String, val password: String, serverUrl: String = DEFAULT_WEBDAV_SERVER) {
    val serverUrl: String = normalizeWebDavServer(serverUrl)
    // Keep the original provider's history and resumable parts without migrating sensitive files.
    val identity: String get() = if (serverUrl == DEFAULT_WEBDAV_SERVER) account else "$serverUrl\n$account"
    val rootUrl: String get() = serverUrl + "ERead/"
    init {
        require(account.isNotBlank() && account.length <= 320 && ':' !in account && '\r' !in account && '\n' !in account) { "请输入 WebDAV 账户" }
        require(password.isNotBlank() && password.length <= 4096) { "请输入 WebDAV 密码" }
    }
    override fun toString(): String = "CloudCredentials(redacted)"
}
