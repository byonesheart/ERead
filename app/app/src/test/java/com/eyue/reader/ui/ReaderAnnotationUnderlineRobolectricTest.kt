package com.eyue.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.UnderlineStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAnnotationUnderlineRobolectricTest {
    private val style = TextStyle(fontSize = 20.sp, lineHeight = 32.sp)
    private fun measure(text: AnnotatedString, width: Int = 180, direction: LayoutDirection = LayoutDirection.Ltr): TextLayoutResult =
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(1f), direction, cacheSize = 0)
            .measure(text, style, constraints = Constraints(maxWidth = width))

    @Test fun underlineTagsSurviveParagraphFormattingWithoutChangingTextOrGlyphColor() {
        val source = "A paragraph.\nNext paragraph."
        val annotation = AnnotatedString.Builder(source).apply {
            addReaderUnderline(2, source.length, UnderlineStyle.WAVY, Color.Red)
        }.toAnnotatedString()
        val transformed = readerParagraphText(annotation, style)
        val tag = transformed.text.getStringAnnotations(ReaderUnderlineAnnotationTag, 0, source.length).single()
        assertEquals(2, tag.start)
        assertEquals(source.length, tag.end)
        assertEquals("WAVY|-65536", tag.item)
        assertTrue(transformed.text.spanStyles.none { it.item.color == Color.Red })
        (0..source.length).forEach { assertEquals(it, transformed.offsetMapping.originalToTransformed(it)) }
    }

    @Test fun wrappedSelectionsDrawSeparateBoundsForEverySelectedLine() {
        val source = "The first selected words continue through several wrapped lines and end at this place."
        val layout = measure(AnnotatedString(source), width = 160)
        val end = layout.getLineEnd(2, visibleEnd = true) - 2
        val segments = readerUnderlineSegments(layout, 2, end)
        assertEquals(setOf(0, 1, 2), segments.map { it.line }.toSet())
        segments.forEach {
            assertTrue(it.right > it.left)
            assertTrue(it.left >= layout.getLineLeft(it.line) - 1f)
            assertTrue(it.right <= layout.getLineRight(it.line) + 1f)
            assertTrue(it.y > layout.getLineBaseline(it.line))
            assertTrue(it.y < layout.getLineBottom(it.line))
        }
        assertEquals(layout.getBoundingBox(2).left, segments.first().left, .1f)
        assertEquals(layout.getBoundingBox(end - 1).right, segments.last().right, .1f)
    }

    @Test fun paragraphGapsNewlinesAndEmptyRangesProduceNoUnderlineSegments() {
        val source = "First paragraph.\nSecond paragraph."
        val rendered = readerParagraphText(AnnotatedString(source), style)
        val layout = measure(rendered.text, width = 300)
        val gapOffset = source.indexOf('\n')
        val gapLine = layout.getLineForOffset(gapOffset)
        val segments = readerUnderlineSegments(layout, 0, source.length)
        assertFalse(segments.any { it.line == gapLine })
        assertTrue(readerUnderlineSegments(layout, gapOffset, gapOffset + 1).isEmpty())
        assertTrue(readerUnderlineSegments(layout, 5, 5).isEmpty())
    }

    @Test fun rightToLeftTextAndSurrogatePairsKeepFiniteOrderedGlyphBounds() {
        val text = AnnotatedString("مرحبا بالعالم 😀 قراءة ممتعة")
        val layout = measure(text, width = 400, direction = LayoutDirection.Rtl)
        val segments = readerUnderlineSegments(layout, 0, text.length)
        assertTrue(segments.isNotEmpty())
        segments.forEach { assertTrue(it.left.isFinite() && it.right.isFinite()); assertTrue(it.right > it.left) }
        assertEquals(layout.getLineLeft(0), segments.minOf { it.left }, 1f)
        assertTrue(segments.maxOf { it.right } <= layout.getLineRight(0) + 1f)
    }

    @Test fun waveUsesAlternatingSmoothArcsAndClipsItsLastArcToTheSelectedText() {
        val curves = readerUnderlineCurves(10f, 29f, 20f, 1.5f, 4f)
        assertEquals(5, curves.size)
        assertEquals(29f, curves.last().end.x, 0f)
        assertTrue(curves[0].control.y > 20f)
        assertTrue(curves[1].control.y < 20f)
        assertTrue(curves.all { it.end.y == 20f && it.end.x <= 29f })
        assertTrue(curves.last().control.y - 20f < curves.first().control.y - 20f)
        assertTrue(readerUnderlineCurves(2f, 2f, 0f, 1f, 4f).isEmpty())
    }
}
