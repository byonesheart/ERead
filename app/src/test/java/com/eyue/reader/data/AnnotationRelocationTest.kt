package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test

class AnnotationRelocationTest {
    @Test fun rangeAcrossExpandedWhitespaceKeepsBothEndsAndQuote() {
        val old = listOf("标题\n甲乙\n丙丁\n尾段")
        val updated = listOf("标题\n\n甲乙\n\n丙丁\n\n尾段")
        val note = Annotation(bookId = "book", chapterIndex = 0, pageIndex = 4,
            characterOffset = 3, endOffset = 8, quote = "甲乙\n丙丁", note = "保留笔记")
        val position = TextParser.relocatePosition(old, updated, 0, note.characterOffset, note.quote)
        val mapped = relocateTextAnnotation(note, old, updated, position)
        assertEquals("甲乙\n\n丙丁", updated[0].substring(mapped.characterOffset, mapped.endOffset))
        assertEquals("甲乙\n\n丙丁", mapped.quote)
        assertEquals(note.id, mapped.id)
        assertEquals(note.note, mapped.note)
    }

    @Test fun bookmarksRemainPointAnchorsAndCrossChapterRangesStopAtTheirChapterEnd() {
        val old = listOf("开头\n甲乙\n第二章\n丙丁")
        val updated = listOf("开头\n甲乙", "第二章\n丙丁")
        val note = Annotation(bookId = "book", chapterIndex = 0, pageIndex = 2,
            characterOffset = 3, endOffset = old[0].length, quote = "甲乙\n第二章\n丙丁")
        val position = TextParser.relocatePosition(old, updated, 0, note.characterOffset)
        val mapped = relocateTextAnnotation(note, old, updated, position)
        assertEquals(0, mapped.chapterIndex)
        assertEquals(updated[0].length, mapped.endOffset)
        assertEquals("甲乙", mapped.quote)
        val bookmark = note.copy(kind = AnnotationKind.BOOKMARK, endOffset = 0, note = "", quote = "原摘录")
        val point = relocateTextAnnotation(bookmark, old, updated, position)
        assertEquals(0, point.endOffset)
        assertEquals(bookmark.quote, point.quote)
    }
}
