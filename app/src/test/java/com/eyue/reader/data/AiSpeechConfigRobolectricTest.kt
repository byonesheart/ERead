package com.eyue.reader.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import javax.crypto.spec.SecretKeySpec
import java.io.File
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechConfigRobolectricTest {
    @Test fun speechRequestUsesConfiguredModelAndVoiceWithoutEmbeddingTheSecret() {
        val config = AiSpeechConfig("https://voice.example.org/v1/audio/speech", "private-key", "my-tts", "reader")
        val request = JSONObject(config.request("正文\n第二段").toString(Charsets.UTF_8))
        assertEquals("my-tts", request.getString("model"))
        assertEquals("reader", request.getString("voice"))
        assertEquals("mp3", request.getString("response_format"))
        assertEquals("正文\n第二段", request.getString("input"))
        assertFalse(request.has("instructions"))
        assertFalse(request.toString().contains("private-key"))
        assertFalse(config.toString().contains("private-key"))
    }
    @Test fun speechStyleIsSentSeparatelyFromTheTextAndBlankStyleIsOmitted() {
        val config = AiSpeechConfig("https://voice.example.org/v1/audio/speech", "private-key", "my-tts", "reader", "  平静自然\n对白区分情绪  ")
        val request = JSONObject(config.request("正文").toString(Charsets.UTF_8))
        assertEquals("平静自然\n对白区分情绪", request.getString("instructions"))
        assertEquals("正文", request.getString("input"))
        assertFalse(request.toString().contains("private-key"))
        val blank = AiSpeechConfig(config.url, config.key, config.model, config.voice, " \n ")
        assertFalse(JSONObject(blank.request("正文").toString(Charsets.UTF_8)).has("instructions"))
    }
    @Test fun configJsonRoundTripPreservesSpeechStyleAndExistingFields() {
        val source = AiSpeechConfig("https://voice.example.org/v1/audio/speech", "private-key", "my-tts", "reader", "语速舒缓\n自然停顿")
        val restored = AiSpeechConfig.fromJson(JSONObject(source.toJson().toString()))
        assertEquals(source.url, restored.url)
        assertEquals(source.key, restored.key)
        assertEquals(source.model, restored.model)
        assertEquals(source.voice, restored.voice)
        assertEquals(source.instructions, restored.instructions)
        assertFalse(restored.toString().contains("private-key"))
    }
    @Test fun oldConfigJsonWithoutSpeechStyleRemainsUsable() {
        val json = JSONObject().put("url", "https://voice.example.org/v1/audio/speech").put("key", "private-key")
            .put("model", "my-tts").put("voice", "reader")
        val restored = AiSpeechConfig.fromJson(json)
        assertEquals("", restored.instructions)
        assertEquals("reader", restored.voice)
        assertEquals("private-key", restored.key)
        assertFalse(JSONObject(restored.request("正文").toString(Charsets.UTF_8)).has("instructions"))
    }
    @Test fun malformedEndpointCredentialsAndMissingModelAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AiSpeechConfig("https://user:password@example.org/speech", "", "tts", "voice") }
        assertThrows(IllegalArgumentException::class.java) { AiSpeechConfig("https://example.org/speech", "key\ninjection", "tts", "voice") }
        assertThrows(IllegalArgumentException::class.java) { AiSpeechConfig("https://example.org/speech", "", "", "voice") }
    }
    @Test fun mimoVoiceDesignUsesSeparateRolesAndDisablesTextRewriting() {
        val config = AiSpeechConfig("https://api.xiaomimimo.com/v1", "private-key", "mimo-v2.5-tts-voicedesign", "unused", "温柔女声", SpeechApiProtocol.MIMO, "小说")
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", config.endpoint)
        val body = JSONObject(config.request("原始正文").toString(Charsets.UTF_8))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("温柔女声", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("assistant", body.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertEquals("原始正文", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("wav", body.getJSONObject("audio").getString("format"))
        assertFalse(body.getJSONObject("audio").getBoolean("optimize_text_preview"))
        assertFalse(body.getJSONObject("audio").has("voice"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.toString().contains("private-key"))
        val restored = AiSpeechConfig.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(SpeechApiProtocol.MIMO, restored.protocol)
        assertEquals(config.id, restored.id)
        assertEquals("小说", restored.name)
        assertThrows(IllegalArgumentException::class.java) {
            AiSpeechConfig(config.url, "", config.model, "", "", SpeechApiProtocol.MIMO)
        }
    }
    @Test fun baseAddressesAreExpandedOnTheSameServerAndFullEndpointsArePreserved() {
        assertEquals("https://voice.example.org/v1/audio/speech", AiSpeechConfig("https://voice.example.org", "", "tts", "voice").endpoint)
        assertEquals("https://voice.example.org/custom/audio/speech", AiSpeechConfig("https://voice.example.org/custom/audio/speech", "", "tts", "voice").endpoint)
        val preset = AiSpeechConfig("https://voice.example.org/proxy/v1/", "", "mimo-v2.5-tts", "冰糖", "", SpeechApiProtocol.MIMO)
        assertEquals("https://voice.example.org/proxy/v1/chat/completions", preset.endpoint)
        assertEquals("冰糖", JSONObject(preset.request("正文").toString(Charsets.UTF_8)).getJSONObject("audio").getString("voice"))
    }
    @Test fun oldEncryptedConfigMigratesToNamedProfilesAndKeepsSecretsOnlyForSameProfileAndService() {
        val legacyJson = JSONObject().put("url", "https://voice.example.org/v1/audio/speech").put("key", "private-key")
            .put("model", "tts").put("voice", "voice")
        val migrated = AiSpeechProfiles.fromJson(legacyJson)
        assertEquals("legacy", migrated.selected()?.id)
        assertEquals("默认配置", migrated.selected()?.name)
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "ai-speech.encrypted")
        file.delete(); File(file.path + ".bak").delete()
        try {
            val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            file.writeBytes(cipher.iv + cipher.doFinal(legacyJson.toString().toByteArray(Charsets.UTF_8)))
            val store = AiSpeechConfigStore(context) { key }
            assertEquals("legacy", store.read()?.id)
            assertEquals(SpeechApiProtocol.OPENAI, store.read()?.protocol)
            store.save(AiSpeechConfig("https://second.example.org/v1", "second-key", "tts", "other", name = "第二配置", id = "second"))
            assertEquals(2, store.profiles().size)
            assertEquals("second", store.read()?.id)
            store.select("legacy")
            store.save(AiSpeechConfig("https://voice.example.org/v1", "", "new-model", "voice", name = "改名", id = "legacy"))
            assertEquals("private-key", store.read()?.key)
            assertEquals("改名", store.read()?.name)
            store.save(AiSpeechConfig("https://voice.example.org/v1", "", "tts", "voice", id = "third"))
            assertEquals("", store.read()?.key)
            store.select("legacy")
            store.save(AiSpeechConfig("https://different.example.org/v1", "", "tts", "voice", id = "legacy"))
            assertEquals("", store.read()?.key)
            assertFalse(file.readText().contains("second-key"))
            assertFalse(file.readText().contains("private-key"))
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
    @Test fun switchingProtocolOnTheSameApiServicePreservesSavedKeyWithoutSavingDraftOrSelectingIt() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "ai-speech.encrypted")
        file.delete(); File(file.path + ".bak").delete()
        try {
            val store = AiSpeechConfigStore(context) { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
            val old = AiSpeechConfig("https://api.xiaomimimo.com/v1/audio/speech", "saved-secret", "tts", "voice", id = "existing")
            store.save(old)
            store.save(AiSpeechConfig("https://other.example/v1", "other-key", "tts", "voice", id = "selected"))
            val draft = AiSpeechConfig("https://api.xiaomimimo.com/v1", "", "mimo-v2.5-tts", "", protocol = SpeechApiProtocol.MIMO, id = old.id)
            val resolved = store.resolve(draft)
            assertEquals("saved-secret", resolved.key)
            assertEquals("https://api.xiaomimimo.com/v1/chat/completions", resolved.endpoint)
            assertEquals("selected", store.read()?.id)
            assertEquals(SpeechApiProtocol.OPENAI, store.profiles().first { it.id == old.id }.protocol)
            store.save(draft)
            assertEquals("saved-secret", store.read()?.key)
            assertEquals(SpeechApiProtocol.MIMO, store.read()?.protocol)
            assertEquals("saved-secret", store.resolve(AiSpeechConfig("https://api.xiaomimimo.com/v1/chat/completions", "",
                "mimo-v2.5-tts", "冰糖", protocol = SpeechApiProtocol.MIMO, id = old.id)).key)
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
    @Test fun draftNeverReusesCredentialsAcrossProfilesOriginsOrApiPrefixes() {
        val previous = AiSpeechConfig("https://voice.example/v1/audio/speech", "saved-secret", "tts", "voice", id = "saved")
        val profiles = listOf(previous)
        fun draft(url: String, id: String = "saved", key: String = "") = AiSpeechConfig(url, key, "mimo-v2.5-tts", "",
            protocol = SpeechApiProtocol.MIMO, id = id)
        assertEquals("", resolveAiSpeechConfigDraft(draft("https://other.example/v1"), profiles).key)
        assertEquals("", resolveAiSpeechConfigDraft(draft("http://voice.example/v1"), profiles).key)
        assertEquals("", resolveAiSpeechConfigDraft(draft("https://voice.example:8443/v1"), profiles).key)
        assertEquals("", resolveAiSpeechConfigDraft(draft("https://voice.example/tenant/v1"), profiles).key)
        assertEquals("", resolveAiSpeechConfigDraft(draft("https://voice.example/v1", id = "new"), profiles).key)
        assertEquals("new-secret", resolveAiSpeechConfigDraft(draft("https://voice.example/v1", key = "new-secret"), profiles).key)
        assertTrue(draft("https://api.xiaomimimo.com/v1").requiresApiKey)
        assertFalse(draft("https://private.example/v1").requiresApiKey)
    }

    @Test fun rolePromptIsOptInAndBothProtocolsIgnoreRoleDataUntilEnabled() {
        for (protocol in SpeechApiProtocol.entries) {
            val disabled = AiSpeechConfig("https://voice.example/v1", "", "tts", "voice", "基础朗读风格", protocol)
            val enabled = AiSpeechConfig(disabled.url, disabled.key, disabled.model, disabled.voice, disabled.instructions,
                protocol, rolePromptEnabled = true)
            assertFalse(disabled.rolePromptEnabled)
            assertFalse(disabled.request("正文", "角色敏感资料").toString(Charsets.UTF_8).contains("角色敏感资料"))
            assertTrue(disabled.request("正文", "角色敏感资料").toString(Charsets.UTF_8).contains("基础朗读风格"))
            assertTrue(enabled.request("正文", "角色资料").toString(Charsets.UTF_8).contains("角色资料"))
            assertFalse(AiSpeechConfig.fromJson(disabled.toJson().apply { remove("rolePromptEnabled") }).rolePromptEnabled)
            assertTrue(AiSpeechConfig.fromJson(enabled.toJson()).rolePromptEnabled)
        }
    }

    @Test fun encryptedProfilesAndBlankKeyResolutionPreserveTheRolePromptToggle() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "ai-speech.encrypted")
        file.delete(); File(file.path + ".bak").delete()
        try {
            val store = AiSpeechConfigStore(context) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
            val original = AiSpeechConfig("https://voice.example/v1", "saved-secret", "tts", "voice", id = "role-toggle", rolePromptEnabled = true)
            store.save(original)
            assertTrue(requireNotNull(store.read()).rolePromptEnabled)
            val changed = AiSpeechConfig(original.url, "", original.model, original.voice, id = original.id, rolePromptEnabled = true)
            assertTrue(store.resolve(changed).rolePromptEnabled)
            assertEquals("saved-secret", store.resolve(changed).key)
            store.save(AiSpeechConfig(original.url, "", original.model, original.voice, id = original.id, rolePromptEnabled = false))
            assertFalse(requireNotNull(store.read()).rolePromptEnabled)
            assertEquals("saved-secret", store.read()?.key)
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
}
