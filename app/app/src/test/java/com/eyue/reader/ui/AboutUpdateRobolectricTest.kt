package com.eyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.AppUpdateMirror
import com.eyue.reader.data.AppUpdateResult
import org.junit.Assert.assertEquals
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
class AboutUpdateRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private fun update() = AppUpdateResult(true, "next", 99, "核心功能更新", null,
        "https://github.com/byonesheart/ERead/releases", mirrors = listOf(
            AppUpdateMirror("百度网盘", "https://pan.baidu.com/s/example", "READ")))

    @Test fun startupResultsShowTheirDownloadLinksWithoutAnotherCheck() {
        var checks = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AboutIntro(onCheckUpdate = { checks++; update() }, availableUpdate = update())
                }
            }
        }
        compose.onNodeWithTag("update_github_download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("update_mirror_0").performScrollTo().assertTextContains("提取码 READ", substring = true)
        compose.runOnIdle { assertEquals(0, checks) }
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
}
