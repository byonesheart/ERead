package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.SpeechApiProtocol
import com.eyue.reader.data.SpeechEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
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
class SpeechEngineSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val config = AiSpeechConfig("https://voice.example.org/v1", "secret", "tts", "voice", name = "日常朗读", id = "first")

    @Test fun rolePromptDefaultsOffAndBothTestingAndSavingUseTheEditedToggle() {
        var tested: AiSpeechConfig? = null
        var saved: AiSpeechConfig? = null
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onSaveProfile = { saved = it }, onTestConnection = { tested = it; "已收到音频" }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_role_prompt").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("此功能尚不稳定").assertExists()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, tested?.rolePromptEnabled) }
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle { assertEquals(true, saved?.rolePromptEnabled) }
    }

    @Test fun anEnabledSavedRolePromptCanBeSwitchedOffWithoutLosingItsOtherFields() {
        val enabled = AiSpeechConfig(config.url, config.key, config.model, config.voice, config.instructions,
            config.protocol, config.name, config.id, rolePromptEnabled = true)
        var saved: AiSpeechConfig? = null
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, enabled, {}, { _, _, _, _, _ -> }, onSaveProfile = { saved = it }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_role_prompt").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle {
            assertEquals(false, saved?.rolePromptEnabled)
            assertEquals(enabled.id, saved?.id)
            assertEquals(enabled.model, saved?.model)
        }
    }

    @Test fun aSingleButtonSwitchesEngineAndOnlyAiShowsConfigurationIcon() {
        compose.setContent {
            var engine by remember { mutableStateOf(SpeechEngine.SYSTEM) }
            MaterialTheme { SpeechEngineSettings(engine, config, { engine = it }, { _, _, _, _, _ -> }) }
        }
        compose.onNodeWithText("当前使用：").assertIsDisplayed()
        compose.onNodeWithTag("speech_engine_toggle").assertTextContains("系统语音")
        compose.onNodeWithTag("ai_speech_settings").assertDoesNotExist()
        compose.onNodeWithText("配置在线语音 API").assertDoesNotExist()
        compose.onNodeWithTag("speech_engine_toggle").performClick().assertTextContains("AI 语音")
        compose.onNodeWithTag("ai_speech_settings").assertIsDisplayed()
        compose.onNodeWithTag("speech_engine_toggle").performClick().assertTextContains("系统语音")
        compose.onNodeWithTag("ai_speech_settings").assertDoesNotExist()
    }
    @Test fun systemFallbackIsOptInAndOnlyShownForAiSpeech() {
        compose.setContent {
            var engine by remember { mutableStateOf(SpeechEngine.SYSTEM) }
            var fallback by remember { mutableStateOf(false) }
            MaterialTheme { SpeechEngineSettings(engine, config, { engine = it }, { _, _, _, _, _ -> },
                fallbackEnabled = fallback, onFallbackEnabled = { fallback = it }) }
        }
        compose.onNodeWithTag("ai_speech_fallback").assertDoesNotExist()
        compose.onNodeWithTag("speech_engine_toggle").performClick()
        compose.onNodeWithTag("ai_speech_fallback").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("speech_engine_toggle").performClick()
        compose.onNodeWithTag("ai_speech_fallback").assertDoesNotExist()
        compose.onNodeWithTag("speech_engine_toggle").performClick()
        compose.onNodeWithTag("ai_speech_fallback").assertIsOn()
    }
    @Test fun mimoVoiceDesignHidesVoiceIdAndRequiresDescriptionBeforeSaving() {
        var saved: AiSpeechConfig? = null
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onSaveProfile = { saved = it }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_protocol_mimo").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_model").performScrollTo().performTextReplacement("mimo-v2.5-tts-voicedesign")
        compose.onNodeWithTag("ai_speech_voice").assertDoesNotExist()
        compose.onNodeWithText("保存并启用").assertIsNotEnabled()
        compose.onNodeWithTag("ai_speech_instructions").performScrollTo().performTextInput("温柔清晰的成年女声")
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle {
            assertEquals(SpeechApiProtocol.MIMO, saved?.protocol)
            assertEquals("温柔清晰的成年女声", saved?.instructions)
            assertEquals("first", saved?.id)
            assertEquals("", saved?.key)
        }
    }
    @Test fun savedProfilesCanBeSelectedWithoutReenteringTheirSecrets() {
        val second = AiSpeechConfig(config.url, "second-secret", "second-model", "second-voice", name = "睡前朗读", id = "second")
        var selected = ""
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            profiles = listOf(config, second), onSelectProfile = { selected = it }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithText("选择已保存配置").performClick()
        compose.onNodeWithText("睡前朗读").performClick()
        compose.runOnIdle { assertEquals("second", selected) }
        compose.onNodeWithTag("ai_profile_name").assertTextContains("睡前朗读")
        compose.onNodeWithText("second-secret").assertDoesNotExist()
        compose.onNodeWithTag("ai_speech_model").performScrollTo().assertTextContains("second-model")
    }
    @Test fun connectionTestUsesUnsavedDraftWithoutSavingOrSelectingIt() {
        var tested: AiSpeechConfig? = null
        var saves = 0
        var selects = 0
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onSaveProfile = { saves++ }, onSelectProfile = { selects++ }, onTestConnection = {
                tested = it; "已生成测试音频"
            }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_url").performScrollTo().performTextReplacement("https://draft.example.org/v1")
        compose.onNodeWithTag("ai_speech_key").performScrollTo().performTextReplacement("draft-token")
        compose.onNodeWithTag("ai_speech_model").performScrollTo().performTextReplacement("draft-model")
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试成功：已生成测试音频")
        compose.onNodeWithTag("ai_speech_test_success_icon").assertIsDisplayed().assertContentDescriptionEquals("测试连接成功")
        compose.onNodeWithText("连接成功", substring = false).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("https://draft.example.org/v1", tested?.url)
            assertEquals("draft-token", tested?.key)
            assertEquals("draft-model", tested?.model)
            assertEquals(0, saves)
            assertEquals(0, selects)
        }
    }
    @Test fun changingDraftCancelsTheTestAndDiscardsALateResult() {
        val response = CompletableDeferred<String>()
        var started = false
        var cancelled = false
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onTestConnection = {
                started = true
                try { withContext(NonCancellable) { response.await() } } finally { cancelled = true }
            }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(started) }
        compose.onNodeWithTag("ai_speech_model").performScrollTo().performTextReplacement("updated-model")
        compose.onNodeWithTag("ai_speech_test").performScrollTo().assertIsEnabled()
        compose.runOnIdle { response.complete("过期结果") }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("ai_speech_test_result").assertDoesNotExist()
    }
    @Test fun dismissingConfigurationCancelsAnInFlightTest() {
        var started = false
        var cancelled = false
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onTestConnection = {
                started = true
                try { awaitCancellation() } finally { cancelled = true }
            }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(started) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("ai_speech_test").assertDoesNotExist()
    }
    @Test fun failuresAreReadableRedactedAndCanBeRetried() {
        var attempts = 0
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, config, {}, { _, _, _, _, _ -> },
            onTestConnection = {
                attempts++
                if (attempts == 1) error("HTTP 401：secret 无效")
                "已收到音频"
            }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试失败：HTTP 401：[已隐藏] 无效")
        compose.onNodeWithTag("ai_speech_test_failed_icon").assertIsDisplayed().assertContentDescriptionEquals("测试连接失败")
        compose.onNodeWithTag("ai_speech_test_success_icon").assertDoesNotExist()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试成功：已收到音频")
        compose.onNodeWithTag("ai_speech_test_success_icon").assertIsDisplayed()
        compose.onNodeWithTag("ai_speech_test_failed_icon").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
    @Test fun officialMimoRequiresAKeyAndSavedKeyHintUsesTheActualCredential() {
        val saved = AiSpeechConfig("https://api.xiaomimimo.com/v1/audio/speech", "saved-token", "mimo-v2.5-tts", "voice",
            name = "MiMo", id = "mimo")
        var attempts = 0
        compose.setContent { MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, saved, {}, { _, _, _, _, _ -> },
            onTestConnection = { attempts++; "已收到音频" }) } }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_protocol_mimo").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_url").performScrollTo().performTextReplacement("https://api.xiaomimimo.com/v1")
        compose.onNodeWithText("已保存密钥，留空沿用此服务的密钥").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("ai_speech_url").performScrollTo().performTextReplacement("https://other.example.org/v1")
        compose.onNodeWithText("已保存密钥，留空沿用此服务的密钥").assertDoesNotExist()
        compose.onNodeWithTag("ai_speech_url").performTextReplacement("https://api.xiaomimimo.com/other/v1")
        compose.onNodeWithText("此服务没有可沿用的已保存密钥，请填写 API 密钥").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, attempts) }
        compose.onNodeWithTag("ai_speech_key").performScrollTo().performTextReplacement("new-token")
        compose.onNodeWithTag("ai_speech_test").performScrollTo().assertIsEnabled()
    }
    @Test fun readingSettingsDialogReusesEngineAndProfileConfigurationControls() {
        val second = AiSpeechConfig(config.url, "second-secret", "second-model", "second-voice", name = "睡前朗读", id = "second")
        var shown by mutableStateOf(true)
        var selected by mutableStateOf("")
        var saved: AiSpeechConfig? = null
        var tested: AiSpeechConfig? = null
        compose.setContent {
            var engine by remember { mutableStateOf(SpeechEngine.SYSTEM) }
            MaterialTheme { if (shown) SpeechEngineDialog(engine, config, { engine = it }, { _, _, _, _, _ -> },
                onDismiss = { shown = false }, profiles = listOf(config, second), onSelectProfile = { selected = it },
                onSaveProfile = { saved = it }, onTestConnection = { tested = it; "已收到测试音频" }) }
        }
        compose.onNodeWithText("当前使用：").assertIsDisplayed()
        compose.onNodeWithTag("speech_engine_toggle").performClick().assertTextContains("AI 语音")
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithText("选择已保存配置").performClick()
        compose.onNodeWithText("睡前朗读").performClick()
        compose.onNodeWithTag("ai_speech_model").performScrollTo().performTextReplacement("draft-model")
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试成功：已收到测试音频")
        compose.runOnIdle {
            assertEquals("second", selected)
            assertEquals("draft-model", tested?.model)
            assertNull(saved)
        }
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle { assertEquals("draft-model", saved?.model) }
        compose.onNodeWithText("朗读设置").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("朗读设置").assertDoesNotExist()
    }

    @Test fun connectionStatusStaysVisibleOutsideTheScrolledFormAndSurvivesParentRecomposition() {
        val response = CompletableDeferred<String>()
        var revision by mutableIntStateOf(0)
        var attempts = 0
        compose.setContent {
            val selected = if (revision == 0) config else AiSpeechConfig(config.url, config.key, config.model,
                config.voice, name = config.name, id = config.id)
            MaterialTheme { SpeechEngineSettings(SpeechEngine.AI, selected, {}, { _, _, _, _, _ -> },
                onTestConnection = { attempts++; response.await() }) }
        }
        compose.onNodeWithTag("ai_speech_settings").performClick()
        compose.onNodeWithTag("ai_speech_test").performScrollTo().performClick()
        compose.onNodeWithTag("ai_speech_test_running").assertIsDisplayed()
        compose.onNodeWithTag("ai_profile_name").performScrollTo()
        compose.runOnIdle { response.complete("已生成音频，耗时 2 秒") }
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试成功：已生成音频，耗时 2 秒")
        compose.onNodeWithText("AI 语音配置").assertIsDisplayed()
        compose.runOnIdle { revision++ }
        compose.onNodeWithTag("ai_speech_test_result").assertIsDisplayed().assertTextContains("测试成功：已生成音频，耗时 2 秒")
        compose.onNodeWithText("AI 语音配置").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun testFailureFeedbackRedactsUnknownBearerTokensAndRawStructuredResponseBodies() {
        val safe = safeAiSpeechTestMessage("HTTP 401：secret；Bearer different-token；sk-other-token", listOf("secret"))
        assertTrue(safe.contains("401"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("different-token"))
        assertFalse(safe.contains("sk-other-token"))
        assertEquals("服务返回 500，请检查服务器地址、模型和密钥",
            safeAiSpeechTestMessage("HTTP 500: {\"error\":\"private response body\"}", emptyList()))
        assertEquals("请检查服务器地址、模型和密钥",
            safeAiSpeechTestMessage("<html>private response body</html>", emptyList()))
        assertEquals("请检查服务器地址、模型和密钥",
            safeAiSpeechTestMessage("[\"private response body\"]", emptyList()))
    }
}
