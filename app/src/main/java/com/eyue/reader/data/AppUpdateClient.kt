package com.eyue.reader.data

import com.eyue.reader.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppUpdateResult(
    val available: Boolean,
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val downloadUrl: String?,
    val releaseUrl: String,
    val published: Boolean = true,
    val mirrors: List<AppUpdateMirror> = emptyList()
)

data class AppUpdateMirror(val name: String, val url: String, val extractionCode: String = "")

/** A manual, anonymous request; no book, device identifier or configured service credentials are sent. */
class AppUpdateClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun check(currentVersionCode: Int): AppUpdateResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(UPDATE_MANIFEST_URL)
            .header("Accept", "application/json").header("User-Agent", "ERead/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("检查更新失败，请检查网络后重试", e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code == 404) AppUpdateResult(false, BuildConfig.VERSION_NAME, currentVersionCode,
                            "仓库尚未发布更新文件，可前往 GitHub 查看。", null, APP_RELEASES_URL, published = false)
                        else {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                403, 429 -> "GitHub 暂时限制请求，请稍后重试"
                                else -> "检查更新失败（HTTP ${it.code}）"
                            })
                            val body = it.body ?: throw IOException("更新文件为空")
                            if (body.contentLength() > MAX_UPDATE_BYTES) throw IOException("更新文件过大")
                            val bytes = body.byteStream().use { input -> input.readBytesBounded() }
                            parseAppUpdate(bytes.toString(Charsets.UTF_8), currentVersionCode)
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is IOException) failure else IOException("更新文件格式不正确，请前往 GitHub 查看", failure))
                }
            }
        })
    }
}

internal const val UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/byonesheart/ERead/main/update.json"
internal const val APP_RELEASES_URL = "https://github.com/byonesheart/ERead/releases"
private const val MAX_UPDATE_BYTES = 512 * 1024

internal fun parseAppUpdate(text: String, currentVersionCode: Int): AppUpdateResult {
    val json = JSONObject(text)
    val code = json.getInt("versionCode")
    val name = json.getString("versionName").trim()
    require(code > 0 && name.isNotBlank() && name.length <= 80) { "更新版本号无效" }
    val release = json.optString("releaseUrl", APP_RELEASES_URL).let {
        require(safeUpdateLink(it, download = false)) { "更新页面地址无效" }; it
    }
    val download = json.optString("downloadUrl").trim().takeIf { it.isNotEmpty() }?.also {
        require(safeUpdateLink(it, download = true)) { "更新下载地址无效" }
    }
    val notes = json.opt("changelog").let { value ->
        if (value is org.json.JSONArray) (0 until value.length()).joinToString("\n") { "• ${value.getString(it)}" }
        else json.optString("changelog")
    }
    val mirrors = json.optJSONArray("mirrors")?.let { links ->
        require(links.length() <= 8) { "更新下载链接过多" }
        List(links.length()) { index ->
            val link = links.getJSONObject(index)
            val title = link.getString("name").trim()
            val url = link.getString("url").trim()
            val password = link.optString("extractionCode").trim()
            require(title.isNotBlank() && title.length <= 40 && password.length <= 40 && safeMirrorLink(url)) { "网盘下载地址无效" }
            AppUpdateMirror(title, url, password)
        }
    }.orEmpty()
    return AppUpdateResult(code > currentVersionCode, name, code, notes, download, release, mirrors = mirrors)
}

private fun safeMirrorLink(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme == "https" && uri.host in setOf("pan.baidu.com", "pan.quark.cn") &&
        uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
        uri.path.startsWith("/s/") && uri.path.length > 3 && !uri.path.split('/').any { it == "." || it == ".." }
}.getOrDefault(false)

private fun safeUpdateLink(value: String, download: Boolean): Boolean = runCatching {
    val uri = java.net.URI(value)
    val path = uri.path.orEmpty()
    uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null && uri.port == -1 &&
        uri.rawFragment == null && uri.rawQuery == null && !path.split('/').any { it == "." || it == ".." } &&
        if (download) path.startsWith("/byonesheart/ERead/releases/download/") && path.endsWith(".apk")
        else path == "/byonesheart/ERead/releases" || path.startsWith("/byonesheart/ERead/releases/tag/")
}.getOrDefault(false)

private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > MAX_UPDATE_BYTES) throw IOException("更新文件过大")
        output.write(buffer, 0, count)
    }
}
