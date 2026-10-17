package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextParserTest {
    @Test fun defaultRuleUsesBoundedWholeLinesWithoutTakingSurroundingBlankLines() {
        val text = "正文\n\n　第一章 初遇　\n\n内容\n\n第二章重逢\n末尾"
        val matches = Regex(DEFAULT_CHAPTER_RULE, RegexOption.MULTILINE).findAll(text).map { it.value }.toList()
        assertEquals(listOf("　第一章 初遇　", "第二章重逢"), matches)
        assertEquals(listOf("前言", "第一章 初遇", "第二章重逢"), TextParser.split(text, DEFAULT_CHAPTER_RULE).map { it.title })
    }

    @Test fun defaultRuleSupportsNamedChineseUnitsAndEnglishRomanChapters() {
        listOf("第十卷 归途", "第3节 终点", "第两百回", "Chapter IV: Down the Rabbit-Hole",
            "PART 2", "Section 10 The End", "楔子", "后记").forEach {
            assertEquals(it, TextParser.split("$it\n内容", DEFAULT_CHAPTER_RULE).single().title)
        }
    }

    @Test fun defaultRuleDoesNotTreatFootnoteNumbersAndLongBodyLinesAsTitles() {
        listOf("[1] 译者注", "（二）备注", "1. 这是一条注释", "No. 3 Reference", "注释：第一章解释", "文中提到第一章",
            "第一章的内容见下文。", "Chapter 1abc", "第一章 " + "长正文".repeat(30)).forEach {
            assertEquals("正文", TextParser.split(it, DEFAULT_CHAPTER_RULE).single().title)
        }
    }

    @Test fun separatedTitlesCanContainQuestionMarksAndAbbreviations() {
        for (title in listOf("第十二章 去哪里？", "Chapter 1: Dr. Alice", "第一章初遇")) {
            assertEquals(title, TextParser.split(title, DEFAULT_CHAPTER_RULE).single().title)
        }
    }

    @Test fun relocatedReadingAndSelectionEndpointsSurviveCompactParagraphWhitespace() {
        val old = "甲。\n\n乙🙂句子。\n\n丙"
        val next = "甲。\n乙🙂句子。\n丙"
        assertEquals(0 to next.indexOf("乙"), TextParser.relocatePosition(listOf(old), listOf(next), 0, old.indexOf("乙")))
        assertEquals(0 to next.indexOf("乙"), TextParser.relocatePosition(listOf(old), listOf(next), 0, old.indexOf("乙"), "乙🙂句子。\n\n丙"))
        assertEquals(0 to next.length, TextParser.relocatePosition(listOf(old), listOf(next), 0, old.length))
    }
    @Test fun compactParagraphsMapsUtf16ImagesAndStylesWhileKeepingSpecialBoundaries() {
        val text = "标题\n\n甲🙂\n\n乙\n\n引用\n\n后续\n\n结尾"
        val chapter = ParsedTextChapter("章节", text, listOf(InlineImage(20, "one.png"), InlineImage(20, "two.png")),
            listOf(SourceTextStyle(0, 2, fontScale = 1.5f, paragraph = true),
                SourceTextStyle(4, 7, bold = true), SourceTextStyle(12, 14, italic = true, paragraph = true)))
        val (compacted, offsets) = TextParser.compactParagraphs(chapter)
        assertEquals("标题\n\n甲🙂\n乙\n\n引用\n\n后续\n结尾", compacted.text)
        assertEquals(listOf(18, 18), compacted.inlineImages.map { it.offset })
        assertEquals("引用", compacted.text.substring(compacted.sourceStyles[2].start, compacted.sourceStyles[2].end))
        assertEquals(15, offsets[16])
        assertEquals(20, offsets[22])
        assertTrue(offsets.asList().zipWithNext().all { (before, after) -> after >= before })
    }
    @Test fun decodesUtf8BomAndGb18030() {
        val text = "第一章 春风\n这是正文。"
        assertEquals(text, TextParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, TextParser.decode(text.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun decodesUtf16AndNormalizesNewlines() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "第一章\r\n正文".toByteArray(Charsets.UTF_16LE)
        assertEquals("第一章\n正文", TextParser.decode(bytes))
    }

    @Test fun defaultRuleBuildsChaptersAndPreservesPreface() {
        val chapters = TextParser.split("前置说明\n\n第一章 风\n内容一\n第二章 雨\n内容二", DEFAULT_CHAPTER_RULE)
        assertEquals(listOf("前言", "第一章 风", "第二章 雨"), chapters.map { it.title })
        assertTrue(chapters[2].text.endsWith("内容二"))
    }

    @Test fun customRuleHandlesLatinChapterHeaders() {
        val chapters = TextParser.split("Part 1\nhello\nPart 2\nworld", "^Part \\d+$")
        assertEquals(listOf("Part 1", "Part 2"), chapters.map { it.title })
    }

    @Test fun noChapterMatchRetainsSingleBody() {
        assertEquals(listOf(ParsedTextChapter("正文", "一个没有章节名的故事")),
            TextParser.split("一个没有章节名的故事", DEFAULT_CHAPTER_RULE))
    }

    @Test fun authorRequiresAnExplicitMetadataField() {
        assertEquals("", TextParser.metadata("作者在树下写字\n某某著\n正文").author)
        assertEquals(TextMetadata("新故事", "林秋"), TextParser.metadata("书名：新故事\n作者：林秋\n第一章"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyRule() { TextParser.validateRule("") }
    @Test(expected = IllegalArgumentException::class) fun rejectsRuleMatchingEmpty() { TextParser.validateRule(".*") }

    @Test fun naturalNamesOrderNumbersIncludingLongOnes() {
        val names = listOf("第10话", "第2话", "第1话", "第99999999999999999999话")
        assertEquals(listOf("第1话", "第2话", "第10话", "第99999999999999999999话"), names.sortedWith(NaturalOrder))
    }

    @Test fun relocatesQuoteToNewChapterAfterSplit() {
        assertEquals(1 to 3, TextParser.relocatePosition(listOf("before chapter unique quotation after"),
            listOf("before chapter", "...unique quotation after"), 0, 15, "unique quotation"))
    }

    @Test fun preservesExcerptPositionWhenTextIsInsertedBeforeIt() {
        val original = "这一段是完整保留的文字，我们要在这里继续读下去。"
        assertEquals(0 to 11, TextParser.relocatePosition(listOf(original),
            listOf("新前言。" + original), 0, 7))
    }

    @Test fun duplicateQuotesChooseOccurrenceClosestToOldPosition() {
        assertEquals(1 to 0, TextParser.relocatePosition(listOf("echo____", "echo"),
            listOf("echo____", "echo"), 1, 0, "echo"))
    }

    @Test fun changedContentFallsBackToCumulativeOffset() {
        assertEquals(1 to 1, TextParser.relocatePosition(listOf("abcd", "efgh"),
            listOf("XXX", "YYYYY"), 1, 0, "not present"))
    }

    @Test fun missingChaptersAndOutOfBoundsPositionsAreClamped() {
        assertEquals(0 to 0, TextParser.relocatePosition(emptyList(), emptyList(), 50, 90))
        assertEquals(0 to 2, TextParser.relocatePosition(listOf("abc"), listOf("ab"), 50, 90))
    }

    @Test fun repeatedDecorationImagesRelocateToTheNearestTextPosition() {
        val chapters = listOf(
            ParsedTextChapter("one", "a".repeat(100), listOf(InlineImage(20, "shared.png"))),
            ParsedTextChapter("two", "b".repeat(100), listOf(InlineImage(70, "shared.png")))
        )
        assertEquals(1 to InlineImage(70, "shared.png"), TextParser.relocateImage(chapters, "shared.png", 169))
    }
}
