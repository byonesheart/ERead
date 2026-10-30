package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSliderCommitTest {
    @get:Rule val compose = createComposeRule()
    private var settings by mutableStateOf(ReaderSettings())
    private var commits = 0

    @Before fun setUp() {
        settings = ReaderSettings(brightness = .4f)
        commits = 0
    }

    @Test fun compactFontSliderPreviewsWhileDraggingAndCommitsOnceOnRelease() {
        compactSettings()
        val initialSize = settings.fontSize
        val slider = beginDrag("阅读字号")
        compose.runOnIdle {
            assertEquals(0, commits)
            assertEquals(initialSize, settings.fontSize)
        }
        compose.onNodeWithText(initialSize.toInt().toString()).assertDoesNotExist()
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, commits)
            assertTrue(settings.fontSize > initialSize)
            assertEquals(.4f, settings.brightness)
        }
    }

    @Test fun brightnessAlsoWaitsUntilTheFingerIsReleased() {
        compactSettings()
        val slider = beginDrag("阅读亮度")
        compose.runOnIdle {
            assertEquals(0, commits)
            assertEquals(.4f, settings.brightness)
            assertEquals(19f, settings.fontSize)
        }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits); assertTrue(settings.brightness > .4f) }
    }

    @Test fun lineAndLetterSpacingEachSubmitOnlyTheirFinalValue() {
        compactSettings()
        val initialLineHeight = settings.lineHeight
        val lineSlider = beginDrag("阅读行间距")
        compose.runOnIdle {
            assertEquals(0, commits)
            assertEquals(initialLineHeight, settings.lineHeight)
        }
        lineSlider.performTouchInput { up() }
        var committedLineHeight = 0f
        compose.runOnIdle {
            assertEquals(1, commits)
            assertTrue(settings.lineHeight > initialLineHeight)
            committedLineHeight = settings.lineHeight
            assertEquals(0f, settings.letterSpacing)
        }
        val letterSlider = beginDrag("阅读字间距")
        compose.runOnIdle {
            assertEquals(1, commits)
            assertEquals(0f, settings.letterSpacing)
        }
        letterSlider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(2, commits)
            assertTrue(settings.letterSpacing > 0f)
            assertEquals(committedLineHeight, settings.lineHeight)
            assertEquals(19f, settings.fontSize)
        }
    }

    @Test fun compactSettingsExposeFontControlsAndImageImportWithoutOpeningAnotherPanel() {
        var imageImports = 0
        compose.setContent {
            MaterialTheme { ReaderCompactSettings(settings, false, ::commit, {}, { imageImports++ }) }
        }
        compose.onNodeWithTag("reader_section_文字与排版").performScrollTo().performClick()
        compose.onNodeWithText("宋体").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("系统宋体", settings.fontName); assertEquals(1, commits) }
        compose.onNodeWithText("加粗").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.bold) }
        compose.onNodeWithTag("reader_section_纸张").performScrollTo().performClick()
        compose.onNodeWithText("导入图片").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, imageImports) }
    }

    @Test fun externalSettingsOnlyExposeSharedPreferencesAndKeepReadingSettingsIntact() {
        compose.setContent {
            MaterialTheme { SettingsScreen(LibraryState(settings = settings), ::commit, {}, {}, {},
                onThemeMode = { commit(settings.copy(nightMode = it == com.eyue.reader.data.ThemeMode.DARK)) }) }
        }
        compose.onNodeWithText("按你的习惯阅读").assertDoesNotExist()
        compose.onNodeWithText("默认字体").assertDoesNotExist()
        compose.onNodeWithText("自定义目录规则").assertDoesNotExist()
        compose.onNodeWithText("朗读语速").assertDoesNotExist()
        compose.onNodeWithText("阅读方式").assertDoesNotExist()
        compose.onNodeWithContentDescription("默认阅读字号").assertDoesNotExist()
        compose.onNodeWithTag("settings_theme").assertIsDisplayed()
        compose.onNodeWithTag("settings_theme_dark").performClick()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertTrue(settings.nightMode)
            assertEquals(19f, settings.fontSize)
            assertEquals(1.85f, settings.lineHeight)
        }
    }

    private fun compactSettings() {
        compose.setContent {
            MaterialTheme { ReaderCompactSettings(settings, false, ::commit, {}, {}) }
        }
    }

    private fun commit(value: ReaderSettings) { commits++; settings = value }

    private fun beginDrag(description: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("reader_section_文字与排版").let {
            if (it.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已收起") it.performScrollTo().performClick()
        }
        val slider = compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
        slider.performTouchInput {
            down(Offset(width * .3f, center.y))
            moveTo(Offset(width * .55f, center.y))
            moveTo(Offset(width * .8f, center.y))
        }
        return slider
    }
}
