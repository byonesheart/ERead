package com.eyue.reader.data

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderSourceEditTransactionRobolectricTest {
    private lateinit var root: File
    private lateinit var app: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private val lockOwner = Any()
    private var locked = false

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse { WorkManager.initialize(base, Configuration.Builder().build()) }
        val prefix = "source-edit-vm-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        app = object : Application() { init { attachBaseContext(context) } }
        val initialized = WorkManager.getInstance(base).pruneWork().result
        await { initialized.isDone }
        initialized.get()
    }

    @After fun cleanup() {
        if (locked) { cloudOperationMutex.unlock(lockOwner); locked = false }
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            store.clear()
            await { job.isCompleted }
        }
        root.deleteRecursively()
    }

    @Test fun sourceOverwriteWaitsForBackupAndPublishesMatchingSourceAndLibrary() {
        val original = "第一章\n\n旧正文\n保留段落"
        val source = File(root, "original.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            val files = linkedMapOf("mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                "book.opf" to "<package><metadata><title>覆盖事务</title></metadata><manifest><item id='body' href='body.xhtml'/></manifest><spine><itemref idref='body'/></spine></package>",
                "body.xhtml" to "<html><body><h1>第一章</h1><p>旧正文</p><p>保留段落</p></body></html>")
            files.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        val parsed = EpubArchive.read(source)
        val text = SourceHtmlEdit.plain(parsed.sections.single().html) { null }
        assertEquals(original, text)
        val cache = File(root, "chapter.txt").apply { writeText(text) }
        val book = Book(title = parsed.title, format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = cache.absolutePath, characterCount = text.length)),
            sourcePath = source.absolutePath, contentVersion = 6)
        val repository = LibraryRepository(app)
        repository.save(LibraryState(books = listOf(book)))
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading }
        vm.open(vm.state.books.single())
        await { repository.load().books.single().lastReadAt > 0 }
        val bytes = source.readBytes()
        runBlocking { cloudOperationMutex.lock(lockOwner) }
        locked = true
        vm.editTxt(0, text.indexOf("旧正文"), text.indexOf("旧正文") + 3, "新的正文")
        val deadline = System.nanoTime() + 300_000_000L
        while (System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(vm.importing)
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(text, File(repository.load().books.single().chapters.single().textPath!!).readText())
        cloudOperationMutex.unlock(lockOwner)
        locked = false
        await { !vm.importing }
        assertNull(vm.pendingSourceWriteUri)
        val saved = repository.load().books.single()
        val sourceText = SourceHtmlEdit.plain(EpubArchive.read(source).sections.single().html) { null }
        assertEquals(text.replace("旧正文", "新的正文"), sourceText)
        assertEquals(sourceText, File(saved.chapters.single().textPath!!).readText())
        assertFalse(cloudOperationMutex.isLocked)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for source-edit state")
    }
}
