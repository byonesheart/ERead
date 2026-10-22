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

    @Test fun preparedCacheStoresBothCssProjectionsAndSwitchingNeedsNoSourceManifest() = runBlocking {
        val book = fixtureBook()
        val prepared = loadChapterContent(context, book, 0)
        assertTrue(prepared.chapter.sourceCssStyles.size > prepared.chapter.sourceStyles.size)
        assertEquals(prepared.chapter.sourceStyles, prepared.chapter.readerSourceStyles(false))
        assertEquals(prepared.chapter.sourceCssStyles, prepared.chapter.readerSourceStyles(true))
        val metadata = File("${book.chapters[0].lazySourcePath}.prepared.json")
        assertEquals(1, JSONObject(metadata.readText()).getInt("sourceCssVersion"))
        metadata.writeText(JSONObject(metadata.readText()).apply { remove("sourceCssVersion"); remove("sourceCssStyles") }.toString())
        val upgraded = loadChapterContent(context, book, 0)
        assertEquals(prepared, upgraded)
        assertTrue(File(book.chapters[0].lazySourcePath!!).delete())
        val repeated = loadChapterContent(context, book, 0)
        assertEquals(prepared, repeated)
        assertEquals(prepared.chapter.sourceStyles, repeated.chapter.readerSourceStyles(false))
        assertEquals(prepared.chapter.sourceCssStyles, repeated.chapter.readerSourceStyles(true))
    }

    @Before fun setUp() {
        root = File("build/robolectric-lazy-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        // Windows can briefly retain a handle to the now-empty test directory.
        for (attempt in 0 until 5) {
            if (root.deleteRecursively()) break
            Thread.sleep(50)
        }
        check(!root.exists())
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
                "OPS/book.opf" to "<package><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='two' href='two.xhtml' media-type='application/xhtml+xml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>",
                "OPS/nav.xhtml" to "<html><nav><ol><li><span>原生分卷</span><ol><li><a href='one.xhtml'>First</a></li><li><a href='two.xhtml'>Second</a></li></ol></li></ol></nav></html>",
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
        assertEquals(listOf("原生分卷", "原生分卷"), book.chapters.map { it.tocParentTitle })
        assertEquals(book, LibraryRepository(context).let { it.readBook(it.writeBook(book)) })
        val upgraded = importer.upgradeInlineBooks(LibraryState(books = listOf(book.copy(
            sourcePath = source.absolutePath, contentVersion = 0)))).books.single()
        assertEquals(book.chapters.map { it.title }, upgraded.chapters.map { it.title })
        assertEquals(book.chapters.map { it.tocParentTitle }, upgraded.chapters.map { it.tocParentTitle })
        assertTrue(source.delete())
        val prepared = loadChapterContent(context, book, 1)
        assertTrue(prepared.text.contains("Second body."))
        assertTrue(prepared.chapter.sourceStyles.isNotEmpty())
        assertEquals("原生分卷", prepared.chapter.tocParentTitle)
        assertFalse(File("${book.chapters[0].lazySourcePath}.prepared.json").exists())
    }

    @Test fun whitespaceProjectionKeepsEmojiBoundariesAndDoesNotAllocateFullCharacterMap() {
        val source = "标题 \n\n  词🙂  \n正文"
        val text = "标题\n\n词🙂\n正文"
        val start = source.indexOf("词")
        val styles = mapCanonicalStyles(source, text, listOf(SourceTextStyle(start, start + 3, bold = true)))
        assertEquals("词🙂", text.substring(styles.single().start, styles.single().end))
    }

    @Test fun crossFileFootnotesSurviveLazyPreparationAndRepeatedOpenWithoutTheManifest() = runBlocking {
        val html = "<body><p>正文🙂<a epub:type='noteref' href='notes.xhtml#n1'><sup>[1]</sup></a>续文。</p></body>"
        val targets = mapOf("notes.xhtml#n1" to "<p>跨文件注释<a href='#fnref1'>↩</a></p>")
        val parsed = HtmlChapterParser.readPlainWithNotes("正文", html, noteTargets = targets) { null }
        val chapter = saveLazyChapter(root, 0, parsed, html, "", emptyMap(), targets)
        val book = Book(title = "注释", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        assertEquals(parsed.sourceFootnotes, chapter.sourceFootnotes)
        assertEquals("<p>跨文件注释<a href='#fnref1'>↩</a></p>",
            JSONObject(File(chapter.lazySourcePath!!).readText()).getJSONObject("noteTargets").getString("notes.xhtml#n1"))
        val prepared = loadChapterContent(context, book, 0)
        assertEquals(parsed.text, prepared.text)
        assertEquals("[1]", prepared.chapter.sourceFootnotes.single().let { prepared.text.substring(it.start, it.end) })
        assertEquals("跨文件注释", prepared.chapter.sourceFootnotes.single().content)
        assertTrue(File(chapter.lazySourcePath!!).delete())
        assertEquals(prepared, loadChapterContent(context, book, 0))
    }

    @Test fun oldPreparedMetadataIsUpgradedOnceForLocalSourceFootnotes() = runBlocking {
        val html = "<body><p>正文<a role='doc-noteref' href='#n1'>[1]</a></p><aside id='n1' role='doc-footnote'>本章注释</aside></body>"
        val parsed = HtmlChapterParser.readPlain("正文", html) { null }
        val chapter = saveLazyChapter(root, 0, parsed, html, "", emptyMap()).copy(sourceFootnotes = emptyList())
        val book = Book(title = "旧注释", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        val first = loadChapterContent(context, book, 0)
        val metadata = File("${chapter.lazySourcePath}.prepared.json")
        metadata.writeText(JSONObject(metadata.readText()).put("sourceFootnotesVersion", 1).apply { remove("sourceFootnotes") }.toString())
        val upgraded = loadChapterContent(context, book, 0)
        assertEquals(first, upgraded)
        assertEquals("本章注释", upgraded.chapter.sourceFootnotes.single().content)
        assertEquals(2, JSONObject(metadata.readText()).getInt("sourceFootnotesVersion"))
        assertTrue(File(chapter.lazySourcePath!!).delete())
        assertEquals(upgraded, loadChapterContent(context, book, 0))
    }

    @Test fun olderTxtChaptersGainSourceNotesWithoutRewritingTheirTextOrLibraryMetadata() = runBlocking {
        val text = "第一回\n正文[1]后文。\n\n[1] 原书释文。"
        val source = File(root, "old-txt.txt").apply { writeText(text) }
        val chapter = Chapter(title = "第一回", textPath = source.absolutePath, characterCount = text.length)
        val book = Book(title = "旧TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(chapter), characterOffset = text.indexOf("后文"))
        val prepared = loadChapterContent(context, book, 0)
        assertEquals(text, prepared.text)
        assertEquals(text, source.readText())
        assertEquals(chapter.id, prepared.chapter.id)
        assertEquals(chapter.textPath, prepared.chapter.textPath)
        assertEquals("原书释文。", prepared.chapter.sourceFootnotes.single().content)
        assertTrue(book.chapters.single().sourceFootnotes.isEmpty())
        assertEquals(text.indexOf("后文"), book.characterOffset)
    }

    @Test fun oldEpubManifestWithoutNoteTargetsRecoversOnlyReciprocalChapterLocalNotes() = runBlocking {
        val html = "<p>正文<sup><small><a href='part.html#filepos900'>[1]</a></small></sup><a id='filepos100'></a>后文。</p>" +
            "<p id='filepos900'><a href='part.html#filepos100'>[1]</a> 本章释文。</p>"
        assertOldManifestNotes(BookFormat.EPUB, html)
        val unrelated = "<p id='ref'>正文<a href='notes.html#fn1'>[1]</a></p>" +
            "<aside id='fn1'><a href='other.html#ref'>[1]</a> 不同文档的同名锚点。</aside>"
        assertTrue(HtmlChapterParser.readPlain("正文", unrelated) { null }.sourceFootnotes.isEmpty())
    }

    @Test fun oldMobiManifestWithoutNoteTargetsRecoversNativeChapterLocalReturnPairs() = runBlocking {
        val html = "<p>正文<sup><small><a filepos='900'>[1]</a></small></sup>后文。</p>" +
            "<p><a filepos='100'>[1]</a> 本章释文。</p>"
        assertOldManifestNotes(BookFormat.MOBI, html)
    }

    private suspend fun assertOldManifestNotes(format: BookFormat, html: String) {
        val plain = HtmlChapterParser.readPlain("正文", html) { null }
        val chapter = saveLazyChapter(root, 0, plain, html, "", emptyMap()).copy(sourceFootnotes = emptyList())
        val source = File(chapter.lazySourcePath!!)
        source.writeText(JSONObject(source.readText()).put("version", 1).apply { remove("noteTargets") }.toString())
        val textFile = File(chapter.textPath!!)
        val originalBytes = textFile.readBytes()
        val book = Book(title = "旧缓存", format = format, mode = LibraryMode.NOVEL, chapters = listOf(chapter),
            characterOffset = plain.text.indexOf("后文"))
        val prepared = loadChapterContent(context, book, 0)
        assertEquals("本章释文。", prepared.chapter.sourceFootnotes.single().content)
        assertEquals("[1]", prepared.chapter.sourceFootnotes.single().let { prepared.text.substring(it.start, it.end) })
        assertArrayEquals(originalBytes, textFile.readBytes())
        assertEquals(chapter.id, prepared.chapter.id)
        assertEquals(chapter.textPath, prepared.chapter.textPath)
        assertTrue(book.chapters.single().sourceFootnotes.isEmpty())
        assertTrue(source.delete())
        assertEquals(prepared, loadChapterContent(context, book, 0))
    }

    @Test fun footnoteProjectionKeepsTextAndZeroWidthImageReferencesAtCanonicalOffsets() {
        val source = "标题 \n\n  词🙂[1]  \n正文"
        val canonical = "标题\n\n词🙂[1]\n正文"
        val start = source.indexOf("[1]")
        val image = source.indexOf("正文")
        val notes = mapCanonicalFootnotes(source, canonical, listOf(
            SourceFootnote(start, start + 3, "[1]", "文字注释"), SourceFootnote(image, image, "图标", "图片注释", "marker.png")))
        assertEquals(canonical.indexOf("[1]"), notes[0].start)
        assertEquals("[1]", canonical.substring(notes[0].start, notes[0].end))
        assertEquals(canonical.indexOf("正文"), notes[1].start)
        assertEquals(notes[1].start, notes[1].end)
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
