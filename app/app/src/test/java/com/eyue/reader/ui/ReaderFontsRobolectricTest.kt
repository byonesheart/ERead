package com.eyue.reader.ui

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

    @Test fun fontSwitchKeepsRequestedSizeSpacingAndViewport() {
        val original = ReaderSettings(fontSize = 21f, lineHeight = 1.9f, letterSpacing = .7f)
        val specs = listOf("跟随系统", "系统宋体", "系统黑体").map { name ->
            readerLayoutSpec(original.copy(fontName = name), density, LayoutDirection.Ltr, IntSize(320, 640))
        }
        specs.forEach { spec ->
            assertEquals(21.sp, spec.textStyle.fontSize)
            assertEquals((21f * 1.9f).sp, spec.textStyle.lineHeight)
            assertEquals(.7.sp, spec.textStyle.letterSpacing)
            assertEquals(specs.first().textWidth, spec.textWidth)
            assertEquals(specs.first().textHeight, spec.textHeight)
        }
    }

    @Test fun bundledChineseGlyphsKeepTheConfiguredFortyPixelBaselineSpacing() {
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        listOf("系统宋体", "系统黑体").forEach { name ->
            val settings = ReaderSettings(fontName = name, fontSize = 20f, lineHeight = 2f, letterSpacing = 0f)
            val spec = readerLayoutSpec(settings, density, LayoutDirection.Ltr, IntSize(320, 640))
            val layout = measurer.measure(AnnotatedString("山水之间，故事继续。\n安静地阅读。"), spec.textStyle,
                constraints = Constraints(maxWidth = spec.textWidth))
            assertEquals(2, layout.lineCount)
            assertEquals(40f, layout.getLineBaseline(1) - layout.getLineBaseline(0), .5f)
            assertEquals(20f, layout.getBoundingBox(0).width, .5f)
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
