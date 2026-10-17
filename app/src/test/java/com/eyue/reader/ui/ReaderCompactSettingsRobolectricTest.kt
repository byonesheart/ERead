package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.ImportedFont
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
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
class ReaderCompactSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactFontChoicesKeepBoldIndependentAndOpenMoreBeforeImporting() {
        var settings by mutableStateOf(ReaderSettings(fontName = "自选字体", fontPath = "/font.ttf",
            fonts = listOf(ImportedFont("自选字体", "/font.ttf"))))
        var moreFonts = false
        var imports = 0
        compose.setContent { MaterialTheme {
            ReaderCompactSettings(settings, false, { settings = it }, { imports++ }, {}, {}, onFonts = { moreFonts = true })
        } }
        compose.onNodeWithText("自选字体").assertDoesNotExist()
        compose.onNodeWithText("等宽字体").assertDoesNotExist()
        compose.onNodeWithText("宋体").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("系统宋体", settings.fontName); assertNull(settings.fontPath) }
        compose.onNodeWithText("加粗").performClick()
        compose.onNodeWithText("黑体").performClick()
        compose.runOnIdle { assertEquals("系统黑体", settings.fontName); assertTrue(settings.bold) }
        compose.onNodeWithText("系统", substring = false).performClick()
        compose.runOnIdle { assertEquals("跟随系统", settings.fontName); assertTrue(settings.bold) }
        compose.onNodeWithTag("reader_more_fonts").performClick()
        compose.runOnIdle { assertTrue(moreFonts); assertEquals(0, imports) }
    }

    @Test fun readingModesAreImmediatelyAvailableForComicsWithoutOpeningMoreSettings() {
        var settings by mutableStateOf(ReaderSettings())
        var openedMore = 0
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, true, { settings = it }, {}, {}, { openedMore++ }) } }
        compose.onNodeWithTag("reader_mode_paged").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReadingMode.PAGED, settings.readingMode) }
        compose.onNodeWithTag("reader_mode_vertical_paged").performClick()
        compose.runOnIdle { assertEquals(ReadingMode.VERTICAL_PAGED, settings.readingMode) }
        compose.onNodeWithTag("reader_mode_scroll").performClick()
        compose.runOnIdle { assertEquals(ReadingMode.SCROLL, settings.readingMode); assertEquals(0, openedMore) }
    }

    @Test fun additionalFontDialogListsImportedFontsAndOwnsTheImportAction() {
        var settings by mutableStateOf(ReaderSettings(fonts = listOf(ImportedFont("自选字体", "/font.ttf"))))
        var imports = 0
        compose.setContent { MaterialTheme { ReaderFontSettings(settings, { settings = it }, { imports++ }, {}) } }
        compose.onNodeWithText("自选字体").performClick()
        compose.runOnIdle { assertEquals("/font.ttf", settings.fontPath) }
        compose.onNodeWithText("等宽字体").performClick()
        compose.runOnIdle { assertNull(settings.fontPath); assertEquals("等宽字体", settings.fontName) }
        compose.onNodeWithText("导入 TTF / OTF 字体").performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText("跟随系统").assertDoesNotExist()
    }

    @Test fun resetDefaultsNeedsConfirmationAndCancelKeepsCurrentSettings() {
        var resets = 0
        compose.setContent { MaterialTheme { ReaderMoreSettings(ReaderSettings(fontSize = 28f), {}, {}, {}, {},
            onResetDefaults = { resets++ }) } }
        compose.onNodeWithTag("reader_reset_defaults").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithTag("reader_reset_defaults").performScrollTo().performClick()
        compose.onNodeWithTag("reader_reset_defaults_confirm").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithTag("reader_reset_defaults_confirm").assertDoesNotExist()
    }
}
