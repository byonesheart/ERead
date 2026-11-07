package com.eyue.reader.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppUpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val notifications get() = applicationContext.getSystemService(NotificationManager::class.java)
    private val title get() = "E阅 " + inputData.getString("versionName").orEmpty()
    private val noticeId get() = 741_201
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

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
                    val transfer = UpdateApkTransfer(client, partial, etag)
                    var reportedAt = 0L
                    transfer.download(url) { bytes, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - reportedAt >= 500 || bytes == total) {
                            val percent = if (total > 0) (bytes * 100 / total).toInt() else -1
                            setProgress(workDataOf("percent" to percent))
                            post(noticeId, progressNotification(percent))
                            reportedAt = now
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
                Result.failure(workDataOf("error" to failure.message))
            } catch (_: Exception) {
                Result.failure(workDataOf("error" to "下载未完成，请检查网络或剩余空间后继续"))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressNotification(-1)
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

    private fun progressNotification(percent: Int) = notification()
        .setContentText(if (percent < 0) "正在下载更新" else "已下载 $percent%")
        .setProgress(100, percent.coerceAtLeast(0), percent < 0).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(android.R.drawable.ic_media_pause, "暂停",
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .build()

    private fun post(id: Int, notification: android.app.Notification) {
        try { notifications.notify(id, notification) } catch (_: SecurityException) { }
    }
}

internal class UpdateDownloadException(message: String) : IOException(message)

internal class UpdateApkTransfer(private val client: OkHttpClient, private val partial: File, private val etagFile: File) {
    private val activeCall = AtomicReference<Call?>()

    suspend fun download(url: String, progress: suspend (Long, Long) -> Unit) = coroutineScope {
        val stop = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { activeCall.get()?.cancel() }
        }
        try {
            val etag = if (etagFile.isFile) etagFile.readText().takeIf { it.startsWith('"') } else null
            var offset = partial.length().takeIf { etag != null && it in 1 until MAX_APK_BYTES } ?: 0
            var response = open(url, offset, etag)
            if (offset > 0 && response.code == 416) {
                response.close()
                offset = 0
                response = open(url, 0, null)
            }
            response.use { receive(it, offset, etag, progress) }
        } finally {
            stop.cancel()
        }
    }

    private suspend fun open(source: String, offset: Long, etag: String?): Response {
        var url = source
        repeat(6) {
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .header("User-Agent", "ERead/${BuildConfig.VERSION_NAME}")
            if (offset > 0) request.header("Range", "bytes=$offset-").header("If-Range", requireNotNull(etag))
            val call = client.newCall(request.build())
            activeCall.set(call)
            currentCoroutineContext().ensureActive()
            val response = call.execute()
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

    private suspend fun receive(response: Response, offset: Long, etag: String?, progress: suspend (Long, Long) -> Unit) {
        if (response.code != 200 && response.code != 206)
            throw UpdateDownloadException("GitHub 下载失败（HTTP ${response.code}），请检查网络后继续")
        val body = response.body ?: throw UpdateDownloadException("GitHub 返回了空安装包")
        val append = response.code == 206
        val newEtag = response.header("ETag")?.takeIf { it.startsWith('"') }
        val total = if (append) {
            val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
            val start = range?.groupValues?.get(1)?.toLongOrNull()
            val end = range?.groupValues?.get(2)?.toLongOrNull()
            val size = range?.groupValues?.get(3)?.toLongOrNull()
            if (offset <= 0 || start != offset || size == null || end != size - 1 ||
                (newEtag != null && newEtag != etag)) {
                etagFile.delete()
                throw UpdateDownloadException("续传位置校验失败，请继续下载以重新获取安装包")
            }
            size
        } else body.contentLength()
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
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > MAX_APK_BYTES || (total >= 0 && bytes > total))
                    throw UpdateDownloadException("下载内容长度不正确")
                output.write(buffer, 0, count)
                progress(bytes, total)
            }
        }
        if (bytes == 0L || (total >= 0 && total != bytes)) throw UpdateDownloadException("安装包下载不完整，请继续下载")
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
