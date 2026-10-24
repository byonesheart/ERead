package com.eyue.reader.ui

import com.eyue.reader.data.EbookSourceDocument
import com.eyue.reader.data.sourceHtmlAttribute
import com.eyue.reader.data.sourceHtmlTags
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EbookReaderHtmlRobolectricTest {
    @Test fun fakeHeadInACommentCannotHideTheOnlyActivePolicy() {
        val rendered = secure("<html><!-- <head> --><head><script>publisher()</script><style>p{color:red}</style></head><body><p>正文</p></body></html>")
        assertTrustedEnvelope(rendered)
        assertFalse(rendered.contains("publisher()"))
        assertTrue(rendered.contains("<style>p{color:red}</style>"))
        assertTrue(rendered.contains("<p>正文</p>"))
    }

    @Test fun preHeadScriptAndEventAttributesAreRemovedBeforeOurReaderRuns() {
        val rendered = secure("<script>early()</script><html onload='steal()'><head></head>" +
            "<body onpageshow=again()><p onclick='replace()' style='color:red'>正文<img src='a.png' onerror='bad()'/></p></body></html>")
        assertTrustedEnvelope(rendered)
        assertFalse(rendered.contains("early()"))
        listOf("onload", "onpageshow", "onclick", "onerror").forEach { assertFalse(rendered.contains(it)) }
        assertTrue(rendered.contains("style='color:red'"))
        assertTrue(rendered.contains("src='a.png'"))
    }

    @Test fun framesObjectsAndSelfClosingEmbedsCannotCreateAnotherScriptContext() {
        val rendered = secure("<html><head></head><body><iframe srcdoc='<script>nested()</script>'>frame fallback</iframe>" +
            "<object data='data:text/html,evil'><p>object fallback</p></object><embed src='data:text/html,evil'/>" +
            "<frameset><frame src='https://example.invalid/'></frameset><p>仍然可读</p></body></html>")
        assertTrustedEnvelope(rendered)
        val tags = sourceHtmlTags(rendered).map { it.name }.toList()
        assertFalse(tags.any { it in setOf("iframe", "object", "embed", "frame", "frameset") })
        assertFalse(rendered.contains("nested()"))
        assertFalse(rendered.contains("object fallback"))
        assertTrue("A void embed must not remove the following book content", rendered.contains("<p>仍然可读</p>"))
    }

    @Test fun sourceHeadCssAndNestedBodyStructureRemainIntact() {
        val css = ".book:before{content:'<head>';background:url('../art/paper.svg')}"
        val rendered = secure("<html lang='zh'><head id='publisher'><link rel='stylesheet' href='../styles/main.css'/>" +
            "<style>$css</style></head><body class='book' style='padding:1em'><div class='outer'><table><tr><td>正文</td></tr></table></div></body></html>")
        assertTrustedEnvelope(rendered)
        assertTrue(rendered.contains("<html lang='zh'>"))
        assertTrue(rendered.contains("<head id='publisher'>"))
        assertTrue(rendered.contains("<link rel='stylesheet' href='../styles/main.css'/>"))
        assertTrue(rendered.contains("<style>$css</style>"))
        assertTrue(rendered.contains("<body class='book' style='padding:1em'><div class='outer'><table><tr><td>正文</td></tr></table></div>"))
    }

    @Test fun relativeLinksAndDataImagesSurviveButEncodedExecutableUrlsDoNot() {
        val rendered = secure("<html><body><a href='notes.xhtml#n1'>[1]</a><a href='jav&#x61;script:bad()'>坏</a>" +
            "<a href='java&#9;script:bad()'>坏2</a><svg><a xlink:href='javascript:bad()'>坏3</a></svg>" +
            "<img src='data:image/png;base64,AQ=='/><a href='data:text/html,bad'>坏4</a><img src='content://other/private'/></body></html>")
        assertTrustedEnvelope(rendered)
        val targets = sourceHtmlTags(rendered).flatMap { tag -> listOfNotNull(sourceHtmlAttribute(tag.attributes, "href"),
            sourceHtmlAttribute(tag.attributes, "src"), sourceHtmlAttribute(tag.attributes, "xlink:href")).asSequence() }.toList()
        assertEquals(listOf("notes.xhtml#n1", "data:image/png;base64,AQ=="), targets)
    }

    @Test fun publisherPolicyRefreshBaseAndNonceCannotOverrideReaderPolicy() {
        val rendered = secure("<html><head><base href='https://evil.invalid/'/><meta http-equiv='refresh' content='0;url=https://evil.invalid/'/>" +
            "<meta http-equiv='Content-Security-Policy' content=\"script-src 'unsafe-inline'\"/>" +
            "<meta charset='GBK'/></head><body nonce='publisher'><script nonce='trusted-nonce'>publisher()</script><p>正文</p></body></html>")
        assertTrustedEnvelope(rendered)
        assertEquals(1, sourceHtmlTags(rendered).count { !it.closing && sourceHtmlAttribute(it.attributes, "http-equiv")?.equals("Content-Security-Policy", true) == true })
        assertFalse(sourceHtmlTags(rendered).any { it.name == "base" })
        assertFalse(rendered.contains("publisher()"))
        assertFalse(rendered.contains("nonce='publisher'"))
        assertTrue(rendered.contains("<meta charset=\"UTF-8\">"))
    }

    @Test fun headlessAndMalformedPreHeadContentStillHasAnEarlyActivePolicy() {
        for (html in listOf("<p>正文</p>", "<p>前文</p><head><style>p{color:red}</style></head><body><p>后文</p></body>")) {
            val rendered = secure(html)
            assertTrustedEnvelope(rendered)
            assertTrue(rendered.contains("正文") || rendered.contains("前文") && rendered.contains("后文"))
        }
    }

    @Test fun unclosedPublisherScriptDoesNotConsumeTheTrustedReaderScript() {
        val rendered = secure("<html><body><p>已读正文</p><script>neverClosed()")
        assertTrustedEnvelope(rendered)
        assertFalse(rendered.contains("neverClosed()"))
        assertTrue(rendered.contains("<p>已读正文</p>"))
    }

    @Test fun nonceCannotInjectPolicyOrMarkup() {
        val source = EbookSourceDocument("test", "<p>正文</p>", "https://eread.local/test/archive/body.xhtml", "正文")
        assertTrue(runCatching { ebookReaderHtml(source, "reader()", "' unsafe-inline '><script>") }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun secure(html: String) = ebookReaderHtml(EbookSourceDocument("test", html,
        "https://eread.local/test/archive/body.xhtml", "正文"), "trustedReader()", "trusted-nonce")

    private fun assertTrustedEnvelope(html: String) {
        val tags = sourceHtmlTags(html).toList()
        val head = tags.first { !it.closing && it.name == "head" }
        val headEnd = tags.first { it.closing && it.name == "head" }
        val policy = tags.single { !it.closing && sourceHtmlAttribute(it.attributes, "http-equiv")?.equals("Content-Security-Policy", true) == true }
        assertTrue(policy.start >= head.end && policy.end <= headEnd.start)
        assertFalse("No active publisher content may precede the policy", tags.any {
            it.start < policy.start && it.name !in setOf("html", "head", "meta")
        })
        val scripts = tags.filter { !it.closing && it.name == "script" }
        assertEquals(1, scripts.size)
        assertEquals("trusted-nonce", sourceHtmlAttribute(scripts.single().attributes, "nonce"))
        assertTrue(html.indexOf("trustedReader()") > policy.end)
    }
}
