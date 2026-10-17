package com.eyue.reader.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechPositionTest {
    @Test fun resumingRequiresTheOriginalNodeToRemainInTheCurrentParagraph() {
        val text = "第一段仍在读。\n第二段。"
        assertTrue(readerSpeechSameParagraph(text, 0, 4))
        assertTrue(readerSpeechSameParagraph(text, 5, 4))
        assertFalse(readerSpeechSameParagraph(text, text.indexOf("第二"), 4))
        assertFalse(readerSpeechSameParagraph(text, 0, text.length))
        assertFalse(readerSpeechSameParagraph("", 0, 0))
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
