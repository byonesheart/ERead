package com.eyue.reader.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderMarginsLayoutRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun defaultMarginsKeepTheOriginalBodyAndPermanentStatusGutters() {
        val density = Density(1f)
        val body = readerBodyViewport(density, IntSize(360, 800))
        assertEquals(IntSize(360, 742), body)
        val layout = readerLayoutSpec(ReaderSettings(), density, LayoutDirection.Ltr, body, context)
        assertEquals(312, layout.textWidth)
        assertEquals(706, layout.textHeight)
    }

    @Test fun independentPhysicalMarginsUseDpAndDoNotSwapForRightToLeftText() {
        val settings = ReaderSettings(marginLeft = 3f, marginRight = 7f, marginTop = 5f, marginBottom = 11f)
        val padding = readerContentPadding(settings)
        assertEquals(3.dp, padding.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(7.dp, padding.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(5.dp, padding.calculateTopPadding())
        assertEquals(11.dp, padding.calculateBottomPadding())
        val layout = readerLayoutSpec(settings, Density(2.5f, 1.8f), LayoutDirection.Rtl, IntSize(400, 600), context)
        assertEquals(374, layout.textWidth)
        assertEquals(559, layout.textHeight)
    }

    @Test fun smallViewportsAndInvalidSettingsNeverCreateNegativeLayoutDimensions() {
        val small = readerLayoutSpec(ReaderSettings(marginLeft = 64f, marginRight = 64f, marginTop = 64f, marginBottom = 64f),
            Density(4f), LayoutDirection.Ltr, IntSize(30, 20), context)
        assertEquals(1, small.textWidth)
        assertEquals(1, small.textHeight)
        val padding = readerContentPadding(ReaderSettings(marginLeft = Float.NaN, marginRight = -1f,
            marginTop = Float.POSITIVE_INFINITY, marginBottom = 80f))
        assertEquals(24.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(18.dp, padding.calculateTopPadding())
        assertEquals(64.dp, padding.calculateBottomPadding())
    }
}
