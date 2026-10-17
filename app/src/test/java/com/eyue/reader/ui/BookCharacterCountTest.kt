package com.eyue.reader.ui

import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.util.UUID

class BookCharacterCountTest {
    @Test fun countsUnicodeCodePointsWithoutWhitespaceOrImagePlaceholders() {
        assertEquals(6L, countTextCharacters(StringReader("正文A😀𠀀。 \t\r\n\u3000\u00A0\uFFFC")))
    }

    @Test fun supplementaryCharacterAcrossBuffersIsOnlyCountedOnce() {
        assertEquals(8193L, countTextCharacters(StringReader("a".repeat(8191) + "😀文")))
    }

    @Test fun sumsChaptersAndPdfPageTextWithoutDuplicatingTheirCombinedText() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "book-count-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val first = File(root, "one.txt").apply { writeText("甲 乙😀") }.absolutePath
            val second = File(root, "two.txt").apply { writeText("AB\nC") }.absolutePath
            val book = Book(title = "统计", format = BookFormat.PDF, mode = LibraryMode.NOVEL, chapters = listOf(
                Chapter(title = "文本章节", textPath = first, pages = listOf(PageSource("pdf", 0, first))),
                Chapter(title = "PDF 页面", pages = listOf(PageSource("pdf", 1, second)))
            ))
            assertEquals(6L, bookCharacterCount(book))
            assertEquals(0L, bookCharacterCount(book.copy(mode = LibraryMode.COMIC)))
        } finally { root.deleteRecursively() }
    }

    @Test fun speechMenuRequiresLocalNovelTextAndAcceptsPdfText() {
        val novel = Book(title = "正文", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "正文", textPath = "chapter.txt", characterCount = 12)))
        assertTrue(novel.hasSpeechText())
        assertFalse(novel.copy(mode = LibraryMode.COMIC).hasSpeechText())
        assertFalse(novel.copy(cloudOnly = true).hasSpeechText())
        assertFalse(novel.copy(chapters = listOf(Chapter(title = "图片", textPath = "empty.txt", characterCount = 0))).hasSpeechText())
        assertTrue(novel.copy(chapters = listOf(Chapter(title = "PDF", pages = listOf(PageSource("pdf", 0, "page.txt"))))).hasSpeechText())
    }
}
