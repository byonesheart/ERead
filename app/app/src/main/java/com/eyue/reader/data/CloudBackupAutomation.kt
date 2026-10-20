package com.eyue.reader.data

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.eyue.reader.MainActivity
import com.eyue.reader.data.cloud.CloudBackupService
import com.eyue.reader.data.cloud.CloudOperationProgress
import com.eyue.reader.data.cloud.CloudHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun cloudBackupBooks(state: LibraryState): List<Book> =
    if (!state.cloudAutoBackup) emptyList() else state.books.filter { it.cloudEnabled && !it.cloudOnly }

internal fun cloudBackupHistoryState(state: LibraryState, backupAt: (String) -> Long): LibraryState =
    state.copy(books = state.books.map { book ->
        val time = backupAt(book.id)
        if (time > 0) book.copy(cloudEnabled = true, cloudBackupAt = maxOf(time, book.cloudBackupAt)) else book
    })

internal class CloudBackupHistory(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_backup_history", Context.MODE_PRIVATE)
    private fun key(account: String, bookId: String): String =
        MessageDigest.getInstance("SHA-256").digest("$account\n$bookId".toByteArray()).joinToString("") { "%02x".format(it) }
    fun digest(account: String, bookId: String): String? = preferences.getString(key(account, bookId), null)
    fun uploadedDigest(account: String, bookId: String): String? = preferences.getString("uploaded-${key(account, bookId)}", null)
    fun cloudBookId(account: String, bookId: String): String = preferences.getString("cloud-id-${key(account, bookId)}", null) ?: bookId
    fun linkBook(account: String, bookId: String, cloudId: String) {
        preferences.edit().putString("cloud-id-${key(account, bookId)}", cloudId).commit()
    }
    fun approvedCopies(account: String, bookId: String): Set<String> =
        preferences.getStringSet("copies-${key(account, bookId)}", emptySet()).orEmpty().toSet()
    fun approveCopies(account: String, bookId: String, remoteIds: Set<String>) {
        preferences.edit().putStringSet("copies-${key(account, bookId)}", remoteIds).commit()
    }
    fun record(account: String, bookId: String, digest: String, time: Long) {
        preferences.edit().putString(key(account, bookId), digest).putLong("time-${key(account, bookId)}", time)
            .putString("uploaded-${key(account, bookId)}", digest)
            .remove("paused-${key(account, bookId)}").commit()
    }
    fun rememberAutomaticBaseline(account: String, bookId: String, digest: String) {
        preferences.edit().putString(key(account, bookId), digest).commit()
    }
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun pauseAutomatic(account: String, bookId: String) { preferences.edit().putBoolean("paused-${key(account, bookId)}", true).commit() }
    fun automaticPaused(account: String, bookId: String): Boolean = preferences.getBoolean("paused-${key(account, bookId)}", false)
    fun forget(account: String, bookId: String) {
        preferences.edit().remove(key(account, bookId)).remove("time-${key(account, bookId)}")
            .remove("uploaded-${key(account, bookId)}")
            .remove("cloud-id-${key(account, bookId)}").remove("copies-${key(account, bookId)}")
            .putBoolean("paused-${key(account, bookId)}", true).commit()
    }
    fun backupAt(account: String, bookId: String): Long = preferences.getLong("time-${key(account, bookId)}", 0)
    fun error(): String? = preferences.getString("last_error", null)
    fun error(message: String?) { preferences.edit().putString("last_error", message).apply() }
}

internal fun cloudBackupFingerprint(repository: LibraryRepository, state: LibraryState, book: Book): String {
    return CloudBookFingerprint(repository, state, book).fingerprint(book)
}

internal fun cloudBackupFingerprintTail(repository: LibraryRepository, state: LibraryState, book: Book): List<String> {
    val parts = mutableListOf<String>()
    state.annotations.filter { it.bookId == book.id }.sortedBy { it.id }.forEach { parts += repository.writeAnnotation(it).toString() }
    val settings = if (book.mode == LibraryMode.COMIC) state.comicSettings else state.settings
    parts += repository.writeSettings(settings).toString()
    state.groups.find { it.id == book.groupId }?.let { parts += "${it.id}\n${it.name}\n${it.mode}" }
    val files = listOfNotNull(book.sourcePath, book.coverPath) + book.chapters.flatMap { chapter ->
        listOfNotNull(chapter.textPath) + chapter.inlineImages.map { it.path } +
            chapter.pages.flatMap { listOfNotNull(it.path, it.textPath) }
    } + settings.fonts.map { it.path } + listOfNotNull(settings.fontPath, settings.backgroundImagePath) +
        settings.appearanceProfiles.flatMap { listOfNotNull(it.appearance.fontPath, it.appearance.backgroundImagePath) }
    files.distinct().forEach { path ->
        val file = File(path)
        if (!path.startsWith("content:")) parts += "$path\n${file.length()}\n${file.lastModified()}"
    }
    return parts
}

internal fun cloudDirtyBookIds(repository: LibraryRepository, state: LibraryState, account: String,
                              history: CloudBackupHistory, check: () -> Unit = {}): Set<String> = state.books.filter { book ->
    check()
    book.cloudEnabled && !book.cloudOnly && cloudBackupFingerprint(repository, state, book) != history.uploadedDigest(account, book.id)
}.map { it.id }.toSet()

internal object CloudBackupAutomation {
    private const val PERIODIC = "eread-cloud-periodic"
    private const val CHANGE = "eread-cloud-change"
    private const val MANUAL = "eread-cloud-upload"
    private const val BOOK_TAG = "eread-cloud-book:"
    internal const val KEY_BOOK = "book"
    internal const val KEY_ACCOUNT = "account"
    internal const val KEY_TITLE = "title"
    internal const val KEY_STAGE = "stage"
    internal const val KEY_FRACTION = "fraction"
    internal const val KEY_ERROR = "error"
    internal const val KEY_CONFLICT = "conflict"
    internal fun automaticConstraints(allowMetered: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).build()

    fun configure(context: Context, enabled: Boolean, allowMetered: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        val policy = context.getSharedPreferences("cloud_backup_network", Context.MODE_PRIVATE)
        val changed = policy.getBoolean("metered", false) != allowMetered
        policy.edit().putBoolean("metered", allowMetered).apply()
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(CHANGE)
        } else {
            if (changed) manager.cancelUniqueWork(CHANGE)
            manager.enqueueUniquePeriodicWork(PERIODIC,
                if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CloudBackupWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(automaticConstraints(allowMetered)).build())
        }
    }

    fun changed(context: Context, state: LibraryState, configured: Boolean) {
        if (!configured || cloudBackupBooks(state).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudBackupWorker>().setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(automaticConstraints(state.cloudAutoBackupMetered)).build())
    }

    internal fun uploadRequest(book: Book, account: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInputData(workDataOf(KEY_BOOK to book.id, KEY_ACCOUNT to account, KEY_TITLE to book.title))
            .addTag(BOOK_TAG + book.id)
            .addTag(MANUAL)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()

    suspend fun upload(context: Context, book: Book, account: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("$MANUAL-${book.id}", ExistingWorkPolicy.KEEP, uploadRequest(book, account)).await()
    }

    fun uploads(context: Context): Flow<List<WorkInfo>> =
        kotlinx.coroutines.flow.combine(WorkManager.getInstance(context).getWorkInfosByTagFlow(MANUAL),
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MANUAL)) { current, legacy -> (current + legacy).distinctBy { it.id } }

    fun bookId(info: WorkInfo): String? = info.tags.firstOrNull { it.startsWith(BOOK_TAG) }?.removePrefix(BOOK_TAG)

    fun cancelUpload(context: Context, bookId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(BOOK_TAG + bookId)
    }

    suspend fun cancelUploadAndAwait(context: Context, bookId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(BOOK_TAG + bookId).await()
    }

    suspend fun cancelSubmissionAndUpload(context: Context, bookId: String, submission: Job?, submissionMutex: Mutex) {
        // A WorkManager cancellation alone cannot see a submission still saving the library.
        submission?.cancelAndJoin()
        submissionMutex.withLock { cancelUploadAndAwait(context, bookId) }
    }

    fun cancelUploads(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(MANUAL)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
    }
}

class CloudBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var lastProgressAt = 0L
    private var lastStage = ""
    private val manualBookId get() = inputData.getString(CloudBackupAutomation.KEY_BOOK)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("准备上传备份", null)

    private fun foregroundInfo(stage: String, fraction: Float?): ForegroundInfo {
        val channel = "cloud-backup"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "云端备份", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("上传《${inputData.getString(CloudBackupAutomation.KEY_TITLE).orEmpty()}》")
            .setContentText(stage).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt().coerceIn(0, 100), fraction == null)
            .addAction(android.R.drawable.ic_delete, "取消上传", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = id.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    private fun report(progress: CloudOperationProgress) {
        if (manualBookId == null || isStopped) return
        val now = SystemClock.elapsedRealtime()
        if (progress.stage == lastStage && now - lastProgressAt < 500) return
        lastProgressAt = now
        lastStage = progress.stage
        setProgressAsync(progressData(progress.stage, progress.fraction))
        setForegroundAsync(foregroundInfo(progress.stage, progress.fraction))
    }

    private fun progressData(stage: String, fraction: Float?): Data = workDataOf(
        CloudBackupAutomation.KEY_BOOK to manualBookId,
        CloudBackupAutomation.KEY_STAGE to stage.take(600),
        CloudBackupAutomation.KEY_FRACTION to (fraction ?: -1f))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WorkManager can restart an old task before the first-run consent screen is shown.
        if (!ConsentStore(applicationContext).isAccepted()) return@withContext Result.retry()
        val service = CloudBackupService(applicationContext, onProgress = ::report)
        val repository = LibraryRepository(applicationContext)
        val history = CloudBackupHistory(applicationContext)
        try {
            val account = service.identity() ?: return@withContext if (manualBookId == null) Result.success()
                else Result.failure(workDataOf(CloudBackupAutomation.KEY_ERROR to "请先配置 WebDAV 服务器、账户与密码"))
            if (manualBookId != null) {
                require(account == inputData.getString(CloudBackupAutomation.KEY_ACCOUNT)) { "WebDAV 配置已变更，请重新上传" }
                setForeground(foregroundInfo("准备上传备份", null))
                setProgress(progressData("排队等待", null))
            }
            val state = cloudBackupHistoryState(repository.load()) { history.backupAt(account, it) }
            if (state.cloudIdentity != null && state.cloudIdentity != account) return@withContext Result.success()
            val books = if (manualBookId == null) cloudBackupBooks(state) else state.books.filter { it.id == manualBookId && !it.cloudOnly }
            if (manualBookId == null) history.error(null)
            for (book in books) uploadBook(service, repository, history, account, book.id)
            Result.success()
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            val message = error.message ?: "云端备份失败"
            if (manualBookId == null) history.error(message)
            val sourceUnavailable = generateSequence<Throwable>(error) { it.cause }
                .any { it is java.io.FileNotFoundException || it is SecurityException }
            val retryable = !sourceUnavailable && if (error is CloudHttpException) error.retryable else error is IOException
            if (retryable && runAttemptCount < 5) {
                if (manualBookId != null) setProgress(progressData("网络中断，等待重试", null))
                Result.retry()
            } else Result.failure(workDataOf(CloudBackupAutomation.KEY_ERROR to message.take(600),
                CloudBackupAutomation.KEY_CONFLICT to (error is CloudUploadConflictException)))
        }
    }

    private suspend fun uploadBook(service: CloudBackupService, repository: LibraryRepository,
        history: CloudBackupHistory, account: String, bookId: String) {
        fun currentBook(state: LibraryState): Book? =
            (if (manualBookId == null) cloudBackupBooks(state) else state.books.filterNot { it.cloudOnly }).find { it.id == bookId }
        while (true) {
            currentCoroutineContext().ensureActive()
            val initial = cloudBackupHistoryState(repository.load()) { history.backupAt(account, it) }
            val book = currentBook(initial) ?: return
            if (manualBookId == null && (history.automaticPaused(account, bookId) ||
                history.digest(account, bookId) == cloudBackupFingerprint(repository, initial, book))) return
            val keys = cloudUploadIdentityKeys(account, hydrateBookSourceIdentity(applicationContext, book))
            var completed = false
            cloudOperationMutex.withUploadIdentity(keys) {
                require(service.identity() == account && service.configured()) { "WebDAV 配置已变更，请重新上传" }
                val beforeLock = currentBook(repository.load()) ?: run { completed = true; return@withUploadIdentity }
                if (cloudUploadIdentityKeys(account, hydrateBookSourceIdentity(applicationContext, beforeLock)) != keys) return@withUploadIdentity
                val catalog = service.list(account)
                val identities = service.restoreBookMatches(LibraryState(books = listOf(beforeLock)), catalog)
                val targetId = cloudUploadTargetId(beforeLock, catalog, identities, history.cloudBookId(account, bookId))
                cloudOperationMutex.withBooks(listOf(bookId, targetId)) {
                    if (manualBookId != null) setProgress(progressData("准备上传备份", null))
                    // Recheck the source and catalog after waiting for a book lock: neither old matches nor old aliases authorize a write.
                    val latest = cloudBackupHistoryState(repository.load()) { history.backupAt(account, it) }
                    val current = currentBook(latest) ?: run { completed = true; return@withBooks }
                    if (cloudUploadIdentityKeys(account, hydrateBookSourceIdentity(applicationContext, current)) != keys) return@withBooks
                    val freshCatalog = service.list(account)
                    val matches = service.restoreBookMatches(LibraryState(books = listOf(current)), freshCatalog)
                    val freshTarget = cloudUploadTargetId(current, freshCatalog, matches, history.cloudBookId(account, bookId))
                    if (freshTarget != targetId) return@withBooks
                    completed = true
                    if (manualBookId == null && history.automaticPaused(account, bookId)) return@withBooks
                    val fingerprint = cloudBackupFingerprint(repository, latest, current)
                    if (manualBookId == null && history.digest(account, bookId) == fingerprint) return@withBooks
                    val conflicts = cloudUploadConflicts(latest, setOf(bookId), freshCatalog, matches).filterNot {
                        it.remote.id == history.cloudBookId(account, bookId) || it.remote.id in history.approvedCopies(account, bookId)
                    }
                    if (conflicts.isNotEmpty()) {
                        history.pauseAutomatic(account, bookId)
                        val message = "《${current.title}》存在无法确认的同名云端书籍，请点击云图标选择要保留的版本"
                        if (manualBookId != null) throw CloudUploadConflictException(message)
                        history.error(message)
                        return@withBooks
                    }
                    val (uploadState, uploadBook) = cloudUploadSnapshot(latest, current, targetId)
                    val info = try {
                        service.upload(uploadState, uploadBook) {
                            if (manualBookId == null && history.automaticPaused(account, bookId))
                                throw CancellationException("已暂停本书自动备份")
                        }
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (manualBookId == null && history.automaticPaused(account, bookId)) return@withBooks
                        throw cancelled
                    }
                    currentCoroutineContext().ensureActive()
                    if (manualBookId == null && history.automaticPaused(account, bookId)) return@withBooks
                    history.linkBook(account, bookId, targetId)
                    history.record(account, bookId, fingerprint, info.backupAt)
                }
            }
            if (completed) return
        }
    }
}
