package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection
import com.eyue.reader.data.PageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun nativeParentsUseContiguousGroupsAndPreservePhysicalChapterIndices() {
        val chapters = listOf(
            Chapter(id = "intro", title = "序言"),
            Chapter(id = "one", title = "启程", tocParentTitle = "第一卷 起点"),
            Chapter(id = "two", title = "相遇", tocParentTitle = "第一卷 起点"),
            Chapter(id = "three", title = "远行", tocParentTitle = "第二卷 远方"),
            Chapter(id = "four", title = "归来", tocParentTitle = "第一卷 起点"))
        val entries = readerContentsEntries(chapters, hierarchical = true)
        assertEquals(listOf("序言", "第一卷 起点", "启程", "相遇", "第二卷 远方", "远行", "第一卷 起点", "归来"), entries.map { it.title })
        assertEquals(listOf(0, 1, 1, 2, 3, 3, 4, 4), entries.map { it.chapter })
        assertEquals(setOf(1, 2), entries[1].groupChapters)
        assertEquals("one/parent", entries[2].parentKey)
        assertEquals(chapters.map { it.id }, entries.filter { it.key == chapters[it.chapter].id }.map { it.key })
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        val collapsed = entries.filter { it.parentKey == null }
        assertEquals(1, readerContentsPositionIndex(collapsed).current(2, 250))
        assertEquals(3, readerContentsPositionIndex(collapsed).current(4, 20))
        assertEquals(3, readerContentsPositionIndex(entries).current(2, 250))
        assertEquals("第2章 · 第2节 · 第一卷 起点 / 相遇", readerSearchLocationIndex(chapters).label(2, 0))
    }

    @Test fun aRealNativeParentRemainsJumpableWithoutAnExtraSyntheticCopy() {
        val chapters = listOf(Chapter(id = "parent", title = "卷首"),
            Chapter(id = "child", title = "初遇", tocParentTitle = "卷首"))
        val entries = readerContentsEntries(chapters, hierarchical = true)
        assertEquals(listOf("parent", "child"), entries.map { it.key })
        assertEquals(setOf(0, 1), entries.first().groupChapters)
        assertEquals(0, entries.first().chapter)
        assertEquals(0, readerContentsPositionIndex(entries).current(0, 20))
        assertEquals("parent", entries.last().parentKey)
        assertEquals("第1章 · 未分节 · 卷首", readerSearchLocationIndex(chapters).label(0, 0))
        assertEquals("第1章 · 第1节 · 卷首 / 初遇", readerSearchLocationLabel(chapters, 1, 0))
    }

    @Test fun explicitChapterSectionsAndVolumeChaptersFormOnlyTwoLevels() {
        val chapters = listOf(Chapter(id = "volume", title = "第一卷 起点"),
            Chapter(id = "chapter-one", title = "第一章 开始"), Chapter(id = "chapter-two", title = "第二章 远行"),
            Chapter(id = "parent", title = "第三章 回归"), Chapter(id = "section-one", title = "第一节 见面"),
            Chapter(id = "section-two", title = "第二小节 告别"), Chapter(id = "last", title = "第四章 尾声"))
        val entries = readerContentsEntries(chapters, hierarchical = true)
        assertTrue(entries.all { it.depth <= 1 })
        // The later section belongs to its actual chapter; the volume is not made a third-level parent.
        assertNull(entries.first().parentKey)
        assertEquals(setOf(0), entries.first().groupChapters)
        assertNull(entries[1].parentKey)
        assertEquals("parent", entries[4].parentKey)
        assertEquals("parent", entries[5].parentKey)
        assertEquals(setOf(3, 4, 5), entries[3].groupChapters)
        val volumeOnly = readerContentsEntries(chapters.take(3), hierarchical = true)
        assertEquals(listOf(0, 1, 1), volumeOnly.map { it.depth })
        assertEquals(setOf(0, 1, 2), volumeOnly.first().groupChapters)
        assertEquals("第1章 · 第2节 · 第一卷 起点 / 第二章 远行", readerSearchLocationIndex(chapters.take(3)).label(2, 1))
    }

    @Test fun flatChaptersAndAnnotationsNeverInventSectionGroups() {
        val chapters = listOf(Chapter(title = "第一章 开始"), Chapter(title = "第二章 离开"),
            Chapter(title = "[注1] 第三章说明"), Chapter(title = "第一节 引文"),
            Chapter(title = "第一章中的事情如下。"), Chapter(title = "第二节 摘录"))
        val entries = readerContentsEntries(chapters, hierarchical = true)
        assertEquals(chapters.map { it.id }, entries.map { it.key })
        assertTrue(entries.all { it.parentKey == null && it.depth == 0 && it.groupChapters == setOf(it.chapter) })
        assertEquals("第2章 · 未分节 · 第二章 离开", readerSearchLocationIndex(chapters).label(1, 20))
        val off = readerContentsEntries(listOf(Chapter(title = "第一章 开始"), Chapter(title = "第一节 见面")), hierarchical = false)
        assertTrue(off.all { it.depth == 0 })
    }

    @Test fun explicitChapterAndSectionNumbersAllowHalfAndFullWidthSpacing() {
        val chapters = listOf(Chapter(id = "spaced-parent", title = "第　1　章 起点"),
            Chapter(id = "spaced-child", title = "第 1 节 见面"),
            Chapter(id = "paragraph", title = "第 2 章中的内容如下。"))
        val entries = readerContentsEntries(chapters, hierarchical = true)
        assertEquals(listOf(0, 1, 0), entries.map { it.depth })
        assertEquals("spaced-parent", entries[1].parentKey)
        assertNull(entries[2].parentKey)
        assertEquals("第1章 · 第1节 · 第　1　章 起点 / 第 1 节 见面", readerSearchLocationIndex(chapters).label(1, 0))
    }

    @Test fun comicParentLinksAndSearchLocationsKeepTheExistingSectionPath() {
        val entries = readerContentsEntries(listOf(chapter), hierarchical = true)
        assertEquals(listOf(0, 1, 2, 2, 1), entries.map { it.depth })
        assertEquals("chapter", entries[1].parentKey)
        assertEquals("chapter/0", entries[3].parentKey)
        assertEquals("第1章 · 第1节 · 第2小节 · 第1话 / 上篇 / 场景2", readerSearchLocationIndex(listOf(chapter)).label(0, 4))
        assertEquals("第1章 · 未分节 · 第1话", readerSearchLocationLabel(listOf(chapter), 0, 1))
        val collapsed = entries.filter { it.parentKey == null }
        assertEquals(0, readerContentsPositionIndex(collapsed).current(0, 6))
        assertEquals(-1, readerContentsPositionIndex(collapsed).current(0, 8))
    }

    @Test fun aLargePlainDirectoryBuildsOneIndexWithoutLoadingAnyChapterFiles() {
        val chapters = List(20_000) { Chapter(id = "chapter-$it", title = "篇目$it", textPath = "missing-$it.txt") }
        val entries = readerContentsEntries(chapters, hierarchical = true)
        val locations = readerSearchLocationIndex(chapters)
        val positions = readerContentsPositionIndex(entries)
        assertEquals(20_000, entries.size)
        assertEquals(19_999, positions.current(19_999, 30))
        assertEquals("第20000章 · 未分节 · 篇目19999", locations.label(19_999, 30))
        assertFalse(entries.any { it.parentKey != null })
    }
}
