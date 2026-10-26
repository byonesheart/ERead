package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import com.eyue.reader.ui.ReaderChapterStore
import com.eyue.reader.ui.readerChapterLengths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeferredEbookContentRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        root = File("build/deferred-ebook-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun book(sharedDocument: Boolean = false): Pair<File, Book> {
        val original = EpubFixtures.create(context, root, "deferred", oneSpine = sharedDocument, footnotes = true)
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "books/$id").apply { mkdirs() }
        // Recreate a partially prepared 1.11.7 book to verify existing libraries remain readable.
        val source = original.copyTo(File(folder, "source.epub"))
        val parsed = EpubArchive.read(source, catalogOnly = true)
        val chapters = DeferredEbookContent.epub(source, parsed, folder).toMutableList()
        chapters[0] = DeferredEbookContent.ensure(chapters[0])
        return original to Book(id = id, title = parsed.title, format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = chapters, contentVersion = 10, sourcePath = source.absolutePath)
    }

    @Test fun importCachesEveryChapterAndKeepsTheCompleteOriginal() {
        val original = EpubFixtures.create(context, root, "complete", footnotes = true)
        val folder = File(context.filesDir, "books/complete").apply { mkdirs() }
        val book = EpubTestImporter(context).import(original, folder, "complete", "完整缓存")
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters.all { File(it.textPath!!).isFile && File(it.lazySourcePath!!).isFile && it.characterCount >= 0 })
        assertTrue(book.chapters.none { DeferredEbookContent.pending(it) })
        assertTrue(original.readBytes().contentEquals(File(book.sourcePath!!).readBytes()))
        assertNotEquals(original.canonicalPath, File(book.sourcePath!!).canonicalPath)
    }

    @Test fun openingAndLengthHintsNeverReadAnUnvisitedChapter() = runBlocking {
        val (_, book) = book()
        val store = ReaderChapterStore(context, book)
        val lengths = readerChapterLengths(context, book, allowScan = false)
        assertEquals(2, lengths.size)
        assertTrue(lengths.all { it > 0 })
        assertTrue(store.readText(0).contains("正文甲"))
        assertFalse(File(book.chapters[1].textPath!!).exists())
        assertTrue(DeferredEbookContent.pending(book.chapters[1]))
    }

    @Test fun requestedChapterIsPreparedAfterTheExternalFileIsGone() = runBlocking {
        val (original, book) = book()
        assertTrue(original.delete())
        val store = ReaderChapterStore(context, book)
        val text = store.readText(1)
        assertEquals("继续正文", text.trim())
        val source = EbookSourceStore(context, book)
        try {
            assertTrue(source.load(1, text).html.contains("继续正文"))
            assertFalse(DeferredEbookContent.pending(book.chapters[1]))
        } finally { source.close() }
    }

    @Test fun backgroundIndexCanStopAndResumeWithoutChangingChapterIdentityOrPosition() = runBlocking {
        val (_, initial) = book()
        val book = initial.copy(chapterIndex = 1, characterOffset = 2, progress = .75f)
        val ids = book.chapters.map { it.id }
        try {
            indexEbookChapters(book) { index, _ -> if (index == 0) throw CancellationException("暂停索引") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(File(book.chapters[1].textPath!!).exists())
        val lengths = mutableMapOf<Int, Int>()
        indexEbookChapters(book) { index, length -> lengths[index] = length }
        assertEquals(2, lengths.size)
        assertTrue(book.chapters.none { DeferredEbookContent.pending(it) })
        assertEquals(ids, book.chapters.map { it.id })
        assertEquals(1, book.chapterIndex)
        assertEquals(2, book.characterOffset)
        assertEquals(.75f, book.progress, 0f)
    }

    @Test fun twoReadersShareACompleteChapterCache() {
        val (_, book) = book()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reads = pool.invokeAll(List(2) { Callable { readChapterText(book, 1) } }).map { it.get() }
            assertEquals(reads.first(), reads.last())
            assertEquals(reads.first().length, DeferredEbookContent.cached(book.chapters[1])!!.characterCount)
        } finally { pool.shutdownNow() }
    }

    @Test fun cachedTextIsReusedEvenWhenTheDeferredSourceReferenceIsUnavailable() {
        val (_, book) = book()
        val expected = readChapterText(book, 1)
        File(book.chapters[1].lazySourcePath!! + ".deferred.json").writeText("not JSON")
        assertEquals(expected, readChapterText(book, 1))
    }

    @Test fun aSavedTextEditCannotBeOverwrittenByTheDeferredOriginal() {
        val (_, book) = book()
        val ready = DeferredEbookContent.ensure(book.chapters[1])
        File(ready.textPath!!).writeText("用户已经保存的新正文")
        DeferredEbookContent.ensure(ready)
        assertEquals("用户已经保存的新正文", File(ready.textPath).readText())
    }

    @Test fun sharedSpineAnchorsKeepTheirOwnTextAndSourceMarkup() = runBlocking {
        val (original, book) = book(sharedDocument = true)
        val old = EpubArchive.read(original)
        assertEquals(old.sections.size, book.chapters.size)
        for (index in book.chapters.indices) {
            val text = readChapterText(book, index)
            val expected = HtmlChapterParser.readPlainWithNotes(old.sections[index].title, old.sections[index].html,
                noteTargets = old.sections[index].sourceNoteTargets) { attributes ->
                sourceHtmlAttribute(attributes, "src")?.let { "image" }
            }
            assertEquals(expected.text, text)
        }
    }

    @Test fun backupMaterializesOnlyWhenExplicitlyRequestedAndKeepsFootnotes() = runBlocking {
        val (_, book) = book()
        assertFalse(File(book.chapters[1].textPath!!).exists())
        val backup = materializeCloudBook(context, book)
        assertTrue(backup.chapters.all { it.lazySourcePath == null && File(it.textPath!!).isFile })
        assertTrue(backup.chapters[0].sourceFootnotes.any { it.content.contains("标准 EPUB 注释正文") })
        assertEquals(book.chapters.map { it.id }, backup.chapters.map { it.id })
    }

    @Test fun indexingDerivedCachesDoesNotDirtyAnUneditedCloudBook() = runBlocking {
        val (_, book) = book()
        val state = LibraryState(books = listOf(book))
        val repository = LibraryRepository(context)
        val before = cloudBackupFingerprint(repository, state, book)
        indexEbookChapters(book) { _, _ -> }
        assertEquals(before, cloudBackupFingerprint(repository, state, book))
    }

    @Test fun actualFilesHaveCompleteImportCachesAndKeepTheirOriginalDirectories() = runBlocking<Unit> {
        val configured = System.getenv("EREAD_EBOOK_SMOKE_SOURCE")
        assumeTrue(!configured.isNullOrBlank())
        val sources = listOf(File(configured!!)) + System.getenv("EREAD_NOTES_SOURCE_DIR")?.let { directory ->
            File(directory).listFiles()?.filter { it.extension.lowercase() in setOf("epub", "mobi") }
        }.orEmpty()
        val measurements = org.json.JSONArray()
        for (source in sources) {
            val originalHash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val started = System.nanoTime()
            val id = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "books/$id").apply { mkdirs() }
            val imported = if (source.extension.equals("epub", true)) EpubTestImporter(context).import(source, folder, id, source.nameWithoutExtension)
                else DocumentImporter::class.java.declaredMethods.single { it.name == "importMobi" }.apply { isAccessible = true }
                    .invoke(DocumentImporter(context), source, folder, id, source.nameWithoutExtension, "(?!)", {}) as Book
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val ready = imported.chapters.count { File(it.textPath!!).isFile }
            assertEquals("All chapters must be cached at import", imported.chapters.size, ready)
            assertTrue(imported.chapters.none { DeferredEbookContent.pending(it) })
            val directory = if (imported.format == BookFormat.EPUB) EpubArchive.read(source).sections.map { it.title to it.tocParentTitle }
                else MobiArchive.read(source).also { assertTrue(it.hasEmbeddedToc) }.sections.map { it.title to it.tocParentTitle }
            assertEquals(directory, imported.chapters.map { it.title to it.tocParentTitle })
            val ids = imported.chapters.map { it.id }
            val store = ReaderChapterStore(context, imported)
            EbookSourceStore(context, imported).use { display ->
                for (index in listOf(0, imported.chapters.size / 2, imported.chapters.lastIndex).distinct()) {
                    val text = store.readText(index)
                    val document = display.load(index, text)
                    assertTrue(text.isNotBlank() || DeferredEbookContent.ensure(imported.chapters[index]).inlineImages.isNotEmpty())
                    assertEquals(text.filterNot(Char::isWhitespace), SourceHtmlEdit.plain(document.html) { "image" }.filterNot(Char::isWhitespace))
                }
            }
            assertEquals(ids, imported.chapters.map { it.id })
            assertArrayEquals(originalHash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
            measurements.put(JSONObject().put("format", imported.format.name).put("sourceBytes", source.length())
                .put("chapters", imported.chapters.size).put("initialReady", ready).put("importMs", elapsed))
        }
        System.getenv("EREAD_TEST_ARTIFACTS")?.let { directory -> File(directory).apply { mkdirs() }
            .resolve("complete-import-measurements.json").writeText(measurements.toString(2)) }
    }
}
