package com.eyue.reader.ui

import com.eyue.reader.data.AiSpeechChapterCacheStatus
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechCacheStatusTest {
    @Test fun fullBookStatusUsesChapterMetadataWithoutOpeningText() {
        val book = Book(title = "Metadata", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "Text", textPath = "missing-untouched.txt"),
                Chapter(title = "Empty", textPath = "missing-empty.txt", characterCount = 0),
                Chapter(title = "Images", pages = listOf(PageSource("image.png")))))
        val result = readerSpeechCacheStatus(book, AiSpeechChapterCacheStatus(setOf(0), setOf(1)))
        assertEquals(setOf(0), result.chapters)
        assertEquals(setOf(1), result.otherChapters)
        assertTrue(result.complete)
    }

    @Test fun unknownTextChaptersAndPdfTextPagesRequireCurrentConfigurationCompletion() {
        val book = Book(title = "Required", format = BookFormat.PDF, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "Unknown", textPath = "missing-unknown.txt"),
                Chapter(title = "Page text", pages = listOf(PageSource("page.pdf", textPath = "missing-page.txt")))))
        assertFalse(readerSpeechCacheStatus(book, AiSpeechChapterCacheStatus(setOf(0), setOf(1))).complete)
        assertTrue(readerSpeechCacheStatus(book, AiSpeechChapterCacheStatus(setOf(0, 1))).complete)
        assertFalse(readerSpeechCacheStatus(book.copy(chapters = emptyList()), AiSpeechChapterCacheStatus()).complete)
    }

    @Test fun indexedBlankChaptersDoNotPreventACompleteBookBadge() {
        val book = Book(title = "Blank chapter", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "Unknown blank", textPath = "missing-blank.txt"),
                Chapter(title = "Text", textPath = "missing-text.txt")))
        val cached = AiSpeechChapterCacheStatus(currentChapters = setOf(1), emptyChapters = setOf(0))
        val result = readerSpeechCacheStatus(book, cached)
        assertTrue(result.complete)
        assertEquals(setOf(1), result.chapters)
    }
}
