package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.SourceTextAlignment
import com.eyue.reader.data.SourceTextStyle
import com.eyue.reader.data.InlineImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderParagraphSpacingRobolectricTest {
    private val density = Density(1f, 1f)
    private val spec = readerLayoutSpec(ReaderSettings(fontName = "系统黑体", fontSize = 20f, lineHeight = 2f),
        density, LayoutDirection.Ltr, IntSize(320, 640))

    @Test fun customParagraphSpacingChangesOnlyTheExtraParagraphHeight() {
        val text = AnnotatedString("First paragraph.\nSecond paragraph.")
        val compact = measure(readerParagraphText(text, spec.textStyle, .4f).text)
        val wide = measure(readerParagraphText(text, spec.textStyle, 1.2f).text)
        assertEquals(16, wide.size.height - compact.size.height)
        for (offset in 0..text.length) assertEquals(offset,
            readerParagraphText(text, spec.textStyle, 1.2f).offsetMapping.originalToTransformed(offset))
    }

    @Test fun singleNewlineAddsASixteenPixelSpacerWhileWrappedBodyLinesStayFortyPixelsApart() {
        val original = AnnotatedString("The trees follow the path into the distance and the quiet breeze moves through the grass.\nA new paragraph begins.")
        val transformed = readerParagraphText(original, spec.textStyle)
        val layout = measure(transformed.text)
        val spacer = spacerLine(layout)
        assertTrue(spacer > 1)
        assertEquals(16f, layout.getLineBottom(spacer) - layout.getLineTop(spacer), 1f)
        assertEquals(56f, layout.getLineBaseline(spacer + 1) - layout.getLineBaseline(spacer - 1), 1f)
        for (line in 1 until spacer) assertEquals(40f, layout.getLineBaseline(line) - layout.getLineBaseline(line - 1), 1f)
        for (offset in 0..original.length) assertEquals(offset,
            transformed.offsetMapping.transformedToOriginal(transformed.offsetMapping.originalToTransformed(offset)))
        assertEquals("The trees follow the path into the distance and the quiet breeze moves through the grass.\nA new paragraph begins.", original.text)
    }

    @Test fun existingBlankLineRetainsItsOriginalHeightWithoutAnotherSpacer() {
        val original = AnnotatedString("Short opening.\n\nAlready separated.\nThe last paragraph.")
        val transformed = readerParagraphText(original, spec.textStyle)
        assertEquals(1, transformed.text.text.count { it == '\u200B' })
        assertTrue(transformed.text.text.startsWith("Short opening.\n\nAlready separated."))
        val layout = measure(transformed.text)
        val middle = layout.getLineForOffset(transformed.text.text.indexOf("Already"))
        val last = layout.getLineForOffset(transformed.text.text.indexOf("The last"))
        assertEquals(80f, layout.getLineBaseline(middle) - layout.getLineBaseline(0), 1f)
        assertEquals(56f, layout.getLineBaseline(last) - layout.getLineBaseline(middle), 1f)
        val spacer = spacerLine(layout)
        assertEquals(16f, layout.getLineBottom(spacer) - layout.getLineTop(spacer), 1f)
    }

    @Test fun enlargedSourceHeadingAndIndentDoNotEnlargeTheBodyBasedSpacer() {
        val text = "A heading.\nA body paragraph."
        val headingEnd = text.indexOf('\n') + 1
        val styles = ReaderSourceStyles(text, listOf(SourceTextStyle(start = 0, end = headingEnd,
            fontScale = 2f, bold = true, alignment = SourceTextAlignment.CENTER,
            firstLineIndentEm = 2f, restLineIndentEm = 1f, paragraph = true)))
        val original = styles.apply(text, 0, text.length, spec.textStyle)
        val transformed = readerParagraphText(original, spec.textStyle)
        val layout = measure(transformed.text)
        val spacer = spacerLine(layout)
        assertEquals(16f, layout.getLineBottom(spacer) - layout.getLineTop(spacer), 1f)
        assertEquals(80f, layout.getLineBottom(0) - layout.getLineTop(0), 1f)
        assertEquals(40f, layout.getLineBottom(spacer + 1) - layout.getLineTop(spacer + 1), 1f)
        assertEquals(text, original.text)
        assertEquals(headingEnd, transformed.offsetMapping.originalToTransformed(headingEnd))
        assertEquals(headingEnd + 1, transformed.offsetMapping.transformedToOriginal(headingEnd + 1))
    }

    @Test fun denseParagraphPaginationKeepsEverySourceCharacterAndFitsMeasuredPages() = runBlocking {
        val text = "A heading😀\n" + "A short paragraph😀.\n".repeat(700) + "The final paragraph."
        val headingEnd = text.indexOf('\n')
        val styles = ReaderSourceStyles(text, listOf(SourceTextStyle(0, headingEnd, fontScale = 2f,
            bold = true, alignment = SourceTextAlignment.CENTER, paragraph = true)))
        val imageOffset = text.indexOf('\n') + 1
        val pages = readerPaginate(RuntimeEnvironment.getApplication(), listOf(text), spec.textStyle,
            density, LayoutDirection.Ltr, 180, 180, inlineImages = listOf(listOf(InlineImage(imageOffset, "synthetic.png"))),
            sourceStyles = listOf(styles)).single()
        assertTrue(pages.size > 20)
        assertEquals(1, pages.count { it.imageIndex >= 0 })
        val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr, cacheSize = 0)
        var offset = 0
        for (page in pages) {
            assertEquals(offset, page.start)
            if (page.imageIndex >= 0) { assertEquals(imageOffset, page.start); continue }
            assertTrue(page.end > page.start)
            val display = readerParagraphText(styles.apply(text, page.start, page.end, spec.textStyle), spec.textStyle)
            val layout = measurer.measure(display.text, spec.textStyle, constraints = Constraints(maxWidth = 180))
            assertTrue("displayed page exceeds its viewport: ${layout.size.height}", layout.size.height <= 181)
            if (page.end < text.length) assertTrue(!(Character.isHighSurrogate(text[page.end - 1]) && Character.isLowSurrogate(text[page.end])))
            offset = page.end
        }
        assertEquals(text.length, offset)
        assertEquals(text, pages.filter { it.imageIndex < 0 }.joinToString("") { text.substring(it.start, it.end) })
    }

    private fun measure(text: AnnotatedString): TextLayoutResult = TextMeasurer(
        createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr, cacheSize = 0
    ).measure(text, spec.textStyle, constraints = Constraints(maxWidth = spec.textWidth))

    private fun spacerLine(layout: TextLayoutResult): Int =
        layout.getLineForOffset(layout.layoutInput.text.text.indexOf('\u200B').also { assertTrue(it >= 0) })
}
