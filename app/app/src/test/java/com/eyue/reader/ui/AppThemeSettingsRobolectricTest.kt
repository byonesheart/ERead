package com.eyue.reader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppThemeSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingPixelUpdatesTheWholeInterfaceWithoutOverwritingReaderPreferences() {
        val original = LibraryState(settings = ReaderSettings(fontSize = 25f, background = ReaderBackground.GREEN),
            comicSettings = ReaderSettings(fontSize = 16f, paragraphSpacing = 1.4f))
        var state by mutableStateOf(original)
        var shape: Shape? = null
        var colors: ColorScheme? = null
        compose.setContent {
            EReadTheme(dark = false, eink = false, style = state.appTheme) {
                val panelShape = appShape(24.dp)
                val themeColors = MaterialTheme.colorScheme
                SideEffect { shape = panelShape; colors = themeColors }
                AppThemeSettingsPage(state.appTheme) { state = state.copy(appTheme = it) }
            }
        }
        compose.onNodeWithTag("app_theme_classic").assertIsSelected()
        compose.runOnIdle {
            assertEquals(LightAppColors.background, colors!!.background)
            assertEquals(LightAppColors.primary, colors!!.primary)
            assertTrue(shape!!.createOutline(Size(200f, 100f), LayoutDirection.Ltr, Density(1f)) is Outline.Rounded)
        }
        compose.onNodeWithTag("app_theme_pixel").performClick().assertIsSelected()
        compose.onNodeWithTag("app_theme_classic").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(PixelLightAppColors.background, colors!!.background)
            assertEquals(PixelLightAppColors.primary, colors!!.primary)
            assertTrue(shape!!.createOutline(Size(200f, 100f), LayoutDirection.Ltr, Density(1f)) is Outline.Generic)
            assertEquals(original.settings, state.settings)
            assertEquals(original.comicSettings, state.comicSettings)
        }
    }

    @Test fun pixelHasLightAndDarkPalettesWhileEinkKeepsItsMonochromeContrast() {
        for (dark in listOf(false, true)) {
            val pixel = appColors(AppThemeStyle.PIXEL, dark)
            assertNotEquals(appColors(AppThemeStyle.CLASSIC, dark).background, pixel.background)
            assertNotEquals(pixel.surface, pixel.onSurface)
            assertEquals(if (dark) PixelDarkAppColors else PixelLightAppColors, pixel)
            val ink = appColors(AppThemeStyle.PIXEL, dark, eink = true)
            assertEquals(einkAppColors(dark).primary, ink.primary)
            assertEquals(einkAppColors(dark).surface, ink.surface)
            assertEquals(if (dark) Color.Black else Color.White, ink.background)
            assertEquals(if (dark) Color.White else Color.Black, ink.onBackground)
        }
    }
}
