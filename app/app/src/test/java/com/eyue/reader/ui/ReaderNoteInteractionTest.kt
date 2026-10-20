package com.eyue.reader.ui

import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNoteInteractionTest {
    private val text = "开篇😀。\n这段文字有笔记，下一段没有。"
    private fun note(start: Int, end: Int, quote: String = "", id: String = "note") = Annotation(
        id = id, bookId = "book", chapterIndex = 1, pageIndex = 0, characterOffset = start,
        endOffset = end, quote = quote, kind = AnnotationKind.NOTE, note = "对应想法")

    @Test fun sourceRangesUseUtf16AndTheirExclusiveEndSurvivesLineWrapping() {
        val start = text.indexOf("这段")
        val end = text.indexOf("，")
        val item = note(start, end)
        assertEquals(6, start)
        assertEquals(listOf(item), readerTextNotes(text, listOf(item), "book", 1, start))
        assertEquals(listOf(item), readerTextNotes(text, listOf(item), "book", 1, end - 1))
        assertEquals(emptyList<Annotation>(), readerTextNotes(text, listOf(item), "book", 1, end))
    }

    @Test fun oldNotesWithoutEndOffsetsUseOnlyTheirExactSavedQuote() {
        val start = text.indexOf("这段")
        val item = note(start, 0, "这段文字")
        assertEquals(start until start + 4, readerAnnotationRange(text, item))
        assertEquals(listOf(item), readerTextNotes(text, listOf(item), "book", 1, start + 2))
        assertNull(readerAnnotationRange(text, item.copy(quote = "来自别处的文字")))
        assertNull(readerAnnotationRange(text, item.copy(quote = "")))
    }

    @Test fun overlappingNotesAreReturnedWithoutBookmarksOtherBooksOrImageNotes() {
        val first = note(7, 13, id = "first").copy(createdAt = 1L)
        val second = note(8, 14, id = "second").copy(createdAt = 2L)
        val excluded = listOf(first.copy(bookId = "another"), first.copy(chapterIndex = 2),
            first.copy(kind = AnnotationKind.BOOKMARK), first.copy(kind = AnnotationKind.HIGHLIGHT),
            first.copy(imagePath = "image.png"), first.copy(note = " "))
        assertEquals(listOf(second, first), readerTextNotes(text, listOf(first, second) + excluded, "book", 1, 9))
    }

    @Test fun invalidAndPastEndRangesCannotExposeAnUnrelatedNote() {
        assertNull(readerAnnotationRange(text, note(-1, 6)))
        assertNull(readerAnnotationRange(text, note(text.length, text.length + 1)))
        assertNull(readerAnnotationRange(text, note(4, 2)))
        val tail = note(text.length - 3, Int.MAX_VALUE)
        assertEquals(text.length - 3 until text.length, readerAnnotationRange(text, tail))
        assertEquals(emptyList<Annotation>(), readerTextNotes(text, listOf(tail), "book", 1, text.length))
    }

    @Test fun tappingHighlightedOrUnderlinedTextReturnsItForManagementEvenWithoutANote() {
        val highlight = note(6, 13, id = "highlight").copy(kind = AnnotationKind.HIGHLIGHT, note = "", createdAt = 1L)
        val underline = note(8, 14, id = "underline").copy(kind = AnnotationKind.UNDERLINE, note = "", createdAt = 3L)
        val thought = note(7, 13, id = "thought").copy(createdAt = 2L)
        val excluded = listOf(highlight.copy(id = "other-book", bookId = "other"),
            underline.copy(id = "other-chapter", chapterIndex = 0),
            highlight.copy(id = "image", imagePath = "page.png"),
            thought.copy(id = "empty-note", note = " "),
            thought.copy(id = "bookmark", kind = AnnotationKind.BOOKMARK))
        assertEquals(listOf(underline, thought, highlight),
            readerTextAnnotations(text, listOf(highlight, underline, thought) + excluded, "book", 1, 9))
        assertEquals(listOf(underline), readerTextAnnotations(text, listOf(highlight, underline), "book", 1, 13))
        assertTrue(readerTextAnnotations(text, listOf(highlight, underline), "book", 1, 14).isEmpty())
    }

    @Test fun legacyHighlightAndUnderlineOnlyMatchTheirVerifiedSavedQuote() {
        val start = text.indexOf("这段")
        for (kind in listOf(AnnotationKind.HIGHLIGHT, AnnotationKind.UNDERLINE)) {
            val item = note(start, 0, "这段文字").copy(kind = kind, note = "")
            assertEquals(listOf(item), readerTextAnnotations(text, listOf(item), "book", 1, start + 1))
            assertTrue(readerTextAnnotations(text, listOf(item.copy(quote = "其他文字")), "book", 1, start + 1).isEmpty())
        }
    }

    @Test fun onlyNotesLongerThanTwentyUnicodeCharactersRequireAModalDialog() {
        val short = note(6, 13).copy(note = "短".repeat(20))
        assertFalse(readerAnnotationNeedsDialog(listOf(short)))
        assertTrue(readerAnnotationNeedsDialog(listOf(short.copy(note = "短".repeat(21)))))
        assertFalse(readerAnnotationNeedsDialog(listOf(short.copy(note = "😀".repeat(20)))))
        assertTrue(readerAnnotationNeedsDialog(listOf(short.copy(note = "😀".repeat(21)))))
        assertFalse(readerAnnotationNeedsDialog(listOf(short.copy(kind = AnnotationKind.HIGHLIGHT, note = "长".repeat(100)),
            short.copy(kind = AnnotationKind.UNDERLINE, note = "长".repeat(100)))))
        assertTrue(readerAnnotationNeedsDialog(listOf(short, short.copy(id = "long", note = "长".repeat(21)))))
        assertFalse(readerAnnotationNeedsDialog(emptyList()))
    }
}
