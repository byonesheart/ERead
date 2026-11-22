package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.cloud.CloudCredentials
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
class S3BackupSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configurationValidatesTestsAndSavesWithoutExposingTheSecret() {
        var saved: CloudCredentials? = null
        var tested: CloudCredentials? = null
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(false) }
            S3BackupSettings(emptyList(), false, open, { open = it }, { credentials, _ -> saved = credentials },
                { tested = it; "S3 连接成功：已验证读写权限" }, {})
        } }
        compose.onNodeWithTag("cloud_open_s3").performClick()
        compose.onNodeWithTag("s3_save").assertIsNotEnabled()
        for ((tag, value) in listOf("endpoint" to "https://storage.example.com", "bucket" to "reader-backups",
            "access" to "example-access", "secret" to "example-secret")) {
            compose.onNodeWithTag("s3_$tag").performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithTag("s3_test").performScrollTo().performClick()
        compose.onNodeWithTag("s3_test_result").performScrollTo().assertTextContains("S3 连接成功", substring = true)
        compose.runOnIdle { assertNotNull(tested); assertNull(saved) }
        compose.onNodeWithTag("s3_save").performClick()
        compose.runOnIdle {
            assertEquals("reader-backups", saved!!.s3!!.bucket)
            assertEquals("us-east-1", saved!!.s3!!.region)
            assertEquals("example-secret", saved!!.password)
            assertFalse(saved.toString().contains("example-secret"))
        }
        compose.onNodeWithTag("s3_save").assertDoesNotExist()
    }

    @Test fun testFailureIsVisibleAndDoesNotSaveAConfiguration() {
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(true) }
            S3BackupSettings(emptyList(), false, open, { open = it }, { _, _ -> fail("Unexpected save") },
                { error("S3 权限不足") }, {})
        } }
        for ((tag, value) in listOf("endpoint" to "https://storage.example.com", "bucket" to "reader-backups",
            "access" to "example-access", "secret" to "example-secret")) {
            compose.onNodeWithTag("s3_$tag").performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithTag("s3_test").performScrollTo().performClick()
        compose.onNodeWithTag("s3_test_result").performScrollTo().assertTextEquals("S3 权限不足")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("s3_save").assertDoesNotExist()
    }
}
