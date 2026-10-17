package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStyleParserTest {
    private fun styles(html: String, css: String = "") = SourceStyleParser.mark(html, css, "test_").styles

    @Test fun normalMobiJustifiedParagraphsUseReaderFormattingWithoutSeparateParagraphStyles() {
        val html = "<body><p height='12pt' width='2em' align='justify'><font color='#000000'>第一段🙂</font></p>" +
            "<p height='12pt' width='2em' align='justify'><font color='#000000'>第二段</font></p></body>"
        val marked = SourceStyleParser.mark(html, "", "test_")
        assertTrue(marked.styles.isEmpty())
        assertEquals(html, marked.html)
    }

    @Test fun mixedOrdinaryStartAndJustifiedParagraphsUseOneReaderBodyFormat() {
        assertTrue(styles("<body><p align='justify'>正文一</p><p>正文二</p><p>正文三</p></body>").isEmpty())
        assertTrue(styles("<body><p>正文一</p><p align='justify'>正文二</p><p align='justify'>正文三</p></body>").isEmpty())
    }

    @Test fun justifiedBodyBaselineKeepsCenteredHeadingQuoteAndInlineEmphasisSpecial() {
        val parsed = styles("<body><p align='center'><strong>标题</strong></p>" +
            "<p align='justify'>正文一<strong>强调🙂</strong></p><p align='justify'>正文二</p>" +
            "<blockquote align='justify'>引用</blockquote></body>")
        assertEquals(4, parsed.size)
        assertEquals(SourceTextAlignment.CENTER, parsed.first().alignment)
        assertTrue(parsed.first().paragraph)
        assertEquals(true, parsed[1].bold)
        assertEquals(true, parsed[2].bold)
        assertTrue(parsed.last().paragraph)
        assertEquals(SourceTextAlignment.JUSTIFY, parsed.last().alignment)
        assertEquals(1f, parsed.last().restLineIndentEm!!, .001f)
    }

    @Test fun thousandsOfUnclosedAnchorMarkersDoNotBecomeStyleAncestors() {
        val html = "<body>" + "<a name='marker'>文字 ".repeat(20_000) + "<p>正文<strong>强调</strong></p></body>"
        val parsed = styles(html, "a strong{font-style:italic}")
        assertEquals(1, parsed.size)
        assertEquals(true, parsed.single().bold)
        assertNull(parsed.single().italic)
    }

    @Test fun missingHeadAndStyleClosersRecoverAtBodyAndDoNotLeakCssText() {
        val parsed = SourceStyleParser.mark("<head>" + "<style>h1{font-size:3em}".repeat(3000) +
            "<body><h1>标题</h1><p>正文</p></body>", "", "test_")
        assertFalse(parsed.html.contains("font-size"))
        assertTrue(parsed.html.contains("正文"))
        assertEquals(2f, parsed.styles.single().fontScale!!, .001f)
    }

    @Test fun deepUnclosedTagsAndUnmatchedClosingTagsKeepValidEmphasis() {
        val parsed = styles("<body>" + "<div>".repeat(5000) + "</missing>".repeat(5000) +
            "<p>正文<strong>强调</strong></p></body>", ".missing div{font-size:3em} .missing strong{font-style:italic}")
        assertEquals(1, parsed.size)
        assertEquals(true, parsed.single().bold)
        assertNull(parsed.single().italic)
    }

    @Test fun malformedStylesheetBlocksCommentsAndImportsFinishWithoutApplyingPartialRules() {
        assertTrue(styles("<p>正文</p>", "not a CSS rule ".repeat(50_000)).isEmpty())
        val malformed = "not a rule ".repeat(10_000) + "\n@import missing\n" + "{".repeat(10_000) + "}"
        assertTrue(styles("<p>正文</p>", malformed).isEmpty())
        assertTrue(styles("<p>正文</p>", "/* unterminated comment ".repeat(3000)).isEmpty())
        val parsed = styles("<p>正文</p><h1>标题</h1>", "@media screen{h1{font-size:125%}}")
        assertEquals(1.25f, parsed.single().fontScale!!, .001f)
    }

    @Test fun boundedSelectorCacheDoesNotAliasDifferentAncestorsAfterCapacityIsReached() {
        val html = "<body>" + "<div>".repeat(600) +
            "<section class='one'><strong>甲</strong></section><section class='two'><strong>乙</strong></section>" +
            "</div>".repeat(600) + "</body>"
        val parsed = styles(html, ".one strong{font-style:normal} .two strong{font-style:italic}")
        assertEquals(listOf(false, true), parsed.map { it.italic })
        assertTrue(parsed.all { it.bold == true })
    }

    @Test fun unclosedHeadingStylesEndBeforeTheNextBlockInsteadOfNestingToTheEndOfTheBook() {
        val html = (0 until 3000).joinToString("") { "<h1>Heading $it<span>title<p>Body $it</p>" }
        val marked = SourceStyleParser.mark(html, "", "heading_")
        assertEquals(3000, marked.styles.size)
        marked.html.split("<h1>").drop(1).forEachIndexed { index, chapter ->
            assertTrue(chapter.indexOf("heading_${index}E\uE001") in 0 until chapter.indexOf("<p>"))
            assertFalse(chapter.substringAfter("<p>").contains("heading_${index}E\uE001"))
        }
    }

    @Test fun styleScanningPropagatesCooperativeCancellation() {
        var checks = 0
        val cancelled = java.util.concurrent.CancellationException("cancel styles")
        val error = runCatching {
            SourceStyleParser.mark("<a name='marker'>text ".repeat(20_000), "", "test_") {
                if (++checks == 12) throw cancelled
            }
        }.exceptionOrNull()
        assertTrue(error === cancelled)
        assertEquals(12, checks)
    }

    @Test fun commentedHeadingsAndStylesAreExcludedFromVisibleFormatting() {
        val marked = SourceStyleParser.mark("<!--<style>h1{font-size:5em}</style><h1>隐藏标题</h1>-->" +
            "<h1>真实标题</h1><!--<blockquote>隐藏引用</blockquote>--><p>正文</p>", "", "test_")
        assertFalse(marked.html.contains("隐藏"))
        assertEquals(1, marked.styles.size)
        assertTrue(marked.styles.single().fontScale!! < 5f)
    }

    @Test fun repeatedParagraphSelectorsReuseMatchingWithoutLosingInlineOverrides() {
        val css = (0 until 100).joinToString("\n") { ".unused$it p{font-size:3em}" } +
            "p.body{font-size:20px;text-indent:2em} span.emphasis{font-weight:bold}"
        val html = "<body><div>" + (0 until 3000).joinToString("") {
            "<p class='body' id='unselected$it'><span class='emphasis' style='font-style:${if (it % 2 == 0) "italic" else "normal"}'>文字🙂</span></p>"
        } + "</div></body>"
        val parsed = styles(html, css)
        assertEquals(3000, parsed.size)
        assertTrue(parsed.all { !it.paragraph && it.bold == true && it.fontScale == null })
        assertEquals(true, parsed[0].italic)
        assertEquals(false, parsed[1].italic)
        assertEquals(false, parsed.last().italic)
    }

    @Test fun selectorCacheDoesNotShareDeclarationsBetweenDifferentAncestorClasses() {
        val html = "<body><div class='one'><p><em>甲</em></p></div><div class='two'><p><em>乙</em></p></div></body>"
        val parsed = styles(html, ".one > p em{font-style:normal} .two > p em{font-style:italic}")
        assertEquals(listOf(false, true), parsed.map { it.italic })
    }

    @Test fun cssBlocksWithLiteralClosingBracesRemainSeparateRules() {
        val parsed = styles("<p>正文</p><h1>标题</h1><blockquote>引用</blockquote>",
            "/* 常规书内 CSS */ h1 { font-size: 150%; text-align: center; } blockquote { font-size: 80%; font-style: italic; }")
        assertEquals(2, parsed.size)
        assertEquals(1.5f, parsed[0].fontScale!!, .001f)
        assertEquals(SourceTextAlignment.CENTER, parsed[0].alignment)
        assertEquals(.8f, parsed[1].fontScale!!, .001f)
        assertEquals(true, parsed[1].italic)
    }

    @Test fun ordinaryBodyFontFamilySizeSpacingAndIndentAreLeftToTheReader() {
        val html = "<body><div><p class='body'>正文1</p><p class='body'>正文2</p></div></body>"
        val parsed = SourceStyleParser.mark(html, "body{font-family:serif;line-height:3} .body{font-size:20px;text-indent:2em}", "test_")
        assertTrue(parsed.styles.isEmpty())
        assertEquals(html, parsed.html)
    }

    @Test fun headingSizeIsRelativeToTheActualOrdinaryParagraphBaseline() {
        val parsed = styles("<body><div><h1>标题</h1><p class='body'>正文</p></div></body>",
            ".body{font-size:20px;text-indent:2em} h1{font-size:30px;text-align:center;font-style:italic}")
        val heading = parsed.single()
        assertEquals(1.5f, heading.fontScale!!, .001f)
        assertEquals(true, heading.bold)
        assertEquals(true, heading.italic)
        assertEquals(SourceTextAlignment.CENTER, heading.alignment)
        assertTrue(heading.paragraph)
        assertEquals(0f, heading.firstLineIndentEm!!, .001f)
    }

    @Test fun quotePreservesRelativeSizeAlignmentAndTotalFirstAndRestIndents() {
        val parsed = styles("<body><p>正文</p><blockquote>引用</blockquote></body>",
            "body{font-size:20px} blockquote{font-size:80%;margin-left:1em;text-indent:2em;text-align:justify;font-style:italic}")
        val quote = parsed.single()
        assertEquals(.8f, quote.fontScale!!, .001f)
        assertEquals(2.4f, quote.firstLineIndentEm!!, .001f)
        assertEquals(.8f, quote.restLineIndentEm!!, .001f)
        assertEquals(SourceTextAlignment.JUSTIFY, quote.alignment)
        assertEquals(true, quote.italic)
    }

    @Test fun inlineEmphasisAndExplicitNormalOverrideUseOnlySpecifiedAttributes() {
        val parsed = styles("<p>正文<strong>粗<span style='font-weight:normal;font-style:italic'>恢复</span></strong><em>斜</em></p>")
        assertEquals(3, parsed.size)
        assertEquals(true, parsed[0].bold)
        assertEquals(false, parsed[1].bold)
        assertEquals(true, parsed[1].italic)
        assertEquals(true, parsed[2].italic)
        assertTrue(parsed.none { it.paragraph })
        assertTrue(parsed.all { it.fontScale == null && it.alignment == null && it.firstLineIndentEm == null })
    }

    @Test fun selectorSpecificityDescendantsAndInlineStyleOverrideAreRespected() {
        val parsed = styles("<body><p>正文</p><div class='chapter'><h2 id='title' class='title' style='text-align:right'>标题</h2></div></body>",
            "h2{font-size:2em} .chapter > h2.title{font-size:1.6em;text-align:center} #title{font-size:1.8em} .missing h2{font-size:3em}")
        val heading = parsed.single()
        assertEquals(1.8f, heading.fontScale!!, .001f)
        assertEquals(SourceTextAlignment.END, heading.alignment)
    }

    @Test fun embeddedStylesAreReadBeforeRemovingHeadContent() {
        val parsed = SourceStyleParser.mark("<html><head><style>h1{font-size:125%;font-weight:normal}</style></head><body><h1>标题</h1><p>正文</p></body></html>", "", "test_")
        assertFalse(parsed.html.contains("<head>"))
        assertEquals(1.25f, parsed.styles.single().fontScale!!, .001f)
        assertEquals(false, parsed.styles.single().bold)
    }

    @Test fun anUnstyledWrapperDoesNotOverrideNormalParagraphsInAnotherFontSize() {
        val parsed = styles("<body style='font-size:12px'><div><p style='font-size:18px'>正文1</p><p style='font-size:18px'>正文2</p></div></body>")
        assertTrue(parsed.isEmpty())
    }

    @Test fun bodyAndStyledContainersDoNotReplaceTheReadersNormalBodyFormatting() {
        val html = "<html><body><div class='wrapper'><p class='body'>正文1</p><p class='body'>正文2</p></div></body></html>"
        val css = "html,body,.wrapper{font-size:16px;font-weight:normal} .body{font-size:20px;text-indent:2em}"
        assertTrue(styles(html, css).isEmpty())
    }

    @Test fun unsupportedCssSelectorsAndFontFamilyDoNotCreateSourceStyles() {
        val parsed = styles("<p>正文<span class='face'>文字</span></p>", ".face{font-family:fantasy} p:hover{font-size:3em} p[data-role='title']{font-weight:bold}")
        assertTrue(parsed.isEmpty())
        assertNull(styles("<p>正文<em>强调</em></p>").single().bold)
    }
}
