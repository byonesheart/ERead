package com.eyue.reader.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportQueueRobolectricTest {
    private lateinit var root: File
    private lateinit var app: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val opened = ConcurrentHashMap.newKeySet<String>()
    private val authority = "import-queue-${UUID.randomUUID()}"
    private val editLockOwner = Any()
    private var editLockHeld = false

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse { WorkManager.initialize(base, Configuration.Builder().build()) }
        val prefix = "import-queue-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        app = object : Application() { init { attachBaseContext(isolated) } }
        app.getSharedPreferences("onboarding", 0).edit().putBoolean("demo_seeded", true).commit()
        LibraryRepository(app).save(LibraryState())
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = when (uri.lastPathSegment.orEmpty().substringAfterLast('.')) {
                "png" -> "image/png"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(arrayOf<Any?>(uri.lastPathSegment, File(root, uri.pathSegments.joinToString("/")).length()))
                }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val name = requireNotNull(uri.lastPathSegment)
                opened += name
                check(gates[name]?.await(15, TimeUnit.SECONDS) != false) { "Test source did not release" }
                return ParcelFileDescriptor.open(File(root, uri.pathSegments.joinToString("/")), ParcelFileDescriptor.parseMode(mode))
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { this.authority = this@ImportQueueRobolectricTest.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @After fun cleanup() {
        gates.values.forEach { it.countDown() }
        if (editLockHeld) { cloudOperationMutex.unlock(editLockOwner); editLockHeld = false }
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            store.clear()
            await { job.isCompleted }
        }
        check(root.canonicalFile.parentFile == RuntimeEnvironment.getApplication().cacheDir.canonicalFile)
        root.deleteRecursively()
    }

    @Test fun anotherBatchQueuesWhileParsingAndCompletedBooksAreReadableBeforeTheBatchEnds() {
        val first = source("first.txt")
        val held = source("held.txt", held = true)
        val last = source("last.txt")
        openLibrary()
        vm.importFiles(listOf(first, held), LibraryMode.NOVEL)
        await { "held.txt" in opened && vm.state.books.size == 1 }
        val imported = vm.state.books.single()
        assertEquals("first", imported.title)
        assertEquals(1, vm.pendingImport!!.completed)
        assertEquals(1, LibraryRepository(app).load().books.size)
        vm.open(imported)
        await { vm.activeBookId == imported.id }

        vm.importFiles(listOf(last), LibraryMode.NOVEL)
        assertEquals(2, vm.pendingImports.size)
        assertFalse(vm.pendingImports[0].queued)
        assertTrue(vm.pendingImports[1].queued)
        assertNull(vm.pendingImports[1].progress)
        assertFalse("last.txt" in opened)
        assertTrue(vm.importing)

        gates.getValue("held.txt").countDown()
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 3 }
        assertEquals(setOf("first", "held", "last"), vm.state.books.map { it.title }.toSet())
        assertEquals(imported.id, vm.activeBookId)
        assertEquals(3, LibraryRepository(app).load().books.size)
        assertNull("Successful import should not show a toast", vm.message)
    }

    @Test fun importingAnotherBookDoesNotRejectAnExistingBooksCloudActionAtTheImportGate() {
        val existing = source("existing.txt")
        val held = source("held.txt", held = true)
        openLibrary()
        vm.importFiles(listOf(existing), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val book = vm.state.books.single()
        vm.importFiles(listOf(held), LibraryMode.NOVEL)
        await { "held.txt" in opened }
        assertTrue(vm.importing)
        vm.uploadCloudBook(book)
        assertEquals("请先配置 WebDAV 网盘", vm.message)
        vm.message = null
        vm.deleteCloudBook(book.copy(cloudEnabled = true))
        await { vm.cloudTransfers.isEmpty() && vm.message != null }
        assertEquals("请先配置 WebDAV 网盘", vm.message)
        assertTrue(vm.importing)
        assertEquals(listOf(book), vm.state.books)
        gates.getValue("held.txt").countDown()
        await { !vm.importing && vm.state.books.size == 2 }
    }

    @Test fun aFailedFileDoesNotRemoveSuccessfulBooksOrLeakItsErrorsIntoTheNextBatch() {
        val first = source("good.txt")
        val held = source("held.txt", held = true)
        val invalid = File(root, "broken.mobi").apply { writeText("not a MOBI file") }
        val last = source("next.txt")
        openLibrary()
        vm.importFiles(listOf(first, Uri.fromFile(invalid), held), LibraryMode.NOVEL)
        await { "held.txt" in opened && vm.state.books.size == 1 }
        vm.importFiles(listOf(last), LibraryMode.NOVEL)
        gates.getValue("held.txt").countDown()
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 3 }
        assertEquals(setOf("good", "held", "next"), vm.state.books.map { it.title }.toSet())
        assertFalse(vm.message.orEmpty().contains("broken"))
        assertNull(vm.message)
    }

    @Test fun unsupportedFileReportsItsNameAndClearsThePlaceholderBeforeTheNextImport() {
        val unsupported = File(root, "archive.zip").apply { writeText("not a readable book") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(unsupported)), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() }

        assertTrue(vm.state.books.isEmpty())
        assertTrue(vm.message.orEmpty().contains("archive.zip"))
        assertTrue(vm.message.orEmpty().contains("不支持的文件格式"))
        assertNull(vm.importProgress)

        vm.importFiles(listOf(source("after-unsupported.txt")), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 1 }
        assertEquals("after-unsupported", vm.state.books.single().title)
        assertNull(vm.message)
    }

    @Test fun mixedSelectionKeepsReadableBooksAndReportsTheUnsupportedFile() {
        val unsupported = File(root, "notes.docx").apply { writeText("unsupported document") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(unsupported), source("readable.txt")), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() }

        assertEquals(listOf("readable"), vm.state.books.map { it.title })
        assertEquals(1, LibraryRepository(app).load().books.size)
        assertTrue(vm.message.orEmpty().contains("notes.docx"))
        assertTrue(vm.message.orEmpty().contains("不支持的文件格式"))
    }

    @Test fun emptyTextReportsFailureWithoutKeepingAPartialBookOrBlockingTheQueue() {
        val empty = File(root, "empty.txt").apply { writeText(" \n\t") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(empty)), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() }

        assertTrue(vm.state.books.isEmpty())
        assertTrue(vm.message.orEmpty().contains("empty.txt"))
        assertTrue(vm.message.orEmpty().contains("TXT 文件为空"))
        assertTrue(File(app.filesDir, "books").listFiles().orEmpty().isEmpty())

        vm.importFiles(listOf(source("after-empty.txt")), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 1 }
        assertEquals("after-empty", vm.state.books.single().title)
        assertNull(vm.message)
    }

    @Test fun externalOpenKeepsRequestDeduplicationWhenItWaitsBehindAnotherImport() {
        val held = source("held.txt", held = true)
        val external = source("external.txt")
        openLibrary()
        vm.importFiles(listOf(held), LibraryMode.NOVEL)
        await { "held.txt" in opened }
        vm.openExternalFiles(listOf(external), "text/plain", "same-external-request")
        vm.openExternalFiles(listOf(external), "text/plain", "same-external-request")
        assertEquals(2, vm.pendingImports.size)
        gates.getValue("held.txt").countDown()
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.activeBookId != null }
        assertEquals(2, vm.state.books.size)
        assertEquals("external", vm.state.books.single { it.id == vm.activeBookId }.title)
        assertEquals("same-external-request", app.getSharedPreferences("external_open", 0).getString("request_id", null))
    }

    @Test fun importsReceivedBeforeLibraryLoadWaitWithoutReplacingTheLoadedShelf() {
        val source = source("early.txt")
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        vm.importFiles(listOf(source), LibraryMode.NOVEL)
        assertEquals(1, vm.pendingImports.size)
        assertTrue(vm.pendingImports.single().queued)
        await { !vm.loading && !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(listOf("early"), vm.state.books.map { it.title })
    }

    @Test fun anotherImportWaitsForPreviewPreparationAndDoesNotSaveItsDraft() {
        val original = source("editable.txt")
        val next = source("after-edit.txt")
        openLibrary()
        vm.importFiles(listOf(original), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 1 }
        val book = vm.state.books.single()
        vm.open(book)
        await { vm.activeBookId == book.id }
        runBlocking { cloudOperationMutex.lock(editLockOwner) }
        editLockHeld = true
        vm.editTxt(0, 0, 0, "保留下来的修改。\n")
        assertTrue(vm.importing)
        vm.importFiles(listOf(next), LibraryMode.NOVEL)
        assertTrue(vm.pendingImports.single().queued)
        repeat(5) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        assertFalse("after-edit.txt" in opened)
        assertEquals(1, vm.state.books.size)
        cloudOperationMutex.unlock(editLockOwner)
        editLockHeld = false
        await { !vm.importing && vm.pendingImports.isEmpty() && vm.state.books.size == 2 }
        assertTrue(vm.hasBookDraft(book.id))
        assertFalse(LibraryRepository(app).load().books.single { it.id == book.id }.chapters.map {
            File(requireNotNull(it.textPath)).readText()
        }.joinToString("\n").contains("保留下来的修改。"))
        vm.saveBookDraft(book.id)
        await { !vm.importing && !vm.hasBookDraft(book.id) }
        val saved = LibraryRepository(app).load().books.single { it.id == book.id }
        assertTrue(saved.chapters.map { File(requireNotNull(it.textPath)).readText() }.joinToString("\n").contains("保留下来的修改。"))
    }

    @Test fun importerPublishesEachBookBeforeReadingTheNextAndKeepsImagesTogether() = runBlocking<Unit> {
        val first = source("one.txt")
        val second = source("two.txt")
        val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Book>()
        val importer = DocumentImporter(app)
        val task = async(Dispatchers.IO) {
            importer.importFiles(listOf(first, second), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE) {
                if (it.title == "one") { delivered.complete(it); release.await() }
            }
        }
        try {
            assertEquals("one", delivered.await().title)
            assertFalse("two.txt" in opened)
        } finally { release.complete(Unit) }
        assertEquals(listOf("one", "two"), task.await().map { it.title })

        val imageFiles = (1..2).map { index -> File(root, "$index.png").apply {
            val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try { writeBytes(java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }) } finally { bitmap.recycle() }
        } }
        var calls = 0
        val images = importer.importFiles(imageFiles.map { Uri.parse("content://$authority/${it.name}") },
            LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE) { calls++ }
        assertEquals(1, calls)
        assertEquals(1, images.size)
        assertEquals(LibraryMode.COMIC, images.single().mode)
        assertEquals(2, images.single().chapters.sumOf { it.pages.size })
    }

    @Test fun identicalOriginalFileIsNotAddedAgainAndItsNotesAndReadingPositionRemain() {
        val uri = source("duplicate.txt")
        openLibrary()
        vm.importFiles(listOf(uri), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val original = vm.state.books.single()
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, note = "保留已有笔记")
        vm.annotate(note)
        vm.importFiles(listOf(uri), LibraryMode.NOVEL)
        await { vm.duplicateImportTitle != null }
        assertEquals(original.title, vm.duplicateImportTitle)
        vm.acknowledgeDuplicateImport()
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(listOf(original), vm.state.books)
        assertEquals(listOf(note), vm.state.annotations)
        assertNull(vm.pendingImportConflict)
        assertNull(vm.message)
        assertEquals(1, File(app.filesDir, "books").listFiles()!!.size)
    }

    @Test fun uncertainSameNameWaitsForSelectionAndSkippingPreservesOldBookAndContinuesBatch() {
        val left = File(root, "left/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n已有内容。") }
        val right = File(root, "right/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n不同的内容，长度也不同。") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(left)), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val original = vm.state.books.single()
        vm.importFiles(listOf(sourceUri(right), source("after-conflict.txt")), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        assertEquals(listOf(original.id), vm.pendingImportConflict!!.existing.map { it.id })
        assertEquals(listOf(original), vm.state.books)
        assertFalse("after-conflict.txt" in opened)
        vm.resolveImportConflict(emptySet())
        assertNotNull(vm.pendingImportConflict)
        vm.resolveImportConflict(setOf(original.id))
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(setOf(original.id, vm.state.books.single { it.title == "after-conflict" }.id), vm.state.books.map { it.id }.toSet())
        assertTrue(left.exists())
        assertTrue(right.exists())
        assertEquals(2, File(app.filesDir, "books").listFiles()!!.size)
    }

    @Test fun uncertainSameNameCanKeepBothOrReplaceOnlyTheChosenExistingCopy() {
        val left = File(root, "left/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n已有内容。") }
        val right = File(root, "right/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n不同的内容，长度也不同。") }
        val third = File(root, "third/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n另一个版本的内容，长度明显更长一些。") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(left)), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val original = vm.state.books.single()
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, note = "旧副本笔记")
        vm.annotate(note)
        vm.importFiles(listOf(sourceUri(right)), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        val secondId = vm.pendingImportConflict!!.incoming.id
        vm.resolveImportConflict(setOf(original.id, secondId))
        await { !vm.importing && vm.state.books.size == 2 }
        assertEquals(listOf(note), vm.state.annotations)
        vm.importFiles(listOf(sourceUri(third)), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        val thirdId = vm.pendingImportConflict!!.incoming.id
        vm.resolveImportConflict(setOf(secondId, thirdId))
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(setOf(secondId, thirdId), vm.state.books.map { it.id }.toSet())
        assertTrue(vm.state.annotations.isEmpty())
        assertFalse(File(app.filesDir, "books/${original.id}").exists())
        assertTrue(listOf(left, right, third).all { it.exists() })
        assertEquals(setOf(secondId, thirdId), LibraryRepository(app).load().books.map { it.id }.toSet())
    }

    @Test fun keepingOnlyOneExistingCopyRemovesUnselectedExistingAndImportedCopies() {
        val sources = (1..3).map { index -> File(root, "$index/same.txt").apply {
            parentFile!!.mkdirs(); writeText("第一章\n${"不同的内容。".repeat(index)}")
        } }
        openLibrary()
        vm.importFiles(listOf(sourceUri(sources[0])), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val first = vm.state.books.single()
        vm.importFiles(listOf(sourceUri(sources[1])), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        val secondId = vm.pendingImportConflict!!.incoming.id
        vm.resolveImportConflict(setOf(first.id, secondId))
        await { !vm.importing && vm.state.books.size == 2 }
        vm.importFiles(listOf(sourceUri(sources[2])), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        vm.resolveImportConflict(setOf(secondId))
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(listOf(secondId), vm.state.books.map { it.id })
        assertEquals(listOf(secondId), File(app.filesDir, "books").listFiles()!!.map { it.name })
        assertTrue(sources.all { it.exists() })
    }

    @Test fun cancellingAReplacementBeforeItsCommitKeepsTheOriginalBookAndNotes() {
        val left = File(root, "left/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n已有内容。") }
        val right = File(root, "right/same.txt").apply { parentFile!!.mkdirs(); writeText("第一章\n不同的内容，长度也不同。") }
        openLibrary()
        vm.importFiles(listOf(sourceUri(left)), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val original = vm.state.books.single()
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, note = "不得丢失的已有笔记")
        vm.annotate(note)
        vm.flush()
        await { LibraryRepository(app).load().annotations.contains(note) }
        vm.importFiles(listOf(sourceUri(right)), LibraryMode.NOVEL)
        await { vm.pendingImportConflict != null }
        runBlocking { cloudOperationMutex.lock(editLockOwner) }
        editLockHeld = true
        vm.resolveImportConflict(setOf(vm.pendingImportConflict!!.incoming.id))
        await { vm.pendingImportConflict == null }
        val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
        store.clear()
        await { job.isCompleted }
        assertEquals(listOf(original), LibraryRepository(app).load().books)
        assertEquals(listOf(note), LibraryRepository(app).load().annotations)
        assertEquals(listOf(original.id), File(app.filesDir, "books").listFiles()!!.map { it.name })
        assertTrue(left.exists())
        assertTrue(right.exists())
    }

    @Test fun aNewBookCanFinishImportingWhileOtherBooksOccupyAllCloudTransferSlots() {
        val source = source("during-cloud-transfers.txt")
        openLibrary()
        runBlocking { cloudOperationMutex.lock(editLockOwner) }
        editLockHeld = true
        vm.importFiles(listOf(source), LibraryMode.NOVEL)
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(listOf("during-cloud-transfers"), vm.state.books.map { it.title })
        assertNull(vm.message)
    }

    private fun sourceUri(file: File): Uri = Uri.parse("content://$authority/${file.relativeTo(root).invariantSeparatorsPath}")

    @Test fun externalExistingBookOpensWithoutReopeningOrReparsingItsProviderStream() {
        val source = sourceUri(EpubFixtures.create(app, root, "external-fast"))
        openLibrary()
        vm.importFiles(listOf(source), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val book = vm.state.books.single()
        opened.clear()
        assertFalse(book.sourceLinked)
        gates["external-fast.epub"] = CountDownLatch(1)
        vm.openExternalFiles(listOf(source), "application/epub+zip", "existing-fast")
        await { vm.activeBookId == book.id && !vm.importing }
        assertEquals(1, vm.state.books.size)
        assertFalse("The existing private copy should open before any provider stream is read", "external-fast.epub" in opened)
    }

    @Test fun externalOpeningIsVisibleImmediatelyAndBackKeepsImportWithoutAnUnexpectedJump() {
        val source = source("external-held.txt", held = true)
        openLibrary()
        vm.openExternalFiles(listOf(source), "text/plain", "held-external")
        assertNotNull(vm.openingExternalImport)
        await { "external-held.txt" in opened }
        vm.dismissExternalOpening()
        assertNull(vm.openingExternalImport)
        assertEquals(1, vm.pendingImports.size)
        gates.getValue("external-held.txt").countDown()
        await { !vm.importing && vm.state.books.size == 1 }
        assertNull(vm.activeBookId)
    }

    @Test fun selectingAnExistingBookForImportDoesNotReadOrParseItsFileAgain() {
        val source = sourceUri(EpubFixtures.create(app, root, "already-imported"))
        openLibrary()
        vm.importFiles(listOf(source), LibraryMode.NOVEL)
        await { !vm.importing && vm.state.books.size == 1 }
        val id = vm.state.books.single().id
        opened.clear()
        gates["already-imported.epub"] = CountDownLatch(1)
        vm.importFiles(listOf(source), LibraryMode.NOVEL)
        await { vm.duplicateImportTitle != null }
        vm.acknowledgeDuplicateImport()
        await { !vm.importing && vm.pendingImports.isEmpty() }
        assertEquals(listOf(id), vm.state.books.map { it.id })
        assertFalse("Identity metadata must be checked before copying or parsing the file", "already-imported.epub" in opened)
    }

    @Test fun externalTextDoesNotWaitForReaderViewportOrImportPagination() {
        openLibrary()
        vm.settings(ReaderSettings(readingMode = ReadingMode.PAGED))
        vm.openExternalFiles(listOf(source("external-paged.txt")), "text/plain", "external-no-layout")
        await { !vm.importing && vm.activeBookId != null }
        assertNull(vm.message)
    }

    private fun source(name: String, held: Boolean = false): Uri {
        File(root, name).writeText("第一章 开始\n这是一段用于真实导入回归的正文。\n\n第二章 后续\n正文内容继续。")
        if (held) gates[name] = CountDownLatch(1)
        return Uri.parse("content://$authority/$name")
    }

    private fun openLibrary() {
        vm = ReaderViewModel(app)
        store.put("reader", vm)
        await { !vm.loading }
        assertTrue(vm.state.books.isEmpty())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for import queue")
    }
}
