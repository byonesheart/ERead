package com.eyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.eyue.reader.data.AppUpdateMirror
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.UpdateDownload
import com.eyue.reader.data.UpdateDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutUpdateRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private fun update() = AppUpdateResult(true, "next", 99, "核心功能更新",
        "https://github.com/byonesheart/ERead/releases/download/vNext/ERead.apk",
        "https://github.com/byonesheart/ERead/releases", mirrors = listOf(
            AppUpdateMirror("百度网盘", "https://pan.baidu.com/s/example", "READ")))

    @Test fun startupResultsShowTheirDownloadLinksWithoutAnotherCheck() {
        var checks = 0
        var download: AppUpdateResult? = null
        val controls = UpdateDownloadControls(null, false, { download = it }, {}, {}, {}, {})
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUpdateDownloadControls provides controls) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AboutIntro(onCheckUpdate = { checks++; update() }, availableUpdate = update())
                    }
                }
            }
        }
        compose.onNodeWithTag("update_github_download").performScrollTo()
            .assertTextEquals("GitHub 下载（需网络支持）").performClick()
        compose.onNodeWithTag("update_mirror_0").performScrollTo().assertTextContains("提取码 READ", substring = true)
        compose.runOnIdle { assertEquals(0, checks); assertEquals(update(), download) }
    }

    @Test fun aLateStartupResultAppearsOnTheAlreadyOpenAboutPage() {
        var result by mutableStateOf<AppUpdateResult?>(null)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { AboutIntro(availableUpdate = result) }
            }
        }
        compose.onNodeWithTag("update_github_download").assertDoesNotExist()
        compose.runOnIdle { result = update() }
        compose.onNodeWithTag("update_github_download").performScrollTo().assertIsDisplayed()
    }

    @Test fun previewResultsAreClearlyLabelledAndProvideTheirGithubDownload() {
        val preview = update().copy(versionName = "next-beta.1", prerelease = true)
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { AboutIntro(availableUpdate = preview) }
        } }
        compose.onNodeWithText("发现测试版本 next-beta.1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("update_github_download").assertExists()
        compose.onNodeWithTag("update_mirror_0").assertDoesNotExist()
    }

    @Test fun downloadPanelUpdatesTheBarAndKeepsReceivedBytesVisibleWhileRetrying() {
        val update = update()
        val task = UpdateDownload(UUID.randomUUID(), UUID.randomUUID(), update.versionCode, update.versionName,
            requireNotNull(update.downloadUrl))
        val total = 8L * 1024 * 1024
        fun status(bytes: Long, total: Long, state: WorkInfo.State = WorkInfo.State.RUNNING, attempts: Int = 0) =
            UpdateDownloadStatus(task, WorkInfo(task.workId, state, emptySet(),
                progress = workDataOf("bytes" to bytes, "total" to total), runAttemptCount = attempts), bytes)
        var state by mutableStateOf(status(total / 4, total))
        compose.setContent {
            MaterialTheme { UpdateDownloadPanel(UpdateDownloadControls(state, false, {}, {}, {}, {}, {})) }
        }
        compose.onNodeWithTag("update_download_progress").assertIsDisplayed()
        compose.onNodeWithTag("update_download_bytes").assertTextContains("25%", substring = true)
        compose.runOnIdle { state = status(total / 2, total) }
        compose.onNodeWithTag("update_download_bytes").assertTextContains("50%", substring = true)
        compose.runOnIdle { state = status(total / 2, -1, WorkInfo.State.ENQUEUED, attempts = 1) }
        compose.onNodeWithText("等待网络或自动重试", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("update_download_bytes").assertIsDisplayed()
        compose.runOnIdle { state = status(total / 2, -1) }
        compose.onNodeWithTag("update_download_progress").assertIsDisplayed()
        compose.onNodeWithTag("update_download_bytes").assertIsDisplayed()
    }
}
