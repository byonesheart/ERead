package com.eyue.reader.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechPositionTest {
    @Test fun sentenceRestartUsesTheActualSentenceInsteadOfTheParagraphOrAudioChunk() {
        val text = "第一段仍在读。\n  前一句。第二句包含😀表情。末句。"
        assertEquals(text.indexOf("第二句"), readerSpeechSentenceStart(text, text.indexOf("表情")))
        assertEquals(text.indexOf("前一句"), readerSpeechSentenceStart(text, text.indexOf("前一句") + 2))
        assertEquals(0, readerSpeechSentenceStart("", 0))
    }
    @Test fun sentenceRestartKeepsClosingQuotesAndDecimalNumbersWithTheirSentence() {
        val text = "“还要继续吗？！” 他答道：价格是3.14元。 Next sentence."
        assertEquals(0, readerSpeechSentenceStart(text, text.indexOf('”')))
        assertEquals(text.indexOf("他答道"), readerSpeechSentenceStart(text, text.indexOf("14")))
        assertEquals(text.indexOf("Next"), readerSpeechSentenceStart(text, text.indexOf("sentence")))
    }
    @Test fun sentenceRestartAcrossTransportChunksKeepsOriginalUtf16Offsets() {
        val text = "开头。" + "长句😀".repeat(100) + "。下一句。"
        assertEquals(3, readerSpeechSentenceStart(text, 250))
        assertEquals(text.indexOf("下一句"), readerSpeechSentenceStart(text, text.lastIndex - 1))
    }
    @Test fun spokenParagraphExcludesAdjacentParagraphsAndPreservesUtf16Offsets() {
        val text = "第一段😀。\n第二段。\n第三段。"
        assertEquals("第二段。", readerSpeechParagraph(text, 8)?.let { text.substring(it) })
        assertEquals(7 until 11, readerSpeechParagraph(text, 9))
        assertNull(readerSpeechParagraph("", 0))
    }
    @Test fun advancingAndRewindingUsesParagraphBoundaries() {
        val text = "甲段。\n乙段。\n丙段。"
        assertEquals(8, readerSpeechStep(text, 5, 1))
        assertEquals(4, readerSpeechStep(text, 6, -1))
        assertEquals(0, readerSpeechStep(text, 4, -1))
        assertEquals(text.length, readerSpeechStep(text, text.lastIndex, 1))
    }
}
