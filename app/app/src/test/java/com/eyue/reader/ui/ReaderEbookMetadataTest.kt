package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.SourceFootnote
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReaderEbookMetadataTest {
    @Test fun sourceRouteFindsAttachedNumericDefinitionsWithoutPreparingNativeStyles() {
        val text = "原文😀[1]。\n\n[1]无空格的本章释文。"
        val chapter = Chapter(title = "第一章", lazySourcePath = "must-not-read.source.json")
        val prepared = readerEbookChapterWithFootnotes(chapter, text)
        val note = prepared.sourceFootnotes.single()
        assertEquals(text.indexOf("[1]"), note.start)
        assertEquals("[1]", text.substring(note.start, note.end))
        assertEquals("无空格的本章释文。", note.content)
        assertEquals(chapter.copy(sourceFootnotes = listOf(note)), prepared)
    }

    @Test fun sourceRouteKeepsExistingOriginalLinkMetadata() {
        val chapter = Chapter(title = "原链接", sourceFootnotes = listOf(SourceFootnote(0, 1, "①", "链接释文")))
        assertSame(chapter, readerEbookChapterWithFootnotes(chapter, "①正文[1]。\n\n[1]数字释文。") {
            fail("Existing source links do not require a second text scan")
        })
    }

    @Test fun sourceRouteDoesNotTurnListsOrAmbiguousDefinitionsIntoNotes() {
        val chapter = Chapter(title = "普通列表")
        assertSame(chapter, readerEbookChapterWithFootnotes(chapter, "[1]列表项。\n[2]列表项。"))
        assertSame(chapter, readerEbookChapterWithFootnotes(chapter, "原文[1]。\n\n[1]解释一。\n\n[1]解释二。"))
    }

    @Test(expected = CancellationException::class)
    fun sourceRouteFootnoteScanCanBeCanceledDuringChapterNavigation() {
        readerEbookChapterWithFootnotes(Chapter(title = "已离开的章节"), "原文[1]。\n\n[1]释文。") {
            throw CancellationException("chapter changed")
        }
    }
}
