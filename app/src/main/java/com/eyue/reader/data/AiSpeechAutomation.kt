package com.eyue.reader.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.eyue.reader.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager owns conversion after the screen or process disappears. Input contains no API secrets. */
internal object AiSpeechAutomation {
    private const val TAG = "eread-ai-conversion"
    private val planLock = Any()
    private val submissionLocks = Array(32) { Mutex() }
    private val conversionSlots = Semaphore(3)
    internal suspend fun <T> withConversionSlot(block: suspend () -> T): T = conversionSlots.withPermit { block() }
    private const val BOOK_TAG = "$TAG-book:"
    private const val TITLE_TAG = "$TAG-title:"
    private const val PLAN_TAG = "$TAG-plan:"
    internal const val KEY_BOOK = "book"
    internal const val KEY_PROFILE = "profile"
    internal const val KEY_CONFIG = "configuration"
    internal const val KEY_VERSION = "contentVersion"
    internal const val KEY_ROLES = "roles"
    internal const val KEY_PLAN = "plan"
    internal const val KEY_TITLE = "title"
    internal const val KEY_ERROR = "error"

    internal fun request(context: Context, book: Book, config: AiSpeechConfig, chapters: Set<Int>?): OneTimeWorkRequest {
        require(book.mode == LibraryMode.NOVEL && !book.cloudOnly) { "请先下载小说正文" }
        val selected = chapters?.sorted() ?: book.chapters.indices.toList()
        require(selected.isNotEmpty() && selected.all { it in book.chapters.indices }) { "请选择有效章节" }
        val planId = UUID.randomUUID().toString()
        val commonRoles = if (config.rolePromptEnabled) LibraryRepository(context).load().commonVoiceRoles else emptyList()
        writePlan(context, planId, selected.toSet())
        return OneTimeWorkRequestBuilder<AiSpeechConversionWorker>()
            .setInputData(workDataOf(KEY_BOOK to book.id, KEY_TITLE to book.title.take(600),
                KEY_PROFILE to config.id, KEY_CONFIG to AiSpeechCache.configurationIdentity(config), KEY_PLAN to planId,
                KEY_VERSION to book.contentVersion, KEY_ROLES to AiSpeechCache.rolesDigest(book, config, commonRoles)))
            .addTag(TAG).addTag(BOOK_TAG + book.id).addTag(TITLE_TAG + book.title.take(600)).addTag(PLAN_TAG + planId)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
    }

    suspend fun enqueue(context: Context, book: Book, config: AiSpeechConfig, chapters: Set<Int>? = null) = withContext(Dispatchers.IO) {
        submissionLocks[(book.id.hashCode() and Int.MAX_VALUE) % submissionLocks.size].withLock {
            val manager = WorkManager.getInstance(context)
            val name = "$TAG-${book.id}"
            val existing = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).firstOrNull { !it.state.isFinished }
            if (existing != null) existing.id else {
                val request = request(context, book, config, chapters)
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
                request.id
            }
        }
    }

    fun observe(context: Context): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)
    fun bookId(info: WorkInfo): String? = info.tags.firstOrNull { it.startsWith(BOOK_TAG) }?.removePrefix(BOOK_TAG)
    fun cancel(context: Context, bookId: String) { WorkManager.getInstance(context).cancelAllWorkByTag(BOOK_TAG + bookId) }
    suspend fun cancelAndAwait(context: Context, bookId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(BOOK_TAG + bookId).await()
    }
    suspend fun cancelAllAndAwait(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG).await() }

    fun progress(info: WorkInfo): AiSpeechBatchProgress? {
        val book = bookId(info) ?: return null
        val data = if (info.state.isFinished && info.outputData.keyValueMap.isNotEmpty()) info.outputData else info.progress
        return AiSpeechBatchProgress(bookId = book,
            title = data.getString(KEY_TITLE) ?: info.tags.firstOrNull { it.startsWith(TITLE_TAG) }?.removePrefix(TITLE_TAG).orEmpty(),
            chapterIndex = data.getInt("chapter", -1), chapterTitle = data.getString("chapterTitle").orEmpty(),
            completed = data.getInt("completed", 0), total = data.getInt("total", 0),
            running = !info.state.isFinished, error = data.getString(KEY_ERROR) ?:
                if (info.state == WorkInfo.State.FAILED) "AI 语音转化失败，请重试" else null,
            cancelled = info.state == WorkInfo.State.CANCELLED,
            chapterCompleted = data.getInt("chapterCompleted", 0), chapterTotal = data.getInt("chapterTotal", 0))
    }

    suspend fun progress(context: Context, info: WorkInfo): AiSpeechBatchProgress? = withContext(Dispatchers.IO) {
        val summary = progress(info) ?: return@withContext null
        val plan = synchronized(planLock) { runCatching {
            val id = requireNotNull(info.tags.firstOrNull { it.startsWith(PLAN_TAG) }?.removePrefix(PLAN_TAG))
            JSONObject(planFile(context, id).readFully().toString(Charsets.UTF_8))
        }.getOrNull() }
        fun indices(array: JSONArray?): Set<Int> = if (array == null) emptySet() else
            (0 until array.length()).map { array.getInt(it) }.filter { it >= 0 }.toSet()
        val selected = indices(plan?.optJSONArray("chapters"))
        val completed = indices(plan?.optJSONArray("completed")).intersect(selected)
        val chapters = plan?.optJSONObject("progress")?.let { rows -> rows.keys().asSequence().mapNotNull { key ->
            val index = key.toIntOrNull()?.takeIf { it in selected } ?: return@mapNotNull null
            val row = rows.optJSONObject(key) ?: return@mapNotNull null
            index to AiSpeechChapterProgress(row.optInt("completed"), row.optInt("total"))
        }.toMap() }.orEmpty()
        summary.copy(selectedChapters = selected, completedChapters = completed, chapterProgress = chapters,
            completed = if (plan != null) completed.size else summary.completed,
            total = if (summary.total > 0) summary.total else selected.size)
    }

    internal fun writePlan(context: Context, id: String, selected: Set<Int>, completed: Set<Int> = emptySet(),
                           chapterProgress: Map<Int, AiSpeechChapterProgress> = emptyMap()): Unit = synchronized(planLock) {
        val plan = planFile(context, id)
        plan.baseFile.parentFile?.mkdirs()
        val stream = plan.startWrite()
        try {
            val progress = JSONObject().apply { chapterProgress.forEach { (index, value) ->
                put(index.toString(), JSONObject().put("completed", value.completed).put("total", value.total))
            } }
            stream.write(JSONObject().put("chapters", JSONArray(selected.sorted())).put("completed", JSONArray(completed.sorted()))
                .put("progress", progress).toString().toByteArray(Charsets.UTF_8))
            plan.finishWrite(stream)
        } catch (error: Throwable) { plan.failWrite(stream); throw error }
    }

    internal fun progressData(progress: AiSpeechBatchProgress): Data = workDataOf(KEY_TITLE to progress.title.take(600),
        "chapter" to progress.chapterIndex, "chapterTitle" to progress.chapterTitle.take(600),
        "completed" to progress.completed, "total" to progress.total,
        "chapterCompleted" to progress.chapterCompleted, "chapterTotal" to progress.chapterTotal,
        KEY_ERROR to progress.error?.take(600))

    internal fun planFile(context: Context, id: String): AtomicFile {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "AI 语音转化任务无效" }
        return AtomicFile(File(context.filesDir, "ai-speech-jobs/$id.json"))
    }
}

/** Reload book/profile identities for every execution; rescheduled work reuses completed cached chunks. */
internal suspend fun runAiSpeechConversion(context: Context, input: Data,
    loadBook: (String) -> Book? = { id -> LibraryRepository(context).load().books.find { it.id == id } },
    loadConfig: (String) -> AiSpeechConfig? = { id -> AiSpeechConfigStore(context).profiles().find { it.id == id } },
    synthesize: (suspend (AiSpeechConfig, String, File) -> Unit)? = null,
    onProgress: suspend (AiSpeechBatchProgress) -> Unit): AiSpeechBatchProgress = AiSpeechAutomation.withConversionSlot { withContext(Dispatchers.IO) {
    val bookId = requireNotNull(input.getString(AiSpeechAutomation.KEY_BOOK)) { "AI 语音任务缺少书籍" }
    val book = requireNotNull(loadBook(bookId)) { "书籍已删除，转化已停止" }
    require(book.contentVersion == input.getInt(AiSpeechAutomation.KEY_VERSION, -1)) { "书籍正文或目录已修改，请重新选择章节转化" }
    val profile = requireNotNull(input.getString(AiSpeechAutomation.KEY_PROFILE)) { "AI 语音任务缺少配置" }
    val config = requireNotNull(loadConfig(profile)) { "AI 语音配置已删除，请重新转化" }
    require(AiSpeechCache.configurationIdentity(config) == input.getString(AiSpeechAutomation.KEY_CONFIG)) { "AI 语音配置已修改，请重新转化" }
    val commonRoles = if (config.rolePromptEnabled) LibraryRepository(context).load().commonVoiceRoles else emptyList()
    require(input.getString(AiSpeechAutomation.KEY_ROLES) == AiSpeechCache.rolesDigest(book, config, commonRoles)) {
        "角色音色已修改，请重新转化"
    }
    val plan = AiSpeechAutomation.planFile(context, requireNotNull(input.getString(AiSpeechAutomation.KEY_PLAN)))
    val array = JSONObject(plan.readFully().toString(Charsets.UTF_8)).getJSONArray("chapters")
    val selected = (0 until array.length()).map { array.getInt(it) }.toSet()
    AiSpeechBatch(context, commonRoles, synthesize).generate(book, config, selected, onProgress)
} }

class AiSpeechConversionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var progress = AiSpeechBatchProgress(inputData.getString(AiSpeechAutomation.KEY_BOOK).orEmpty(),
        inputData.getString(AiSpeechAutomation.KEY_TITLE).orEmpty())
    private var lastReportAt = 0L
    private var lastStartedChapters: Set<Int> = emptySet()
    private var lastCompletedChapters: Set<Int> = emptySet()
    private var filteredPlanSaved = false

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val channel = "ai-speech-conversion"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "AI 语音转化", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stage = if (progress.total == 0) "排队等待转化" else "${progress.completed}/${progress.total} 章 · ${progress.chapterTitle}" +
            if (progress.chapterTotal > 0) " (${progress.chapterCompleted}/${progress.chapterTotal})" else ""
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("转化《${progress.title}》AI 语音")
            .setContentText(stage).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, ((progress.fraction ?: 0f) * 100).toInt().coerceIn(0, 100), progress.fraction == null)
            .addAction(android.R.drawable.ic_delete, "中断转化", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!ConsentStore(applicationContext).isAccepted()) return@withContext Result.retry()
        try {
            setForeground(foregroundInfo())
            val final = runAiSpeechConversion(applicationContext, inputData) { updated ->
                val now = SystemClock.elapsedRealtime()
                progress = updated
                if (!updated.running || !filteredPlanSaved && updated.selectedChapters.isNotEmpty() ||
                    updated.chapterProgress.keys != lastStartedChapters || updated.completedChapters != lastCompletedChapters || now - lastReportAt >= 500) {
                    if (updated.selectedChapters.isNotEmpty()) {
                        inputData.getString(AiSpeechAutomation.KEY_PLAN)?.let {
                            AiSpeechAutomation.writePlan(applicationContext, it, updated.selectedChapters, updated.completedChapters, updated.chapterProgress)
                        }
                        filteredPlanSaved = true
                    }
                    lastStartedChapters = updated.chapterProgress.keys.toSet()
                    lastCompletedChapters = updated.completedChapters
                    lastReportAt = now
                    setProgress(AiSpeechAutomation.progressData(updated))
                    setForeground(foregroundInfo())
                }
            }
            Result.success(AiSpeechAutomation.progressData(final))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) {
            progress = progress.copy(running = false, error = error.message ?: "AI 语音转化失败")
            Result.failure(AiSpeechAutomation.progressData(progress))
        }
    }
}
