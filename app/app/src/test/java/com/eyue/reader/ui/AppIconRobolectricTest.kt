package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val host = AtomicReference<View>()

    @Test fun pixelGlyphHasActualSixteenCellEdgesAndSwitchingBackRestoresTheClassicGlyph() {
        var theme by mutableStateOf(AppThemeStyle.CLASSIC)
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, false, theme) {
                Box(Modifier.background(Color.White)) {
                    AppIcon(Icons.Outlined.Search, "搜索", Modifier.size(96.dp).testTag("glyph"), Color.Black)
                }
            }
        }
        compose.onNodeWithContentDescription("搜索").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        val classic = capture("glyph", "icon-classic")
        compose.runOnIdle { theme = AppThemeStyle.PIXEL }
        val pixel = capture("glyph", "icon-pixel")
        assertFalse("Pixel theme must alter the actual glyph, not just its tint", pixels(classic).contentEquals(pixels(pixel)))
        assertTrue(pixels(pixel).any { it != android.graphics.Color.WHITE })
        assertPixelCells(pixel)
        compose.runOnIdle { theme = AppThemeStyle.CLASSIC }
        val restored = capture("glyph")
        assertArrayEquals(pixels(classic), pixels(restored))
        classic.recycle(); pixel.recycle(); restored.recycle()
    }

    @Test fun tintChangesDoNotLeaveStaleColorsInCachedPixelGlyphs() {
        var color by mutableStateOf(Color.Red)
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, false, AppThemeStyle.PIXEL) {
                Box(Modifier.background(Color.White)) {
                    AppIcon(Icons.Outlined.Search, "搜索", Modifier.size(96.dp).testTag("glyph"), color)
                }
            }
        }
        val red = capture("glyph")
        compose.runOnIdle { color = Color.Blue }
        val blue = capture("glyph")
        assertTrue(pixels(red).any { android.graphics.Color.red(it) > android.graphics.Color.blue(it) + 50 })
        assertTrue(pixels(blue).any { android.graphics.Color.blue(it) > android.graphics.Color.red(it) + 50 })
        assertTrue(pixels(blue).none { android.graphics.Color.red(it) > android.graphics.Color.blue(it) + 50 })
        assertPixelCells(blue)
        red.recycle(); blue.recycle()
    }

    @Test fun autoMirroredPixelArrowsStillFollowTheLayoutDirection() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, false, AppThemeStyle.PIXEL) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.background(Color.White)) {
                        AppIcon(Icons.AutoMirrored.Outlined.ArrowForward, "前进", Modifier.size(96.dp).testTag("glyph"), Color.Black)
                    }
                }
            }
        }
        val ltr = capture("glyph")
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        val rtl = capture("glyph")
        assertFalse(pixels(ltr).contentEquals(pixels(rtl)))
        for (y in 0 until ltr.height) for (x in 0 until ltr.width) {
            assertEquals(ltr.getPixel(x, y), rtl.getPixel(rtl.width - x - 1, y))
        }
        ltr.recycle(); rtl.recycle()
    }

    @Test fun actualHomeAndSpeechControlsKeepTheirActionsInDarkAndEinkPixelThemes() {
        var dark by mutableStateOf(false)
        var eink by mutableStateOf(false)
        var imports = 0
        var notes = 0
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(dark, eink, AppThemeStyle.PIXEL) {
                Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background).testTag("controls")) {
                    ModeTabs(LibraryMode.NOVEL, {}, { notes++ }, onImport = { imports++ })
                    ReaderSpeechStatusIcon(true, "本章语音已缓存", Modifier.padding(16.dp).testTag("speech"))
                }
            }
        }
        compose.onNodeWithTag("top_import").performClick()
        compose.onNodeWithTag("notebook_entry").performClick()
        compose.runOnIdle { assertEquals(1, imports); assertEquals(1, notes) }
        compose.onNodeWithContentDescription("本章语音已缓存").assertIsDisplayed()
        capture("controls", "pixel-controls-light").recycle()
        compose.runOnIdle { dark = true }
        capture("controls", "pixel-controls-dark").recycle()
        compose.runOnIdle { eink = true }
        capture("controls", "pixel-controls-eink").recycle()
        compose.onNodeWithTag("top_import").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, imports) }
    }

    private fun capture(tag: String, artifact: String? = null): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.runOnIdle {
            val view = host.get()
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
                .also { if (it !== full) full.recycle() }
        }
        if (artifact != null) System.getenv("EREAD_TEST_ARTIFACTS")?.let { folder ->
            File(folder).mkdirs()
            File(folder, "$artifact.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        return bitmap
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertPixelCells(bitmap: Bitmap) {
        assertEquals(96, bitmap.width); assertEquals(96, bitmap.height)
        for (row in 0 until 16) for (column in 0 until 16) {
            val first = bitmap.getPixel(column * 6, row * 6)
            for (y in 0 until 6) for (x in 0 until 6) {
                assertEquals("Pixel glyph contains a smoothed cell", first, bitmap.getPixel(column * 6 + x, row * 6 + y))
            }
        }
    }
}
