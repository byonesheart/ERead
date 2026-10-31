package com.eyue.reader.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.eyue.reader.data.cloud.CloudBackupService
import com.eyue.reader.data.cloud.CloudCredentialStore
import com.eyue.reader.data.cloud.CloudCredentials
import com.eyue.reader.data.cloud.CloudTransport
import com.eyue.reader.data.cloud.CloudVersionedMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EbookSourceWritebackRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setUp() {
        root = File("build/source-edit-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }
    @After fun tearDown() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        // Windows can briefly retain a handle to the now-empty test directory.
        repeat(5) {
            if (root.deleteRecursively()) return
            Thread.sleep(50)
        }
        check(!root.exists())
    }

    @Test fun sourceTextEditKeepsUntouchedTagsCssEntitiesImagesAndParagraphs() {
        val html = "<html><head><style>p{margin:1em}</style></head><body><h1 id='title'>标题</h1>" +
            "<p class='body'>甲&amp;<b>旧🙂正文</b>后文<img recindex='1'/></p><blockquote>引用保留</blockquote><p>末段</p></body></html>"
        val original = SourceHtmlEdit.plain(html) { "image" }
        val target = original.replace("旧🙂正文", "新🙂修改正文").replace("末段", "末段的旧版编辑")
        val result = SourceHtmlEdit.replace(html, target) { "image" }
        assertEquals(target, SourceHtmlEdit.plain(result.html) { "image" })
        assertTrue(result.html.contains("<style>p{margin:1em}</style>"))
        assertTrue(result.html.contains("<h1 id='title'>标题</h1>"))
        assertTrue(result.html.contains("<p class='body'>甲&amp;<b>新🙂修改正文</b>后文<img recindex='1'/></p>"))
        assertTrue(result.html.contains("<blockquote>引用保留</blockquote>"))
    }

    @Test fun sourceEditMapsWhitespaceBeforeNormalizingAndKeepsOriginalMarkup() {
        val examples = listOf(
            "<html><body>\n  <h1>标题</h1>\n  <p>  旧正文<span>后文</span></p>\n  <p>末段</p>\n</body></html>",
            "<html><body><div>\n  <p>旧正文</p>\n\n  <p>后文</p>\n</div></body></html>",
            "<html><body><ul><li>旧正文</li><li>后文</li></ul><table><tr><td>表格文字</td></tr></table></body></html>",
            "<html><body><p>旧正文<br/>  后文<br/><br/>末段</p></body></html>")
        examples.forEach { html ->
            val original = SourceHtmlEdit.plain(html) { null }
            val target = original.replace("旧正文", "已编辑的🙂正文")
            val edited = SourceHtmlEdit.replace(html, target) { null }
            assertEquals(target, SourceHtmlEdit.plain(edited.html) { null })
            assertEquals(html.replace("旧正文", "已编辑的🙂正文"), edited.html)
        }
    }

    @Test fun migratedEpubWritesTheExternalOriginalAndPrivateCopyOnlyWhenCommitted() {
        val source = epubFixture()
        val external = File(root, "external.epub").apply { source.copyTo(this) }
        val initial = epubBook(source)
        val privateDirectory = File(context.filesDir, "books/${initial.id}").apply { mkdirs() }
        val privateSource = File(privateDirectory, "source.epub").apply { source.copyTo(this) }
        val fullText = ZipFile(source).use { zip -> SourceHtmlEdit.plain(zip.getInputStream(zip.getEntry("body.xhtml")).reader().readText()) { "image" } }
        val fullChapter = Chapter(title = "正文", textPath = File(root, "legacy-full.txt").apply { writeText(fullText) }.absolutePath,
            characterCount = fullText.length)
        val book = initial.copy(chapters = listOf(fullChapter), sourcePath = privateSource.absolutePath,
            sourceUri = external.absolutePath, sourceLinked = false)
        val index = org.json.JSONObject().put("version", 1).put("spine", org.json.JSONArray(book.chapters.mapIndexed { chapter, item ->
            org.json.JSONObject().put("href", "body.xhtml")
                .put("chapterIndex", chapter).put("chapterId", item.id)
        })).put("manifest", org.json.JSONObject().put("readingOrder", org.json.JSONArray()))
        File(privateDirectory, "readium-publication.json").writeText(index.toString())
        assertEquals(book.sourceUri, EbookSourceWriteback.source(book))
        val updated = change(book, 0, "旧🙂正文", "修改后正文")
        val original = external.readBytes()
        val transaction = EbookSourceWriteback.prepare(context, book, updated, folder("external-transaction"))
        assertArrayEquals(original, external.readBytes())
        assertArrayEquals(original, privateSource.readBytes())
        transaction.commit()
        assertTrue(EpubArchive.read(external).sections.first().html.contains("修改后正文"))
        assertArrayEquals(external.readBytes(), privateSource.readBytes())
        transaction.rollback()
        assertArrayEquals(original, external.readBytes())
        assertArrayEquals(original, privateSource.readBytes())
        transaction.finish()
    }

    @Test fun epubCommitReallyOverwritesSourceAndRollbackRestoresExactOriginalArchive() {
        val source = epubFixture()
        val originalBytes = source.readBytes()
        val book = epubBook(source)
        val updated = change(book, 0, "旧🙂正文", "新🙂正文和新增内容")
        val transaction = EbookSourceWriteback.prepare(context, book, updated, folder("transaction"))
        assertArrayEquals(originalBytes, source.readBytes())
        transaction.commit()
        val opened = EpubArchive.read(source)
        assertTrue(opened.sections.first().html.contains("新🙂正文和新增内容"))
        assertEquals(book.title, opened.title)
        assertEquals(book.author, opened.author)
        ZipFile(source).use { zip ->
            assertEquals("h1{text-align:center} blockquote{font-style:italic}", zip.getInputStream(zip.getEntry("styles.css")).reader().readText())
            assertArrayEquals(png, zip.getInputStream(zip.getEntry("cover.png")).readBytes())
            assertTrue(zip.getInputStream(zip.getEntry("body.xhtml")).reader().readText().contains("<blockquote>引用保留</blockquote>"))
            val html = zip.getInputStream(zip.getEntry("body.xhtml")).reader().readText()
            assertTrue(html.contains("<img src='missing.png'/><img src='https://example.invalid/remote.png'/>"))
        }
        transaction.rollback()
        assertArrayEquals(originalBytes, source.readBytes())
        transaction.finish()
        assertFalse(File(root, "transaction/original.epub").exists())
        assertFalse(File(root, "transaction/prepared.epub").exists())
    }

    @Test fun oldAppOnlyEditsAreIncludedWhenNextEditOverwritesEpub() {
        val source = epubFixture()
        val original = epubBook(source)
        val oldEdit = change(original, 0, "旧🙂正文", "旧版已修改正文")
        val updated = change(oldEdit, 0, "引用保留", "引用也修改了")
        val transaction = EbookSourceWriteback.prepare(context, oldEdit, updated, folder("old-edit"))
        transaction.commit()
        val html = EpubArchive.read(source).sections.first().html
        assertTrue(html.contains("旧版已修改正文"))
        assertTrue(html.contains("引用也修改了"))
        transaction.finish()
    }

    @Test fun mobiWritePreservesMetadataPicturesAndRebuildsByteBasedTocAndFilepos() {
        val source = mobiFixture()
        val originalBytes = source.readBytes()
        val parsed = MobiArchive.read(source)
        val book = mobiBook(source, parsed)
        val chapter = book.chapters.indexOfFirst { readChapterText(it).contains("旧🙂正文") }
        val updated = change(book, chapter, "旧🙂正文", "新的🙂正文".repeat(1000))
        val transaction = EbookSourceWriteback.prepare(context, book, updated, folder("mobi-edit"))
        assertArrayEquals(originalBytes, source.readBytes())
        transaction.commit()
        var html = ""
        val reopened = MobiArchive.read(source, captureHtml = { html = it })
        assertEquals(parsed.title, reopened.title)
        assertEquals(parsed.author, reopened.author)
        assertEquals(parsed.sections.map { it.title }, reopened.sections.map { it.title })
        assertTrue(reopened.hasEmbeddedToc)
        assertStandardIndex(source)
        assertTrue(reopened.sections.last().html.contains("第二章的正文"))
        assertEquals(1, reopened.images.size)
        assertArrayEquals(png, reopened.cover!!.bytes)
        val filepos = Regex("filepos='([0-9]+)'").find(html)!!.groupValues[1].toInt()
        assertTrue(html.toByteArray().copyOfRange(filepos, html.toByteArray().size).toString(Charsets.UTF_8).startsWith("<h1 id='two'>"))
        assertEquals("BOOKMOBI", source.readBytes().copyOfRange(60, 68).toString(Charsets.US_ASCII))
        transaction.rollback()
        assertArrayEquals(originalBytes, source.readBytes())
    }

    @Test fun aSecondMobiEditReopensPreviousTextAndDoesNotDuplicateImagesOrLoseChapterBoundaries() {
        val source = mobiFixture()
        val original = mobiBook(source, MobiArchive.read(source))
        val chapter = original.chapters.indexOfFirst { readChapterText(it).contains("旧🙂正文") }
        val first = change(original, chapter, "旧🙂正文", "第一次改动")
        EbookSourceWriteback.prepare(context, original, first, folder("first")).apply { commit(); finish() }
        val firstSize = source.length()
        val second = change(first, chapter, "第一次改动", "第二次修改🙂")
        EbookSourceWriteback.prepare(context, first, second, folder("second")).apply { commit(); finish() }
        val reopened = MobiArchive.read(source)
        assertEquals(original.chapters.map { it.title }, reopened.sections.map { it.title })
        assertTrue(reopened.sections.any { it.html.contains("第二次修改🙂") })
        assertEquals(1, reopened.images.size)
        assertTrue("旧目录记录不能在每次保存时累加", source.length() <= firstSize + 128)
        assertTrue("非图片嵌入资源必须保留", source.readBytes().toString(Charsets.ISO_8859_1).contains("FONT-resource-preserved"))
    }

    @Test fun sourceChangedBetweenPrepareAndCommitIsNotOverwritten() {
        val source = epubFixture()
        val book = epubBook(source)
        val updated = change(book, 0, "旧🙂正文", "修改正文")
        val transaction = EbookSourceWriteback.prepare(context, book, updated, folder("conflict"))
        val external = source.readBytes() + byteArrayOf(1, 2, 3)
        source.writeBytes(external)
        assertThrows(IllegalArgumentException::class.java) { transaction.commit() }
        assertArrayEquals(external, source.readBytes())
        transaction.rollback()
        assertArrayEquals(external, source.readBytes())
    }

    @Test fun txtPrepareKeepsTheSourceUntilCommitAndRollbackRestoresItsOriginalEncoding() {
        val source = File(root, "source.txt").apply { writeBytes("第一章\r\n原来的正文".toByteArray(charset("GB18030"))) }
        val original = source.readBytes()
        val editedSource = File(root, "draft.txt").apply { writeText("第一章\n已经修改的正文🙂", Charsets.UTF_8) }
        val book = Book(title = "TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL, sourcePath = source.absolutePath, chapters = emptyList())
        val transaction = EbookSourceWriteback.prepare(context, book, book.copy(sourcePath = editedSource.absolutePath), folder("txt"))
        assertArrayEquals(original, source.readBytes())
        transaction.commit()
        assertArrayEquals(editedSource.readBytes(), source.readBytes())
        assertEquals("第一章\n已经修改的正文🙂", source.readText(Charsets.UTF_8))
        transaction.rollback()
        assertArrayEquals(original, source.readBytes())
        transaction.finish()
        assertFalse(File(root, "txt/original.txt").exists())
        assertFalse(File(root, "txt/prepared.txt").exists())
    }

    @Test fun txtSourceChangedAfterPreparingIsNeverOverwritten() {
        val source = File(root, "source.txt").apply { writeText("原正文") }
        val edited = File(root, "draft.txt").apply { writeText("编辑后的正文") }
        val book = Book(title = "TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL, sourcePath = source.absolutePath, chapters = emptyList())
        val transaction = EbookSourceWriteback.prepare(context, book, book.copy(sourcePath = edited.absolutePath), folder("txt-conflict"))
        source.writeText("其他软件已修改的正文")
        val external = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) { transaction.commit() }
        transaction.rollback()
        assertArrayEquals(external, source.readBytes())
        transaction.finish()
    }

    @Test fun failedOrCancelledTxtPreparationNeverChangesTheOriginalFile() {
        val source = File(root, "source.txt").apply { writeText("原正文") }
        val original = source.readBytes()
        val book = Book(title = "TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL, sourcePath = source.absolutePath, chapters = emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            EbookSourceWriteback.prepare(context, book, book.copy(sourcePath = File(root, "missing.txt").absolutePath), folder("txt-missing"))
        }
        assertArrayEquals(original, source.readBytes())
        val edited = File(root, "large-draft.txt").apply { writeText("修改后的正文".repeat(50_000)) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EbookSourceWriteback.prepare(context, book, book.copy(sourcePath = edited.absolutePath), folder("txt-cancelled")) {
                if (++checks > 5) throw CancellationException("cancel")
            }
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun txtEpubAndMobiReportMonotonicPreparationAndCommitProgress() {
        val text = File(root, "source.txt").apply { writeText("原正文") }
        val editedText = File(root, "draft.txt").apply { writeText("修改正文🙂".repeat(20_000)) }
        val txt = Book(title = "TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL, sourcePath = text.absolutePath, chapters = emptyList())
        val epub = epubBook(epubFixture())
        val mobiSource = mobiFixture()
        val mobi = mobiBook(mobiSource, MobiArchive.read(mobiSource))
        listOf(txt to txt.copy(sourcePath = editedText.absolutePath), epub to change(epub, 0, "旧🙂正文", "新正文"),
            mobi to change(mobi, mobi.chapters.indexOfFirst { readChapterText(it).contains("旧🙂正文") }, "旧🙂正文", "新正文"))
            .forEach { (before, edited) ->
                val preparation = mutableListOf<Pair<String, Float>>()
                val transaction = EbookSourceWriteback.prepare(context, before, edited, folder("progress-${before.format}"),
                    onProgress = { stage, fraction -> preparation += stage to fraction })
                assertProgress(preparation)
                assertTrue(preparation.any { it.first.contains("校验") })
                if (before.format != BookFormat.TXT) {
                    assertTrue(preparation.any { it.first.contains("处理") })
                    assertTrue(preparation.any { it.first.contains("重新打包") })
                }
                val saving = mutableListOf<Pair<String, Float>>()
                transaction.commit { stage, fraction -> saving += stage to fraction }
                assertProgress(saving)
                assertTrue(saving.any { it.first == "写入源文件" })
                assertTrue(saving.any { it.first == "校验保存结果" })
                assertEquals("保存完成", saving.last().first)
                transaction.finish()
            }
    }

    private fun assertProgress(progress: List<Pair<String, Float>>) {
        assertTrue(progress.size >= 4)
        assertEquals(0f, progress.first().second)
        assertEquals(1f, progress.last().second)
        assertTrue(progress.all { it.first.isNotBlank() && it.second in 0f..1f })
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous.second <= next.second })
    }

    @Test fun importedMobiPagebreakChaptersEditWithoutInventingParagraphSeparators() = runBlocking {
        val source = mobiFixture("<body>第一段旧正文<mbp:pagebreak/>第二段保持原样</body>", includeCover = false)
        val book = importMobi(source)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters.all { it.lazySourcePath != null })
        var originalHtml = ""
        MobiArchive.read(source, captureHtml = { originalHtml = it })
        assertNotEquals(SourceHtmlEdit.plain(originalHtml) { null },
            book.chapters.joinToString("\n\n") { readChapterText(it) })
        val updated = change(book, 0, "旧正文", "新正文及新增文字")
        val transaction = EbookSourceWriteback.prepare(context, book, updated, folder("pagebreak"))
        transaction.commit()
        val reopened = MobiArchive.read(source)
        assertEquals(book.chapters.map { it.title }, reopened.sections.map { it.title })
        assertEquals(updated.chapters.map { readChapterText(it) },
            reopened.sections.map { SourceHtmlEdit.plain(it.html) { null } })
        transaction.finish()
    }

    @Test fun importedMobiInlineChapterAnchorsKeepEachChapterAndCanBeEditedAgain() = runBlocking {
        val source = mobiFixture("<body><a href='#one'>第一章</a><a href='#two'>第二章</a>" +
            "<p><a id='one'></a>第一章旧正文<a id='two'></a>第二章保留正文</p></body>", includeCover = false)
        val book = importMobi(source)
        val index = book.chapters.indexOfFirst { readChapterText(it).contains("第一章旧正文") }
        assertTrue(index >= 0)
        val updated = change(book, index, "旧正文", "新正文")
        val first = EbookSourceWriteback.prepare(context, book, updated, folder("inline-first"))
        first.commit(); first.finish()
        val secondIndex = updated.chapters.indexOfFirst { readChapterText(it).contains("第二章保留正文") }
        val secondEdit = change(updated, secondIndex, "保留正文", "也能修改")
        val second = EbookSourceWriteback.prepare(context, updated, secondEdit, folder("inline-second"))
        second.commit(); second.finish()
        assertEquals(secondEdit.chapters.map { readChapterText(it) },
            MobiArchive.read(source).sections.map { SourceHtmlEdit.plain(it.html) { null } })
    }

    @Test fun cancellingSourceMappingNeverChangesTheOriginalArchive() {
        val source = epubFixture()
        val original = source.readBytes()
        val book = epubBook(source)
        val updated = change(book, 0, "旧🙂正文", "修改正文")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EbookSourceWriteback.prepare(context, book, updated, folder("cancelled")) {
                if (++checks > 3) throw CancellationException("cancel")
            }
        }
        assertArrayEquals(original, source.readBytes())
        val next = EbookSourceWriteback.prepare(context, book, updated, folder("after-cancel"))
        next.commit(); next.finish()
        assertTrue(EpubArchive.read(source).sections.first().html.contains("修改正文"))
    }

    @Test fun importedLazyEpubAndMobiKeepEditsInSavedCacheSourceAndCloudRoundTrip() = runBlocking {
        val epub = File(root, "lazy.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                val entries = mapOf(
                    "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                    "book.opf" to "<package><metadata><title>懒加载 EPUB</title></metadata><manifest><item id='one' href='one.xhtml'/><item id='two' href='two.xhtml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>",
                    "one.xhtml" to "<body><h1>第一章</h1><p>第一章旧正文</p></body>",
                    "two.xhtml" to "<body><h1>第二章</h1><p>第二章保留正文</p></body>")
                entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            }
        }
        val method = DocumentImporter::class.java.getDeclaredMethod("importEpub", File::class.java, File::class.java,
            String::class.java, String::class.java, LibraryMode::class.java).apply { isAccessible = true }
        val epubId = UUID.randomUUID().toString()
        val epubBook = (method.invoke(DocumentImporter(context), epub,
            File(context.filesDir, "books/$epubId").apply { mkdirs() }, epubId, "lazy", LibraryMode.NOVEL) as Book)
            .copy(sourcePath = epub.absolutePath)
        val mobi = mobiFixture("<body><h1>第一章</h1><p>第一章旧正文</p><h1>第二章</h1><p>第二章保留正文</p></body>", includeCover = false)
        val books = listOf(epubBook, importMobi(mobi))
        val transport = MemoryCloudTransport()
        val credentials = object : CloudCredentialStore {
            override fun read() = CloudCredentials("unit-test@example.invalid", "test-password")
            override fun write(credentials: CloudCredentials) = Unit
            override fun clear() = Unit
        }
        val cloud = CloudBackupService(context, credentials, { _, _ -> transport })
        val repository = LibraryRepository(context)
        books.forEach { book ->
            assertTrue(book.chapters.all { it.lazySourcePath != null })
            val prepared = loadChapterContent(context, book, 0)
            val start = prepared.text.indexOf("旧正文")
            assertTrue(start >= 0)
            val edit = EbookTextEdit(0, prepared.text, start, start + 3, "已保存的新正文")
            val directory = folder("roundtrip-${book.id}")
            val text = File(directory, "edited.txt").apply { writeText(edit.text) }
            val updated = edit.book(book, edit.chapter(prepared.chapter, text.absolutePath),
                book.chapters.map { readChapterText(it).length })
            val transaction = EbookSourceWriteback.prepare(context, book, updated, directory)
            transaction.commit(); transaction.finish()
            val saved = repository.readBook(repository.writeBook(updated))
            assertEquals(edit.text, loadChapterContent(context, saved, 0).text)
            assertNull(saved.chapters[0].lazySourcePath)
            val originalFile = File(saved.sourcePath!!)
            val sourceText = if (saved.format == BookFormat.EPUB) EpubArchive.read(originalFile).sections.first().html
                else MobiArchive.read(originalFile).sections.first().html
            assertTrue(sourceText.contains("已保存的新正文"))
            cloud.upload(LibraryState(books = listOf(saved)), saved)
            val info = cloud.list().first { it.book.id == saved.id }
            val restored = cloud.download(info).book
            assertEquals(edit.text, File(restored.chapters[0].textPath!!).readText())
            assertArrayEquals(originalFile.readBytes(), File(restored.sourcePath!!).readBytes())
        }
    }

    @Test
    @Config(shadows = [MobiBitmapRegionDecoderShadow::class])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun optionalRealMobiWritebackUsesAnIsolatedCopyAndPreservesEveryOtherChapter() = runBlocking {
        val configured = System.getenv("EREAD_EBOOK_SMOKE_SOURCE")
        assumeTrue("Set EREAD_EBOOK_SMOKE_SOURCE for a real-file writeback smoke test", !configured.isNullOrBlank())
        val supplied = File(requireNotNull(configured))
        require(supplied.isFile)
        val originalHash = java.security.MessageDigest.getInstance("SHA-256").digest(supplied.readBytes())
        val source = supplied.copyTo(File(root, "smoke.mobi"))
        val started = System.nanoTime()
        val book = importMobi(source)
        val imported = System.nanoTime()
        val texts = book.chapters.map { readChapterText(it) }
        val index = texts.indexOfFirst { text -> text.lineSequence().any { it.length >= 40 } }
        require(index >= 0) { "Smoke fixture needs a normal body paragraph" }
        val prepared = loadChapterContent(context, book, index)
        val paragraph = prepared.text.lineSequence().first { it.length >= 40 }
        val phrase = Regex("[\u4e00-\u9fff]{4}").find(paragraph) ?: error("Smoke fixture needs a Chinese body paragraph")
        val start = prepared.text.indexOf(paragraph) + phrase.range.first
        val edit = EbookTextEdit(index, prepared.text, start, start + phrase.value.length, "正文修改校验")
        val directory = folder("real-mobi-edit")
        val cache = File(directory, "edited.txt").apply { writeText(edit.text) }
        val updated = edit.book(book, edit.chapter(prepared.chapter, cache.absolutePath), texts.map { it.length })
        val writeStarted = System.nanoTime()
        val transaction = EbookSourceWriteback.prepare(context, book, updated, directory)
        transaction.commit(); transaction.finish()
        val finished = System.nanoTime()
        val expected = texts.toMutableList().apply { this[index] = edit.text }
        assertEquals(expected, updated.chapters.map { readChapterText(it) })
        val reopened = MobiArchive.read(source)
        val image: (String) -> String? = { attributes ->
            val reference = HtmlChapterParser.attribute(attributes, "recindex")?.toIntOrNull()
                ?: (HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                    ?: HtmlChapterParser.attribute(attributes, "href"))?.let { value ->
                    Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toIntOrNull(32)
                }
            if (reference != null && reopened.images.any { it.recIndex == reference }) "image" else null
        }
        assertEquals(expected.size, reopened.sections.size)
        reopened.sections.forEachIndexed { chapter, section ->
            val actual = SourceHtmlEdit.plain(section.html, image)
            assertTrue("Written chapter $chapter differs: expected ${expected[chapter].length} characters, got ${actual.length}",
                expected[chapter] == actual)
        }
        assertEquals(edit.text, loadChapterContent(context, updated, index).text)
        assertArrayEquals(originalHash, java.security.MessageDigest.getInstance("SHA-256").digest(supplied.readBytes()))
        println("Real MOBI writeback smoke: bytes=${source.length()}, chapters=${book.chapters.size}, " +
            "importMs=${(imported - started) / 1_000_000}, writebackMs=${(finished - writeStarted) / 1_000_000}")
    }

    @Test fun readOnlySafDocumentRequestsWritePermissionWithoutChangingSource() {
        val source = epubFixture()
        val original = source.readBytes()
        val uri = Uri.parse("content://readonly-book/original.epub")
        registerProvider("readonly-book", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (mode.contains('w')) throw SecurityException("read only")
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val book = epubBook(source).copy(sourceUri = uri.toString(), sourceLinked = true)
        val updated = change(book, 0, "旧🙂正文", "修改正文")
        val error = assertThrows(SourceWritePermissionRequiredException::class.java) {
            EbookSourceWriteback.prepare(context, book, updated, folder("read-only"))
        }
        assertEquals(uri.toString(), error.source)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun silentlyTruncatedSafRestoreRetainsTheOnlyCompleteOriginalBackup() {
        val source = epubFixture()
        val original = source.readBytes()
        val uri = Uri.parse("content://truncating-book/original.epub")
        var truncateOnRead = false
        registerProvider("truncating-book", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (mode == "rw") return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_WRITE)
                if (mode.contains('w')) {
                    truncateOnRead = true
                    return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
                }
                if (truncateOnRead) {
                    java.io.RandomAccessFile(source, "rw").use { it.setLength(it.length() / 2) }
                    truncateOnRead = false
                }
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val book = epubBook(source).copy(sourceUri = uri.toString(), sourceLinked = true)
        val updated = change(book, 0, "旧🙂正文", "修改正文")
        val directory = folder("truncated-restore")
        context.contentResolver.openFileDescriptor(uri, "rw")!!.close()
        val transaction = EbookSourceWriteback.prepare(context, book, updated, directory)
        val error = assertThrows(java.io.IOException::class.java) { transaction.commit() }
        assertTrue(error.message!!.contains("原文件暂存于"))
        assertTrue(transaction.recoveryRequired)
        assertArrayEquals(original, File(directory, "original.epub").readBytes())
    }

    @Test
    @Config(shadows = [MobiBitmapRegionDecoderShadow::class])
    fun importedMobiKeepsChapterMarkupAndStylesAcrossDraftSaveReopenAndSecondEdit() = runBlocking {
        val css = ".chapter{margin:2em}.body{text-indent:2em;line-height:1.8}"
        val markup = "<html><head><style>$css</style></head><body class='paper'><div class='chapter'>" +
            "<h1>第一章</h1><p class='body'>第一章<b>旧正文</b></p>" +
            "<h1>第二章</h1><p class='body'>第二章保留正文</p></div></body></html>"
        val source = mobiFixture(markup, includeCover = false)
        val originalBytes = source.readBytes()
        var book = importMobi(source)
        val repository = LibraryRepository(context)
        repeat(2) { pass ->
            val prepared = loadChapterContent(context, book, 0)
            val old = if (pass == 0) "旧正文" else "修改正文"
            val replacement = if (pass == 0) "修改正文" else "再次修改正文"
            val start = prepared.text.indexOf(old)
            assertTrue(start >= 0)
            val edit = EbookTextEdit(0, prepared.text, start, start + old.length, replacement)
            val editFolder = folder("edit-mobi-$pass")
            val textFile = File(editFolder, "chapter.txt").apply { writeText(edit.text) }
            val chapter = retainEditedChapterSource(prepared.chapter, edit.chapter(prepared.chapter, textFile.path))
            val draft = edit.book(book, chapter, book.chapters.map { readChapterText(it).length })
            EbookSourceStore(context, draft, preview = true).use { store ->
                val html = store.load(0, edit.text).html
                assertTrue(html.contains(css))
                assertTrue(html.contains("<p class='body'>第一章<b>$replacement</b></p>"))
                assertTrue(html.contains("<body class='paper'>"))
            }
            if (pass == 0) assertArrayEquals(originalBytes, source.readBytes())
            val saveFolder = folder("save-mobi-$pass")
            val transaction = EbookSourceWriteback.prepare(context, book, draft, saveFolder)
            val saved = saveEditedChapterSources(draft, saveFolder) {}
            EbookSourceStore(context, draft, preview = true).use { previewStore ->
                EbookSourceStore(context, saved).use { savedStore ->
                    assertNotEquals(previewStore.continuousKey, savedStore.continuousKey)
                    assertNotEquals(previewStore.load(0, edit.text).key, savedStore.load(0, edit.text).key)
                }
            }
            transaction.commit(); transaction.finish()
            book = repository.readBook(repository.writeBook(saved))
            EbookSourceStore(context, book).use { store ->
                val html = store.load(0, readChapterText(book, 0)).html
                assertTrue(html.contains(css))
                assertTrue(html.contains("<p class='body'>第一章<b>$replacement</b></p>"))
                assertTrue(html.contains("<body class='paper'>"))
            }
            val parsed = MobiArchive.read(source)
            assertEquals(css, parsed.css)
            assertTrue(parsed.sections.first().html.contains(replacement))
            assertTrue(parsed.sections.last().html.contains("第二章保留正文"))
        }
    }

    @Test
    @Config(shadows = [MobiBitmapRegionDecoderShadow::class])
    fun importedEpubKeepsStyledTextImagesAndLinkedCssBeforeAndAfterSaving() = runBlocking {
        val fixture = epubFixture()
        val image = ByteArrayOutputStream().also { output ->
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val source = File(root, "styled.epub")
        ZipFile(fixture).use { archive -> ZipOutputStream(source.outputStream()).use { zip ->
            archive.entries().asSequence().forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(if (entry.name == "cover.png") image else archive.getInputStream(entry).use { it.readBytes() })
                zip.closeEntry()
            }
        } }
        val original = source.readBytes()
        val id = UUID.randomUUID().toString()
        val imported = EpubTestImporter(context).import(source, File(context.filesDir, "books/$id").apply { mkdirs() }, id, "样书")
            .copy(sourceUri = source.absolutePath)
        val prepared = loadChapterContent(context, imported, 0)
        val from = prepared.text.indexOf("旧🙂正文")
        val edit = EbookTextEdit(0, prepared.text, from, from + "旧🙂正文".length, "修改🙂正文")
        val work = folder("edit-epub-styles")
        val text = File(work, "chapter.txt").apply { writeText(edit.text) }
        val updated = retainEditedChapterSource(prepared.chapter, edit.chapter(prepared.chapter, text.path))
        val draft = edit.book(imported, updated, imported.chapters.map { readChapterText(it).length })
        val save = folder("save-epub-styles")
        val transaction = EbookSourceWriteback.prepare(context, imported, draft, save)
        val committed = saveEditedChapterSources(draft, save) {}
        for (book in listOf(draft, committed)) {
            EbookSourceStore(context, book, preview = true).use { store ->
                val document = store.load(0, edit.text)
                assertTrue(document.html.contains("<b>修改🙂正文</b>"))
                assertTrue(document.html.contains("<blockquote>引用保留</blockquote>"))
                assertTrue(document.html.contains("href='styles.css'"))
                assertTrue(document.html.contains("src='cover.png'"))
            }
        }
        assertArrayEquals(original, source.readBytes())
        transaction.commit(); transaction.finish()
        val repository = LibraryRepository(context)
        val reopened = repository.readBook(repository.writeBook(committed))
        EbookSourceStore(context, reopened).use { store ->
            val document = store.load(0, readChapterText(reopened, 0))
            assertTrue(document.html.contains("<b>修改🙂正文</b>"))
            val css = java.net.URI(document.baseUrl).resolve("styles.css").toString()
            assertEquals("h1{text-align:center} blockquote{font-style:italic}",
                requireNotNull(store.openResource(css)).stream.use { it.readBytes().toString(Charsets.UTF_8) })
        }
        assertTrue(EpubArchive.read(source).sections.first().html.contains("修改🙂正文"))
    }

    private fun registerProvider(authority: String, provider: ContentProvider) {
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun folder(name: String) = File(root, name).apply { mkdirs() }
    private fun importMobi(source: File): Book {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val method = DocumentImporter::class.java.getDeclaredMethod("importMobi", File::class.java, File::class.java,
            String::class.java, String::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java)
        method.isAccessible = true
        return (method.invoke(DocumentImporter(context), source, directory, id, source.nameWithoutExtension,
            DEFAULT_CHAPTER_RULE, {}) as Book).copy(sourcePath = source.absolutePath)
    }
    private class MemoryCloudTransport : CloudTransport {
        private val files = mutableMapOf<String, ByteArray>()
        private val directories = mutableSetOf<String>()
        private fun etag(path: String) = files[path]?.contentHashCode()?.let { "\"$it\"" }
        override fun ensureCollection(path: String) { directories += path.trimEnd('/') }
        override fun collections(path: String) = directories.mapNotNull {
            it.removePrefix(path).takeIf { child -> it.startsWith(path) && child.isNotEmpty() && '/' !in child }
        }
        override fun metadata(path: String, maximum: Int) = files[path]?.copyOf()
        override fun putMetadata(path: String, bytes: ByteArray) { files[path] = bytes.copyOf() }
        override fun versionedMetadata(path: String) = files[path]?.let { CloudVersionedMetadata(it.copyOf(), etag(path)!!) }
        override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean {
            if (etag != this.etag(path)) return false
            files[path] = bytes.copyOf(); return true
        }
        override fun deleteCollection(path: String) { files.keys.removeAll { it.startsWith(path) } }
        override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) {
            files[path] = ByteArray(bytes.toInt()).also { content -> RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(content) } }
            progress(bytes)
        }
        override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long {
            val bytes = files.getValue(path)
            java.io.FileOutputStream(file, append).use { it.write(bytes) }
            progress(bytes.size.toLong()); return bytes.size.toLong()
        }
    }
    /** Inspects the actual standard main/data IDXT mapping, independently of the app's tolerant TOC reader. */
    private fun assertStandardIndex(source: File) {
        val bytes = source.readBytes()
        val recordCount = ByteBuffer.wrap(bytes, 76, 2).short.toInt() and 65535
        val starts = IntArray(recordCount + 1) { index -> if (index == recordCount) bytes.size else ByteBuffer.wrap(bytes, 78 + index * 8, 4).int }
        fun record(index: Int) = bytes.copyOfRange(starts[index], starts[index + 1])
        fun number(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 4).int
        fun short(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 2).short.toInt() and 65535
        val ncx = number(record(0), 244)
        val main = record(ncx)
        assertEquals(192, number(main, 4))
        assertEquals(0, number(main, 12))
        assertEquals(2, number(main, 16))
        assertEquals(192, number(main, 180))
        assertEquals("TAGX", main.copyOfRange(192, 196).toString(Charsets.US_ASCII))
        val idxt = number(main, 20)
        assertEquals(0, idxt % 4)
        assertEquals("IDXT", main.copyOfRange(idxt, idxt + 4).toString(Charsets.US_ASCII))
        var total = 0
        repeat(number(main, 24)) { index ->
            val summary = short(main, idxt + 4 + index * 2)
            val keySize = main[summary].toInt() and 255
            val key = main.copyOfRange(summary + 1, summary + 1 + keySize)
            val declaredCount = short(main, summary + 1 + keySize)
            val data = record(ncx + index + 1)
            assertEquals(1, number(data, 12))
            assertEquals(declaredCount, number(data, 24))
            val dataIdxt = number(data, 20)
            assertEquals(0, dataIdxt % 4)
            assertEquals("IDXT", data.copyOfRange(dataIdxt, dataIdxt + 4).toString(Charsets.US_ASCII))
            val lastEntry = short(data, dataIdxt + 4 + (declaredCount - 1) * 2)
            assertEquals(keySize, data[lastEntry].toInt() and 255)
            assertArrayEquals(key, data.copyOfRange(lastEntry + 1, lastEntry + 1 + keySize))
            total += declaredCount
        }
        assertEquals(total, number(main, 36))
    }
    private fun change(book: Book, chapter: Int, old: String, replacement: String): Book {
        val text = File(book.chapters[chapter].textPath!!).readText()
        val from = text.indexOf(old)
        require(from >= 0)
        val edit = EbookTextEdit(chapter, text, from, from + old.length, replacement)
        val file = File(root, "changed-${UUID.randomUUID()}.txt").apply { writeText(edit.text) }
        return edit.book(book, edit.chapter(book.chapters[chapter], file.absolutePath), book.chapters.map { readChapterText(it).length })
    }
    private fun epubBook(source: File): Book {
        val parsed = EpubArchive.read(source)
        val chapters = parsed.sections.mapIndexed { index, section ->
            val text = SourceHtmlEdit.plain(section.html) { "image" }
            Chapter(title = section.title, textPath = File(root, "epub-$index.txt").apply { writeText(text) }.absolutePath, characterCount = text.length)
        }
        return Book(title = parsed.title, author = parsed.author, format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = chapters, sourcePath = source.absolutePath)
    }
    private fun mobiBook(source: File, parsed: ParsedMobi): Book {
        val chapters = parsed.sections.mapIndexed { index, section ->
            val text = SourceHtmlEdit.plain(section.html) { "image" }
            Chapter(title = section.title, textPath = File(root, "mobi-$index.txt").apply { writeText(text) }.absolutePath, characterCount = text.length)
        }
        return Book(title = parsed.title, author = parsed.author, format = BookFormat.MOBI, mode = LibraryMode.NOVEL, chapters = chapters, sourcePath = source.absolutePath)
    }
    private fun epubFixture(): File = File(root, "source.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            val entries = linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>".toByteArray(),
                "book.opf" to ("<package><metadata><title>源书名</title><creator>源作者</creator><meta name='cover' content='cover'/></metadata>" +
                    "<manifest><item id='body' href='body.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/>" +
                    "<item id='cover' href='cover.png' media-type='image/png'/></manifest><spine><itemref idref='body'/></spine></package>").toByteArray(),
                "nav.xhtml" to "<html><nav><a href='body.xhtml#one'>第一章</a><a href='body.xhtml#two'>第二章</a></nav></html>".toByteArray(),
                "body.xhtml" to ("<html><head><link rel='stylesheet' href='styles.css'/></head><body><h1 id='one'>第一章</h1>" +
                    "<p class='body'>甲&amp;<b>旧🙂正文</b>后文<img src='cover.png'/><img src='missing.png'/><img src='https://example.invalid/remote.png'/></p><blockquote>引用保留</blockquote>" +
                    "<h1 id='two'>第二章</h1><p>第二章正文保留</p></body></html>").toByteArray(),
                "styles.css" to "h1{text-align:center} blockquote{font-style:italic}".toByteArray(),
                "cover.png" to png
            )
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun mobiFixture(content: String? = null, includeCover: Boolean = true): File {
        var html = content ?: ("<body><a filepos='0000000000'>第二章</a><h1 id='one'>第一章</h1>" +
            "<p>甲<b>旧🙂正文</b><img recindex='1'/></p><h1 id='two'>第二章</h1><p>第二章的正文</p></body>")
        if (content == null) {
            val target = html.substring(0, html.indexOf("<h1 id='two'>")).toByteArray().size
            html = html.replace("0000000000", target.toString().padStart(10, '0'))
        }
        val author = "源作者".toByteArray()
        val title = "源书名".toByteArray()
        val fields = listOf(100 to author, 121 to byteArrayOf(0, 0, 0, 4)) +
            if (includeCover) listOf(201 to byteArrayOf(0, 0, 0, 0)) else emptyList()
        val exthSize = 12 + fields.sumOf { it.second.size + 8 }
        val header = ByteArray(248 + exthSize + title.size)
        put16(header, 0, 1); put32(header, 4, html.toByteArray().size); put16(header, 8, 1); put16(header, 10, 4096)
        "MOBI".toByteArray().copyInto(header, 16)
        put32(header, 20, 232); put32(header, 24, 2); put32(header, 28, 65001); put32(header, 36, 6)
        put32(header, 84, 248 + exthSize); put32(header, 88, title.size); put32(header, 104, 6)
        put32(header, 108, 2); put32(header, 128, 64); put32(header, 244, -1)
        "EXTH".toByteArray().copyInto(header, 248); put32(header, 252, exthSize); put32(header, 256, fields.size)
        var at = 260
        fields.forEach { (id, bytes) -> put32(header, at, id); put32(header, at + 4, bytes.size + 8); bytes.copyInto(header, at + 8); at += bytes.size + 8 }
        title.copyInto(header, 248 + exthSize)
        val records = listOf(header, html.toByteArray(), png, "FONT-resource-preserved".toByteArray(), "BOUNDARY".toByteArray())
        val database = ByteArray(78)
        "BOOKMOBI".toByteArray().copyInto(database, 60); put16(database, 76, records.size)
        val output = ByteArrayOutputStream()
        output.write(database)
        at = 78 + records.size * 8 + 2
        records.forEach { record -> val entry = ByteArray(8); put32(entry, 0, at); output.write(entry); at += record.size }
        output.write(byteArrayOf(0, 0)); records.forEach(output::write)
        return File(root, "source.mobi").apply { writeBytes(output.toByteArray()) }
    }
    private fun put16(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 2).putShort(value.toShort()) }
    private fun put32(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).putInt(value) }
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
}
