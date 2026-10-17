package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class HtmlChapterParserContract {
    @Test fun realMobiParagraphStructureKeepsOneNewlineAndReaderBodyFormatting() {
        val chapter = HtmlChapterParser.read("正文", "<body>" +
            "<p height='12pt' width='2em' align='justify'><font color='#000000'>合成第一段🙂<img recindex='1'/></font></p>" +
            "<p height='12pt' width='2em' align='justify'><font color='#000000'>合成第二段<strong>强调</strong></font></p></body>") { "inline.png" }
        assertEquals("合成第一段🙂\n合成第二段强调", chapter.text)
        assertEquals(chapter.text.indexOf('\n'), chapter.inlineImages.single().offset)
        assertTrue(chapter.sourceStyles.none { it.paragraph })
        assertEquals("强调", chapter.sourceStyles.single().let { chapter.text.substring(it.start, it.end) })
    }
    @Test fun quotedGreaterThanInImageAttributesKeepsBothImagesAtTheirOriginalUtf16Offset() {
        val chapter = HtmlChapterParser.read("正文", "<p>甲🙂<img title='a>b' src='one'/><image title='c>d' src='two'/>乙</p>") {
            HtmlChapterParser.attribute(it, "src")?.let { name -> "$name.png" }
        }
        assertEquals("甲🙂乙", chapter.text)
        assertEquals(listOf(InlineImage(3, "one.png"), InlineImage(3, "two.png")), chapter.inlineImages)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun parsingHonorsTheImportTaskCancellationCheck() {
        HtmlChapterParser.read("正文", "<p>正文</p>", checkActive = { throw kotlinx.coroutines.CancellationException("cancelled") }) { null }
    }

    @Test fun androidRegexCompilesAndParsesMultipleCssBlocksForMobiStyleSheets() {
        val html = "<html><head><style>h1 { font-size:150%; text-align:center; } blockquote { font-size:80%; font-style:italic; }</style></head>" +
            "<body><h1>第一章 标题</h1><p>普通正文</p><blockquote>引用🙂</blockquote></body></html>"
        val chapter = HtmlChapterParser.read("章节", html) { null }
        val heading = chapter.sourceStyles.first { chapter.text.substring(it.start, it.end).trim() == "第一章 标题" }
        val quote = chapter.sourceStyles.first { chapter.text.substring(it.start, it.end).trim() == "引用🙂" }
        assertEquals(1.5f, heading.fontScale!!, .001f)
        assertEquals(SourceTextAlignment.CENTER, heading.alignment)
        assertEquals(.8f, quote.fontScale!!, .001f)
        assertEquals(true, quote.italic)
        assertTrue(chapter.sourceStyles.none { chapter.text.substring(it.start, it.end).contains("普通正文") })
        val plain = HtmlChapterParser.read("正文", "<p>无样式正文</p>") { null }
        assertEquals("无样式正文", plain.text)
        assertTrue(plain.sourceStyles.isEmpty())
    }

    @Test fun headingInlineEmphasisAndImageMarkersUseFinalUtf16Ranges() {
        val chapter = HtmlChapterParser.read("章节", "<h1> 标题🙂 </h1><p>甲<strong>🙂<img src='x'/>乙</strong><em>丙</em>丁</p>",
            "h1{text-align:center;font-size:150%}") { "private.png" }
        assertEquals("标题🙂 \n\n甲🙂乙丙丁", chapter.text)
        val heading = chapter.sourceStyles.first { it.paragraph }
        assertEquals("标题🙂 ", chapter.text.substring(heading.start, heading.end))
        assertEquals(1.5f, heading.fontScale!!, .001f)
        assertEquals(SourceTextAlignment.CENTER, heading.alignment)
        val bold = chapter.sourceStyles.first { !it.paragraph && it.bold == true }
        assertEquals("🙂乙", chapter.text.substring(bold.start, bold.end))
        val italic = chapter.sourceStyles.first { !it.paragraph && it.italic == true }
        assertEquals("丙", chapter.text.substring(italic.start, italic.end))
        assertEquals(chapter.text.indexOf("乙"), chapter.inlineImages.single().offset)
        assertTrue(chapter.text.none { it == '\uE000' || it == '\uE001' || it == '\uFFFC' })
    }

    @Test fun textNormalizationKeepsSourceStylesClampedThroughWhitespaceAndTrim() {
        val chapter = HtmlChapterParser.read("章节", "<p>  开头</p><p>  <strong>词🙂</strong>  </p><p>尾声  </p>") { null }
        val bold = chapter.sourceStyles.single()
        assertEquals("词🙂", chapter.text.substring(bold.start, bold.end))
        assertTrue(chapter.sourceStyles.all { it.start >= 0 && it.end <= chapter.text.length && it.end > it.start })
    }

    @Test fun ordinaryBodyAndContainerCssDoNotSurroundTheTextWithSourceFormatting() {
        val chapter = HtmlChapterParser.read("正文", "<body><div><p class='body'>正文1</p><p class='body'>正文2</p></div></body>",
            ".body{font-size:20px;text-indent:2em;line-height:2.5}") { null }
        assertEquals("正文1\n正文2", chapter.text)
        assertTrue(chapter.sourceStyles.isEmpty())
    }

    @Test fun ordinaryParagraphsUseSingleNewlinesAndKeepImageAndEmphasisOffsets() {
        val chapter = HtmlChapterParser.read("正文", "<h1>标题</h1><p>甲🙂</p><p><strong>乙<img src='x'/>丙</strong></p><blockquote>引用</blockquote><p>尾声</p>") { "inline.png" }
        assertTrue(chapter.text.contains("甲🙂\n乙丙"))
        assertTrue(chapter.text.startsWith("标题\n\n"))
        assertTrue(chapter.text.contains("\n\n引用\n\n"))
        assertEquals(chapter.text.indexOf("丙"), chapter.inlineImages.single().offset)
        val emphasis = chapter.sourceStyles.first { !it.paragraph && it.bold == true }
        assertEquals("乙丙", chapter.text.substring(emphasis.start, emphasis.end))
    }

    @Test fun mergeAndCustomChapterSplitPreserveAndClipNestedSourceRangesAndImages() {
        val first = HtmlChapterParser.read("甲", "<h1>第一章 甲</h1><p><strong>正文🙂甲</strong><img src='one'/></p>") { "one.png" }
        val second = HtmlChapterParser.read("乙", "<h1>第二章 乙</h1><blockquote><em>正文乙</em></blockquote>") { null }
        val merged = HtmlChapterParser.merge(listOf(first, second))
        val chapters = HtmlChapterParser.split(merged, DEFAULT_CHAPTER_RULE)
        assertEquals(listOf(first.text, second.text), chapters.map { it.text })
        assertEquals(first.sourceStyles, chapters.first().sourceStyles)
        assertEquals(second.sourceStyles, chapters.last().sourceStyles)
        assertEquals(first.inlineImages, chapters.first().inlineImages)
        val spanning = merged.copy(sourceStyles = listOf(SourceTextStyle(0, merged.text.length, italic = true)))
        val clipped = HtmlChapterParser.split(spanning, DEFAULT_CHAPTER_RULE)
        assertTrue(clipped.all { it.sourceStyles.single().start == 0 && it.sourceStyles.single().end == it.text.length })
    }
}
