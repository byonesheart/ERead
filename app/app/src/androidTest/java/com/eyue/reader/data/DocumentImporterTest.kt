package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentImporterTest {
    private lateinit var context: Context
    private lateinit var fixtures: File
    private lateinit var importer: DocumentImporter
    private val importedBooks = mutableListOf<Book>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixtures = File(context.cacheDir, "importer-test-${UUID.randomUUID()}").apply { mkdirs() }
        importer = DocumentImporter(context)
    }

    @After fun tearDown() {
        importedBooks.forEach { LibraryRepository(context).removeBookFiles(it) }
        fixtures.deleteRecursively()
    }

    @Test fun versionThreeCachedBookCompactsBodyAndMapsReadingImagesAndNotesWithoutAnOriginal() = runBlocking {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val original = "标题\n\n甲🙂\n\n乙\n\n引用\n\n后续\n\n结尾"
        val text = File(directory, "legacy.txt").apply { writeText(original) }
        val image = File(directory, "inline.png").apply { writeBytes(imageBytes(Color.RED)) }
        val chapter = Chapter(id = "stable-chapter", title = "标题", textPath = text.absolutePath,
            inlineImages = listOf(InlineImage(20, image.absolutePath)), sourceStyles = listOf(
                SourceTextStyle(0, 2, fontScale = 1.5f, bold = true, paragraph = true),
                SourceTextStyle(12, 14, italic = true, paragraph = true)))
        val old = Book(id = id, title = "已缓存 MOBI", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(chapter), contentVersion = 3, characterOffset = 20, positionImagePath = image.absolutePath,
            pageIndex = 4, lastReadAt = 123456L, progress = .4f).also { importedBooks += it }
        val mark = Annotation(bookId = id, chapterIndex = 0, pageIndex = 3, characterOffset = 16, endOffset = 22,
            quote = "后续\n\n结尾", note = "保留笔记", kind = AnnotationKind.NOTE, color = 0xffdfc981L)
        val bookmark = Annotation(bookId = id, chapterIndex = 0, pageIndex = 1, characterOffset = 9)
        val upgraded = importer.upgradeInlineBooks(LibraryState(books = listOf(old), annotations = listOf(mark, bookmark)))
        val book = upgraded.books.single()
        assertEquals(4, book.contentVersion)
        assertEquals(old.title, book.title)
        assertEquals(chapter.id, book.chapters.single().id)
        assertEquals(18, book.characterOffset)
        assertEquals(old.positionImagePath, book.positionImagePath)
        assertEquals(123456L, book.lastReadAt)
        assertEquals(.4f, book.progress)
        assertEquals("标题\n\n甲🙂\n乙\n\n引用\n\n后续\n结尾", File(book.chapters.single().textPath!!).readText())
        assertEquals(18, book.chapters.single().inlineImages.single().offset)
        assertEquals(SourceTextStyle(11, 13, italic = true, paragraph = true), book.chapters.single().sourceStyles.last())
        val note = upgraded.annotations.first()
        assertEquals(mark.id, note.id)
        assertEquals(15, note.characterOffset)
        assertEquals(20, note.endOffset)
        assertEquals("后续\n结尾", note.quote)
        assertEquals(mark.note, note.note)
        assertEquals(mark.color, note.color)
        assertEquals(8, upgraded.annotations.last().characterOffset)
        assertEquals(original, text.readText())
        assertEquals(upgraded, importer.upgradeInlineBooks(upgraded))
        assertTrue(importer.lastImportErrors.any { it.contains("目录") })
    }

    @Test fun cloudOnlyInformationSkipsSourceAndStyleUpgrades() = runBlocking {
        val info = Book(title = "待下载", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章")), cloudOnly = true, contentVersion = 1)
        val state = LibraryState(books = listOf(info))
        assertEquals(state, importer.upgradeInlineBooks(state))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun versionSixMobiUpdatesOnceWithoutReparsingCurrentEpub() = runBlocking {
        val source = File(fixtures, "version-five.mobi").apply { writeBytes(mobiFixture()) }
        val imported = import(source, LibraryMode.NOVEL)
        assertEquals(8, imported.contentVersion)
        val previous = imported.copy(contentVersion = 6)
        val epub = Book(title = "保留 EPUB 缓存", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "原目录")), contentVersion = 6)
        val updated = importer.upgradeInlineBooks(LibraryState(books = listOf(previous, epub)))
        assertEquals(7, updated.books.first().contentVersion)
        assertEquals(epub, updated.books.last())
        assertEquals(previous.id, updated.books.first().id)
        assertEquals(updated, importer.upgradeInlineBooks(updated))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun versionSixBodyStyleUpgradePreservesTextFilesReadingPositionImagesAndNotes() = runBlocking {
        val source = File(fixtures, "paragraph-format.mobi").apply { writeBytes(mobiFixture(
            "<body><p align='center'><b>标题</b></p>" +
                "<p height='12pt' width='2em' align='justify'>合成第一段🙂<img recindex='2'/></p>" +
                "<p height='12pt' width='2em' align='justify'>合成第二段<strong>强调</strong></p></body>",
            listOf(imageBytes(Color.RED)))) }
        val imported = import(source, LibraryMode.NOVEL)
        val chapter = imported.chapters.single()
        val original = File(chapter.textPath!!).readText()
        val offset = original.indexOf("合成第二段")
        val legacy = imported.copy(contentVersion = 6, characterOffset = offset, pageIndex = 2,
            progress = .35f, lastReadAt = 123456L, chapters = listOf(chapter.copy(sourceStyles = chapter.sourceStyles +
                SourceTextStyle(offset, original.length, fontScale = 1f, bold = false, italic = false,
                    alignment = SourceTextAlignment.JUSTIFY, firstLineIndentEm = 0f, restLineIndentEm = 0f, paragraph = true))))
        val note = Annotation(bookId = legacy.id, chapterIndex = 0, pageIndex = 0, characterOffset = offset, endOffset = offset + 5,
            quote = "合成第二段", note = "保留笔记", kind = AnnotationKind.NOTE)
        val upgraded = importer.upgradeInlineBooks(LibraryState(books = listOf(legacy), annotations = listOf(note)))
        val book = upgraded.books.single()
        assertEquals(7, book.contentVersion)
        assertEquals(chapter.textPath, book.chapters.single().textPath)
        assertEquals(original, File(book.chapters.single().textPath!!).readText())
        assertEquals(offset, book.characterOffset)
        assertEquals(legacy.lastReadAt, book.lastReadAt)
        assertEquals(legacy.progress, book.progress)
        assertEquals(chapter.inlineImages, book.chapters.single().inlineImages)
        assertEquals(note, upgraded.annotations.single())
        assertTrue(book.chapters.single().sourceStyles.none { it.paragraph && it.alignment == SourceTextAlignment.JUSTIFY })
        assertTrue(book.chapters.single().sourceStyles.any { it.paragraph && it.alignment == SourceTextAlignment.CENTER })
        assertTrue(original.contains("合成第一段🙂\n合成第二段"))
        assertTrue(source.delete())
        assertEquals(upgraded, importer.upgradeInlineBooks(upgraded))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun versionFourFootnoteChaptersAreRebuiltOnceAndKeepGroupsHistoryAndNotes() = runBlocking {
        val source = File(fixtures, "footnotes.mobi").apply { writeBytes(mobiFixture(
            "<h1>Chapter I</h1><p>正文<a href='#note-a'>[1]</a><a href='#note-b'>[2]</a></p>" +
                "<h1>Chapter II</h1><p>后续正文</p><aside id='note-a' epub:type='footnote'><h2>注释一</h2>第一条解释</aside>" +
                "<aside id='note-b' role='doc-footnote'><h2>注释二</h2>第二条解释</aside>")) }
        val imported = import(source, LibraryMode.NOVEL)
        val whole = imported.chapters.joinToString("\n\n") { File(it.textPath!!).readText() }
        val first = whole.indexOf("注释一")
        val second = whole.indexOf("注释二")
        assertTrue(first > 0 && second > first)
        val group = ShelfGroup("收藏", LibraryMode.NOVEL)
        val legacy = imported.copy(contentVersion = 4, chapters = listOf(
            Chapter(title = "前言", textPath = File(fixtures, "legacy-prefix.txt").apply { writeText(whole.substring(0, first)) }.absolutePath),
            Chapter(title = "[1]", textPath = File(fixtures, "legacy-note-one.txt").apply { writeText(whole.substring(first, second)) }.absolutePath),
            Chapter(title = "[2]", textPath = File(fixtures, "legacy-note-two.txt").apply { writeText(whole.substring(second)) }.absolutePath)),
            chapterIndex = 1, characterOffset = 0, progress = .6f, lastReadAt = 123456L, groupId = group.id)
        val note = Annotation(bookId = legacy.id, chapterIndex = 1, pageIndex = 0,
            characterOffset = 0, endOffset = 3, quote = "注释一", note = "保留笔记", kind = AnnotationKind.NOTE)
        val state = LibraryState(books = listOf(legacy), annotations = listOf(note), groups = listOf(group))
        val upgraded = importer.upgradeInlineBooks(state)
        val book = upgraded.books.single()
        assertEquals(7, book.contentVersion)
        assertEquals(listOf("Chapter I", "Chapter II"), book.chapters.map { it.title })
        assertEquals(1, book.chapterIndex)
        assertEquals("注释一", File(book.chapters[1].textPath!!).readText().substring(book.characterOffset, book.characterOffset + 3))
        assertEquals(legacy.id, book.id)
        assertEquals(legacy.lastReadAt, book.lastReadAt)
        assertEquals(legacy.progress, book.progress)
        assertEquals(group.id, book.groupId)
        assertEquals(state.groups, upgraded.groups)
        val mark = upgraded.annotations.single()
        assertEquals(note.id, mark.id)
        assertEquals(note.note, mark.note)
        assertEquals(1, mark.chapterIndex)
        assertEquals(note.quote, File(book.chapters[1].textPath!!).readText().substring(mark.characterOffset, mark.endOffset))
        assertTrue(source.delete())
        assertEquals(upgraded, importer.upgradeInlineBooks(upgraded))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun importsGb18030TextWithExplicitMetadataAndChapters() = runBlocking {
        val source = File(fixtures, "fallback.txt").apply {
            writeBytes("书名：本地标题\r\n作者：测试作者\r\n\r\n第一章 开端\r\n片段甲。\r\n第二章 继续\r\n片段乙。"
                .toByteArray(Charset.forName("GB18030")))
        }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals("本地标题", book.title)
        assertEquals("测试作者", book.author)
        assertEquals(BookFormat.TXT, book.format)
        assertEquals(listOf("第一章 开端", "第二章 继续"), book.chapters.drop(1).map { it.title })
        assertTrue(File(book.chapters.last().textPath!!).readText().contains("片段乙。"))
        assertTrue(File(book.sourcePath!!).isFile)
        assertEquals(0f, book.progress)
        assertEquals(0L, book.lastReadAt)
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun importsEpubPackageCoverAndReadableHtml() = runBlocking {
        val source = File(fixtures, "fixture.epub")
        val entries = mapOf(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfile full-path="OPS/book.opf"/></container>""",
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>真实 EPUB 示例</dc:title><dc:creator>明确作者</dc:creator></metadata><manifest><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="cover" href="cover.png" properties="cover-image" media-type="image/png"/></manifest><spine><itemref idref="body"/></spine></package>""",
            "OPS/nav.xhtml" to """<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><nav><a href="body.xhtml">目录中的第一章</a></nav></html>""",
            "OPS/body.xhtml" to """<html><head><style>notVisibleStyle</style></head><body><h1>第一章</h1><p>第一段 &amp; 第二段。</p><p>第三段。</p><p>甲🙂乙<img src="cover.png"/>丙</p><script>notVisibleScript</script></body></html>"""
        )
        ZipOutputStream(source.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("OPS/cover.png"))
            zip.write(imageBytes(Color.BLUE))
            zip.closeEntry()
        }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals("真实 EPUB 示例", book.title)
        assertEquals("明确作者", book.author)
        assertEquals("目录中的第一章", book.chapters.single().title)
        val text = File(book.chapters.single().textPath!!).readText()
        assertTrue(text.contains("第一段 & 第二段。"))
        assertTrue(text.contains("第三段。"))
        assertFalse(text.contains("notVisible"))
        assertTrue(book.chapters.single().pages.isEmpty())
        assertEquals(text.indexOf("丙"), book.chapters.single().inlineImages.single().offset)
        assertEquals("甲🙂乙丙", text.substring(text.indexOf("甲")))
        assertTrue(File(book.coverPath!!).length() > 0)
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        assertTrue(Color.blue(cover.getPixel(0, 0)) >= 240)
        assertTrue(cover.width <= 480 && cover.height <= 720)
        cover.recycle()
        assertEquals(LibraryMode.NOVEL, import(source, LibraryMode.COMIC).mode)
    }

    @Test fun imageOnlyEpubIsAComicWithPagesFromItsReadingOrder() = runBlocking {
        val source = File(fixtures, "image-only.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><manifest><item id='body' href='body.xhtml' media-type='application/xhtml+xml'/><item id='img' href='page.png' media-type='image/png'/></manifest><spine><itemref idref='body'/></spine></package>",
                "OPS/body.xhtml" to "<html><body><img src='page.png'/><img src='page.png'/></body></html>")
            entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("OPS/page.png")); zip.write(imageBytes(Color.GREEN)); zip.closeEntry()
        }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(2, book.chapters.single().pages.size)
        assertTrue(book.chapters.single().pages.all { it.textPath == null })
        assertTrue(File(book.coverPath!!).exists())
    }

    @Test fun importsRealPdfPagesTextAndSourceMetadata() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(fixtures, "fixture.pdf")
        PDDocument().use { document ->
            document.documentInformation.title = "Explicit PDF Title"
            document.documentInformation.author = "Explicit PDF Author"
            repeat(2) { index ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("PDF import verification page ${index + 1}")
                    content.endText()
                }
            }
            document.save(source)
        }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals("Explicit PDF Title", book.title)
        assertEquals("Explicit PDF Author", book.author)
        assertEquals(BookFormat.PDF, book.format)
        val pages = book.chapters.single().pages
        assertEquals(2, pages.size)
        assertEquals(listOf(0, 1), pages.map { it.pdfPage })
        assertTrue(File(pages[0].textPath!!).readText().contains("PDF import verification page 1"))
        assertTrue(File(pages[1].textPath!!).readText().contains("PDF import verification page 2"))
        assertTrue(File(book.coverPath!!).length() > 0)
        assertTrue(pages.all { File(it.path).isFile })
        val fromComic = import(source, LibraryMode.COMIC)
        assertEquals(LibraryMode.NOVEL, fromComic.mode)
        assertTrue(fromComic.chapters.single().pages.any { it.textPath != null })
    }

    @Test fun comicPdfUsesItsFilenameFirstPageAndNoExtractedTextOrAuthor() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(fixtures, "第12话.pdf")
        PDDocument().use { document ->
            document.documentInformation.title = "应忽略的标题"
            document.documentInformation.author = "应忽略的作者"
            listOf(Color.RED, Color.BLUE).forEach { color ->
                val page = PDPage().also(document::addPage)
                PDPageContentStream(document, page).use { content ->
                    content.setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
                    content.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height)
                    content.fill()
                }
            }
            document.save(source)
        }
        val updates = mutableListOf<ImportProgress?>()
        val collector = launch(Dispatchers.Unconfined) { importer.progress.collect { updates += it } }
        val book = import(source, LibraryMode.NOVEL)
        collector.cancelAndJoin()
        assertEquals("第12话", book.title)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals("第12话", book.chapters.single().title)
        assertEquals("", book.author)
        assertEquals(listOf(0, 1), book.chapters.single().pages.map { it.pdfPage })
        assertTrue(book.chapters.single().pages.all { it.textPath == null })
        assertTrue(File(context.filesDir, "books/${book.id}").walkTopDown().none { it.extension == "txt" })
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        try {
            val pixel = cover.getPixel(cover.width / 2, cover.height / 2)
            assertTrue(Color.red(pixel) >= 240 && Color.blue(pixel) <= 10)
        } finally { cover.recycle() }
        assertTrue(updates.filterNotNull().any { it.stage == "漫画整理完成" && it.fraction == 1f })
        assertTrue(updates.filterNotNull().any { it.stage.contains("文字层") })
        assertEquals(null, updates.last())
    }

    @Test fun failedImportClearsItsProgress() = runBlocking {
        val broken = File(fixtures, "损坏.mobi").apply { writeText("broken mobi") }
        val updates = mutableListOf<ImportProgress?>()
        val collector = launch(Dispatchers.Unconfined) { importer.progress.collect { updates += it } }
        val result = runCatching { importer.importFiles(listOf(Uri.fromFile(broken)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE) }
        collector.cancelAndJoin()
        assertTrue(result.isFailure)
        assertTrue(updates.any { it?.stage == "解压 MOBI 正文记录" })
        assertEquals(null, importer.progress.value)
    }

    @Test fun mergesSelectedImagesAndSortsTheirNumericNames() = runBlocking {
        val colors = listOf(10 to Color.BLUE, 2 to Color.GREEN, 1 to Color.RED)
        val sources = colors.map { (number, color) ->
            File(fixtures, "page-$number.png").apply { writeBytes(imageBytes(color)) }
        }
        val books = importer.importFiles(sources.map(Uri::fromFile), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE)
        importedBooks += books
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("", book.author)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(BookFormat.IMAGES, book.format)
        val pixels = book.chapters.single().pages.map { page ->
            val bitmap = BitmapFactory.decodeFile(page.path)
            bitmap.getPixel(0, 0).also { bitmap.recycle() }
        }
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), pixels)
        assertTrue(book.chapters.single().pages.first().path != book.coverPath)
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        assertTrue(cover.width <= 480 && cover.height <= 720)
        cover.recycle()
        assertEquals(0L, book.lastReadAt)
    }

    @Test fun importsMobiMetadataCoverAndChineseHtmlChapters() = runBlocking {
        val source = File(fixtures, "fixture.mobi").apply { writeBytes(mobiFixture()) }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals(BookFormat.MOBI, book.format)
        assertEquals("真实 MOBI 示例", book.title)
        assertEquals("本地作者", book.author)
        assertEquals(listOf("第一章 清晨", "第二章 黄昏"), book.chapters.map { it.title })
        assertTrue(File(book.chapters.first().textPath!!).readText().contains("这是本地 MOBI 中的中文正文。"))
        assertTrue(File(book.chapters.last().textPath!!).readText().contains("第二章正文。"))
        assertTrue(File(book.coverPath!!).isFile)
        val bitmap = BitmapFactory.decodeFile(book.coverPath!!)
        assertTrue(Color.blue(bitmap.getPixel(0, 0)) >= 240)
        bitmap.recycle()
        assertEquals(0L, book.lastReadAt)
        assertEquals(0f, book.progress)
        assertEquals(LibraryMode.NOVEL, import(source, LibraryMode.COMIC).mode)
        val rebuilt = importer.rebuildChapters(book, "^第二章.*$")
        assertEquals(listOf("前言", "第二章 黄昏"), rebuilt.chapters.map { it.title })
        val custom = importer.importFiles(listOf(Uri.fromFile(source)), LibraryMode.NOVEL, "^第二章.*$").single()
        importedBooks += custom
        assertEquals(listOf("前言", "第二章 黄昏"), custom.chapters.map { it.title })
        val restored = importer.rebuildChapters(custom, DEFAULT_CHAPTER_RULE)
        assertEquals(book.chapters.map { it.title }, restored.chapters.map { it.title })
        val romanSource = File(fixtures, "roman.mobi").apply {
            writeBytes(mobiFixture("<html><body><h1>Chapter I. Morning</h1><p>First body.</p><h1>Chapter II. Evening</h1><p>Second body.</p></body></html>"))
        }
        val roman = import(romanSource, LibraryMode.NOVEL)
        val customRoman = importer.rebuildChapters(roman, "^Chapter II.*$")
        assertEquals(listOf("前言", "Chapter II. Evening"), customRoman.chapters.map { it.title })
        val restoredRoman = importer.rebuildChapters(customRoman, DEFAULT_CHAPTER_RULE)
        assertEquals(listOf("Chapter I. Morning", "Chapter II. Evening"), restoredRoman.chapters.map { it.title })
        assertTrue(File(restoredRoman.chapters.last().textPath!!).readText().contains("Second body."))
    }

    @Test fun imageOnlyMobiIsAComicWithRepeatedPagesAndFirstPageCover() = runBlocking {
        val source = File(fixtures, "图片.mobi").apply {
            writeBytes(mobiFixture("<body><img recindex='3'/><img recindex='2'/><img recindex='3'/></body>",
                listOf(imageBytes(Color.RED), imageBytes(Color.GREEN))))
        }
        val book = import(source, LibraryMode.NOVEL)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(BookFormat.MOBI, book.format)
        assertEquals(listOf(Color.GREEN, Color.RED, Color.GREEN), book.chapters.single().pages.map { page ->
            val bitmap = BitmapFactory.decodeFile(page.path)
            bitmap.getPixel(0, 0).also { bitmap.recycle() }
        })
        assertEquals(null, book.chapters.single().textPath)
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        assertTrue(Color.green(cover.getPixel(0, 0)) >= 240)
        cover.recycle()
        assertTrue(runCatching { importer.rebuildChapters(book, DEFAULT_CHAPTER_RULE) }.isFailure)
    }

    @Test fun mobiBodyRecindicesAndAdjacentImagesPreserveUtf16Offsets() = runBlocking {
        val source = File(fixtures, "inline.mobi").apply {
            writeBytes(mobiFixture("<p>甲🙂乙<img recindex='00002'/><img recindex='00003'/>丙</p>",
                listOf(imageBytes(Color.RED), imageBytes(Color.GREEN))))
        }
        val book = import(source, LibraryMode.NOVEL)
        val chapter = book.chapters.single()
        assertEquals("甲🙂乙丙", File(chapter.textPath!!).readText())
        assertEquals(listOf(4, 4), chapter.inlineImages.map { it.offset })
        assertTrue(chapter.pages.isEmpty())
        assertEquals(listOf(Color.RED, Color.GREEN), chapter.inlineImages.map { image ->
            val bitmap = BitmapFactory.decodeFile(image.path)
            bitmap.getPixel(0, 0).also { bitmap.recycle() }
        })
        val custom = importer.rebuildChapters(book, "^甲.*$")
        assertEquals(chapter.inlineImages, custom.chapters.single().inlineImages)
        val restored = importer.rebuildChapters(custom, DEFAULT_CHAPTER_RULE)
        assertEquals(chapter.inlineImages, restored.chapters.single().inlineImages)
    }

    @Test fun kindleEmbed0001SelectsTheFirstResourceWithoutAnOffByOneShift() = runBlocking {
        val source = File(fixtures, "embed.mobi").apply {
            writeBytes(mobiFixture("<p>甲🙂<img src='kindle:embed:0001?mime=image/png'/>乙</p>", listOf(imageBytes(Color.RED))))
        }
        val book = import(source, LibraryMode.NOVEL)
        val image = book.chapters.single().inlineImages.single()
        assertEquals(3, image.offset)
        val bitmap = BitmapFactory.decodeFile(image.path)
        assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test fun upgradingAnAlreadyImportedMobiPreservesHistoryTextMarksAndRestoresInlineAssets() = runBlocking {
        val source = File(fixtures, "legacy.mobi").apply {
            writeBytes(mobiFixture("<p>甲🙂乙<img recindex='00002'/>丙丁</p>", listOf(imageBytes(Color.RED))))
        }
        val imported = import(source, LibraryMode.NOVEL)
        val oldText = File(File(imported.sourcePath!!).parentFile, "legacy-chapter.txt").apply { writeText("甲🙂乙\uFFFC丙丁") }
        val old = imported.copy(contentVersion = 1, chapters = listOf(Chapter(title = "正文", textPath = oldText.absolutePath)),
            characterOffset = 5, lastReadAt = 123456L, progress = 0.37f)
        val annotation = Annotation(bookId = old.id, chapterIndex = 0, pageIndex = 0, characterOffset = 5,
            endOffset = 7, quote = "丙丁", note = "保留笔记", kind = AnnotationKind.NOTE, color = 0xffdfc981L)
        val state = LibraryState(books = listOf(old), annotations = listOf(annotation), readingSeconds = 82)
        val upgraded = importer.upgradeInlineBooks(state)
        val book = upgraded.books.single()
        assertEquals(7, book.contentVersion)
        assertEquals(4, book.characterOffset)
        assertEquals(123456L, book.lastReadAt)
        assertEquals(0.37f, book.progress)
        assertEquals(4, book.chapters.single().inlineImages.single().offset)
        val mark = upgraded.annotations.single()
        assertEquals(annotation.id, mark.id)
        assertEquals(4, mark.characterOffset)
        assertEquals(6, mark.endOffset)
        assertEquals(annotation.note, mark.note)
        assertEquals(annotation.color, mark.color)
        assertEquals(82L, upgraded.readingSeconds)
        assertEquals(upgraded, importer.upgradeInlineBooks(upgraded))
    }

    private suspend fun import(file: File, mode: LibraryMode): Book =
        importer.importFiles(listOf(Uri.fromFile(file)), mode, DEFAULT_CHAPTER_RULE).single().also { importedBooks += it }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun mobiFixture(html: String = "<html><body><h1>第一章 清晨</h1><p>这是本地 MOBI 中的中文正文。</p><h1>第二章 黄昏</h1><p>第二章正文。</p></body></html>", bodyImages: List<ByteArray> = emptyList()): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val title = "真实 MOBI 示例".toByteArray(Charsets.UTF_8)
        val fields = listOf(100 to "本地作者".toByteArray(Charsets.UTF_8), 503 to title, 201 to byteArrayOf(0, 0, 0, 0))
        val exthSize = 12 + fields.sumOf { 8 + it.second.size }
        val titleOffset = 248 + exthSize
        val header = ByteArray(titleOffset + title.size)
        fun word(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 2).putShort(value.toShort()) }
        fun number(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).putInt(value) }
        word(header, 0, 1)
        number(header, 4, text.size)
        word(header, 8, 1)
        word(header, 10, 4096)
        "MOBI".toByteArray().copyInto(header, 16)
        number(header, 20, 232)
        number(header, 24, 2)
        number(header, 28, 65001)
        number(header, 36, 6)
        number(header, 84, titleOffset)
        number(header, 88, title.size)
        number(header, 104, 6)
        number(header, 108, 2)
        number(header, 128, 64)
        "EXTH".toByteArray().copyInto(header, 248)
        number(header, 252, exthSize)
        number(header, 256, fields.size)
        var location = 260
        fields.forEach { (id, bytes) ->
            number(header, location, id)
            number(header, location + 4, 8 + bytes.size)
            bytes.copyInto(header, location + 8)
            location += 8 + bytes.size
        }
        title.copyInto(header, titleOffset)
        val records = listOf(header, text, imageBytes(Color.BLUE)) + bodyImages
        val tableSize = 78 + records.size * 8 + 2
        val pdb = ByteArray(tableSize + records.sumOf { it.size })
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        word(pdb, 76, records.size)
        location = tableSize
        records.forEachIndexed { index, bytes ->
            number(pdb, 78 + index * 8, location)
            bytes.copyInto(pdb, location)
            location += bytes.size
        }
        return pdb
    }
}
