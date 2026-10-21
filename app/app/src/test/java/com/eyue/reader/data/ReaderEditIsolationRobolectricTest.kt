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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        await { second.id !in vm.editingBookIds && vm.hasBookDraft(second.id) }
        assertFalse(cachedText(second.id).contains("乙的修改。"))
        vm.saveBookDraft(second.id)
        await { second.id !in vm.editingBookIds && !vm.hasBookDraft(second.id) }
        assertEquals(setOf(first.id), vm.editingBookIds)
        assertEquals(second.id, vm.activeBookId)
        assertFalse(vm.textEditBusy)
        vm.message = null
        vm.uploadCloudBook(book(second.id))
        assertEquals("请先配置 WebDAV 网盘", vm.message)

        release.complete(Unit)
        await { vm.editingBookIds.isEmpty() && vm.hasBookDraft(first.id) }
        vm.saveBookDraft(first.id)
        await { vm.editingBookIds.isEmpty() && !vm.hasBookDraft(first.id) }
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
        await { vm.editingBookIds.isEmpty() && vm.hasBookDraft(original.id) }
        vm.saveBookDraft(original.id)
        await { vm.editingBookIds.isEmpty() && !vm.hasBookDraft(original.id) }
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
        assertFalse(vm.hasBookDraft(original.id))
        assertEquals("第一章\n未修改正文。", cachedText(original.id))
        release.complete(Unit)
        runBlocking { withTimeout(2_000) { cloudOperationMutex.withBook(original.id) { } } }

        vm.editTxt(0, 4, 4, "真正保存。")
        await { vm.editingBookIds.isEmpty() && vm.hasBookDraft(original.id) }
        vm.saveBookDraft(original.id)
        await { vm.editingBookIds.isEmpty() && !vm.hasBookDraft(original.id) }
        assertFalse(cachedText(original.id).contains("取消的修改。"))
    }

    @Test fun repeatedPreviewEditsPositionAndNotesStayOutOfSavedStateUntilManualSave() {
        val original = textBook("预览隔离", "第一章\n旧正文，保留后文。")
        openLibrary(original)
        vm.open(book(original.id))
        await { LibraryRepository(app).load().books.single().lastReadAt > 0 }
        val base = book(original.id)
        vm.editTxt(0, 4, 7, "首次预览")
        await { !vm.importing && vm.hasBookDraft(original.id) }
        vm.replaceBookMatch(0, 4, "首次预览", "再次预览")
        await { !vm.importing && vm.activeReaderBook!!.contentVersion == base.contentVersion + 2 }
        val previewText = File(vm.activeReaderBook!!.chapters.single().textPath!!).readText()
        assertEquals("第一章\n再次预览，保留后文。", previewText)
        vm.progress(0, 1, 8, .5f)
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 1, characterOffset = 8, note = "预览内记录")
        vm.annotate(note)
        vm.settings(vm.readerSettings.copy(fontSize = 23f))
        vm.flush()
        await { LibraryRepository(app).load().settings.fontSize == 23f }
        val stored = LibraryRepository(app).load()
        assertEquals(base.contentVersion, stored.books.single().contentVersion)
        assertEquals(base.characterOffset, stored.books.single().characterOffset)
        assertEquals("第一章\n旧正文，保留后文。", File(original.sourcePath!!).readText())
        assertTrue(stored.annotations.isEmpty())
        assertEquals(listOf(note), vm.readerAnnotations(original.id))
        vm.uploadCloudBook(base)
        assertTrue(vm.message.orEmpty().contains("请先保存"))
        val temporary = File(vm.activeReaderBook!!.chapters.single().textPath!!).parentFile!!
        vm.discardBookDraft(original.id)
        assertFalse(vm.hasBookDraft(original.id))
        assertEquals(base.characterOffset, vm.activeReaderBook!!.characterOffset)
        assertTrue(vm.readerAnnotations(original.id).isEmpty())
        await { !temporary.exists() }
    }

    @Test fun cancellingASaveKeepsDraftAndRetryPublishesItBeforeCallingOnSaved() {
        val original = textBook("手动保存", "第一章\n旧正文。")
        openLibrary(original)
        vm.open(book(original.id))
        vm.editTxt(0, 4, 7, "等待手动保存")
        await { !vm.importing && vm.hasBookDraft(original.id) }
        val release = holdBook(original.id)
        val parent = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        val existing = parent.children.toSet()
        var called = false
        vm.saveBookDraft(original.id) { called = true }
        val saving = parent.children.single { it !in existing }
        assertTrue(original.id in vm.bookSaveProgress)
        assertFalse(called)
        saving.cancel()
        await { saving.isCompleted && !vm.importing }
        assertTrue(vm.hasBookDraft(original.id))
        assertTrue(vm.bookSaveProgress.isEmpty())
        assertEquals("第一章\n旧正文。", File(original.sourcePath!!).readText())
        assertFalse(called)
        release.complete(Unit)
        vm.saveBookDraft(original.id) {
            val committed = LibraryRepository(app).load().books.single()
            assertTrue(File(committed.sourcePath!!).readText().contains("等待手动保存"))
            assertFalse(vm.hasBookDraft(original.id))
            called = true
        }
        await { called && !vm.importing }
        assertTrue(vm.bookSaveProgress.isEmpty())
        assertEquals(original.sourcePath, book(original.id).sourcePath)
        assertEquals(cachedText(original.id), File(original.sourcePath!!).readText())
    }

    @Test fun missingSourceSaveFailureAndPermissionCancellationDoNotDiscardPreview() {
        val original = textBook("保留失败预览", "第一章\n旧正文。")
        // The independent read cache remains readable when the original source is temporarily unavailable.
        val cache = File(root, "failure-cache.txt").apply { writeText(File(original.sourcePath!!).readText()) }
        openLibrary(original.copy(chapters = listOf(original.chapters.single().copy(textPath = cache.absolutePath))))
        vm.open(book(original.id))
        vm.editTxt(0, 4, 7, "失败后仍保留")
        await { !vm.importing && vm.hasBookDraft(original.id) }
        val source = File(original.sourcePath!!)
        val bytes = source.readBytes()
        assertTrue(source.delete())
        var called = false
        vm.saveBookDraft(original.id) { called = true }
        await { !vm.importing }
        assertFalse(called)
        assertTrue(vm.hasBookDraft(original.id))
        assertEquals(original.sourcePath, vm.pendingSourceWriteUri)
        vm.cancelSourceWrite()
        assertNull(vm.pendingSourceWriteUri)
        assertTrue(vm.hasBookDraft(original.id))
        assertTrue(File(vm.activeReaderBook!!.chapters.single().textPath!!).readText().contains("失败后仍保留"))
        source.writeBytes(bytes)
        vm.saveBookDraft(original.id) { called = true }
        await { called && !vm.importing }
        assertFalse(vm.hasBookDraft(original.id))
        assertTrue(source.readText().contains("失败后仍保留"))
    }

    @Test fun batchRemovalPreflightsUnsavedBooksAndKeepsExternalSources() {
        val first = textBook("批量移除甲", "第一章\n甲正文。")
        val second = textBook("批量移除乙", "第一章\n乙正文。")
        openLibrary(first, second)
        vm.open(book(first.id))
        vm.annotate(Annotation(bookId = first.id, chapterIndex = 0, pageIndex = 0, note = "一并移除"))
        vm.editTxt(0, 4, 4, "未保存。")
        await { !vm.importing && vm.hasBookDraft(first.id) }

        vm.removeBooks(listOf(first, second), false)
        assertTrue(vm.message.orEmpty().contains("未保存"))
        assertEquals(setOf(first.id, second.id), vm.state.books.map { it.id }.toSet())
        assertTrue(vm.hasBookDraft(first.id))
        vm.discardBookDraft(first.id)
        val vanished = first.copy(id = "already-removed-${UUID.randomUUID()}")
        vm.removeBooks(listOf(first, first, second, vanished), false)
        await { vm.state.books.isEmpty() && LibraryRepository(app).load().books.isEmpty() }
        assertTrue(vm.state.annotations.isEmpty())
        assertEquals("第一章\n甲正文。", File(first.sourcePath!!).readText())
        assertEquals("第一章\n乙正文。", File(second.sourcePath!!).readText())
    }

    @Test fun notesAndReadingPositionChangedWhileSavingAreMergedWithoutRewritingTheSource() {
        val originalText = "第一章\n旧正文，保留后文。"
        val original = textBook("保存期间继续阅读", originalText)
        val cache = File(root, "separate-reading-cache.txt").apply { writeText(originalText) }
        openLibrary(original.copy(chapters = listOf(original.chapters.single().copy(textPath = cache.absolutePath))))
        vm.open(book(original.id))
        val oldNote = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, note = "保存开始时的笔记")
        vm.annotate(oldNote)
        vm.flush()
        await { LibraryRepository(app).load().annotations == listOf(oldNote) }
        vm.editTxt(0, 4, 7, "修改正文")
        await { !vm.importing && vm.hasBookDraft(original.id) }
        val release = holdPersistence()
        var called = false
        vm.saveBookDraft(original.id) { called = true }
        await { vm.bookSaveProgress[original.id]?.stage == "保存阅读记录" }
        val source = File(original.sourcePath!!)
        assertTrue(source.readText().contains("修改正文"))
        val writeTime = 946684800000L
        assertTrue(source.setLastModified(writeTime))
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 2,
            characterOffset = 9, note = "保存中新增，必须保留")
        vm.progress(0, 2, 9, .7f)
        vm.deleteAnnotation(oldNote.id)
        vm.annotate(note)
        assertEquals(listOf(note), vm.readerAnnotations(original.id))
        assertFalse(vm.state.annotations.any { it.id == note.id })

        release.complete(Unit)
        await { called && !vm.importing }
        assertFalse(vm.hasBookDraft(original.id))
        assertEquals(listOf(note), vm.state.annotations)
        assertEquals(9, book(original.id).characterOffset)
        assertEquals(2, book(original.id).pageIndex)
        assertEquals(.7f, book(original.id).progress)
        vm.flush()
        await { LibraryRepository(app).load().let { it.annotations == listOf(note) && it.books.single().characterOffset == 9 } }
        assertEquals(writeTime, source.lastModified())
        assertTrue(source.readText().contains("修改正文"))
    }

    @Test fun localRemovalMarksItsBookBusyImmediatelyAndCancellationReleasesIt() {
        val original = textBook("移除期间禁止同书编辑", "第一章\n原正文。")
        openLibrary(original)
        val release = holdBook(original.id)
        val parent = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        val existing = parent.children.toSet()
        vm.removeBooks(listOf(original), false)
        val removal = parent.children.single { it !in existing }
        assertEquals("正在移除书籍", vm.cloudTransfers[original.id]?.stage)
        vm.open(book(original.id))
        vm.editTxt(0, 4, 4, "不能成为预览")
        assertFalse(vm.hasBookDraft(original.id))
        assertTrue(vm.editingBookIds.isEmpty())
        assertTrue(vm.message.orEmpty().contains("请等待"))
        removal.cancel()
        await { removal.isCompleted && vm.cloudTransfers.isEmpty() }
        assertTrue(vm.state.books.any { it.id == original.id })
        assertEquals("第一章\n原正文。", File(original.sourcePath!!).readText())
        release.complete(Unit)
        vm.removeBooks(listOf(original), false)
        await { vm.state.books.isEmpty() && vm.cloudTransfers.isEmpty() }
        assertFalse(vm.hasBookDraft(original.id))
        assertEquals("第一章\n原正文。", File(original.sourcePath!!).readText())
    }

    @Test fun progressOpenAndFlushOnlyWritePositionsAfterTheInitialCatalogHasSettled() {
        val original = textBook("独立阅读位置", "第一章\n正文。")
        openLibrary(original)
        vm.flush()
        await { File(app.filesDir, "reading_positions.json").exists() }
        val metadata = File(app.filesDir, "library.json").readBytes()
        vm.open(book(original.id))
        vm.progress(0, 2, 6, .65f, imagePath = "当前图片.png")
        vm.flush()
        await { LibraryRepository(app).load().books.single().characterOffset == 6 }
        assertArrayEquals(metadata, File(app.filesDir, "library.json").readBytes())
        val stored = LibraryRepository(app).load().books.single()
        assertEquals(2, stored.pageIndex)
        assertEquals(.65f, stored.progress)
        assertEquals("当前图片.png", stored.positionImagePath)
        assertTrue(stored.lastReadAt > 0)
    }

    @Test fun cancellingAPendingCatalogWriteForNewProgressKeepsBothChanges() {
        val original = textBook("取消待写任务", "第一章\n正文。")
        openLibrary(original)
        vm.open(book(original.id))
        val release = holdPersistence()
        vm.settings(vm.readerSettings.copy(fontSize = 27f))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        vm.progress(0, 3, 7, .75f)
        vm.flush()
        release.complete(Unit)
        await { LibraryRepository(app).load().let { it.settings.fontSize == 27f && it.books.single().characterOffset == 7 } }
        val metadata = File(app.filesDir, "library.json").readBytes()
        vm.progress(0, 4, 8, .85f)
        vm.flush()
        await { LibraryRepository(app).load().books.single().characterOffset == 8 }
        assertArrayEquals(metadata, File(app.filesDir, "library.json").readBytes())
    }

    @Test fun aLateProgressCallbackFromThePreviousReaderDoesNotChangeEitherBooksSavedPosition() {
        val first = textBook("旧阅读会话", "第一章\n甲正文。")
        val second = textBook("当前阅读会话", "第一章\n乙正文。")
        openLibrary(first, second)
        vm.open(book(first.id))
        vm.progress(0, 1, 4, .25f, bookId = first.id)
        vm.open(book(second.id))
        vm.progress(0, 2, 6, .75f, bookId = second.id)
        vm.progress(0, 99, 999, 1f, bookId = first.id)
        vm.flush()
        await { LibraryRepository(app).load().books.first { it.id == second.id }.characterOffset == 6 }
        val saved = LibraryRepository(app).load()
        assertEquals(4, saved.books.first { it.id == first.id }.characterOffset)
        assertEquals(.25f, saved.books.first { it.id == first.id }.progress)
        assertEquals(6, saved.books.first { it.id == second.id }.characterOffset)
        assertEquals(.75f, saved.books.first { it.id == second.id }.progress)
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

    private fun holdPersistence(): CompletableDeferred<Unit> {
        val saves = ReaderViewModel::class.java.getDeclaredField("saves").apply { isAccessible = true }.get(vm)
        val mutex = LibrarySaveCoordinator::class.java.getDeclaredField("mutex").apply { isAccessible = true }.get(saves) as Mutex
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().also { releases += it }
        holders.launch { mutex.withLock { acquired.complete(Unit); release.await() } }
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
