package com.eyue.reader.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.SourceTextAlignment
import com.eyue.reader.data.SourceTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSourceStylesTest {
    private val base = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 36.sp)

    @Test fun measurementChunkGuardIncludesLargeSourceLineAndParagraphSpacing() {
        assertEquals(8f, ReaderSourceStyles("正文", listOf(SourceTextStyle(0, 2, paragraph = true, lineHeightEm = 8f))).maximumScale, .001f)
        assertEquals(7f, ReaderSourceStyles("正文", listOf(SourceTextStyle(0, 2, paragraph = true, spaceBeforeEm = 7f, spaceAfterEm = 6f))).maximumScale, .001f)
        assertEquals(1f, ReaderSourceStyles("正文", emptyList()).maximumScale, .001f)
    }

    @Test fun sourceCssUsesItsLineAndLetterSpacingAndCollapsedMarginsWithoutReplacingFontFamily() {
        val text = "甲\n乙"
        val source = ReaderSourceStyles(text, listOf(
            SourceTextStyle(0, 1, fontScale = 1f, paragraph = true, lineHeightEm = 1.3f,
                letterSpacingEm = .1f, spaceBeforeEm = .2f, spaceAfterEm = .3f),
            SourceTextStyle(2, 3, paragraph = true, spaceBeforeEm = .8f, spaceAfterEm = 0f)))
        val styled = source.apply(text, 0, text.length, base)
        assertEquals(26.sp, styled.paragraphStyles.first().item.lineHeight)
        assertEquals(2.sp, styled.spanStyles.first().item.letterSpacing)
        assertNull(styled.spanStyles.first().item.fontFamily)
        assertEquals(.8f, source.paragraphGapAfter(2, .1f), .001f)
        val rendered = readerParagraphText(styled, base, gapEm = .1f).text
        assertEquals(16.sp, rendered.paragraphStyles.last().item.lineHeight)
        assertEquals(0f, source.paragraphGapAfter(text.length, .1f), .001f)
    }

    @Test fun specialParagraphKeepsSourceShapeAndUsesBodyFontForIndentation() {
        val text = "引用的标题\n普通正文"
        val source = ReaderSourceStyles(text, listOf(SourceTextStyle(0, 5, fontScale = 2f, bold = true,
            italic = true, alignment = SourceTextAlignment.CENTER, firstLineIndentEm = 2f,
            restLineIndentEm = 1f, paragraph = true)))
        val styled = source.apply(text, 0, text.length, base)
        val title = styled.spanStyles.first().item
        assertEquals(40.sp, title.fontSize)
        assertEquals(FontWeight.Bold, title.fontWeight)
        assertEquals(FontStyle.Italic, title.fontStyle)
        assertNull("源样式不能替换软件选择的字体", title.fontFamily)
        val paragraph = styled.paragraphStyles.first().item
        assertEquals(TextAlign.Center, paragraph.textAlign)
        assertEquals(40.sp, paragraph.textIndent!!.firstLine)
        assertEquals(20.sp, paragraph.textIndent!!.restLine)
        assertEquals(72.sp, paragraph.lineHeight)
        assertTrue(styled.spanStyles.none { it.start >= 6 })
        assertTrue(styled.paragraphStyles.none { it.start >= 6 })
    }

    @Test fun sourceCascadeAndPageContinuationDoNotRepeatFirstLineIndent() {
        val text = "引用abcdefghijk\n"
        val source = ReaderSourceStyles(text, listOf(
            SourceTextStyle(0, text.length - 1, fontScale = 1.5f, bold = true,
                alignment = SourceTextAlignment.END, firstLineIndentEm = 2f, restLineIndentEm = 0.5f, paragraph = true),
            SourceTextStyle(4, 8, fontScale = 0.75f, bold = false, italic = true)))
        val continuation = source.apply(text, 4, text.length, base)
        val inner = continuation.spanStyles.first().item
        assertEquals(15.sp, inner.fontSize)
        assertEquals(FontWeight.Normal, inner.fontWeight)
        assertEquals(FontStyle.Italic, inner.fontStyle)
        assertNull(inner.fontFamily)
        val paragraph = continuation.paragraphStyles.first().item
        assertEquals(TextAlign.End, paragraph.textAlign)
        assertEquals(10.sp, paragraph.textIndent!!.firstLine)
        assertEquals(10.sp, paragraph.textIndent!!.restLine)
        assertEquals(text.substring(4).removeSuffix("\n"), continuation.text)
    }

    @Test fun unstyledSlicesAndParagraphOnlyStylesDoNotAddEmptyFontRanges() {
        val text = "标题\n普通正文\n居中的引用"
        val source = ReaderSourceStyles(text, listOf(SourceTextStyle(0, 2, bold = true),
            SourceTextStyle(8, text.length, alignment = SourceTextAlignment.CENTER, paragraph = true)))
        val ordinary = source.apply(text, 3, 7, base)
        assertEquals("普通正文", ordinary.text)
        assertTrue(ordinary.spanStyles.isEmpty())
        assertTrue(ordinary.paragraphStyles.isEmpty())
        val quote = source.apply(text, 8, text.length, base)
        assertTrue(quote.spanStyles.isEmpty())
        assertEquals(TextAlign.Center, quote.paragraphStyles.single().item.textAlign)
        val empty = ReaderSourceStyles(text, emptyList()).apply(text, 0, text.length, base)
        assertEquals(text, empty.text)
        assertTrue(empty.spanStyles.isEmpty())
        assertTrue(empty.paragraphStyles.isEmpty())
    }
}
