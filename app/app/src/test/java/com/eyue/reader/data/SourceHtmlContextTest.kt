package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHtmlContextTest {
    @Test fun readingImageOffsetsAccountForCommentsAndHiddenHeadWithoutRescanning() {
        val scan = SourceHtmlScan("<html><head><title>隐藏🙂</title><img src='hidden.png'/></head><body><!--注释🙂--><p>正文🙂<img src='one.png'/></p><script>ignore()</script><img src='two.png'/></body></html>")
        assertEquals(listOf("<img src='one.png'/>", "<img src='two.png'/>"), scan.readingImages.map { scan.readingHtml.substring(it.start, it.end) })
    }

    @Test fun sharedScanPreservesVisibleOffsetsAndTheExactCommentStrippedReadingText() {
        val html = "<html><head><style>p:before{content:'<h1>假标题</h1>'}</style></head>" +
            "<body class='book'>甲<!-- 注释 -->🙂乙<a href='#fn1'>[1]</a><aside id='fn1'>释文</aside></body></html>"
        val scan = SourceHtmlScan(html)
        assertEquals(hideHtmlContent(maskHtmlComments(html), true), scan.visibleHtml)
        assertEquals(hideHtmlContent(removeHtmlComments(html), false), scan.readingHtml)
        assertTrue(scan.readingHtml.contains("甲🙂乙"))
        assertEquals(html.indexOf("🙂"), scan.visibleHtml.indexOf("🙂"))
        assertEquals(sourceNoteReferences(html), sourceNoteReferences(html, scan = scan))
        assertTrue(scan.notes.contains(html.indexOf("释文")))
        assertFalse(scan.notes.contains(html.indexOf("甲")))
    }

    @Test fun sharedScanRecoversMalformedHeadsAndRetainsFragmentBodyAttributes() {
        for (html in listOf("<head><style>css<h1>假标题</h1><body class='book'><h1>真标题</h1></body>",
            "<body>甲<!-- 未结束注释", "<style/><body>正文</body>")) {
            val scan = SourceHtmlScan(html)
            assertEquals(hideHtmlContent(maskHtmlComments(html), true), scan.visibleHtml)
            assertEquals(hideHtmlContent(removeHtmlComments(html), false), scan.readingHtml)
        }
        val html = "<body class='book'><h1>第一章</h1><h1>第二章</h1></body>"
        val at = html.indexOf("<h1>第二章")
        assertEquals(sourceBodyFragment(html.substring(at), sourceBodyTag(html)), SourceHtmlScan(html).bodyFragment(at, html.length))
    }

    @Test fun quoteAwareTagsKeepGreaterThanCharactersAndExactUtf16Boundaries() {
        val html = "甲🙂<a href='#chapter' title='x > y'>章</a><h1 id=chapter>标题</h1>"
        val tags = sourceHtmlTags(html).toList()
        assertEquals(listOf("a", "a", "h1", "h1"), tags.map { it.name })
        assertEquals(3, tags.first().start)
        assertEquals("#chapter", sourceHtmlAttribute(tags.first().attributes, "href"))
        assertEquals("x > y", sourceHtmlAttribute(tags.first().attributes, "title"))
        val anchor = sourceHtmlElements(html, setOf("a")).single()
        assertEquals("章", html.substring(anchor.tag.end, anchor.contentEnd))
    }

    @Test fun missingTerminatorsAndUnclosedElementsConsumeForwardAndRecoverAtTheNextTag() {
        val html = "<broken ".repeat(5000) + "<a name='marker'>".repeat(5000) + "<a href='#real'>真实目录</a>"
        assertEquals("真实目录", sourceHtmlElements(html, setOf("a")).single().let { html.substring(it.tag.end, it.contentEnd) })
        assertEquals(5002, sourceHtmlTags(html).count())
        assertTrue(sourceHtmlTags("<a title='unterminated <h1>in quote").none())
    }

    @Test fun malformedHiddenHeadRecoversAtBodyAndOffsetMaskingDoesNotMoveText() {
        val html = "<head><style>css<h1>假标题</h1><body><h1>真标题</h1></body>"
        val masked = hideHtmlContent(html, preserveOffsets = true)
        assertEquals(html.length, masked.length)
        assertEquals(html.indexOf("真标题"), masked.indexOf("真标题"))
        assertFalse(masked.contains("假标题"))
        assertTrue(hideHtmlContent("<style/><body>正文</body>", false).contains("正文"))
    }

    @Test fun closedStyleAndScriptContentDoNotExposeFakeBodyOrHeadingTags() {
        val html = "<head><style>p:before{content:'<body><h1>Hidden</h1>'}</style>" +
            "<script>var value='<body><h1>Fake</h1>';</script></head><body class='real'><h1>Chapter I</h1></body>"
        assertEquals("<body class='real'>", sourceBodyTag(html))
        val masked = hideHtmlContent(html, true)
        assertEquals(html.indexOf("Chapter I"), masked.indexOf("Chapter I"))
        assertFalse(masked.contains("Hidden"))
        assertFalse(masked.contains("Fake"))
        assertEquals(1, sourceHtmlElements(masked, setOf("h1")).size)
    }

    @Test fun noteRangesRemainCorrectAcrossUnmatchedClosingsAndEmptyNamedAnchors() {
        val html = "<a name='chapter'>".repeat(5000) + "</missing>".repeat(5000) +
            "<aside role='doc-footnote'><span>注释</span></aside><h1>真实章</h1><a id='fn1'><h2>[1]</h2>"
        val notes = SourceHtmlNotes(html)
        assertTrue(notes.contains(html.indexOf("注释")))
        assertFalse(notes.contains(html.indexOf("真实章")))
        assertTrue(notes.contains(html.indexOf("<a id='fn1'>")))
        assertFalse(notes.contains(html.indexOf("<h2>")))
    }

    @Test fun malformedAnchorScannerBenchmarksThePreviouslyQuadraticCaseWithoutTimeThresholds() {
        for (count in listOf(400, 800, 1600)) {
            val html = "<a name='target'>text ".repeat(count)
            val started = System.nanoTime()
            assertTrue(sourceHtmlElements(html, setOf("a")).isEmpty())
            println("Single-pass unclosed anchor scanner: $count anchors, ${html.length} characters, ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
}
