package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LazyChapterContentRobolectricTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before fun setUp() {
        root = File("build/robolectric-lazy-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        check(root.deleteRecursively())
    }

    @Test fun loadsOnlyRequestedChapterAndKeepsExactCanonicalTextOffsets() = runBlocking {
        val book = fixtureBook()
        val chapter = book.chapters[1]
        val before = File(chapter.textPath!!).readText()
        assertEquals(before.length, chapter.characterCount)
        assertTrue(chapter.sourceStyles.isEmpty())
        val prepared = loadChapterContent(context, book, 1)
        assertEquals(before, prepared.text)
        assertEquals(chapter.id, prepared.chapter.id)
        assertEquals(chapter.inlineImages, prepared.chapter.inlineImages)
        assertTrue(prepared.chapter.sourceStyles.any { it.paragraph && it.alignment == SourceTextAlignment.CENTER })
        assertEquals("词🙂", prepared.chapter.sourceStyles.first { !it.paragraph && it.bold == true }
            .let { prepared.text.substring(it.start, it.end) })
        assertFalse(File("${book.chapters[0].lazySourcePath}.prepared.json").exists())
        assertTrue(File("${chapter.lazySourcePath}.prepared.json").exists())
    }

    @Test fun repeatedOpenUsesPreparedCacheWithoutSourceManifestOrOriginal() = runBlocking {
        val book = fixtureBook()
        val first = loadChapterContent(context, book, 0)
        assertTrue(File(book.chapters[0].lazySourcePath!!).delete())
        val repeated = loadChapterContent(context, book, 0)
        assertEquals(first, repeated)
    }

    @Test fun preparedCloudSerializationContainsAllStylesAndNoLocalLazyDependencies() = runBlocking {
        val original = fixtureBook()
        val materialized = materializeCloudBook(context, original)
        assertEquals(original.id, materialized.id)
        assertEquals(original.chapters.map { it.id }, materialized.chapters.map { it.id })
        assertTrue(materialized.chapters.all { it.lazySourcePath == null && it.sourceStyles.isNotEmpty() })
        val repository = LibraryRepository(context)
        val restored = repository.readBook(repository.writeBook(materialized))
        assertEquals(materialized, restored)
        assertTrue(restored.chapters.all { File(it.textPath!!).isFile })
    }

    @Test fun editedChapterReopensAndBacksUpWithoutReapplyingTheUnchangedSourceHtml() = runBlocking {
        val original = fixtureBook()
        val prepared = loadChapterContent(context, original, 1)
        val start = prepared.text.indexOf("正文1")
        val edit = EbookTextEdit(1, prepared.text, start, start + 3, "修改后的正文1")
        val textFile = File(root, "edited.txt").apply { writeText(edit.text) }
        val edited = edit.book(original, edit.chapter(prepared.chapter, textFile.absolutePath),
            original.chapters.map { it.characterCount })
        assertSame(original.chapters[0], edited.chapters[0])
        assertEquals(original.sourcePath, edited.sourcePath)
        assertNull(edited.chapters[1].lazySourcePath)
        assertFalse(File("${original.chapters[0].lazySourcePath}.prepared.json").exists())
        val repository = LibraryRepository(context)
        val reopened = repository.readBook(repository.writeBook(edited))
        assertTrue(reopened.hasTextEdits)
        assertEquals(edit.text, loadChapterContent(context, reopened, 1).text)
        assertTrue(reopened.chapters[1].sourceStyles.any { it.paragraph && it.alignment == SourceTextAlignment.CENTER })
        assertEquals(prepared.chapter.inlineImages.map { it.path }, reopened.chapters[1].inlineImages.map { it.path })
        val materialized = materializeCloudBook(context, reopened)
        assertEquals(edit.text, loadChapterContent(context, materialized, 1).text)
        assertEquals(reopened.chapters[1], materialized.chapters[1])
        val rebuilt = DocumentImporter(context).rebuildChapters(reopened, DEFAULT_CHAPTER_RULE)
        val rebuiltText = rebuilt.chapters.joinToString("\n") { File(requireNotNull(it.textPath)).readText() }
        assertTrue(rebuiltText.contains("修改后的正文1"))
        assertTrue(rebuilt.hasTextEdits)
        assertEquals(original.chapters.sumOf { it.inlineImages.size }, rebuilt.chapters.sumOf { it.inlineImages.size })
        assertTrue(rebuilt.chapters.flatMap { it.sourceStyles }.any { it.alignment == SourceTextAlignment.CENTER })
    }

    @Test fun libraryRoundTripPreservesPendingLazyChapterAndOldMetadataDefaults() {
        val book = fixtureBook()
        val repository = LibraryRepository(context)
        assertEquals(book, repository.readBook(repository.writeBook(book)))
        val legacy = repository.writeChapter(book.chapters[0]).apply { remove("characterCount"); remove("lazySourcePath") }
        val chapter = repository.readChapter(legacy)
        assertEquals(-1, chapter.characterCount)
        assertNull(chapter.lazySourcePath)
        val manifests = book.chapters.map { JSONObject(File(it.lazySourcePath!!).readText()) }
        assertEquals(1, manifests.map { it.getString("cssPath") }.distinct().size)
        assertTrue(manifests.all { !it.has("css") })
        assertEquals(1, File(book.chapters[0].textPath!!).parentFile!!.listFiles()!!.count { it.extension == "css" })
    }

    @Test fun sameLengthCanonicalChangeCannotReuseStaleStyleMetadata() = runBlocking {
        val book = fixtureBook()
        loadChapterContent(context, book, 0)
        val textFile = File(book.chapters[0].textPath!!)
        val original = textFile.readText()
        textFile.writeText(original.replace("标题", "变动"))
        assertEquals(original.length, textFile.readText().length)
        val result = runCatching { loadChapterContent(context, book, 0) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun corruptedCountIsRegeneratedFromImmutableChapterSource() = runBlocking {
        val book = fixtureBook()
        val prepared = loadChapterContent(context, book, 0)
        val metadata = File("${book.chapters[0].lazySourcePath}.prepared.json")
        metadata.writeText(JSONObject(metadata.readText()).put("characterCount", "broken").toString())
        assertEquals(prepared, loadChapterContent(context, book, 0))
        assertEquals(prepared.text.length, JSONObject(metadata.readText()).getInt("characterCount"))
    }

    @Test fun preparingCloudStylesLeavesAutomaticBackupFingerprintStable() = runBlocking {
        val book = fixtureBook()
        val state = LibraryState(books = listOf(book), readingSeconds = 125)
        val repository = LibraryRepository(context)
        val before = cloudBackupFingerprint(repository, state, book)
        val first = materializeCloudBook(context, book)
        val second = materializeCloudBook(context, book)
        assertEquals(first, second)
        assertEquals(before, cloudBackupFingerprint(repository, state, book))
        assertTrue(first.chapters.all { it.lazySourcePath == null && it.sourceStyles.isNotEmpty() })
    }

    @Test fun importsEpubWithPlainTextReadyAndStylesDeferredForEveryChapter() = runBlocking {
        val source = File(root, "fixture.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='two' href='two.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>",
                "OPS/one.xhtml" to "<body><h1>First</h1><p>First body.</p></body>",
                "OPS/two.xhtml" to "<body><h1>Second</h1><p>Second body.</p></body>")
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        val importer = DocumentImporter(context)
        val directory = File(context.filesDir, "books/imported").apply { mkdirs() }
        val method = DocumentImporter::class.java.getDeclaredMethod("importEpub", File::class.java, File::class.java,
            String::class.java, String::class.java, LibraryMode::class.java).apply { isAccessible = true }
        val book = method.invoke(importer, source, directory, "imported", "fixture", LibraryMode.COMIC) as Book
        assertEquals(LibraryMode.NOVEL, book.mode)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters.all { it.lazySourcePath != null && it.sourceStyles.isEmpty() })
        assertTrue(book.chapters.all { File(it.textPath!!).readText().length == it.characterCount })
        assertTrue(source.delete())
        val prepared = loadChapterContent(context, book, 1)
        assertTrue(prepared.text.contains("Second body."))
        assertTrue(prepared.chapter.sourceStyles.isNotEmpty())
        assertFalse(File("${book.chapters[0].lazySourcePath}.prepared.json").exists())
    }

    @Test fun whitespaceProjectionKeepsEmojiBoundariesAndDoesNotAllocateFullCharacterMap() {
        val source = "标题 \n\n  词🙂  \n正文"
        val text = "标题\n\n词🙂\n正文"
        val start = source.indexOf("词")
        val styles = mapCanonicalStyles(source, text, listOf(SourceTextStyle(start, start + 3, bold = true)))
        assertEquals("词🙂", text.substring(styles.single().start, styles.single().end))
    }

    private fun fixtureBook(): Book {
        val directory = File(context.filesDir, "books/${UUID.randomUUID()}").apply { mkdirs() }
        File(directory, "image.png").writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jH1kAAAAASUVORK5CYII="))
        val chapters = (0..1).map { index ->
            val html = "<body><h1>标题$index</h1><p>  <strong>词🙂</strong>  </p><p>正文$index<img src='img'/></p></body>"
            val images = mapOf(" src='img'/" to File(directory, "image.png").absolutePath)
            val plain = HtmlChapterParser.readPlain("第 $index 章", html) { images[it] }
            saveLazyChapter(directory, index, plain, html, "h1{text-align:center;font-size:150%}", images)
        }
        return Book(title = "Lazy source", format = BookFormat.MOBI, mode = LibraryMode.NOVEL, chapters = chapters,
            sourcePath = File(root, "missing-original.mobi").absolutePath, sourceLinked = true)
    }
}
