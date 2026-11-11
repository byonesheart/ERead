package com.eyue.reader.data.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.eyue.reader.data.ConsentStore
import com.eyue.reader.data.LibraryMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class WebDavImportEntry(val name: String, val directory: Boolean, val bytes: Long?)
internal data class WebDavImportProfile(val id: String, val account: String, val server: String)
internal data class WebDavReadyImport(val id: UUID, val file: File, val mode: LibraryMode)

internal fun supportedWebDavBook(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("epub", "mobi", "pdf", "txt")

internal fun validWebDavImportName(name: String): Boolean = name.isNotBlank() && name !in setOf(".", "..") &&
    name.toByteArray().size <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }

internal class WebDavImportProfiles(private val context: Context) {
    private val preferences = context.getSharedPreferences("webdav-import-profiles", Context.MODE_PRIVATE)
    private fun ids(): Set<String> = preferences.getStringSet("profiles", emptySet()).orEmpty().toSet()

    fun read(id: String): CloudCredentials? {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "网盘配置无效" }
        return EncryptedCloudCredentialStore(context, id).read()
    }

    fun list(): List<WebDavImportProfile> = ids().mapNotNull { id ->
        runCatching { read(id) }.getOrNull()?.let { WebDavImportProfile(id, it.account, it.serverUrl) }
    }.sortedBy { it.server }

    fun save(credentials: CloudCredentials): WebDavImportProfile {
        val id = MessageDigest.getInstance("SHA-256").digest(credentials.identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        EncryptedCloudCredentialStore(context, id).write(credentials)
        check(preferences.edit().putStringSet("profiles", ids() + id).commit()) { "网盘配置保存失败" }
        return WebDavImportProfile(id, credentials.account, credentials.serverUrl)
    }

    fun remove(profile: WebDavImportProfile) {
        EncryptedCloudCredentialStore(context, profile.id).clear()
        check(preferences.edit().putStringSet("profiles", ids() - profile.id).commit()) { "网盘配置移除失败" }
    }
}

internal object WebDavImports {
    const val TAG = "webdav-book-import"
    const val NAME = "name"
    const val MODE = "mode"
    const val FRACTION = "fraction"
    const val ERROR = "error"
    private fun root(context: Context) = File(context.filesDir, "webdav-imports")
    fun directory(context: Context, id: UUID) = File(root(context), id.toString())

    suspend fun enqueue(context: Context, profile: WebDavImportProfile, folder: List<String>,
        entries: List<WebDavImportEntry>, mode: LibraryMode) = withContext(Dispatchers.IO) {
        val requests = entries.distinctBy { it.name }.map { entry ->
            require(!entry.directory && supportedWebDavBook(entry.name)) { "请选择图书文件" }
            val segments = folder + entry.name
            require(segments.all(::validWebDavImportName)) { "网盘文件路径无效" }
            val path = JSONArray(segments).toString()
            require(path.toByteArray().size <= 6000) { "网盘文件路径过长" }
            // A private WebDAV server can be reachable without a validated Internet connection.
            // Let the request decide reachability and use WorkManager backoff on I/O failures.
            OneTimeWorkRequestBuilder<WebDavBookDownloadWorker>()
                .setInputData(workDataOf("profile" to profile.id, "path" to path, MODE to mode.name))
                .addTag(TAG).build()
        }
        if (requests.isNotEmpty()) WorkManager.getInstance(context).enqueue(requests).await()
    }

    fun ready(context: Context): List<WebDavReadyImport> = root(context).listFiles().orEmpty().mapNotNull { directory ->
        runCatching {
            val id = UUID.fromString(directory.name)
            val marker = File(directory, "ready.json")
            if (!marker.isFile) return@mapNotNull null
            val info = JSONObject(AtomicFile(marker).readFully().decodeToString())
            val name = info.getString(NAME)
            require(validWebDavImportName(name) && supportedWebDavBook(name))
            val file = File(directory, name)
            if (file.isFile && file.length() > 0) WebDavReadyImport(id, file, LibraryMode.valueOf(info.getString(MODE)))
            else null
        }.getOrNull()
    }.sortedBy { it.file.lastModified() }
}

class WebDavBookDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!ConsentStore(applicationContext).isAccepted()) return@withContext Result.retry()
        val directory = WebDavImports.directory(applicationContext, id)
        val part = File(directory, "download.part")
        var transport: WebDavCloudTransport? = null
        try {
            val profile = requireNotNull(inputData.getString("profile"))
            val credentials = requireNotNull(WebDavImportProfiles(applicationContext).read(profile)) { "请重新填写网盘连接信息" }
            val values = JSONArray(requireNotNull(inputData.getString("path")))
            val path = (0 until values.length()).map(values::getString)
            val name = path.last()
            require(path.all(::validWebDavImportName) && supportedWebDavBook(name)) { "网盘文件路径无效" }
            val mode = LibraryMode.valueOf(requireNotNull(inputData.getString(WebDavImports.MODE)))
            val output = File(directory, name)
            val marker = AtomicFile(File(directory, "ready.json"))
            if (marker.baseFile.isFile && output.isFile && output.length() > 0) return@withContext Result.success()
            check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
            val work = currentCoroutineContext()
            val connection = WebDavCloudTransport(credentials) { work.ensureActive() }
            transport = connection
            val watcher = launch {
                try { while (true) { delay(200); work.ensureActive() } }
                finally { connection.cancel() }
            }
            try {
                setProgress(workDataOf(WebDavImports.NAME to name, WebDavImports.MODE to mode.name))
                val foreground = try {
                    setForeground(notification(name, null))
                    true
                } catch (_: IllegalStateException) {
                    work.ensureActive()
                    false
                }
                var lastUpdate = 0L
                connection.downloadImport(path, part) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 500) {
                        lastUpdate = now
                        val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                        setProgressAsync(workDataOf(WebDavImports.NAME to name, WebDavImports.MODE to mode.name,
                            WebDavImports.FRACTION to (fraction ?: -1f)))
                        if (foreground) setForegroundAsync(notification(name, fraction))
                    }
                }
                work.ensureActive()
                check(part.renameTo(output)) { "无法保存下载的图书" }
                val stream = marker.startWrite()
                try {
                    stream.write(JSONObject().put(WebDavImports.NAME, name).put(WebDavImports.MODE, mode.name)
                        .toString().toByteArray())
                    marker.finishWrite(stream)
                } catch (failure: Throwable) { marker.failWrite(stream); throw failure }
                Result.success()
            } finally { watcher.cancel() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val retryable = if (failure is CloudHttpException) failure.retryable else failure is IOException
            if (retryable && runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(WebDavImports.ERROR to (failure.message ?: "网盘图书下载失败").take(350)))
        } finally {
            transport?.cancel()
            part.delete()
            if (!File(directory, "ready.json").isFile) {
                directory.listFiles().orEmpty().forEach { it.delete() }
                directory.delete()
            }
        }
    }

    private fun notification(name: String, fraction: Float?): ForegroundInfo {
        val channel = "webdav-import"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "网盘图书导入", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(name).setContentText("下载图书")
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "取消下载", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }
}
