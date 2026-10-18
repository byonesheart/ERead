package com.eyue.reader.data

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderEditIsolationRobolectricTest {
    private lateinit var root: File
    private lateinit var app: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private val holders = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val releases = mutableListOf<CompletableDeferred<Unit>>()

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse { WorkManager.initialize(base, Configuration.Builder().build()) }
        val prefix = "reader-edit-isolation-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        app = object : Application() { init { attachBaseContext(context) } }
        app.getSharedPreferences("onboarding", 0).edit().putBoolean("demo_seeded", true).commit()
    }

    @After fun cleanup() {
        releases.forEach { it.complete(Unit) }
        holders.cancel()
        runBlocking { holders.coroutineContext[Job]!!.join() }
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            store.clear()
            await { job.isCompleted }
        }
        check(root.canonicalFile.parentFile == RuntimeEnvironment.getApplication().cacheDir.canonicalFile)
        root.deleteRecursively()
    }

    @Test fun anEditWaitingForOneBookDoesNotBlockAnotherBooksEditOrCloudAction() {
        val first = textBook("甲", "第一章\n甲的旧正文。")
        val second = textBook("乙", "第一章\n乙的旧正文。")
        openLibrary(first, second)
        val release = holdBook(first.id)
        vm.open(book(first.id))
        vm.editTxt(0, 4, 4, "甲的修改。")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(setOf(first.id), vm.editingBookIds)
        assertTrue(vm.textEditBusy)

        // Waiting for this book must not reserve every cloud transfer slot.
        runBlocking { withTimeout(2_000) { cloudOperationMutex.withBook("unrelated-${UUID.randomUUID()}") { } } }
        vm.open(book(second.id))
        assertFalse(vm.textEditBusy)
        vm.editTxt(0, 4, 4, "乙的修改。")
        await { second.id !in vm.editingBookIds && cachedText(second.id).contains("乙的修改。") }
        assertEquals(setOf(first.id), vm.editingBookIds)
        assertEquals(second.id, vm.activeBookId)
        assertFalse(vm.textEditBusy)
        vm.message = null
        vm.uploadCloudBook(book(second.id))
        assertEquals("请先配置 WebDAV 网盘", vm.message)

        release.complete(Unit)
        await { vm.editingBookIds.isEmpty() && cachedText(first.id).contains("甲的修改。") }
        assertFalse(vm.importing)
        assertFalse(vm.textEditBusy)
        val saved = LibraryRepository(app).load()
        for (id in listOf(first.id, second.id)) {
            val stored = saved.books.single { it.id == id }
            assertEquals(File(stored.sourcePath!!).readText(), stored.chapters.joinToString("\n\n") { File(it.textPath!!).readText() })
        }
        assertTrue(cachedText(second.id).contains("乙的修改。"))
    }

    @Test fun aFailedEditReleasesTheBookAndTransferSlotForTheNextEdit() {
        val original = textBook("失败后恢复", "第一章\n未修改正文。")
        openLibrary(original)
        vm.open(book(original.id))
        vm.editTxt(0, 100_000, 100_000, "无效位置")
        await { vm.editingBookIds.isEmpty() }
        assertFalse(vm.importing)
        assertFalse(vm.textEditBusy)
        assertTrue(vm.message.orEmpty().contains("编辑位置无效"))
        assertEquals("第一章\n未修改正文。", cachedText(original.id))
        runBlocking { withTimeout(2_000) { cloudOperationMutex.withBook(original.id) { } } }

        vm.editTxt(0, 4, 4, "恢复编辑。")
        await { vm.editingBookIds.isEmpty() && cachedText(original.id).contains("恢复编辑。") }
        assertFalse(vm.importing)
        assertTrue(File(LibraryRepository(app).load().books.single().sourcePath!!).readText().contains("恢复编辑。"))
    }

    @Test fun cancellingAnEditWaitingForItsBookClearsBusyStateAndAllowsANewEdit() {
        val original = textBook("取消后恢复", "第一章\n未修改正文。")
        openLibrary(original)
        vm.open(book(original.id))
        val release = holdBook(original.id)
        val parent = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        val existing = parent.children.toSet()
        vm.editTxt(0, 4, 4, "取消的修改。")
        val edit = parent.children.single { it !in existing }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(setOf(original.id), vm.editingBookIds)

        edit.cancel()
        await { edit.isCompleted && vm.editingBookIds.isEmpty() }
        assertFalse(vm.importing)
        assertFalse(vm.textEditBusy)
        assertEquals("第一章\n未修改正文。", cachedText(original.id))
        release.complete(Unit)
        runBlocking { withTimeout(2_000) { cloudOperationMutex.withBook(original.id) { } } }

        vm.editTxt(0, 4, 4, "真正保存。")
        await { vm.editingBookIds.isEmpty() && cachedText(original.id).contains("真正保存。") }
        assertFalse(cachedText(original.id).contains("取消的修改。"))
    }

    private fun textBook(title: String, text: String): Book {
        val source = File(root, "$title.txt").apply { writeText(text) }
        return Book(title = title, format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            sourcePath = source.absolutePath,
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath, characterCount = text.length)))
    }

    private fun openLibrary(vararg books: Book) {
        LibraryRepository(app).save(LibraryState(books = books.toList()))
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading }
        assertFalse(vm.cloudConfigured)
    }

    private fun book(id: String) = vm.state.books.single { it.id == id }
    private fun cachedText(id: String) = book(id).chapters.joinToString("\n\n") { File(it.textPath!!).readText() }

    private fun holdBook(id: String): CompletableDeferred<Unit> {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { releases += it }
        holders.launch { cloudOperationMutex.withBook(id) { acquired.complete(Unit); release.await() } }
        runBlocking { withTimeout(2_000) { acquired.await() } }
        return release
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for isolated edit: ${vm.message}; editing=${vm.editingBookIds}")
    }
}
