package com.eyue.reader.data

import android.app.NotificationManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.eyue.reader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class UpdateDownload(
    val token: UUID, val workId: UUID, val versionCode: Int, val versionName: String, val url: String
)

internal data class UpdateDownloadStatus(val download: UpdateDownload, val work: WorkInfo?, val bytes: Long) {
    val running get() = work?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    val ready get() = work?.state == WorkInfo.State.SUCCEEDED
    val total get() = work?.progress?.getLong("total", -1)?.takeIf { it > 0 && it >= bytes }
    val progress get() = total?.let { bytes.toFloat() / it }
    val phase get() = work?.progress?.getString("phase")
    val rate get() = work?.progress?.getLong("rate", 0)?.takeIf { it > 0 && work.state == WorkInfo.State.RUNNING }
    val error get() = work?.outputData?.getString("error")
}

/** A single transfer remains visible when the settings screen or activity is recreated. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AppUpdateDownloads private constructor(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
    private val manager get() = WorkManager.getInstance(this.context)
    private val current = MutableStateFlow(readDownload())
    private val commands = Mutex()
    val status = current.flatMapLatest { task ->
        if (task == null) flowOf(null)
        else manager.getWorkInfoByIdFlow(task.workId).map { work ->
            val bytes = work?.progress?.getLong("bytes", -1)?.takeIf { it >= 0 }
                ?: updatePartial(context, task.token).length()
            UpdateDownloadStatus(task, work, bytes)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun start(update: AppUpdateResult) = command {
        val url = requireNotNull(update.downloadUrl)
        require(update.available && update.published && safeUpdateLink(url, download = true))
        val old = current.value
        if (old != null) return@command
        enqueue(UpdateDownload(UUID.randomUUID(), UUID.randomUUID(), update.versionCode, update.versionName, url))
    }

    suspend fun pause(task: UpdateDownload) = command {
        if (current.value == task) manager.cancelWorkById(task.workId).await()
    }

    suspend fun resume(task: UpdateDownload) = command {
        if (current.value != task) return@command
        val state = manager.getWorkInfoByIdFlow(task.workId).first()?.state
        if (state == null || state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED)
            enqueue(task.copy(workId = UUID.randomUUID()))
    }

    suspend fun cancel(task: UpdateDownload) = command {
        if (current.value != task) return@command
        manager.cancelWorkById(task.workId).await()
        removeFiles(task.token)
        save(null)
    }

    suspend fun removeInstalledDownload() = command {
        if (current.value != null || preferences.getInt("versionCode", Int.MAX_VALUE) > BuildConfig.VERSION_CODE)
            return@command
        val token = runCatching { UUID.fromString(preferences.getString("token", null)) }.getOrNull()
        if (token != null) removeFiles(token)
        save(null)
    }

    private suspend fun removeFiles(token: UUID) {
        updateDownloadFiles.withLock {
            for (file in listOf(updateApk(context, token), updatePartial(context, token), updateEtag(context, token))) {
                check(!file.exists() || file.delete()) { "无法删除下载文件" }
            }
            context.getSystemService(NotificationManager::class.java).cancel(UPDATE_READY_NOTIFICATION)
        }
    }

    private suspend fun command(action: suspend () -> Unit) {
        commands.withLock { withContext(NonCancellable + Dispatchers.IO) { action() } }
    }

    private suspend fun enqueue(task: UpdateDownload) {
        val previous = current.value
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setId(task.workId)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .keepResultsForAtLeast(7, TimeUnit.DAYS)
            .setInputData(workDataOf("token" to task.token.toString(), "versionCode" to task.versionCode,
                "versionName" to task.versionName, "url" to task.url))
            .build()
        save(task)
        try { manager.enqueue(request).await() }
        catch (failure: Exception) {
            save(previous)
            throw failure
        }
    }

    private fun save(task: UpdateDownload?) {
        val editor = preferences.edit().clear()
        if (task != null) editor.putString("token", task.token.toString()).putString("workId", task.workId.toString())
            .putInt("versionCode", task.versionCode).putString("versionName", task.versionName).putString("url", task.url)
        check(editor.commit()) { "无法保存下载任务" }
        current.value = task
    }

    private fun readDownload(): UpdateDownload? = runCatching {
        val code = preferences.getInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        UpdateDownload(UUID.fromString(preferences.getString("token", null)), UUID.fromString(preferences.getString("workId", null)),
            code, requireNotNull(preferences.getString("versionName", null)), requireNotNull(preferences.getString("url", null)))
    }.getOrNull()

    companion object {
        private var instance: AppUpdateDownloads? = null

        @Synchronized
        fun get(context: Context): AppUpdateDownloads {
            val app = context.applicationContext
            return instance?.takeIf { it.context == app }
                ?: AppUpdateDownloads(app).also { instance = it }
        }
    }
}

internal val updateDownloadFiles = Mutex()
internal const val OPEN_UPDATE_DOWNLOAD = "com.eyue.reader.OPEN_UPDATE_DOWNLOAD"
internal const val UPDATE_READY_NOTIFICATION = 741_202
internal fun updateApk(context: Context, token: UUID) = File(context.filesDir, "updates/$token.apk")
internal fun updatePartial(context: Context, token: UUID) = File(context.filesDir, "updates/$token.partial.apk")
internal fun updateEtag(context: Context, token: UUID) = File(context.filesDir, "updates/$token.etag")
