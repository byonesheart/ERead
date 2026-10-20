package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class ComicFolderImportTest {
    private lateinit var context: Context
    private lateinit var importer: DocumentImporter
    private val books = mutableListOf<Book>()
    private val temporaryDirectories = mutableListOf<File>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        importer = DocumentImporter(context)
    }

    @After fun tearDown() {
        books.forEach { LibraryRepository(context).removeBookFiles(it) }
        temporaryDirectories.forEach { it.deleteRecursively() }
    }

    @Test fun fileImportEntryAcceptsSafTreeWithMixedImagesSubfoldersAndPdf() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildTreeDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "root")
        val book = importer.importFiles(listOf(uri), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE).single().also { books += it }
        assertMixedChapters(book)
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun fileImportEntryAcceptsDocumentDirectoryWhenGetTypeIsEmpty() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "root")
        assertTrue(context.contentResolver.getType(uri).isNullOrEmpty())
        val book = importer.importFiles(listOf(uri), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE).single().also { books += it }
        assertMixedChapters(book)
    }

    @Test fun folderEntryStillAcceptsSafTree() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildTreeDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "root")
        val book = importer.importFolder(uri, DEFAULT_CHAPTER_RULE).also { books += it }
        assertMixedChapters(book)
    }

    @Test fun explicitDirectoryFlagsWinOverAnExternalFileMimeHint() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "flagroot")
        val book = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE, "text/plain")
            .single().also { books += it }
        assertEquals("flags漫画", book.title)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(listOf(Color.GREEN), pixels(book.chapters.single().pages))
    }

    @Test fun externalMimeHintReadsAnOpaqueOrdinaryFile() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "opaque")
        val book = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE, "text/plain")
            .single().also { books += it }
        assertEquals(BookFormat.TXT, book.format)
        assertEquals("无扩展名文件", book.title)
        assertEquals("元数据作者", book.author)
    }

    @Test fun readonlyDirectoryMimeAliasesStillRouteToFolderImport() = runBlocking {
        grantProvider()
        listOf("inoderoot" to Color.BLUE, "resroot" to Color.GREEN).forEach { (id, color) ->
            val uri = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, id)
            val book = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE, "text/plain")
                .single().also { books += it }
            assertEquals(LibraryMode.COMIC, book.mode)
            assertEquals(listOf(color), pixels(book.chapters.single().pages))
        }
    }

    @Test fun supportedExternalMimeHintReplacesGenericBinaryTypeForOpaquePdf() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "binarypdf")
        val book = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE, "application/pdf")
            .single().also { books += it }
        assertEquals(BookFormat.PDF, book.format)
        assertEquals(listOf(0, 1), book.chapters.single().pages.map { it.pdfPage })
        assertTrue(runCatching { importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE, "application/zip") }.isFailure)
    }

    @Test fun fileDirectoryNamedLikeAnImageIsImportedAsAComicFolder() = runBlocking {
        val folder = File(context.cacheDir, "comic-test-${UUID.randomUUID()}.png").apply { mkdirs() }
        temporaryDirectories += folder
        val chapter = File(folder, "第2话").apply { mkdirs() }
        writeImage(File(folder, "2.png"), Color.GREEN)
        writeImage(File(folder, "1.png"), Color.RED)
        writeImage(File(chapter, "1.png"), Color.BLUE)
        val book = importer.importFiles(listOf(Uri.fromFile(folder)), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE)
            .single().also { books += it }
        assertEquals(listOf(folder.name), book.chapters.map { it.title })
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), pixels(book.chapters.single().pages))
        assertTrue(book.chapters.single().subsections.isEmpty())
    }

    @Test fun folderHierarchyCompressesSingleWrappersAndPreservesNestedPdfSectionRanges() = runBlocking {
        val folder = File(context.cacheDir, "hierarchy-${UUID.randomUUID()}").apply { mkdirs() }
        temporaryDirectories += folder
        File(folder, "空目录").mkdirs()
        val wrapper = File(folder, "单独打包目录").apply { mkdirs() }
        val first = File(wrapper, "第2话").apply { mkdirs() }
        val last = File(wrapper, "第10话").apply { mkdirs() }
        writeImage(File(first, "1.png"), Color.RED)
        writePdf(File(first, "第2节.pdf"), 2)
        val section = File(first, "第10节").apply { mkdirs() }
        writeImage(File(section, "1.png"), Color.GREEN)
        val secondPart = File(section, "第2段").apply { mkdirs() }
        val tenthPart = File(section, "第10段").apply { mkdirs() }
        writeImage(File(secondPart, "1.png"), Color.BLUE)
        writeImage(File(tenthPart, "1.png"), Color.MAGENTA)
        writeImage(File(last, "1.png"), Color.BLUE)
        val book = importer.importFolder(Uri.fromFile(folder), DEFAULT_CHAPTER_RULE).also { books += it }
        assertEquals(folder.name, book.title)
        assertNestedHierarchy(book)
        assertTrue(book.sourceLinked)
    }

    @Test fun safHierarchyUsesTheSameCompressedChaptersAndAbsoluteNestedSectionRanges() = runBlocking {
        grantProvider()
        val uri = DocumentsContract.buildTreeDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "hroot")
        val book = importer.importFiles(listOf(uri), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE).single().also { books += it }
        assertEquals("层级漫画", book.title)
        assertNestedHierarchy(book)
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    private fun assertNestedHierarchy(book: Book) {
        assertEquals(listOf("第2话", "第10话"), book.chapters.map { it.title })
        val chapter = book.chapters.first()
        assertEquals(6, chapter.pages.size)
        assertEquals(listOf(ComicSection("第2节", 1, 3), ComicSection("第10节", 3, 6,
            listOf(ComicSection("第2段", 4, 5), ComicSection("第10段", 5, 6)))), chapter.subsections)
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA), pixels(chapter.pages.filter { it.pdfPage < 0 }))
        assertEquals(listOf(0, 1), chapter.pages.filter { it.pdfPage >= 0 }.map { it.pdfPage })
        assertTrue(book.chapters.flatMap { it.pages }.all { it.textPath == null })
        assertEquals("", book.author)
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        try { assertTrue(Color.red(cover.getPixel(0, 0)) >= 250) } finally { cover.recycle() }
    }

    @Test fun aHugeTallOriginalImageGetsABoundedSeparateCoverWithoutDecodingTheFullBitmap() = runBlocking {
        val folder = File(context.cacheDir, "large-comic-${UUID.randomUUID()}").apply { mkdirs() }
        temporaryDirectories += folder
        val image = File(folder, "1.png")
        writeTallPng(image, 2000, 50_000)
        val book = importer.importFiles(listOf(Uri.fromFile(folder)), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE)
            .single().also { books += it }
        val original = book.chapters.single().pages.single().path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original, bounds)
        assertEquals(2000, bounds.outWidth)
        assertEquals(50_000, bounds.outHeight)
        assertTrue(original != book.coverPath)
        val cover = BitmapFactory.decodeFile(book.coverPath!!)
        assertTrue(cover.width <= 480 && cover.height <= 720)
        assertTrue(cover.byteCount <= 480 * 720 * 4)
        cover.recycle()
        assertTrue(File(book.coverPath!!).length() < 1024 * 1024)
    }

    private fun grantProvider() {
        context.contentResolver.call(Uri.parse("content://${ComicFixtureDocumentsProvider.AUTHORITY}"), "grant_fixture", context.packageName, null)
    }

    private fun assertMixedChapters(book: Book) {
        assertEquals("自然排序漫画", book.title)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(listOf(book.title, "第2话", "第3话", "第10话"), book.chapters.map { it.title })
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), pixels(book.chapters[0].pages))
        assertEquals(listOf(Color.GREEN, Color.BLUE, Color.RED), pixels(book.chapters[1].pages))
        assertEquals(listOf(0, 1), book.chapters[2].pages.map { it.pdfPage })
        assertTrue(book.chapters.flatMap { it.pages }.all { it.textPath == null })
        assertEquals("", book.author)
        assertEquals(listOf(Color.MAGENTA), pixels(book.chapters[3].pages))
        assertEquals(0L, book.lastReadAt)
        assertEquals(0f, book.progress)
    }

    private fun pixels(pages: List<PageSource>): List<Int> = pages.map { page ->
        val image = BitmapFactory.decodeFile(page.path)
        image.getPixel(0, 0).also { image.recycle() }
    }

    private fun writeImage(file: File, color: Int) {
        val image = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun writePdf(file: File, count: Int) {
        val document = PdfDocument()
        try {
            repeat(count) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }

    /** Generates a 100-million-pixel PNG as compressed scanlines, never a 400 MB test bitmap. */
    private fun writeTallPng(file: File, width: Int, height: Int) {
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(1)
        try {
            DeflaterOutputStream(compressed, deflater).use { output ->
                val row = ByteArray(1 + width * 3)
                repeat(width) { row[3 + it * 3] = 0xff.toByte() }
                repeat(height) { output.write(row) }
            }
        } finally { deflater.end() }
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(bytes.size)
                output.write(name)
                output.write(bytes)
                output.writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
    }
}
