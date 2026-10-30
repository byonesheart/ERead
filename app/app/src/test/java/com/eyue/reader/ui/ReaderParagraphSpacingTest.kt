package com.eyue.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderParagraphSpacingTest {
    private val style = TextStyle(fontSize = 20.sp, lineHeight = 32.sp)

    @Test fun indentationOnlyAffectsParagraphStartsAndNeverChangesPersistedOffsets() {
        val indented = style.copy(textIndent = androidx.compose.ui.text.style.TextIndent(40.sp, 0.sp))
        val source = "第一段完整文字\n第二段完整文字"
        val whole = readerParagraphText(AnnotatedString(source), indented, context = source)
        assertTrue(whole.text.paragraphStyles.none { it.item.textIndent != null })
        val continuation = readerParagraphText(AnnotatedString(source.substring(3, 7)), indented,
            context = source, sourceStart = 3)
        assertEquals(0.sp, continuation.text.paragraphStyles.single().item.textIndent!!.firstLine)
        source.indices.forEach { assertEquals(it, whole.offsetMapping.transformedToOriginal(it)) }
    }

    @Test fun singleParagraphBoundariesGetSmallDisplayOnlyGaps() {
        val source = AnnotatedString("第一段\n第二段\n第三段")
        val transformed = readerParagraphText(source, style)
        assertEquals("第一段\u200B第二段\u200B第三段", transformed.text.text)
        assertEquals("第一段\n第二段\n第三段", source.text)
        assertEquals(2, transformed.text.paragraphStyles.size)
        transformed.text.paragraphStyles.forEach { range ->
            assertEquals("\u200B", transformed.text.text.substring(range.start, range.end))
            assertEquals(16.sp, range.item.lineHeight)
            assertEquals(LineHeightStyle.Alignment.Proportional, range.item.lineHeightStyle!!.alignment)
            assertEquals(LineHeightStyle.Trim.None, range.item.lineHeightStyle!!.trim)
        }
        transformed.text.spanStyles.forEach { range ->
            assertEquals("\u200B", transformed.text.text.substring(range.start, range.end))
            assertEquals(1.sp, range.item.fontSize)
        }
    }

    @Test fun sourceOffsetsRoundTripAndDisplayOffsetsStayMonotoneAndBounded() {
        val source = AnnotatedString("甲😀乙\n丙丁\n戊😀己")
        val transformed = readerParagraphText(source, style)
        val mapping = transformed.offsetMapping
        var previous = -1
        for (offset in 0..source.length) {
            val display = mapping.originalToTransformed(offset)
            assertTrue(display in 0..transformed.text.length)
            assertTrue(display >= previous)
            assertEquals(offset, mapping.transformedToOriginal(display))
            previous = display
        }
        previous = -1
        for (offset in 0..transformed.text.length) {
            val original = mapping.transformedToOriginal(offset)
            assertTrue(original in 0..source.length)
            assertTrue(original >= previous)
            previous = original
        }
        assertEquals(source.length, transformed.text.length)
    }

    @Test fun displayedParagraphSpacerKeepsTheOriginalNewlineCoordinate() {
        val transformed = readerParagraphText(AnnotatedString("甲\n乙"), style)
        assertEquals(2, transformed.offsetMapping.originalToTransformed(2))
        assertEquals(1, transformed.offsetMapping.transformedToOriginal(1))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(2))
        assertEquals(3, transformed.offsetMapping.transformedToOriginal(3))
    }

    @Test fun existingEmptyLinesAndLeadingOrTrailingNewlinesDoNotReceiveExtraGaps() {
        listOf("", "正文", "\n正文", "正文\n", "甲\n\n乙", "甲\n\n\n乙", "\n\n", "甲\n  \n乙", "甲\r\n\r\n乙").forEach { raw ->
            val source = AnnotatedString(raw)
            val transformed = readerParagraphText(source, style)
            assertSame(source, transformed.text)
            for (offset in 0..source.length) assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
        }
        assertEquals("甲\n\n乙\u200B丙\n", readerParagraphText(AnnotatedString("甲\n\n乙\n丙\n"), style).text.text)
    }

    @Test fun onlyRealParagraphEndingsAddSpacingAcrossScrollBlocks() {
        val raw = "长段正文\n下段正文"
        assertTrue(readerParagraphGapAfter(raw, raw.indexOf('\n') + 1))
        assertTrue(!readerParagraphGapAfter(raw, 3))
        assertTrue(!readerParagraphGapAfter(raw, raw.length))
        assertTrue(!readerParagraphGapAfter("正文\n\n下段", 3))
        assertTrue(!readerParagraphGapAfter("正文\n \n下段", 3))
        assertTrue(!readerParagraphGapAfter("\n下段", 1))
    }

    @Test fun emojiAndImageAnchorsKeepTheirOriginalOffsets() {
        val raw = "甲😀乙\n图😀片\n尾段"
        val source = AnnotatedString.Builder(raw).apply {
            addStringAnnotation("image", "inline-image", raw.indexOf('图'), raw.indexOf('图') + 1)
        }.toAnnotatedString()
        val transformed = readerParagraphText(source, style)
        val image = transformed.text.getStringAnnotations("image", 0, transformed.text.length).single()
        assertEquals(raw.indexOf('图'), transformed.offsetMapping.transformedToOriginal(image.start))
        assertEquals(raw.indexOf('图') + 1, transformed.offsetMapping.transformedToOriginal(image.end))
        assertEquals("图", transformed.text.text.substring(image.start, image.end))
        assertEquals(2, transformed.text.text.count { it == '\uD83D' })
    }

    @Test fun sourceFormattingAndNotesKeepTheirOriginalUtf16Ranges() {
        val raw = "标题\n第一段\n第二段"
        val emphasis = SpanStyle(fontWeight = FontWeight.Bold, background = Color.Yellow, fontSize = 26.sp)
        val heading = ParagraphStyle(textAlign = TextAlign.Center, lineHeight = 40.sp)
        val source = AnnotatedString.Builder(raw).apply {
            addStyle(emphasis, 1, raw.length - 1)
            addStyle(heading, 0, 3)
            addStringAnnotation("note", "跨段笔记", 1, raw.length - 1)
        }.toAnnotatedString()
        val transformed = readerParagraphText(source, style)
        val sourceSpans = transformed.text.spanStyles.filter { it.item == emphasis }
        val notes = transformed.text.getStringAnnotations("note", 0, transformed.text.length)
        assertEquals(raw.length - 2, sourceSpans.sumOf { it.end - it.start })
        assertEquals(raw.length - 2, notes.sumOf { it.end - it.start })
        assertEquals(source.spanStyles, sourceSpans)
        assertEquals(source.getStringAnnotations("note", 0, source.length), notes)
        val displayHeading = transformed.text.paragraphStyles.single { it.item == heading }
        assertEquals(0, displayHeading.start)
        assertEquals(3, displayHeading.end)
        val gaps = transformed.text.paragraphStyles.filter { it.item.lineHeight == 16.sp }
        assertEquals(2, gaps.size)
    }

    @Test fun displayGapFollowsBodyFontSizeWithoutChangingSourceParagraphHeight() {
        val sourceStyle = ParagraphStyle(lineHeight = 48.sp)
        val source = AnnotatedString.Builder("特殊段\n正文").apply { addStyle(sourceStyle, 0, 4) }.toAnnotatedString()
        val transformed = readerParagraphText(source, TextStyle(fontSize = 30.sp, lineHeight = 42.sp))
        assertEquals(48.sp, transformed.text.paragraphStyles.single { it.item == sourceStyle }.item.lineHeight)
        assertEquals(24.sp, transformed.text.paragraphStyles.single { it.item != sourceStyle }.item.lineHeight)
    }
}
