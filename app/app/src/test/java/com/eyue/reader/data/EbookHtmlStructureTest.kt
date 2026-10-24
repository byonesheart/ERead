package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookHtmlStructureTest {
    @Test fun fullDocumentRemainsByteForCharacterIdentical() {
        val raw = "<!DOCTYPE html><html xmlns='urn:x'><head><style>p{color:red}</style></head><body class='book'>甲🙂</body></html>"
        assertEquals(raw, ebookHtmlFragment(raw, 0, raw.length))
    }

    @Test fun chapterInsideNestedContainersKeepsHeadAttributesAndAncestorSelectors() {
        val raw = "<!DOCTYPE html><html lang='zh'><head><link href='../a.css' rel='stylesheet'/><style>.outer blockquote p{color:red}</style></head>" +
            "<body class='book' style='margin:2em'><div class='outer'><blockquote cite='source'><p>前章</p>" +
            "<p id='chapter2'>甲🙂<em>乙</em></p><p id='chapter3'>后章</p></blockquote></div></body></html>"
        val selected = ebookHtmlFragment(raw, raw.indexOf("<p id='chapter2'>"), raw.indexOf("<p id='chapter3'>"))
        assertTrue(selected.startsWith(raw.substringBefore("<div class='outer'>")))
        assertTrue(selected.contains("<div class='outer'><blockquote cite='source'><p id='chapter2'>甲🙂<em>乙</em></p></blockquote></div>"))
        assertFalse(selected.contains("前章"))
        assertFalse(selected.contains("后章"))
        assertEquals(1, sourceHtmlTags(selected).count { it.name == "body" && !it.closing })
    }

    @Test fun firstSectionIncludesPrefaceButNotFollowingChapter() {
        val raw = "<html><head><title>书名</title></head><body data-x='y'><div>前言<p id='next'>正文</p></div></body></html>"
        assertEquals("<html><head><title>书名</title></head><body data-x='y'><div>前言</div></body></html>",
            ebookHtmlFragment(raw, 0, raw.indexOf("<p id='next'>")))
    }

    @Test fun lastSectionClosesItsAncestorsExactlyOnce() {
        val raw = "<html><head><style>p{margin:0}</style></head><body><div><p>前文</p><p id=x>结尾</p></div></body></html>"
        assertEquals("<html><head><style>p{margin:0}</style></head><body><div><p id=x>结尾</p></div></body></html>",
            ebookHtmlFragment(raw, raw.indexOf("<p id=x>"), raw.length))
    }

    @Test fun textSliceReopensInlineAndBlockAncestors() {
        val raw = "<body class='b'><blockquote><p style='text-align:center'><em>一二三四</em></p></blockquote></body>"
        assertEquals("<html><body class='b'><blockquote><p style='text-align:center'><em>二三</em></p></blockquote></body></html>",
            ebookHtmlFragment(raw, raw.indexOf('二'), raw.indexOf('四')))
    }

    @Test fun missingBodyStillKeepsHeadAndOriginalNestedMarkup() {
        val raw = "<html lang='zh'><head><style>blockquote{padding:1em}</style></head><blockquote><div>前</div><div id=t>正文</div></blockquote></html>"
        val selected = ebookHtmlFragment(raw, raw.indexOf("<div id=t>"), raw.length)
        assertEquals("<html lang='zh'><head><style>blockquote{padding:1em}</style></head><body><blockquote><div id=t>正文</div></blockquote></body></html>", selected)
    }

    @Test fun mobiFragmentWithoutDocumentTagsPreservesPagebreakAndImplicitParagraphs() {
        val raw = "<div class=book><p>前章<mbp:pagebreak/><p id=second>第二章<p>下段</div>"
        val selected = ebookHtmlFragment(raw, raw.indexOf("<mbp:pagebreak"), raw.length)
        assertEquals("<html><body><div class=book><p><mbp:pagebreak/></p><p id=second>第二章</p><p>下段</p></div></body></html>", selected)
    }

    @Test fun rawCssAndScriptCannotInjectFakeAncestorsOrBody() {
        val head = "<html><head><style>p:before{content:'<body><div><p>'}</style><script>var s='<body><blockquote>';</script></head><body class=real>"
        val raw = head + "<div><p>前</p><p id=t>后</p></div></body></html>"
        assertEquals(head + "<div><p id=t>后</p></div></body></html>", ebookHtmlFragment(raw, raw.indexOf("<p id=t>"), raw.length))
    }

    @Test fun omittedStyleAndHeadClosersRecoverAtRealBody() {
        val raw = "<html><head><style>p{margin:0}<body class=b><div><p>前</p><p id=t>后</p></div></body></html>"
        assertEquals("<html><head><style>p{margin:0}</style></head><body class=b><div><p id=t>后</p></div></body></html>",
            ebookHtmlFragment(raw, raw.indexOf("<p id=t>"), raw.length))
    }

    @Test fun listItemsRecoverWithoutDuplicatingEarlierSiblings() {
        val raw = "<body><ul><li>前<ul><li>嵌套</li></ul><li id=t>正文<li>末尾</ul></body>"
        assertEquals("<html><body><ul><li id=t>正文</li><li>末尾</li></ul></body></html>",
            ebookHtmlFragment(raw, raw.indexOf("<li id=t>"), raw.length))
    }

    @Test fun mobiResourcesUseRecordNumbersWithoutFlatteningAttributes() {
        val raw = "<div><img recindex='12' width='90%' alt='甲 > 乙' style='float:right'/><svg><image xlink:href='kindle:embed:001V?mime=image/png' height='50'/></svg>" +
            "<a filepos='000123' class='note' href='old'>[1]</a></div>"
        val seen = mutableListOf<Int>()
        val rewritten = ebookRewriteMobiResources(raw) { seen += it; "https://reader.local/image/$it?a=1&b=2" }
        assertEquals(listOf(12, 63), seen)
        assertTrue(rewritten.contains("<img recindex='12' width='90%' alt='甲 > 乙' style='float:right' src=\"https://reader.local/image/12?a=1&amp;b=2\"/>"))
        assertTrue(rewritten.contains("<image xlink:href=\"https://reader.local/image/63?a=1&amp;b=2\" height='50'/>"))
        assertTrue(rewritten.contains("<a filepos='000123' class='note' href=\"#eread-filepos-123\">[1]</a>"))
    }

    @Test fun unresolvedOrdinaryAndRawTextResourcesRemainUnchanged() {
        val raw = "<style>p:before{content:'<img recindex=1>'}</style><script>var s='<a filepos=12>';</script>" +
            "<img src='normal.png' data-src='kindle:embed:1'/><img recindex='999'/><image xlink:href='external.svg'/><a href='#x'>跳转</a>"
        assertEquals(raw, ebookRewriteMobiResources(raw) { null })
    }

    @Test(expected = IllegalArgumentException::class) fun invalidSourceRangeFailsExplicitly() {
        ebookHtmlFragment("<p>正文</p>", 10, 2)
    }
}
