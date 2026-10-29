package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.ThemeMode
import com.eyue.reader.BuildConfig
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.AppUpdateMirror
import com.eyue.reader.data.cloud.DEFAULT_WEBDAV_SERVER
import com.eyue.reader.data.cloud.CloudOperationProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationRobolectricTest {
    @Test fun moreProjectsOpensTheAuthorsWebsiteInTheBrowser() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("settings_more_projects").performScrollTo().performClick()
        val context = RuntimeEnvironment.getApplication()
        val intent = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertEquals("https://byonesheart.beer", intent.data.toString())
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
    }
    @get:Rule val compose = createComposeRule()

    @Test fun updateNotesOpenASecondaryPageAndNewVersionsOfferCloudDownloads() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}, onCheckUpdate = {
            AppUpdateResult(true, "next", 99, "本次核心更新", null, "https://github.com/byonesheart/ERead/releases",
                mirrors = listOf(AppUpdateMirror("百度网盘", "https://pan.baidu.com/s/example", "READ"),
                    AppUpdateMirror("夸克网盘", "https://pan.quark.cn/s/example", "Jhr4")))
        }) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("settings_current_changelog").assertDoesNotExist()
        compose.onNodeWithTag("settings_open_changelog").performScrollTo().performClick()
        compose.onNodeWithTag("settings_current_changelog").assertExists()
        compose.onNodeWithText("更多功能请自行体验。").assertExists()
        compose.onNodeWithTag("settings_history_changelog").performScrollTo().performClick()
        compose.onNodeWithTag("legal_document_changelog").assertExists()
        compose.onNodeWithText("1.11.13", substring = false).assertExists()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_check_update").performScrollTo().performClick()
        compose.onNodeWithTag("update_github_download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("update_mirror_0").performScrollTo().assertTextContains("提取码 READ", substring = true)
        compose.onNodeWithTag("update_mirror_1").performScrollTo().assertTextContains("提取码 Jhr4", substring = true)
    }

    @Test fun mainSettingsUseCompactEntriesWithInlineDisplayChoicesWithoutServiceForms() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}) } }
        listOf("theme", "speech", "cloud", "cache", "feedback", "support", "about").forEach {
            compose.onNodeWithTag("settings_$it").assertExists()
        }
        compose.onNodeWithTag("speech_engine_toggle").assertDoesNotExist()
        compose.onNodeWithTag("cloud_open_account").assertDoesNotExist()
        compose.onNodeWithTag("settings_clear_history").assertDoesNotExist()
        compose.onNodeWithText("使用说明").assertDoesNotExist()
        compose.onNodeWithText("E阅  1.7.7").assertDoesNotExist()
        compose.onNodeWithTag("settings_subpage_header").assertDoesNotExist()
        compose.onNodeWithTag("settings_eink_mode").assertExists()
        compose.onNodeWithTag("settings_tablet_dual_page").assertExists()
    }

    @Test fun themeOffersLightDarkAndSystemAndOnlyCommitsTheSelectedChoice() {
        val chosen = mutableListOf<ThemeMode>()
        compose.setContent {
            var state by remember { mutableStateOf(LibraryState()) }
            MaterialTheme { SettingsScreen(state, {}, {}, {}, {}, onThemeMode = { chosen += it; state = state.copy(themeMode = it) }) }
        }
        listOf("light", "dark", "system").forEach { compose.onNodeWithTag("settings_theme_$it").assertIsDisplayed() }
        compose.runOnIdle { assertTrue(chosen.isEmpty()) }
        compose.onNodeWithTag("settings_theme_dark").performClick()
        compose.runOnIdle { assertEquals(listOf(ThemeMode.DARK), chosen) }
        compose.onNodeWithTag("settings_theme_dark").assertIsSelected()
        compose.onNodeWithTag("settings_theme_system").performClick()
        compose.runOnIdle { assertEquals(listOf(ThemeMode.DARK, ThemeMode.SYSTEM), chosen) }
        compose.onNodeWithTag("settings_theme_system").assertIsSelected()
    }

    @Test fun speechAndCloudUseSubpagesWithBackNavigation() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings_speech").performScrollTo().performClick()
        compose.onNodeWithTag("speech_engine_toggle").assertIsDisplayed()
        compose.onNodeWithTag("settings_theme").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_cloud").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_open_account").assertIsDisplayed()
        compose.onNodeWithTag("cloud_test_connection").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_theme").assertIsDisplayed()
    }

    @Test fun cloudRestorationShowsLiveProgressAndTheRealApplicationDownloadDirectory() {
        var progress by mutableStateOf<CloudOperationProgress?>(null)
        var restores = 0
        compose.setContent { MaterialTheme {
            SettingsScreen(LibraryState(), {}, {}, {}, {}, cloudConfigured = true,
                onRestoreCloudBookInfo = { restores++; progress = CloudOperationProgress("读取云端书籍列表") },
                restoreProgress = progress)
        } }
        compose.onNodeWithTag("settings_cloud").performScrollTo().performClick()
        val expected = File(RuntimeEnvironment.getApplication().filesDir, "books").absolutePath
        compose.onNodeWithTag("cloud_download_directory").performScrollTo().assertTextEquals(expected)
        compose.onNodeWithText("应用内部存储，按书籍分别保存。").assertExists()
        compose.onNodeWithTag("cloud_restore_progress").assertDoesNotExist()
        compose.onNodeWithTag("cloud_restore_info").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_restore_info").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_progress_bar").performScrollTo()
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithTag("cloud_restore_progress_percent").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, restores); progress = CloudOperationProgress("已读取 2 / 4 本书籍", .5f) }
        compose.onNodeWithTag("cloud_restore_progress_percent").performScrollTo().assertTextEquals("50%")
        compose.onNodeWithTag("cloud_restore_progress_bar").assertRangeInfoEquals(ProgressBarRangeInfo(.5f, 0f..1f))
        compose.runOnIdle { progress = null }
        compose.onNodeWithTag("cloud_restore_progress").assertDoesNotExist()
        compose.onNodeWithTag("cloud_restore_info").performScrollTo().assertIsEnabled()
    }

    @Test fun cacheActionsRequireSeparateConfirmationsAndNeverInvokeEachOther() {
        var history = 0
        var speech = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, { history++ }, onClearAiSpeechCaches = { speech++ }) } }
        compose.onNodeWithTag("settings_cache").performScrollTo().performClick()
        compose.onNodeWithTag("settings_clear_ai_speech").performClick()
        compose.onNodeWithTag("settings_clear_ai_speech_dialog").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, speech); assertEquals(0, history) }
        compose.onNodeWithTag("settings_clear_ai_speech").performClick()
        compose.onNodeWithTag("settings_clear_ai_speech_confirm").performClick()
        compose.runOnIdle { assertEquals(1, speech); assertEquals(0, history) }
        compose.onNodeWithTag("settings_clear_history").performClick()
        compose.onNodeWithTag("settings_clear_history_confirm").performClick()
        compose.runOnIdle { assertEquals(1, speech); assertEquals(1, history) }
    }

    @Test fun aboutPoliciesAndAuthorSupportFeedbackPagesContainTheirActualContent() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("更新日志").assertExists()
        compose.onNodeWithTag("settings_check_update").assertIsDisplayed()
        compose.onNodeWithTag("settings_about_privacy").performScrollTo().performClick()
        compose.onNodeWithTag("settings_subpage_header").assertTextContains("隐私政策", substring = true)
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        compose.onNodeWithTag("legal_document_privacy").assertExists()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_about_terms").performScrollTo().performClick()
        compose.onNodeWithTag("settings_subpage_header").assertTextContains("用户协议", substring = true)
        compose.onNodeWithTag("legal_document_terms").assertExists()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_feedback").performScrollTo().performClick()
        compose.onNodeWithTag("settings_subpage_header").assertTextContains("问题反馈", substring = true)
        compose.onNodeWithTag("feedback_qq").assertIsDisplayed()
        compose.onNodeWithTag("feedback_bilibili").assertIsDisplayed()
        compose.onNodeWithTag("feedback_github").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_support").performScrollTo().performClick()
        compose.onNodeWithTag("settings_subpage_header").assertTextContains("支持作者", substring = true)
        compose.onNodeWithTag("support_wechat").assertExists()
    }

    @Test fun deviceChoicesCommitOnlyExplicitChanges() {
        var eink = false
        var dualPage = false
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}, onEinkMode = { eink = it }, onTabletDualPage = { dualPage = it }) } }
        compose.runOnIdle { assertFalse(eink); assertFalse(dualPage) }
        compose.onNodeWithTag("settings_eink_mode").performClick()
        compose.runOnIdle { assertTrue(eink); assertFalse(dualPage) }
        compose.onNodeWithTag("settings_tablet_dual_page").performClick()
        compose.runOnIdle { assertTrue(dualPage) }
    }

    @Test fun manualUpdateReportsUnpublishedInsteadOfClaimingTheInstalledVersionIsLatest() {
        var checks = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}, onCheckUpdate = {
            checks++
            AppUpdateResult(false, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, "仓库尚未发布更新文件", null,
                "https://github.com/byonesheart/ERead/releases", published = false)
        }) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, checks) }
        compose.onNodeWithTag("settings_check_update").performClick()
        compose.onNodeWithText("尚未发布更新信息").assertIsDisplayed()
        compose.onNodeWithText("✓ 当前已是最新版本").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun manualUpdateFailureIsVisibleAndRetryIsAvailable() {
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {}, onCheckUpdate = { error("网络不可用") }) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("settings_check_update").performClick()
        compose.onNodeWithTag("settings_update_error").assertTextContains("网络不可用", substring = true)
        compose.onNodeWithTag("settings_check_update").assertIsEnabled()
    }

    @Test fun webDavTestsTheUnsavedDraftInsideItsConfigurationWithoutSavingIt() {
        val requests = mutableListOf<Triple<String, String, String>>()
        var saves = 0
        compose.setContent { MaterialTheme {
            CloudBackupSettings("", false, false, false, "", null, { _, _, _ -> saves++ }, {}, {}, {}, {},
                onTestDraftConnection = { account, password, server -> requests += Triple(account, password, server); "连接成功" })
        } }
        compose.onNodeWithTag("cloud_test_connection").assertDoesNotExist()
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cloud_server").performTextReplacement("https://files.example.com/dav")
        compose.onNodeWithTag("cloud_account").performTextInput("reader@example.com")
        compose.onNodeWithTag("cloud_password").performTextInput("draft-password")
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_test_result").performScrollTo().assertTextEquals("连接成功")
        compose.runOnIdle {
            assertEquals(listOf(Triple("reader@example.com", "draft-password", "https://files.example.com/dav/")), requests)
            assertEquals(0, saves)
        }
    }

    @Test fun editingWebDavFieldsCancelsTheOldTestAndRejectsItsLateResult() {
        val result = CompletableDeferred<String>()
        var calls = 0
        compose.setContent { MaterialTheme {
            CloudBackupSettings("reader@example.com", true, false, false, "", null, { _, _, _ -> }, {}, {}, {}, {},
                onTestDraftConnection = { _, _, _ -> calls++; withContext(NonCancellable) { result.await() } })
        } }
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_cancel_test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cloud_server").performScrollTo().performTextReplacement("https://other.example.com/dav")
        compose.runOnIdle { result.complete("过期结果") }
        compose.onNodeWithTag("cloud_test_result").assertDoesNotExist()
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun configuredWebDavCanTestItsSavedPasswordAndMeteredBackupIsExplicit() {
        val requests = mutableListOf<Triple<String, String, String>>()
        var restores = 0
        var metered = false
        compose.setContent { MaterialTheme {
            CloudBackupSettings("reader@example.com", true, false, false, "", null, { _, _, _ -> }, {}, {}, { restores++ }, {},
                onAutoBackupMetered = { metered = it }, onTestDraftConnection = { account, password, server ->
                    requests += Triple(account, password, server); "连接成功"
                })
        } }
        compose.runOnIdle { assertFalse(metered); assertEquals(0, restores); assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("cloud_auto_backup_metered").performClick()
        compose.onNodeWithTag("cloud_restore_info").performClick()
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_test_connection").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(metered); assertEquals(1, restores)
            assertEquals(listOf(Triple("reader@example.com", "", DEFAULT_WEBDAV_SERVER)), requests)
        }
    }
}
