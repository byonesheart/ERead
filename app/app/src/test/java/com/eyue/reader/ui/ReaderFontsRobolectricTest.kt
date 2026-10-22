package com.eyue.reader.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.eyue.reader.R
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFontsRobolectricTest {
    private val density = Density(1f, 1f)
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun songAndHeiResolveToSeparateBundledFamiliesInsteadOfOemAliases() {
        val song = ReaderSettings(fontName = "系统宋体")
        val hei = ReaderSettings(fontName = "系统黑体")
        assertEquals(R.font.reader_song, readerBundledFontResource(song))
        assertEquals(R.font.reader_hei, readerBundledFontResource(hei))
        assertNotEquals(readerFontFamily(song), readerFontFamily(hei))
        listOf(song, hei).forEach { settings ->
            val family = readerFontFamily(settings)
            assertNotEquals(FontFamily.Default, family)
            assertNotEquals(FontFamily.Serif, family)
            assertNotEquals(FontFamily.SansSerif, family)
        }
    }

    @Test fun fontSwitchMatchesVisibleChineseSizeAndCellPitchWithoutChangingLineHeightOrViewport() {
        val original = ReaderSettings(fontSize = 21f, lineHeight = 1.9f, letterSpacing = .7f)
        val specs = listOf("跟随系统", "系统宋体", "系统黑体").map { name ->
            readerLayoutSpec(original.copy(fontName = name), density, LayoutDirection.Ltr, IntSize(320, 640), context)
        }
        val glyphs = "国田中日月山水书读"
        fun metrics(typeface: Typeface, size: Float, tracking: Float): Pair<Float, Float> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size * 10f }
            val bounds = Rect()
            val ink = glyphs.indices.sumOf { index ->
                paint.getTextBounds(glyphs, index, index + 1, bounds)
                maxOf(bounds.width(), bounds.height()).toDouble()
            }.toFloat() / glyphs.length / 10f
            return ink to (paint.measureText(glyphs) / glyphs.length / 10f + tracking)
        }
        val reference = metrics(Typeface.DEFAULT, 21f, .7f)
        specs.forEachIndexed { index, spec ->
            assertEquals((21f * 1.9f).sp, spec.textStyle.lineHeight)
            assertEquals(specs.first().textWidth, spec.textWidth)
            assertEquals(specs.first().textHeight, spec.textHeight)
            val typeface = when (index) { 1 -> context.resources.getFont(R.font.reader_song)
                2 -> context.resources.getFont(R.font.reader_hei); else -> Typeface.DEFAULT }
            val measured = metrics(typeface, spec.textStyle.fontSize.value, spec.textStyle.letterSpacing.value)
            assertEquals(reference.first, measured.first, .2f)
            assertEquals(reference.second, measured.second, .1f)
        }
        assertEquals(21.sp, specs.first().textStyle.fontSize)
        assertEquals(.7.sp, specs.first().textStyle.letterSpacing)
        System.getenv("EREAD_TEST_ARTIFACTS")?.takeIf(String::isNotBlank)?.let { directory ->
            val bitmap = Bitmap.createBitmap(1000, 330, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
                specs.forEachIndexed { index, spec ->
                    paint.typeface = when (index) { 1 -> context.resources.getFont(R.font.reader_song)
                        2 -> context.resources.getFont(R.font.reader_hei); else -> Typeface.DEFAULT }
                    paint.textSize = spec.textStyle.fontSize.value * 3f
                    var x = 16f
                    "山水之间，故事继续。".forEach { character ->
                        val glyph = character.toString()
                        canvas.drawText(glyph, x, 85f + index * 105f, paint)
                        x += paint.measureText(glyph) + spec.textStyle.letterSpacing.value * 3f
                    }
                }
                File(directory, "font-calibration-system-song-hei.png").apply { parentFile?.mkdirs() }.outputStream()
                    .use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun bundledChineseGlyphsKeepTheConfiguredFortyPixelBaselineSpacing() {
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        listOf("系统宋体", "系统黑体").forEach { name ->
            val settings = ReaderSettings(fontName = name, fontSize = 20f, lineHeight = 2f, letterSpacing = 0f)
            val spec = readerLayoutSpec(settings, density, LayoutDirection.Ltr, IntSize(320, 640), context)
            val layout = measurer.measure(AnnotatedString("山水之间，故事继续。\n安静地阅读。"), spec.textStyle,
                constraints = Constraints(maxWidth = spec.textWidth))
            assertEquals(2, layout.lineCount)
            assertEquals(40f, layout.getLineBaseline(1) - layout.getLineBaseline(0), .5f)
            // At mdpi Android can quantize the hinted glyph advance by a physical pixel.
            // Cursor-to-cursor distance includes tracking, unlike a glyph's bounding box.
            assertEquals("$name ${spec.textStyle}", 20f,
                layout.getHorizontalPosition(2, true) - layout.getHorizontalPosition(1, true), 1f)
        }
    }

    @Test fun defaultTrackingIsRoomierAndImportedFontsAreNotResized() {
        assertEquals(.5f, ReaderSettings().letterSpacing, 0f)
        assertEquals(ReaderFontCalibration(), readerFontCalibration(context, ReaderSettings(fontPath = "/custom.otf")))
        listOf("系统宋体", "系统黑体").forEach { name ->
            val value = readerFontCalibration(context, ReaderSettings(fontName = name))
            assertTrue(value.sizeScale in .8f..1.15f)
            assertEquals(value, readerFontCalibration(context, ReaderSettings(fontName = name)))
        }
    }

    @Test fun systemAndImportedFontsKeepTheirSeparateResolutionPaths() {
        val system = ReaderSettings(fontName = "跟随系统")
        assertNull(readerBundledFontResource(system))
        assertEquals(FontFamily.Default, readerFontFamily(system))
        val imported = File(context.cacheDir, "font-priority.otf")
        context.resources.openRawResource(R.font.reader_hei).use { input -> imported.outputStream().use { output -> input.copyTo(output) } }
        val settings = ReaderSettings(fontName = "系统宋体", fontPath = imported.absolutePath)
        assertNull(readerBundledFontResource(settings))
        assertNotEquals(readerFontFamily(settings.copy(fontPath = null)), readerFontFamily(settings))
    }

    @Test fun cachePlatformUsesTheSameBundledTypefaceAsTheLayoutFamily() {
        val song = ReaderSettings(fontName = "系统宋体")
        val hei = ReaderSettings(fontName = "系统黑体")
        assertNotEquals(readerLayoutPlatform(context, song), readerLayoutPlatform(context, hei))
        assertNotEquals(readerLayoutPlatform(context, hei), readerLayoutPlatform(context, ReaderSettings()))
    }
}
