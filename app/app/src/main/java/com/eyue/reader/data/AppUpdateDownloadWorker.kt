package com.eyue.reader.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eyue.reader.BuildConfig
import com.eyue.reader.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppUpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val notifications get() = applicationContext.getSystemService(NotificationManager::class.java)
    private val title get() = "E阅 " + inputData.getString("versionName").orEmpty()
    private val noticeId get() = 741_201
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = runCatching { UUID.fromString(inputData.getString("token")) }.getOrNull()
            ?: return@withContext Result.failure()
        val url = inputData.getString("url").orEmpty()
        val version = inputData.getInt("versionCode", 0)
        if (!safeUpdateLink(url, download = true) || version <= BuildConfig.VERSION_CODE) return@withContext Result.failure()
        updateDownloadFiles.withLock {
            val partial = updatePartial(applicationContext, token)
            val apk = updateApk(applicationContext, token)
            val etag = updateEtag(applicationContext, token)
            try {
                setForeground(getForegroundInfo())
                if (!apk.isFile) {
                    check(partial.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    val retrying = runAttemptCount > 0
                    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                        .proxySelector(updateProxySelector(applicationContext))
                        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
                        .apply { if (retrying) protocols(listOf(Protocol.HTTP_1_1)) }.build()
                    val transfer = UpdateApkTransfer(client, partial, etag, if (retrying) 2L * 1024 * 1024 else null)
                    var reportedAt = -500L
                    var reportedBytes = 0L
                    var endpoint = ""
                    transfer.download(url, onConnecting = { host ->
                        if (host != endpoint) {
                            endpoint = host
                            setProgress(workDataOf("phase" to "正在连接 $host"))
                        }
                    }) { bytes, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - reportedAt >= 500 || bytes == total) {
                            val rate = if (reportedAt >= 0 && now > reportedAt)
                                (bytes - reportedBytes).coerceAtLeast(0) * 1000 / (now - reportedAt) else 0L
                            setProgress(workDataOf("bytes" to bytes, "total" to total, "rate" to rate))
                            post(noticeId, progressNotification(bytes, total))
                            reportedAt = now
                            reportedBytes = bytes
                        }
                    }
                    if (!runCatching { validUpdateApk(applicationContext, partial, version) }.getOrDefault(false)) {
                        partial.delete()
                        etag.delete()
                        return@withLock Result.failure(workDataOf("error" to "安装包校验失败，请重新下载"))
                    }
                    currentCoroutineContext().ensureActive()
                    Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    etag.delete()
                }
                val complete = notification().setContentText("下载完成，点击安装")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true).build()
                post(UPDATE_READY_NOTIFICATION, complete)
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateDownloadException) {
                currentCoroutineContext().ensureActive()
                if (failure.retryable && runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf("error" to failure.message))
            } catch (_: Exception) {
                Result.failure(workDataOf("error" to "下载未完成，请检查网络或剩余空间后继续"))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressNotification(0, -1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(noticeId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(noticeId, notification)
    }

    private fun notification(): NotificationCompat.Builder {
        val channel = "app-updates"
        notifications.createNotificationChannel(NotificationChannel(channel, "应用更新", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(applicationContext, MainActivity::class.java).setAction(OPEN_UPDATE_DOWNLOAD)
        val click = PendingIntent.getActivity(applicationContext, UPDATE_READY_NOTIFICATION, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(applicationContext, channel).setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(click)
    }

    private fun progressNotification(bytes: Long, total: Long): android.app.Notification {
        val percent = if (total > 0) (bytes * 100 / total).toInt() else -1
        return notification()
            .setContentText(when {
                percent == 100 -> "正在校验安装包"
                percent >= 0 -> "已下载 $percent%"
                bytes > 0 -> "已下载 ${Formatter.formatShortFileSize(applicationContext, bytes)}"
                else -> "正在连接下载服务器"
            })
            .setProgress(100, percent.coerceAtLeast(0), percent < 0).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "暂停",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
    }

    private fun post(id: Int, notification: android.app.Notification) {
        try { notifications.notify(id, notification) } catch (_: SecurityException) { }
    }
}

internal class UpdateDownloadException(message: String, val retryable: Boolean = false, cause: IOException? = null) :
    IOException(message, cause)

private fun updateProxySelector(context: Context): ProxySelector {
    val system = ProxySelector.getDefault()
    return object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val proxy = context.getSystemService(ConnectivityManager::class.java)?.defaultProxy
            val host = proxy?.host
            if (proxy != null && !host.isNullOrBlank() && proxy.port in 1..65535 && proxy.pacFileUrl == Uri.EMPTY) {
                if (proxy.exclusionList.any { updateProxyExcludes(uri.host.orEmpty(), it) }) return listOf(Proxy.NO_PROXY)
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, proxy.port)))
            }
            return system?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
        }

        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
            system?.connectFailed(uri, address, failure)
        }
    }
}

internal fun updateProxyExcludes(host: String, pattern: String): Boolean {
    val normalized = pattern.trim().lowercase(java.util.Locale.ROOT)
    return normalized.isNotEmpty() && Regex(normalized.split('*').joinToString(".*", transform = Regex::escape),
        RegexOption.IGNORE_CASE).matches(host)
}

private inline fun <T> readUpdateNetwork(endpoint: String, action: () -> T): T = try {
    action()
} catch (failure: IOException) {
    val reason = when (failure) {
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "安全连接失败"
        else -> "连接中断"
    }
    throw UpdateDownloadException("$endpoint $reason，请检查代理或使用浏览器下载", retryable = true, cause = failure)
}

internal class UpdateApkTransfer(private val client: OkHttpClient, private val partial: File, private val etagFile: File,
    private val rangeSize: Long? = null) {
    private val activeCall = AtomicReference<Call?>()

    suspend fun download(url: String, onConnecting: suspend (String) -> Unit = {},
        progress: suspend (Long, Long) -> Unit): Unit = coroutineScope {
        val stop = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { activeCall.get()?.cancel() }
        }
        try {
            var etag = if (etagFile.isFile) etagFile.readText().takeIf { it.startsWith('"') } else null
            var offset = partial.length().takeIf { etag != null && it in 1 until MAX_APK_BYTES } ?: 0
            var source = url
            repeat(256) {
                var response = open(source, offset, etag, rangeSize, onConnecting)
                if (source != url && response.code in setOf(401, 403, 410)) {
                    response.close()
                    response = open(url, offset, etag, rangeSize, onConnecting)
                }
                if (offset > 0 && response.code == 416) {
                    response.close()
                    offset = 0
                    etag = null
                    response = open(url, 0, null, null, onConnecting)
                }
                val segment = response.use { receive(it, offset, etag, progress) }
                if (segment.total < 0 || segment.bytes == segment.total) return@coroutineScope
                offset = segment.bytes
                etag = segment.etag
                source = response.request.url.toString()
            }
            throw UpdateDownloadException("服务器返回的下载分段过多，请改用浏览器下载")
        } finally {
            stop.cancel()
        }
    }

    private suspend fun open(source: String, offset: Long, etag: String?, limit: Long?,
        onConnecting: suspend (String) -> Unit): Response {
        var url = source
        repeat(6) {
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .header("User-Agent", "ERead/${BuildConfig.VERSION_NAME}")
            if (offset > 0 || limit != null) request.header("Range", "bytes=$offset-${limit?.let { offset + it - 1 } ?: ""}")
            if (offset > 0) request.header("If-Range", requireNotNull(etag))
            val call = client.newCall(request.build())
            activeCall.set(call)
            currentCoroutineContext().ensureActive()
            val endpoint = if (call.request().url.host == "github.com") "GitHub" else "GitHub 文件服务器"
            onConnecting(endpoint)
            val response = readUpdateNetwork(endpoint) { call.execute() }
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.use { it.header("Location")?.let(it.request.url::resolve) }
            if (next == null || !next.isHttps || next.port != 443 || next.username.isNotEmpty() || next.password.isNotEmpty() ||
                (next.host !in ASSET_HOSTS && !safeUpdateLink(next.toString(), download = true))) {
                throw UpdateDownloadException("GitHub 返回了无效的下载地址")
            }
            url = next.toString()
        }
        throw UpdateDownloadException("GitHub 下载重定向次数过多")
    }

    private data class Segment(val bytes: Long, val total: Long, val etag: String?)

    private suspend fun receive(response: Response, offset: Long, etag: String?, progress: suspend (Long, Long) -> Unit): Segment {
        if (response.code != 200 && response.code != 206) {
            val code = response.code
            val retryable = code == 408 || code == 429 || code in 500..599 ||
                (response.request.url.host in ASSET_HOSTS && code in setOf(401, 403, 410))
            throw UpdateDownloadException("GitHub 下载失败（HTTP $code），请检查网络后继续", retryable)
        }
        val body = response.body ?: throw UpdateDownloadException("GitHub 返回了空安装包")
        val ranged = response.code == 206
        val append = ranged && offset > 0
        val newEtag = response.header("ETag")?.takeIf { it.startsWith('"') }
        var endExclusive = body.contentLength()
        val total = if (ranged) {
            val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
            val start = range?.groupValues?.get(1)?.toLongOrNull()
            val end = range?.groupValues?.get(2)?.toLongOrNull()
            val size = range?.groupValues?.get(3)?.toLongOrNull()
            if (response.request.header("Range") == null || start != offset || size == null || end == null ||
                end < offset || end >= size || (append && newEtag != null && newEtag != etag)) {
                etagFile.delete()
                throw UpdateDownloadException("续传位置校验失败，请继续下载以重新获取安装包", retryable = true)
            }
            if (end < size - 1 && newEtag == null && etag == null)
                throw UpdateDownloadException("文件服务器未提供续传校验信息，请改用浏览器下载")
            endExclusive = end + 1
            size
        } else endExclusive
        if (total > MAX_APK_BYTES) throw UpdateDownloadException("安装包超过下载大小限制")
        var bytes = if (append) offset else 0L
        java.io.FileOutputStream(partial, append).use { output ->
            if (!append) {
                if (newEtag == null) etagFile.delete() else etagFile.writeText(newEtag)
            }
            progress(bytes, total)
            val input = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = readUpdateNetwork("GitHub 文件服务器") { input.read(buffer) }
                if (count < 0) break
                bytes += count
                if (bytes > MAX_APK_BYTES || (endExclusive >= 0 && bytes > endExclusive))
                    throw UpdateDownloadException("下载内容长度不正确")
                output.write(buffer, 0, count)
                progress(bytes, total)
            }
        }
        if (bytes == 0L || (endExclusive >= 0 && endExclusive != bytes))
            throw UpdateDownloadException("安装包下载不完整，请继续下载", retryable = true)
        return Segment(bytes, total, if (append) etag else newEtag)
    }

    companion object {
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com")
    }
}

@Suppress("DEPRECATION")
internal fun validUpdateApk(context: Context, file: File, version: Int): Boolean {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
    val installed = pm.getPackageInfo(context.packageName, flags)
    val code = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode else apk.versionCode.toLong()
    fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val expected = signers(installed)?.toSet().orEmpty()
    return apk.packageName == context.packageName && code == version.toLong() &&
        expected.isNotEmpty() && signers(apk)?.toSet() == expected
}
