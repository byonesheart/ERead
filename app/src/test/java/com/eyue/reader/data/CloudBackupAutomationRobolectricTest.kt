package com.eyue.reader.data

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import com.eyue.reader.data.cloud.CloudCredentials

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudBackupAutomationRobolectricTest {
    private fun book() = Book(id = "durable-book", title = "持久上传", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")),
        characterOffset = 321, progress = .45f, sourceUri = "content://documents/book.mobi", sourceLinked = true)

    @Test fun cancellingBeforeSubmissionPreventsLateReuploadAndKeepsTheOtherBooksQueue() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val mutex = Mutex()
        val saving = CompletableDeferred<Unit>()
        val finishSaving = CompletableDeferred<Unit>()
        val first = CloudBackupAutomation.uploadRequest(book().copy(id = "late-submit-a"), "queued@example.com")
        val second = CloudBackupAutomation.uploadRequest(book().copy(id = "keep-submit-b"), "queued@example.com")
        listOf(first, second).forEach { it.workSpec.expedited = false; it.workSpec.initialDelay = TimeUnit.DAYS.toMillis(1) }
        manager.enqueueUniqueWork("keep-other-book", ExistingWorkPolicy.KEEP, second).result.get(10, TimeUnit.SECONDS)
        val submission = launch {
            saving.complete(Unit)
            finishSaving.await()
            mutex.withLock { manager.enqueueUniqueWork("prevent-late-book", ExistingWorkPolicy.KEEP, first).result.get(10, TimeUnit.SECONDS) }
        }
        saving.await()
        CloudBackupAutomation.cancelSubmissionAndUpload(context, "late-submit-a", submission, mutex)
        finishSaving.complete(Unit)
        assertTrue(submission.isCancelled)
        assertTrue(manager.getWorkInfosForUniqueWork("prevent-late-book").get(10, TimeUnit.SECONDS).isEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(second.id).get(10, TimeUnit.SECONDS)!!.state)
        manager.cancelUniqueWork("keep-other-book").result.get(10, TimeUnit.SECONDS)
        Unit
    }

    @Test fun cancellationWaitsForAnInFlightNonCancellableSubmissionBeforeCancellingItsWork() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val mutex = Mutex()
        val submitting = CompletableDeferred<Unit>()
        val finishSubmission = CompletableDeferred<Unit>()
        val request = CloudBackupAutomation.uploadRequest(book().copy(id = "in-flight-submit"), "queued@example.com")
        request.workSpec.expedited = false
        request.workSpec.initialDelay = TimeUnit.DAYS.toMillis(1)
        val submission = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                mutex.withLock {
                    submitting.complete(Unit)
                    finishSubmission.await()
                    manager.enqueueUniqueWork("in-flight-submission", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
                }
            }
        }
        submitting.await()
        val cancel = async { CloudBackupAutomation.cancelSubmissionAndUpload(context, "in-flight-submit", submission, mutex) }
        try { yield(); assertFalse(cancel.isCompleted) }
        finally { finishSubmission.complete(Unit) }
        cancel.await()
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.state)
    }

    @Test fun manualUploadPersistsIdentityAndRetriesOnAnyConnectedNetworkWithoutStoringPasswords() {
        val request = CloudBackupAutomation.uploadRequest(book(), "reader@example.com")
        val spec = request.workSpec
        val restoredInput = Data.fromByteArray(spec.input.toByteArray())
        assertEquals(book().id, restoredInput.getString(CloudBackupAutomation.KEY_BOOK))
        assertEquals("reader@example.com", restoredInput.getString(CloudBackupAutomation.KEY_ACCOUNT))
        assertEquals(setOf("book", "account", "title"), restoredInput.keyValueMap.keys)
        assertEquals(CloudBackupWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        // After the screen/ViewModel is recreated, book identity is available even before progress.
        val queued = WorkInfo(request.id, WorkInfo.State.ENQUEUED, request.tags)
        assertEquals(book().id, CloudBackupAutomation.bookId(queued))
    }

    @Test fun dirtyBooksCompareTheirOwnProgressNotesRolesAndSettingsButIgnoreReadingTimeAndOpenTimestamp() {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val history = CloudBackupHistory(context)
        val account = "dirty-books@example.invalid"
        val a = book().copy(id = "dirty-a", cloudEnabled = true, cloudBackupAt = 100)
        val b = book().copy(id = "dirty-b", cloudEnabled = true, cloudBackupAt = 100)
        val clean = LibraryState(books = listOf(a, b), readingSeconds = 10)
        clean.books.forEach { history.record(account, it.id, cloudBackupFingerprint(repository, clean, it), 100) }
        val onlyTimeChanged = clean.copy(readingSeconds = 2000, books = listOf(a.copy(lastReadAt = 9999), b))
        assertTrue(cloudDirtyBookIds(repository, onlyTimeChanged, account, history).isEmpty())
        val moved = clean.copy(books = listOf(a.copy(characterOffset = 999, progress = .8f), b))
        assertEquals(setOf(a.id), cloudDirtyBookIds(repository, moved, account, history))
        val note = Annotation(bookId = b.id, chapterIndex = 0, pageIndex = 0, note = "新笔记")
        assertEquals(setOf(b.id), cloudDirtyBookIds(repository, clean.copy(annotations = listOf(note)), account, history))
        val role = BookVoiceRole(name = "角色", nameRegex = "角色", voice = "低沉")
        assertEquals(setOf(a.id), cloudDirtyBookIds(repository, clean.copy(books = listOf(a.copy(voiceRoles = listOf(role)), b)), account, history))
        assertEquals(setOf(a.id, b.id), cloudDirtyBookIds(repository, clean.copy(settings = clean.settings.copy(fontSize = 26f)), account, history))
    }

    @Test fun successfulBackgroundSnapshotClearsDirtyButLaterLocalProgressStaysPending() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val history = CloudBackupHistory(context)
        val account = "updated-snapshot@example.invalid"
        val old = book().copy(cloudEnabled = true, cloudBackupAt = 100)
        val original = LibraryState(books = listOf(old))
        history.record(account, old.id, cloudBackupFingerprint(repository, original, old), 100)
        val current = old.copy(characterOffset = 555, progress = .7f)
        val updated = original.copy(books = listOf(current))
        assertEquals(setOf(old.id), cloudDirtyBookIds(repository, updated, account, history))
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            CloudBackupHistory(context).record(account, current.id, cloudBackupFingerprint(repository, updated, current), 200)
        }
        assertTrue(cloudDirtyBookIds(repository, updated, account, history).isEmpty())
        val later = current.copy(characterOffset = 888, progress = .9f)
        assertEquals(setOf(old.id), cloudDirtyBookIds(repository, updated.copy(books = listOf(later)), account, history))
    }

    @Test fun localOnlyCleanupSuppressesAutomaticUploadWithoutPretendingTheCloudSnapshotWasUpdated() {
        val context = RuntimeEnvironment.getApplication()
        val history = CloudBackupHistory(context)
        val account = "cleanup-baseline@example.invalid"
        history.record(account, "book", "uploaded-records", 100)
        history.rememberAutomaticBaseline(account, "book", "locally-cleared-records")
        assertEquals("locally-cleared-records", history.digest(account, "book"))
        assertEquals("uploaded-records", history.uploadedDigest(account, "book"))
        assertEquals(100, history.backupAt(account, "book"))
    }

    @Test fun completedUploadSurvivesReconstructionAndPreservesNewerReadingProgress() {
        val context = RuntimeEnvironment.getApplication()
        val original = LibraryState(books = listOf(book()), cloudAutoBackup = true, readingSeconds = 999)
        LibraryRepository(context).save(original)
        val libraryFile = File(context.filesDir, "library.json")
        val before = libraryFile.readBytes()
        CloudBackupHistory(context).record("reader@example.com", book().id, "content-record-fingerprint", 500)

        val disk = LibraryRepository(context).load()
        val recreatedHistory = CloudBackupHistory(context)
        val merged = cloudBackupHistoryState(disk) { recreatedHistory.backupAt("reader@example.com", it) }
        assertEquals(original.books.single().copy(cloudEnabled = true, cloudBackupAt = 500), merged.books.single())
        assertEquals(original.readingSeconds, merged.readingSeconds)
        assertEquals(listOf(merged.books.single()), cloudBackupBooks(merged))
        assertEquals(original, disk)
        assertArrayEquals(before, libraryFile.readBytes())
    }

    @Test fun successfulHistoryIsAccountScopedAndNeverRewindsANewerBackup() {
        val context = RuntimeEnvironment.getApplication()
        val history = CloudBackupHistory(context)
        val state = LibraryState(books = listOf(book().copy(cloudBackupAt = 800)))
        history.record("first@example.com", book().id, "digest", 400)
        assertEquals(state, cloudBackupHistoryState(state) { history.backupAt("second@example.com", it) })
        val merged = cloudBackupHistoryState(state) { history.backupAt("first@example.com", it) }
        assertEquals(state.books.single().copy(cloudEnabled = true), merged.books.single())
    }

    @Test fun cancellationPausesAutomaticBackupWithoutDiscardingTheExistingCloudRecord() {
        val context = RuntimeEnvironment.getApplication()
        val account = "paused@example.com"
        val history = CloudBackupHistory(context)
        history.record(account, book().id, "previous-content", 500)
        history.pauseAutomatic(account, book().id)
        val recreated = CloudBackupHistory(context)
        assertTrue(recreated.automaticPaused(account, book().id))
        assertFalse(recreated.automaticPaused("another@example.com", book().id))
        assertEquals(500, recreated.backupAt(account, book().id))
        assertEquals("previous-content", recreated.digest(account, book().id))
        recreated.record(account, book().id, "resumed-content", 600)
        assertFalse(recreated.automaticPaused(account, book().id))
    }

    @Test fun cloudDeletionForgetsHistorySoReconstructionCannotReenableTheBook() {
        val context = RuntimeEnvironment.getApplication()
        val history = CloudBackupHistory(context)
        val account = "deleted@example.com"
        history.record(account, book().id, "digest", 500)
        history.forget(account, book().id)
        val recreated = CloudBackupHistory(context)
        assertNull(recreated.digest(account, book().id))
        assertEquals(0, recreated.backupAt(account, book().id))
        assertTrue(recreated.automaticPaused(account, book().id))
        val local = LibraryState(books = listOf(book()))
        assertEquals(local, cloudBackupHistoryState(local) { recreated.backupAt(account, it) })
    }

    @Test fun cancellingAQueuedUploadIsPersistedByWorkManagerBeforeReturning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val request = CloudBackupAutomation.uploadRequest(book(), "queued@example.com")
        // Hold execution without a real account or network; exercise the actual persistent queue.
        request.workSpec.expedited = false
        request.workSpec.initialDelay = TimeUnit.DAYS.toMillis(1)
        manager.enqueueUniqueWork("cancel-contract", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.state)
        CloudBackupAutomation.cancelUploadAndAwait(context, book().id)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.state)
        assertTrue(manager.getWorkInfosForUniqueWork("cancel-contract").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
    }

    @Test fun automaticBackupUsesUnmeteredNetworkUnlessTheOptionIsEnabled() {
        assertEquals(NetworkType.UNMETERED, CloudBackupAutomation.automaticConstraints(false).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, CloudBackupAutomation.automaticConstraints(true).requiredNetworkType)
        val context = RuntimeEnvironment.getApplication()
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val state = LibraryState(books = listOf(book().copy(cloudEnabled = true)), cloudAutoBackup = true,
            cloudAutoBackupMetered = false)
        CloudBackupAutomation.configure(context, true)
        CloudBackupAutomation.changed(context, state, true)
        val original = manager.getWorkInfosForUniqueWork("eread-cloud-change").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        CloudBackupAutomation.configure(context, true, true)
        CloudBackupAutomation.changed(context, state.copy(cloudAutoBackupMetered = true), true)
        val changed = manager.getWorkInfosForUniqueWork("eread-cloud-change").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        val previous = manager.getWorkInfoById(original.id).get(10, TimeUnit.SECONDS)
        assertTrue(previous == null || previous.state == WorkInfo.State.CANCELLED)
        assertNotEquals(original.id, changed.id)
        CloudBackupAutomation.configure(context, false, true)
    }

    @Test fun historyAndQueueIdentityAreIsolatedByServerAsWellAsAccount() {
        val context = RuntimeEnvironment.getApplication()
        val history = CloudBackupHistory(context)
        val first = CloudCredentials("same@example.com", "password")
        val second = CloudCredentials("same@example.com", "password", "https://other.example.invalid/dav/")
        history.record(first.identity, book().id, "first-digest", 500)
        history.pauseAutomatic(first.identity, book().id)
        assertNull(history.digest(second.identity, book().id))
        assertEquals(0, history.backupAt(second.identity, book().id))
        assertFalse(history.automaticPaused(second.identity, book().id))
        assertEquals(second.identity, CloudBackupAutomation.uploadRequest(book(), second.identity).workSpec.input
            .getString(CloudBackupAutomation.KEY_ACCOUNT))
    }
}
