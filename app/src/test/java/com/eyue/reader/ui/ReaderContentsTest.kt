package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection
import com.eyue.reader.data.PageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentsTest {
    private val chapter = Chapter(id = "chapter", title = "第1话", pages = List(8) { PageSource("$it.png") },
        subsections = listOf(ComicSection("上篇", 2, 6,
            listOf(ComicSection("场景1", 2, 4), ComicSection("场景2", 4, 6))), ComicSection("下篇", 6, 8)))

    @Test fun nestedContentsKeepAbsolutePageTargetsAndUniqueKeys() {
        val entries = readerContentsEntries(listOf(chapter, Chapter(id = "next", title = "第2话")))
        assertEquals(listOf("第1话", "上篇", "场景1", "场景2", "下篇", "第2话"), entries.map { it.title })
        assertEquals(listOf(0, 2, 2, 4, 6, 0), entries.map { it.startPage })
        assertEquals(listOf(0, 1, 2, 2, 1, 0), entries.map { it.depth })
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertEquals(1, entries.last().chapter)
    }

    @Test fun activeSectionUsesDeepestHalfOpenRangeAndLoosePagesUseChapter() {
        val entries = readerContentsEntries(listOf(chapter))
        assertEquals(0, readerCurrentContentsEntry(entries, 0, 1))
        assertEquals(2, readerCurrentContentsEntry(entries, 0, 3))
        assertEquals(3, readerCurrentContentsEntry(entries, 0, 4))
        assertEquals(4, readerCurrentContentsEntry(entries, 0, 6))
        assertEquals(-1, readerCurrentContentsEntry(entries, 0, 8))
        assertEquals("第1话", readerChapterTitle(chapter, 1))
        assertEquals("第1话 / 上篇 / 场景2", readerChapterTitle(chapter, 4))
        assertEquals("第1话 / 下篇", readerChapterTitle(chapter, 6))
    }

    @Test fun novelChapterRemainsSelectedOnAnyTextPage() {
        val entries = readerContentsEntries(listOf(Chapter(title = "第一章", textPath = "chapter.txt")))
        assertEquals(0, readerCurrentContentsEntry(entries, 0, 100))
        assertTrue(entries.single().depth == 0 && entries.single().startPage == 0)
    }
}
