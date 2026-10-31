package com.eyue.reader.data

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.cloud.CloudBackupService
import com.eyue.reader.data.cloud.CloudBookInfo
import com.eyue.reader.data.cloud.CloudRestoredBook
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, instrumentedPackages = ["com.eyue.reader.data.cloud"],
    shadows = [CloudDownloadFeedbackRobolectricTest.DownloadServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CloudDownloadFeedbackRobolectricTest {
    private lateinit var root: File
    private lateinit var app: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private val remote = Book(id = "download-feedback", title = "云端待下载小说", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, cloudEnabled = true, cloudOnly = true, chapters = listOf(Chapter(title = "第一章")))

    @Before fun setup() {
        DownloadServiceShadow.reset()
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse { WorkManager.initialize(base, Configuration.Builder().build()) }
        val prefix = "cloud-download-feedback-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        app = object : Application() { init { attachBaseContext(isolated) } }
        app.getSharedPreferences("onboarding", 0).edit().putBoolean("demo_seeded", true).commit()
        LibraryRepository(app).save(LibraryState(books = listOf(remote)))
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading && vm.cloudConfigured }
        assertNull(vm.message)
    }

    @After fun cleanup() {
        DownloadServiceShadow.pendingList?.also {
            DownloadServiceShadow.pendingList = null
            it.resumeWith(Result.failure(IOException("测试已结束")))
        }
        DownloadServiceShadow.pendingUpload?.also {
            DownloadServiceShadow.pendingUpload = null
            it.resumeWith(Result.failure(IOException("测试已结束")))
        }
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            store.clear()
            await { job.isCompleted }
        }
        DownloadServiceShadow.reset()
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == RuntimeEnvironment.getApplication().cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test fun successfulDownloadUpdatesTheShelfAndSavedRecordsWithoutShowingASuccessMessage() {
        val source = File(app.filesDir, "downloaded.txt").apply { writeText("第一章\n还原后的正文。") }
        val local = remote.copy(sourcePath = source.absolutePath, cloudOnly = false,
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath)))
        val note = Annotation(bookId = local.id, chapterIndex = 0, pageIndex = 0, quote = "正文", note = "云端笔记")
        DownloadServiceShadow.restored = CloudRestoredBook(local, listOf(note), ReaderSettings(), null, 1234)

        vm.downloadCloudBook(vm.state.books.single())
        await { DownloadServiceShadow.downloadCalls == 1 && vm.cloudTransfers.isEmpty() }

        assertFalse(vm.state.books.single().cloudOnly)
        assertEquals(source.absolutePath, vm.state.books.single().sourcePath)
        assertEquals(listOf(note), vm.state.annotations)
        assertNull("A completed download must not show a success toast", vm.message)
        val saved = LibraryRepository(app).load()
        assertFalse(saved.books.single().cloudOnly)
        assertEquals(listOf(note), saved.annotations)
        assertEquals(1234L, CloudBackupHistory(app).backupAt(DownloadServiceShadow.ACCOUNT, local.id))
    }

    @Test fun failedDownloadKeepsThePlaceholderAndStillShowsTheError() {
        DownloadServiceShadow.failure = IOException("测试下载失败，请重试")

        vm.downloadCloudBook(vm.state.books.single())
        await { DownloadServiceShadow.downloadCalls == 1 && vm.cloudTransfers.isEmpty() }

        assertTrue(vm.state.books.single().cloudOnly)
        assertTrue(LibraryRepository(app).load().books.single().cloudOnly)
        assertEquals("测试下载失败，请重试", vm.message)
    }

    @Test fun downloadingAnAlreadyDownloadedBookDoesNotShowASuccessMessageOrDownloadAgain() {
        val source = File(app.filesDir, "downloaded.txt").apply { writeText("第一章\n还原后的正文。") }
        val local = remote.copy(sourcePath = source.absolutePath, cloudOnly = false,
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath)))
        DownloadServiceShadow.restored = CloudRestoredBook(local, emptyList(), ReaderSettings(), null, 1234)
        vm.downloadCloudBook(vm.state.books.single())
        await { DownloadServiceShadow.downloadCalls == 1 && vm.cloudTransfers.isEmpty() }

        vm.downloadCloudBook(vm.state.books.single())
        await { vm.cloudTransfers.isEmpty() }

        assertEquals(1, DownloadServiceShadow.downloadCalls)
        assertFalse(vm.state.books.single().cloudOnly)
        assertNull("A stale download action must remain silent after the book is local", vm.message)
    }

    @Test fun renamingAShelfBookPersistsItsTitleAndMarksTheExistingCloudBookForUpdate() {
        val source = File(app.filesDir, "rename-original.txt").apply { writeText("第一章\n原始正文") }
        val local = remote.copy(cloudOnly = false, sourcePath = source.absolutePath,
            sourceFileName = "原始文件.txt", sourceFileBytes = source.length(),
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath)))
        DownloadServiceShadow.restored = CloudRestoredBook(local, emptyList(), ReaderSettings(), null, 1234)
        vm.downloadCloudBook(vm.state.books.single())
        await { !vm.state.books.single().cloudOnly && vm.cloudTransfers.isEmpty() }
        val before = vm.state.books.single()
        val originalBytes = source.readBytes()
        assertTrue(vm.renameBook(before.id, " 自选书名 "))
        assertFalse(vm.renameBook(before.id, " \n "))
        await { vm.cloudDirtyBookIds == setOf(before.id) && LibraryRepository(app).load().books.single().title == "自选书名" }
        val renamed = vm.state.books.single()
        assertEquals("自选书名", renamed.title)
        assertEquals(before.copy(title = "自选书名", sourcePath = File(source.parentFile, "自选书名.txt").path,
            sourceFileName = "自选书名.txt", chapters = before.chapters.map { it.copy(textPath = File(source.parentFile, "自选书名.txt").path) }), renamed)
        assertFalse(source.exists())
        assertArrayEquals(originalBytes, File(renamed.sourcePath!!).readBytes())
        val (_, uploaded) = cloudUploadSnapshot(vm.state, renamed, "existing-cloud-id")
        assertEquals("自选书名", uploaded.title)
        assertEquals("自选书名.txt", uploaded.sourceFileName)
        assertEquals(before.sourceFileBytes, uploaded.sourceFileBytes)
    }

    @Test fun downloadingARenamedPlaceholderPreservesTheLocalTitleAndDoesNotMarkItAsAlreadyUploaded() {
        assertTrue(vm.renameBook(remote.id, "我的书名"))
        val source = File(app.filesDir, "renamed-placeholder.txt").apply { writeText("第一章\n原始正文") }
        DownloadServiceShadow.restored = CloudRestoredBook(remote.copy(cloudOnly = false, sourcePath = source.absolutePath,
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath))), emptyList(), ReaderSettings(), null, 1234)
        vm.downloadCloudBook(vm.state.books.single())
        await { !vm.state.books.single().cloudOnly && vm.cloudTransfers.isEmpty() && vm.cloudDirtyBookIds == setOf(remote.id) }
        assertEquals("我的书名", vm.state.books.single().title)
        assertEquals("我的书名", LibraryRepository(app).load().books.single().title)
        assertNotEquals(cloudBackupFingerprint(LibraryRepository(app), vm.state, vm.state.books.single()),
            CloudBackupHistory(app).uploadedDigest(DownloadServiceShadow.ACCOUNT, remote.id))
        assertTrue(app.getSharedPreferences("shelf_renames", 0).getStringSet("pending_titles", emptySet()).orEmpty().isEmpty())
    }

    @Test fun uneditedPlaceholderReceivesAnUpdatedCloudTitle() {
        restoreInformation(remote.copy(title = "另一台设备的新书名"))
        assertEquals("另一台设备的新书名", vm.state.books.single().title)
        assertEquals("另一台设备的新书名", LibraryRepository(app).load().books.single().title)
    }

    @Test fun renamedPlaceholderKeepsItsNameAcrossRestartAndInformationRestore() {
        assertTrue(vm.renameBook(remote.id, "我的书名"))
        vm.flush()
        await { LibraryRepository(app).load().books.single().title == "我的书名" }
        val previous = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        store.clear()
        await { previous.isCompleted }
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading && vm.cloudConfigured }
        restoreInformation(remote.copy(title = "云端书名", progress = .8f))
        assertEquals("我的书名", vm.state.books.single().title)
        assertEquals(.8f, vm.state.books.single().progress, 0f)
        assertEquals("我的书名", LibraryRepository(app).load().books.single().title)
        assertEquals(setOf(remote.id), app.getSharedPreferences("shelf_renames", 0).getStringSet("pending_titles", emptySet()))
    }

    @Test fun removingARenamedPlaceholderClearsItsLocalNameMarker() {
        assertTrue(vm.renameBook(remote.id, "我的书名"))
        vm.removeBook(vm.state.books.single(), false)
        await { vm.state.books.isEmpty() && vm.cloudTransfers.isEmpty() }
        assertTrue(app.getSharedPreferences("shelf_renames", 0).getStringSet("pending_titles", emptySet()).orEmpty().isEmpty())
        assertNull(vm.message)
    }

    private fun restoreInformation(book: Book) {
        vm.restoreCloudBookInfo()
        await { DownloadServiceShadow.pendingList != null }
        val cloud = CloudBookInfo(book, emptyList(), ReaderSettings(), null, 1234, "revision", 1, "hash", emptyList())
        DownloadServiceShadow.catalogIds = setOf(book.id)
        DownloadServiceShadow.pendingList!!.also {
            DownloadServiceShadow.pendingList = null
            it.resumeWith(Result.success(listOf(cloud)))
        }
        await { vm.cloudRestoreProgress == null }
        assertNull(vm.message)
    }

    @Test fun mixedBatchDownloadsRemoteBookWhileLocalUploadMetadataIsStillPending() {
        val source = File(app.filesDir, "batch-local.txt").apply { writeText("第一章\n本地等待上传正文。") }
        val local = Book(title = "批量上传", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            sourcePath = source.absolutePath, chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath)))
        val oldJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        store.clear()
        await { oldJob.isCompleted }
        LibraryRepository(app).save(LibraryState(books = listOf(local, remote)))
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading && vm.cloudConfigured }
        val restoredSource = File(app.filesDir, "batch-remote.txt").apply { writeText("第一章\n云端下载完成正文。") }
        DownloadServiceShadow.restored = CloudRestoredBook(remote.copy(cloudOnly = false, sourcePath = restoredSource.absolutePath,
            chapters = listOf(Chapter(title = "第一章", textPath = restoredSource.absolutePath))), emptyList(), ReaderSettings(), null, 2345)

        vm.transferCloudBooks(listOf(local, remote, local.copy(title = "来自旧列表的重复条目")))
        await { DownloadServiceShadow.listCalls == 1 && DownloadServiceShadow.downloadCalls == 1 &&
            vm.state.books.single { it.id == remote.id }.cloudOnly == false && vm.cloudTransfers.keys == setOf(local.id) }
        assertNotNull(DownloadServiceShadow.pendingList)
        assertFalse(LibraryRepository(app).load().books.single { it.id == remote.id }.cloudOnly)
        assertTrue(vm.state.books.single { it.id == local.id }.sourcePath == source.absolutePath)
        DownloadServiceShadow.pendingList!!.also {
            DownloadServiceShadow.pendingList = null
            it.resumeWith(Result.failure(IOException("测试上传入口已验证")))
        }
        await { vm.cloudTransfers.isEmpty() }
        assertEquals(1, DownloadServiceShadow.downloadCalls)
        assertEquals("测试上传入口已验证", vm.message)
    }

    @Test fun confirmingMultipleSameNameBooksClosesTheDecisionWhileUploadsContinue() {
        val first = remote.copy(id = "local-one", cloudOnly = false, cloudEnabled = false, sourceFileName = "first.txt")
        val second = first.copy(id = "local-two", sourceFileName = "second.txt")
        val previous = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        store.clear()
        await { previous.isCompleted }
        LibraryRepository(app).save(LibraryState(books = listOf(first, second)))
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading && vm.cloudConfigured }
        vm.uploadCloudBooks(vm.state.books)
        await { DownloadServiceShadow.pendingList != null }
        val cloud = CloudBookInfo(remote, emptyList(), ReaderSettings(), null, 1234, "revision", 1, "hash", emptyList())
        DownloadServiceShadow.pendingList!!.also { DownloadServiceShadow.pendingList = null; it.resumeWith(Result.success(listOf(cloud))) }
        await { vm.pendingCloudUpload != null && vm.cloudUploadConflicts.singleOrNull()?.locals?.size == 2 }
        vm.resolveCloudUploadSelections(mapOf(remote.id to CloudRestoreSelection(setOf(first.id, second.id), true)), true)
        assertNull("A valid confirmation must dismiss the foreground dialog immediately", vm.pendingCloudUpload)
        await { DownloadServiceShadow.uploadCalls == 1 && DownloadServiceShadow.pendingUpload != null }
        assertTrue(vm.cloudBusy)
        vm.resolveCloudUploadSelections(emptyMap(), true)
        assertEquals("The closed decision cannot submit the upload again", 1, DownloadServiceShadow.uploadCalls)
        for (completed in 1..2) {
            await { DownloadServiceShadow.uploadCalls == completed && DownloadServiceShadow.pendingUpload != null }
            val uploaded = DownloadServiceShadow.uploadedBooks.last()
            DownloadServiceShadow.pendingUpload!!.also {
                DownloadServiceShadow.pendingUpload = null
                it.resumeWith(Result.success(cloud.copy(book = uploaded)))
            }
        }
        await { !vm.cloudBusy }
        assertNull(vm.pendingCloudUpload)
        assertEquals(setOf(first.id, second.id), DownloadServiceShadow.uploadedBooks.map { it.id }.toSet())
        assertTrue(vm.state.books.any { it.id == remote.id && it.cloudOnly })
        assertTrue(vm.state.books.filter { it.id in setOf(first.id, second.id) }.all { it.cloudEnabled })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            Snapshot.sendApplyNotifications()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for cloud download: ${if (::vm.isInitialized) vm.message else "not initialized"}")
    }

    /** Only the remote service boundary is replaced; the ViewModel merges, saves and records the result. */
    @Implements(value = CloudBackupService::class, isInAndroidSdk = false)
    class DownloadServiceShadow {
        @Implementation fun account(): String = ACCOUNT
        @Implementation fun serverUrl(): String = "https://dav.example.invalid/"
        @Implementation fun identity(): String = ACCOUNT

        @Suppress("UNUSED_PARAMETER")
        @Implementation fun catalogBookIds(expectedIdentity: String,
            continuation: Continuation<Set<String>>): Any = catalogIds

        @Suppress("UNUSED_PARAMETER")
        @Implementation fun restoreBookMatches(state: LibraryState, incoming: List<CloudBookInfo>,
            continuation: Continuation<Map<Pair<String, String>, CloudBookMatch>>): Any = emptyMap<Pair<String, String>, CloudBookMatch>()

        @Suppress("UNUSED_PARAMETER")
        @Implementation fun list(expectedIdentity: String?, continuation: Continuation<List<CloudBookInfo>>): Any {
            listCalls++
            pendingList = continuation
            return COROUTINE_SUSPENDED
        }

        @Suppress("UNUSED_PARAMETER")
        @Implementation fun download(bookId: String, continuation: Continuation<CloudRestoredBook>): Any {
            downloadCalls++
            failure?.let { throw it }
            return requireNotNull(restored).also { check(it.book.id == bookId) }
        }

        @Suppress("UNUSED_PARAMETER")
        @Implementation fun upload(state: LibraryState, book: Book, checkAllowed: () -> Unit,
            continuation: Continuation<CloudBookInfo>): Any {
            uploadCalls++
            uploadedBooks += book
            pendingUpload = continuation
            return COROUTINE_SUSPENDED
        }

        companion object {
            const val ACCOUNT = "download-feedback@example.invalid"
            var restored: CloudRestoredBook? = null
            var failure: IOException? = null
            var downloadCalls = 0
            var listCalls = 0
            var pendingList: Continuation<List<CloudBookInfo>>? = null
            var pendingUpload: Continuation<CloudBookInfo>? = null
            var uploadCalls = 0
            var catalogIds: Set<String> = emptySet()
            val uploadedBooks = mutableListOf<Book>()
            fun reset() {
                restored = null; failure = null; downloadCalls = 0; listCalls = 0; pendingList = null
                pendingUpload = null; uploadCalls = 0; uploadedBooks.clear()
                catalogIds = emptySet()
            }
        }
    }
}
