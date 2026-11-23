package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.cloud.CloudCredentials
import com.eyue.reader.data.cloud.S3Location
import com.eyue.reader.data.cloud.WebDavProfile
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

    @Test fun providerPickerHidesSavedConfigurationsOutsideAndNameClickOnlyEdits() {
        var selectedForEdit: String? = null
        var created: CloudProvider? = null
        var profiles by mutableStateOf(listOf(
            WebDavProfile("s3", "access", "https://s3.example.com", "S3备用", false, true),
            WebDavProfile("dav", "reader", "https://dav.example.com", "WebDAV主用", true)))
        compose.setContent { MaterialTheme {
            CloudProfileControl(profiles, profiles.any { it.active }, false,
                { id -> profiles = profiles.map { it.copy(active = it.id == id) } },
                { profiles = profiles.map { it.copy(active = false) } }, { selectedForEdit = it.id }, { created = it }, {})
        } }
        compose.onNodeWithTag("cloud_current_profile").assertTextContains("WebDAV主用", substring = true)
        compose.onNodeWithTag("cloud_toggle_s3").assertDoesNotExist()
        compose.onNodeWithTag("cloud_edit_dav").assertDoesNotExist()
        compose.onNodeWithText("S3备用").assertDoesNotExist()
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_provider_S3").performClick()
        compose.onNodeWithTag("cloud_edit_s3").performClick()
        compose.runOnIdle { assertEquals("s3", selectedForEdit); assertTrue(profiles.single { it.id == "dav" }.active) }
        compose.onNodeWithTag("cloud_toggle_s3").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(1, profiles.count { it.active }); assertTrue(profiles.single { it.id == "s3" }.active) }
        compose.onNodeWithTag("cloud_provider_back").performClick()
        compose.onNodeWithTag("cloud_provider_WEBDAV").performClick()
        compose.onNodeWithTag("cloud_toggle_dav").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(1, profiles.count { it.active }); assertTrue(profiles.single { it.id == "dav" }.active) }
        compose.onNodeWithTag("cloud_toggle_dav").performClick()
        compose.runOnIdle { assertTrue(profiles.none { it.active }) }
        compose.onNodeWithTag("cloud_provider_back").performClick()
        compose.onNodeWithTag("cloud_provider_COS").performClick()
        compose.onNodeWithTag("cloud_create_profile").performClick()
        compose.runOnIdle { assertEquals(CloudProvider.COS, created) }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("cloud_current_profile").assertTextEquals("当前配置：未启用")
    }

    @Test fun profileRemovalRequiresConfirmationInsideTheSelectedProvider() {
        var removed: String? = null
        compose.setContent { MaterialTheme {
            CloudProfileControl(listOf(WebDavProfile("s3", "access", "https://s3.example.com", "备用", true, true)),
                true, false, {}, {}, {}, {}, { removed = it })
        } }
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_provider_S3").performClick()
        compose.onNodeWithTag("cloud_remove_s3").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithTag("cloud_confirm_remove").performClick()
        compose.runOnIdle { assertEquals("s3", removed) }
    }

    @Test fun cosEditorSavesSelectedProviderWithoutPathStyle() {
        var selected: CloudCredentials? = null
        compose.setContent { MaterialTheme {
            S3AccountDialog(null, "腾讯云", { credentials, _ -> selected = credentials }, null, {}, initialCos = true)
        } }
        for ((tag, value) in listOf("endpoint" to "https://cos.ap-nanjing.myqcloud.com", "region" to "ap-nanjing",
            "bucket" to "reader-1250000000", "access" to "example-access", "secret" to "example-secret")) {
            compose.onNodeWithTag("s3_$tag").performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithTag("s3_path_style").assertDoesNotExist()
        compose.onNodeWithTag("s3_save").performClick()
        compose.runOnIdle { assertTrue(selected!!.s3!!.cos); assertFalse(selected!!.s3!!.pathStyle) }
    }

    @Test fun changingSavedEndpointExplainsWhySecretMustBeEnteredAgain() {
        val saved = CloudCredentials("access", "saved-secret", "https://bucket.cos.ap-nanjing.myqcloud.com",
            S3Location("reader-backups", "ap-nanjing"))
        compose.setContent { MaterialTheme { S3AccountDialog(saved, "S3", { _, _ -> }, { "成功" }, {}) } }
        compose.onNodeWithTag("s3_save").assertIsEnabled()
        compose.onNodeWithTag("s3_endpoint").performScrollTo().performTextReplacement("https://cos.ap-nanjing.myqcloud.com")
        compose.onNodeWithTag("s3_validation_error").performScrollTo().assertTextEquals("地址或 Access Key 已更改，请重新填写 Secret Key")
        compose.onNodeWithTag("s3_save").assertIsNotEnabled()
        compose.onNodeWithTag("s3_secret").performScrollTo().performTextReplacement("saved-secret")
        compose.onNodeWithTag("s3_validation_error").assertDoesNotExist()
        compose.onNodeWithTag("s3_save").assertIsEnabled()
        compose.onNodeWithTag("s3_test").performScrollTo().assertIsEnabled()
    }

    @Test fun configurationValidatesTestsAndSavesWithoutExposingTheSecret() {
        var saved: CloudCredentials? = null
        var tested: CloudCredentials? = null
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(true) }
            if (open) S3AccountDialog(null, "S3", { credentials, _ -> saved = credentials },
                { tested = it; "S3 连接成功：已验证读写权限" }, { open = false })
        } }
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
            if (open) S3AccountDialog(null, "S3", { _, _ -> fail("Unexpected save") },
                { error("S3 权限不足") }, { open = false })
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
