package com.eyue.reader.ui

import com.eyue.reader.data.EbookSourceDocument
import com.eyue.reader.data.sourceHtmlAttribute
import com.eyue.reader.data.sourceHtmlTags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File

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
        assertTrue(rendered.contains("<html lang='zh' data-eread-loading>"))
        assertTrue(rendered.contains("html[data-eread-loading] body{opacity:0!important}"))
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

    @Test fun trustedDocumentStartsTheReaderWithoutWaitingForPageFinished() {
        val source = EbookSourceDocument("test", "<p>正文</p>", "https://eread.local/test/archive/book.html", "正文")
        val options = JSONObject().put("canonical", "正文").put("initialNavigation", JSONObject().put("offset", 12))
        val rendered = ebookReaderHtml(source, "trustedReader()", "trusted-nonce", options)
        assertTrustedEnvelope(rendered)
        assertTrue(rendered.contains("\n;window.ERead.init($options);</script>"))
        assertEquals("<p>正文</p>", source.html)
    }

    @Test fun startupTextCannotCloseTheTrustedScriptOrInjectPublisherCode() {
        val text = "正文</script><script>publisher()</script>\u2028\u2029🙂"
        val source = EbookSourceDocument("test", "<p>正文</p>", "https://eread.local/test/archive/book.html", text)
        val rendered = ebookReaderHtml(source, "trustedReader()", "trusted-nonce", JSONObject().put("canonical", text))
        assertTrustedEnvelope(rendered)
        val tags = sourceHtmlTags(rendered).toList()
        val start = tags.single { !it.closing && it.name == "script" }.end
        val end = tags.single { it.closing && it.name == "script" }.start
        val payload = rendered.substring(start, end).substringAfter("window.ERead.init(").removeSuffix(");")
        assertFalse(payload.contains('<'))
        assertFalse(payload.contains('\u2028'))
        assertFalse(payload.contains('\u2029'))
        assertEquals(text, JSONObject(payload).getString("canonical"))
    }

    @Test fun productionScriptAndRestoreCommandAreEmbeddedInTheServedDocument() {
        val paragraphs = (1..30).map { "第${it}段。" + "书架里的故事在这里继续。".repeat(8) }
        val canonical = paragraphs.joinToString("\n\n")
        val source = EbookSourceDocument("bootstrap-test", "<html><head></head><body>" +
            paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>",
            "https://eread.local/bootstrap/archive/book.html", canonical)
        val script = RuntimeEnvironment.getApplication().assets.open("reader/ebook-reader.js").bufferedReader().use { it.readText() }
        val options = JSONObject().put("canonical", canonical).put("config", JSONObject()
            .put("mode", "PAGED").put("turn", "SLIDE").put("authorCss", false).put("dual", false).put("eink", false)
            .put("fontSize", 19).put("lineHeight", 35).put("letterSpacing", .5).put("paragraphGap", 15)
            .put("bold", false).put("night", false).put("color", "#242820")
            .put("left", 24).put("right", 24).put("top", 18).put("bottom", 18).put("font", "sans-serif"))
            .put("decorations", JSONObject().put("annotations", JSONArray()).put("search", "").put("spokenStart", -1).put("spokenEnd", -1))
            .put("images", JSONArray()).put("noteTargets", JSONArray()).put("notes", JSONArray())
            .put("initialNavigation", JSONObject().put("type", "navigate").put("offset", 1500))
        val rendered = ebookReaderHtml(source, script, "bootstrap-test", options)
        assertEquals(1, sourceHtmlTags(rendered).count { !it.closing && it.name == "script" })
        assertTrue(rendered.contains(";window.ERead.init("))
        System.getenv("EREAD_TEST_ARTIFACTS")?.takeIf { it.isNotBlank() }?.let {
            File(it, "bootstrap.json").apply { parentFile?.mkdirs(); writeText(JSONObject()
                .put("html", rendered).put("baseUrl", source.baseUrl).put("offset", 1500).toString()) }
        }
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
