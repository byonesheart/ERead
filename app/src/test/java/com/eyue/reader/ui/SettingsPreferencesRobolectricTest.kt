package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import com.eyue.reader.BuildConfig
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
class SettingsPreferencesRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactHomeOpensSecondaryPagesAndThemeUsesThreeChoices() {
        var selected: ThemeMode? = null
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}, onThemeMode = { selected = it }) } }
        compose.onNodeWithTag("settings_theme_light").assertIsDisplayed()
        compose.onNodeWithTag("settings_theme_dark").assertIsDisplayed()
        compose.onNodeWithTag("settings_theme_system").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.SYSTEM, selected) }
        compose.onNodeWithTag("settings_speech").performScrollTo().performClick()
        compose.onNodeWithText("当前使用：").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_feedback").performScrollTo().performClick()
        compose.onNodeWithTag("settings_theme").assertDoesNotExist()
        compose.onNodeWithText("问题反馈").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithTag("settings_about_privacy").performScrollTo().performClick()
        compose.onNodeWithText("隐私政策").assertIsDisplayed()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").assertDoesNotExist()
    }

    @Test fun cacheActionsRequireConfirmationAndDispatchSeparately() {
        var history = 0; var audio = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, { history++ }, onClearAiSpeechCaches = { audio++ }) } }
        compose.onNodeWithTag("settings_cache").performScrollTo().performClick()
        compose.onNodeWithTag("settings_clear_history").performClick()
        compose.runOnIdle { assertEquals(0, history) }
        compose.onNodeWithText("清空").performClick()
        compose.runOnIdle { assertEquals(1, history); assertEquals(0, audio) }
        compose.onNodeWithTag("settings_clear_ai_speech").performClick()
        compose.onNodeWithTag("settings_clear_ai_speech_confirm").performClick()
        compose.runOnIdle { assertEquals(1, audio) }
    }

    @Test fun webDavTestsTheUnsavedDraftInsideConfigurationWithoutSavingIt() {
        var tested = ""; var saves = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {},
            onSaveCloudAccount = { _, _, _ -> saves++ }, onTestCloudDraftConnection = { account, password, server ->
                tested = "$account|$password|$server"; "WebDAV 连接成功"
            }) } }
        compose.onNodeWithTag("settings_cloud").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_test_connection").assertDoesNotExist()
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_server").performScrollTo().performTextReplacement("https://dav.example.org/files/")
        compose.onNodeWithTag("cloud_account").performScrollTo().performTextReplacement("reader")
        compose.onNodeWithTag("cloud_password").performScrollTo().performTextReplacement("test-password")
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_test_result").performScrollTo().assertTextContains("WebDAV 连接成功")
        compose.runOnIdle { assertEquals("reader|test-password|https://dav.example.org/files/", tested); assertEquals(0, saves) }
    }
}
