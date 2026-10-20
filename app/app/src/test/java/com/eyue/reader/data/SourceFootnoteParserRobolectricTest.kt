package com.eyue.reader.data

import android.text.Html
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SourceFootnoteParserRobolectricTest {
    @Test fun bothCssProjectionsShareCanonicalTextImagesAndFootnoteOffsets() {
        val html = "<body><h1>标题🙂</h1><p class='body'>甲<a role='doc-noteref' href='#fn1'>[1]</a>" +
            "<strong>乙</strong><img src='body.png'/></p><p class='body'>丙</p><aside id='fn1'>解释</aside></body>"
        val css = ".body{text-indent:2em;line-height:1.3;margin-bottom:.7em;letter-spacing:.1em}"
        val plain = HtmlChapterParser.readPlain("正文", html) { "body.png" }
        val prepared = HtmlChapterParser.read("正文", html, css) { "body.png" }
        assertEquals(plain.text, prepared.text)
        assertEquals(plain.inlineImages, prepared.inlineImages)
        assertEquals(plain.sourceFootnotes, prepared.sourceFootnotes)
        assertTrue(prepared.sourceCssStyles.size > prepared.sourceStyles.size)
        val body = prepared.sourceCssStyles.first { prepared.text.substring(it.start, it.end).startsWith("甲") }
        assertEquals(1.3f, body.lineHeightEm!!, .001f)
        assertEquals(.7f, body.spaceAfterEm!!, .001f)
        assertTrue(prepared.text.none { it == '\uE000' || it == '\uE001' })
    }

    @Test fun localNotesKeepCanonicalTextOffsetsAndRemoveBacklinksFromPopupContent() {
        val html = "<body><p>甲🙂 <a epub:type='noteref' href='#fn1'><sup>[1]</sup></a> 后文。</p>" +
            "<aside id='fn1' role='doc-footnote'><p>这是原书注释。<a role='doc-backlink' href='#fnref1'>↩</a></p></aside></body>"
        val plain = HtmlChapterParser.readPlain("章", html) { null }
        val styled = HtmlChapterParser.read("章", html, "p{font-size:120%}") { null }
        val canonical = normalizeHtmlText(Html.fromHtml(html, Html.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH or
            Html.FROM_HTML_SEPARATOR_LINE_BREAK_DIV).toString(), intArrayOf()).text
        assertEquals(canonical, plain.text)
        assertEquals(plain.text, styled.text)
        assertEquals(plain.sourceFootnotes, styled.sourceFootnotes)
        val note = plain.sourceFootnotes.single()
        assertEquals("[1]", note.label)
        assertEquals("[1]", plain.text.substring(note.start, note.end))
        assertEquals("这是原书注释。", note.content)
        assertTrue(note.start > "甲🙂".length)
        assertTrue(plain.text.contains("这是原书注释。"))
    }

    @Test fun suppliedCrossFileTargetWorksWithoutTurningNavigationOrExternalLinksIntoNotes() {
        val html = "<p><a href='#chapter'>第二章</a> 正文<a role='doc-noteref' href='notes.xhtml#detail'>①</a>" +
            "<a role='doc-noteref' href='https://example.invalid/#note'>[2]</a></p><h1 id='chapter'>第二章</h1>"
        val parsed = HtmlChapterParser.readPlainWithNotes("章", html,
            noteTargets = mapOf("notes.xhtml#detail" to "<p>跨文件解释 &amp; 内容。</p>")) { null }
        assertEquals(1, parsed.sourceFootnotes.size)
        val note = parsed.sourceFootnotes.single()
        assertEquals("①", parsed.text.substring(note.start, note.end))
        assertEquals("跨文件解释 & 内容。", note.content)
    }

    @Test fun imageReferenceKeepsItsExistingImageAnchorAndDoesNotInventText() {
        val html = "<p>甲<a epub:type='noteref' href='#detail'><img src='note.png'/></a>乙</p><aside id='detail'>图片小标解释。</aside>"
        val control = html.replace("epub:type='noteref'", "")
        val parsed = HtmlChapterParser.readPlain("章", html) { "local-note.png" }
        val original = HtmlChapterParser.readPlain("章", control) { "local-note.png" }
        assertEquals(original.text, parsed.text)
        assertEquals(original.inlineImages, parsed.inlineImages)
        val note = parsed.sourceFootnotes.single()
        assertEquals("local-note.png", note.imagePath)
        assertEquals(parsed.inlineImages.single().offset, note.start)
        assertEquals(note.start, note.end)
        assertEquals("图片小标解释。", note.content)
    }

    @Test fun filePositionTargetsAndMergedChapterNotesUseStableOffsets() {
        val html = "<p>甲<a filepos='123'><sup>1</sup></a>乙</p>"
        val first = HtmlChapterParser.readPlainWithNotes("第一章", html,
            noteTargets = mapOf("filepos:123" to "<p>数字小标解释。</p>")) { null }
        val second = HtmlChapterParser.readPlainWithNotes("第二章", html,
            noteTargets = mapOf("filepos:123" to "<p>另一章解释。</p>")) { null }
        assertEquals("1", first.text.substring(first.sourceFootnotes.single().start, first.sourceFootnotes.single().end))
        val merged = HtmlChapterParser.merge(listOf(first, second))
        assertEquals(first.sourceFootnotes.single().start + first.text.length + 2,
            merged.sourceFootnotes[1].start)
        assertEquals(listOf("数字小标解释。", "另一章解释。"), merged.sourceFootnotes.map { it.content })
    }
}
