package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicHierarchyPlannerTest {
    private fun page(name: String) = PageSource(name)
    private fun node(title: String, vararg pages: String, children: List<ComicHierarchyNode> = emptyList()) =
        ComicHierarchyNode(title, pages.map(::page), children)

    @Test fun rootImagesRemainDirectBookContentAndKeepTheirPageOrder() {
        val chapter = ComicHierarchyPlanner.chapters(node("原书名", "1.png", "2.png", "10.png")).single()
        assertEquals("原书名", chapter.title)
        assertEquals(listOf("1.png", "2.png", "10.png"), chapter.pages.map { it.path })
        assertTrue(chapter.subsections.isEmpty())
    }

    @Test fun multipleRootFoldersAndPdfRemainNaturallySortedChapters() {
        val root = node("原书名", "cover.png", children = listOf(node("第10话", "10.png"),
            ComicHierarchyNode("第3话", listOf(PageSource("3.pdf", 0), PageSource("3.pdf", 1))), node("第2话", "2.png")))
        val chapters = ComicHierarchyPlanner.chapters(root)
        assertEquals(listOf("原书名", "第2话", "第3话", "第10话"), chapters.map { it.title })
        assertEquals(listOf(0, 1), chapters[2].pages.map { it.pdfPage })
        assertTrue(chapters.all { it.subsections.isEmpty() })
    }

    @Test fun aSingleBranchAtEveryDepthMergesIntoItsParentWithoutChangingTheBookTitle() {
        val root = node("原书名", "root.png", children = listOf(node("wrapper", "chapter.png", children =
            listOf(node("nested", "inner.png", children = listOf(ComicHierarchyNode("单独 PDF", listOf(PageSource("end.pdf", 0)))))))))
        val chapter = ComicHierarchyPlanner.chapters(root).single()
        assertEquals("原书名", chapter.title)
        assertEquals(listOf("root.png", "chapter.png", "inner.png", "end.pdf"), chapter.pages.map { it.path })
        assertTrue(chapter.subsections.isEmpty())
    }

    @Test fun aSingleRootWrapperPromotesItsMultipleReadableBranchesToBookChapters() {
        val root = node("原书名", children = listOf(node("打包目录", children =
            listOf(node("第10话", "10.png"), node("第2话", "2.png")))))
        val chapters = ComicHierarchyPlanner.chapters(root)
        assertEquals(listOf("第2话", "第10话"), chapters.map { it.title })
        assertTrue(chapters.all { it.subsections.isEmpty() })
    }

    @Test fun multipleNestedBranchesBecomeSectionsWithAbsoluteRangesInTheFlatChapter() {
        val chapterNode = node("第1章", "direct.png", children = listOf(
            node("第10节", "section.png", children = listOf(node("第10段", "last1.png", "last2.png"), node("第2段", "first.png"))),
            ComicHierarchyNode("第2节", listOf(PageSource("two.pdf", 0), PageSource("two.pdf", 1)))))
        val root = node("原书名", children = listOf(node("第2章", "other.png"), chapterNode))
        val chapter = ComicHierarchyPlanner.chapters(root).first()
        assertEquals("第1章", chapter.title)
        assertEquals(listOf("direct.png", "two.pdf", "two.pdf", "section.png", "first.png", "last1.png", "last2.png"), chapter.pages.map { it.path })
        assertEquals(listOf(ComicSection("第2节", 1, 3), ComicSection("第10节", 3, 7,
            listOf(ComicSection("第2段", 4, 5), ComicSection("第10段", 5, 7)))), chapter.subsections)
    }

    @Test fun emptyBranchesAreIgnoredBeforeDecidingWhetherToKeepASectionLevel() {
        val root = node("原书名", "direct.png", children = listOf(node("空目录", children = listOf(node("也为空"))),
            ComicHierarchyNode("唯一 PDF", listOf(PageSource("only.pdf", 0), PageSource("only.pdf", 1)))))
        val chapter = ComicHierarchyPlanner.chapters(root).single()
        assertEquals("原书名", chapter.title)
        assertEquals(3, chapter.pages.size)
        assertTrue(chapter.subsections.isEmpty())
        assertTrue(ComicHierarchyPlanner.chapters(node("空书", children = listOf(node("空目录")))).isEmpty())
    }
}
