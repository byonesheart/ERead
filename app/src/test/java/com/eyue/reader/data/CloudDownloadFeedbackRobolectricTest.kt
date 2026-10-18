package com.eyue.reader.data

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.cloud.CloudBackupService
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

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
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
        @Implementation fun download(bookId: String, continuation: Continuation<CloudRestoredBook>): Any {
            downloadCalls++
            failure?.let { throw it }
            return requireNotNull(restored).also { check(it.book.id == bookId) }
        }

        companion object {
            const val ACCOUNT = "download-feedback@example.invalid"
            var restored: CloudRestoredBook? = null
            var failure: IOException? = null
            var downloadCalls = 0
            fun reset() { restored = null; failure = null; downloadCalls = 0 }
        }
    }
}
