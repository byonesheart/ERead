package com.eyue.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
class ReaderNoteTapGeometryTest {
    private val density = Density(1f, 1f)
    private val style = readerLayoutSpec(ReaderSettings(fontName = "系统黑体", fontSize = 20f),
        density, LayoutDirection.Ltr, IntSize(320, 640)).textStyle

    @Test fun aTappedGlyphOnBothSidesOfItsCenterResolvesToTheSameSourceOffset() {
        val source = "First body.\nA note starts here."
        val rendered = readerParagraphText(AnnotatedString(source), style)
        val layout = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density,
            LayoutDirection.Ltr, cacheSize = 0).measure(rendered.text, style, constraints = Constraints(maxWidth = 270))
        val index = source.indexOf("note")
        val glyph = layout.getBoundingBox(index)
        assertEquals(index, readerTextTapOffset(layout, Offset(glyph.left + glyph.width * .2f, glyph.center.y)))
        assertEquals(index, readerTextTapOffset(layout, Offset(glyph.left + glyph.width * .8f, glyph.center.y)))
    }

    @Test fun paragraphGapAndTrailingBlankAreaDoNotCountAsAnnotatedText() {
        val source = "Short.\nNext paragraph."
        val rendered = readerParagraphText(AnnotatedString(source), style)
        val layout = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density,
            LayoutDirection.Ltr, cacheSize = 0).measure(rendered.text, style, constraints = Constraints(maxWidth = 270))
        val gap = layout.getLineForOffset(source.indexOf('\n'))
        assertNull(readerTextTapOffset(layout, Offset(1f, (layout.getLineTop(gap) + layout.getLineBottom(gap)) / 2f)))
        val line = layout.getLineForOffset(source.indexOf("Next"))
        assertNull(readerTextTapOffset(layout, Offset(layout.getLineRight(line) + 20f,
            (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)))
        assertNull(readerTextTapOffset(layout, Offset(-1f, 1f)))
        assertNull(readerTextTapOffset(layout, Offset(1f, layout.size.height + 1f)))
    }

    @Test fun annotationBubblePrefersAboveItsPassageAndPointsAtTheAnchor() {
        val placement = readerAnnotationBubblePlacement(Rect(140f, 300f, 180f, 330f),
            IntRect(0, 24, 320, 640), IntSize(180, 100))
        assertTrue(placement.above)
        assertEquals(IntOffset(70, 200), placement.offset)
        assertEquals(90f, placement.tailX, 0f)
    }

    @Test fun annotationBubbleMovesBelowATopPassageAndClampsToTheVisibleWindow() {
        val visible = IntRect(0, 24, 320, 400)
        val top = readerAnnotationBubblePlacement(Rect(0f, 28f, 30f, 52f), visible, IntSize(180, 100))
        assertFalse(top.above)
        assertEquals(8, top.offset.x)
        assertTrue(top.offset.y >= 52)
        assertTrue(top.tailX >= 0f && top.tailX <= 180f)
        val right = readerAnnotationBubblePlacement(Rect(300f, 330f, 320f, 355f), visible, IntSize(180, 100))
        assertEquals(132, right.offset.x)
        assertTrue(right.offset.y >= visible.top + 8)
        assertTrue(right.offset.y + 100 <= visible.bottom - 8)
        assertTrue(right.tailX >= 0f && right.tailX <= 180f)
    }

    @Test fun annotationBubbleRespectsKeyboardAndInsetBoundsOnEveryEdge() {
        val visible = IntRect(20, 80, 300, 300)
        val placement = readerAnnotationBubblePlacement(Rect(140f, 285f, 180f, 315f), visible, IntSize(200, 120))
        assertTrue(placement.above)
        assertTrue(placement.offset.x >= visible.left + 8)
        assertTrue(placement.offset.x + 200 <= visible.right - 8)
        assertTrue(placement.offset.y >= visible.top + 8)
        assertTrue(placement.offset.y + 120 <= visible.bottom - 8)
        val oversized = readerAnnotationBubblePlacement(Rect(0f, 0f, 10f, 10f),
            IntRect(0, 0, 80, 60), IntSize(200, 120))
        assertTrue(oversized.offset.x >= 0)
        assertTrue(oversized.offset.y >= 0)
    }
}
