package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
class EpubContentsRobolectricTest {
    @Test fun outlineRoundTripsSeparatelyAndLegacyBooksKeepTheUninitializedState() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val book = Book(title = "目录样本", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(id = "body", title = "正文")))
        assertNull(repository.readBook(repository.writeBook(book)).publicationContents)
        val initialized = book.copy(publicationContents = emptyList())
        assertEquals(initialized, repository.readBook(repository.writeBook(initialized)))
        val outlined = book.copy(publicationContents = listOf(PublicationContentsEntry("parent", "父标题", 0),
            PublicationContentsEntry("child", "子标题", 0, "parent")))
        assertEquals(outlined, repository.readBook(repository.writeBook(outlined)))
    }

    @Test fun existingBookGetsOnlyAnOutlineWithoutRewritingTextOrPositions() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val directory = File(context.cacheDir, "contents-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = source(directory)
            val text = File(directory, "body.txt").apply { writeText("现有正文与注释位置必须保持不变。") }
            val chapter = Chapter(id = "kept", title = "旧标题", textPath = text.path, characterCount = text.readText().length)
            val book = Book(id = "original", title = "旧书", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
                chapters = listOf(chapter), sourcePath = source.path, characterOffset = 9, progress = .6f, lastReadAt = 123L)
            val original = text.readBytes(); val archive = source.readBytes()
            val updated = DocumentImporter(context).withPublicationContents(book)
            assertEquals(book, updated.copy(publicationContents = null))
            assertEquals(listOf("分册", "正文"), updated.publicationContents!!.map { it.title })
            assertArrayEquals(original, text.readBytes()); assertArrayEquals(archive, source.readBytes())
            val initialized = DocumentImporter(context).withPublicationContents(updated)
            assertEquals(updated, initialized)
        } finally { directory.deleteRecursively() }
    }

    @Test fun importingANewPublicationStoresItsNativeOutlineWithTheCompleteReadingCache() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val directory = File(context.cacheDir, "contents-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val output = File(directory, "imported").apply { mkdirs() }
            val importer = DocumentImporter(context)
            val method = DocumentImporter::class.java.declaredMethods.single { it.name == "importEpub" && it.parameterCount == 5 }
            method.isAccessible = true
            val book = method.invoke(importer, source(directory), output, "new-book", "导入样本", LibraryMode.NOVEL) as Book
            assertEquals(1, book.chapters.size)
            assertEquals(listOf("分册", "正文"), book.publicationContents!!.map { it.title })
            assertTrue(File(book.sourcePath!!).isFile)
            assertTrue(File(book.chapters.single().textPath!!).readText().contains("现有正文"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aMismatchingLegacyIndexCannotSilentlyRetargetItsReadingRecords() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val directory = File(context.cacheDir, "contents-mismatch-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val book = Book(title = "旧书", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, sourcePath = source(directory).path,
                chapters = List(2) { Chapter(id = "kept-$it", title = "旧片段 $it") }, chapterIndex = 1, characterOffset = 10)
            try { DocumentImporter(context).withPublicationContents(book); fail("Mismatching indexes must not be accepted") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("不匹配")) }
            assertNull(book.publicationContents); assertEquals(1, book.chapterIndex); assertEquals(10, book.characterOffset)
        } finally { directory.deleteRecursively() }
    }

    private fun source(directory: File): File = File(directory, "source.epub").apply {
        val entries = mapOf("META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to "<package><manifest><item id='a' href='a.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='a'/></spine></package>",
            "nav.xhtml" to "<html><nav><ol><li><span>分册</span><ol><li><a href='a.xhtml'>正文</a></li></ol></li></ol></nav></html>",
            "a.xhtml" to "<html><body><p>现有正文与注释位置必须保持不变。</p></body></html>")
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
}
