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

    @Test fun globalDialogueColorStartsOffAndSavesTheChosenColor() {
        var state by mutableStateOf(LibraryState())
        compose.setContent { MaterialTheme { SettingsScreen(state, {}, {}, {}, {}, onDialogueColor = { enabled, color ->
            state = state.copy(dialogueColorEnabled = enabled, dialogueColor = color)
        }) } }
        compose.onNodeWithTag("settings_dialogue_color").assertTextContains("已关闭").performClick()
        compose.onNodeWithTag("dialogue_color_enabled").assertIsOff().performClick()
        compose.onNodeWithContentDescription("紫色").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithTag("settings_dialogue_color").assertTextContains("已开启")
        compose.runOnIdle { assertEquals(0xFF8A5DA8L, state.dialogueColor) }
    }

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

    @Test fun sourceScanningIsPerBookAndAutomaticReloadRequiresItsWarning() {
        val book = Book(id = "selected", title = "书籍", mode = LibraryMode.NOVEL, format = BookFormat.TXT, chapters = emptyList())
        var selected by mutableStateOf(emptySet<String>())
        var auto by mutableStateOf(false)
        var granted = emptySet<String>()
        var folders = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(books = listOf(book), scannedBookIds = selected, autoReloadSources = auto),
            {}, {}, {}, {}, sourceAuthorizations = mapOf(book.id to false), onScannedBooks = { selected = it },
            onAutoReload = { auto = it }, onGrantScanBooks = { granted = it }, onGrantScanFolder = { folders++ }) } }
        compose.onNodeWithTag("settings_scan_local_changes").performScrollTo().performClick()
        compose.onNodeWithTag("source_access_selected").assertTextEquals("未授权")
        compose.onNodeWithTag("source_scan_book_selected").performClick()
        compose.runOnIdle { assertEquals(setOf(book.id), selected) }
        compose.onNodeWithTag("source_grant_batch").performClick()
        compose.runOnIdle { assertEquals(selected, granted) }
        compose.onNodeWithTag("source_grant_folder").performClick()
        compose.runOnIdle { assertEquals(1, folders) }
        compose.onNodeWithTag("source_auto_reload").performClick()
        compose.runOnIdle { assertFalse(auto) }
        compose.onNodeWithTag("source_reload_warning").assertExists()
        compose.onNodeWithTag("source_reload_confirm").performClick()
        compose.runOnIdle { assertTrue(auto) }
    }

    @Test fun unavailableTranslationIsHiddenAndMeteredBackupDefaultsFollowTheSetting() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings_translation").assertDoesNotExist()
    }
    @Test fun scanSwitchDefaultsToManualAndGatesAutomaticReload() {
        var state by mutableStateOf(LibraryState())
        var scans = 0
        compose.setContent { MaterialTheme { SettingsScreen(state, {}, {}, {}, {},
            onScanLocalChanges = { enabled -> state = state.copy(scanLocalChanges = enabled,
                autoReloadSources = enabled && state.autoReloadSources) },
            onAutoReload = { state = state.copy(autoReloadSources = it) }, onScanSourcesNow = { scans++ }) } }
        compose.onNodeWithTag("settings_scan_local_changes").performScrollTo().performClick()
        compose.onNodeWithTag("source_scan_enabled").assertIsOn()
        compose.onNodeWithTag("source_auto_reload").assertIsOff()
        compose.onNodeWithTag("source_scan_enabled").performClick()
        compose.onNodeWithTag("source_auto_reload").assertIsNotEnabled()
        compose.onNodeWithTag("source_scan_now").assertDoesNotExist()
        compose.onNodeWithTag("source_scan_enabled").performClick()
        compose.runOnIdle { assertEquals(SourceScanMode.MANUAL, state.sourceScanMode); assertEquals(0, scans) }
        compose.onNodeWithTag("source_scan_now").performClick()
        compose.runOnIdle { assertEquals(1, scans) }
        compose.onNodeWithTag("source_auto_reload").performClick()
        compose.onNodeWithTag("source_reload_confirm").performClick()
        compose.runOnIdle { assertEquals(SourceScanMode.AUTO, state.sourceScanMode) }
    }

    @Test fun meteredBackupRequiresConsentAndCanStartPrechecked() {
        var confirmed = false
        compose.setContent { MaterialTheme { MeteredBackupDialog(true, {}, { confirmed = it }) } }
        compose.onNodeWithTag("metered_backup_allowed").assertIsOn()
        compose.onNodeWithTag("metered_backup_confirm").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun meteredBackupWithoutTheGlobalPermissionStartsUnchecked() {
        var confirmed = false
        compose.setContent { MaterialTheme { MeteredBackupDialog(false, {}, { confirmed = it }) } }
        compose.onNodeWithTag("metered_backup_allowed").assertIsOff()
        compose.onNodeWithTag("metered_backup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("metered_backup_allowed").performClick()
        compose.onNodeWithTag("metered_backup_confirm").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
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
