package com.eyue.reader.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
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
        check(root.deleteRecursively())
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
        val chapter = book.chapters.indexOfFirst { File(it.textPath!!).readText().contains("旧🙂正文") }
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
        val chapter = original.chapters.indexOfFirst { File(it.textPath!!).readText().contains("旧🙂正文") }
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

    private fun registerProvider(authority: String, provider: ContentProvider) {
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    private fun folder(name: String) = File(root, name).apply { mkdirs() }
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
        return edit.book(book, edit.chapter(book.chapters[chapter], file.absolutePath), book.chapters.map { File(it.textPath!!).readText().length })
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
    private fun mobiFixture(): File {
        var html = "<body><a filepos='0000000000'>第二章</a><h1 id='one'>第一章</h1>" +
            "<p>甲<b>旧🙂正文</b><img recindex='1'/></p><h1 id='two'>第二章</h1><p>第二章的正文</p></body>"
        val target = html.substring(0, html.indexOf("<h1 id='two'>")).toByteArray().size
        html = html.replace("0000000000", target.toString().padStart(10, '0'))
        val author = "源作者".toByteArray()
        val title = "源书名".toByteArray()
        val fields = listOf(100 to author, 201 to byteArrayOf(0, 0, 0, 0), 121 to byteArrayOf(0, 0, 0, 4))
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
