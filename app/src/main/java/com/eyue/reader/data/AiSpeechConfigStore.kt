package com.eyue.reader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

enum class SpeechApiProtocol { OPENAI, MIMO }

class AiSpeechConfig(val url: String, val key: String, val model: String, val voice: String, val instructions: String = "",
    val protocol: SpeechApiProtocol = SpeechApiProtocol.OPENAI, val name: String = "默认配置", val id: String = UUID.randomUUID().toString(),
    val rolePromptEnabled: Boolean = false) {
    val endpoint: String
    val voiceDesign get() = protocol == SpeechApiProtocol.MIMO && model == "mimo-v2.5-tts-voicedesign"
    init {
        val address = url.toHttpUrlOrNull()
        require(url.length <= 4096 && address != null && address.username.isEmpty() && address.password.isEmpty() && address.fragment == null && address.query == null) { "请输入有效的语音接口 HTTP/HTTPS 地址" }
        require(key.length <= 4096 && key.none { it == '\r' || it == '\n' }) { "API 密钥无效" }
        require(model.isNotBlank() && model.length <= 256 && voice.length <= 256) { "请输入模型名称" }
        require(protocol != SpeechApiProtocol.OPENAI || voice.isNotBlank()) { "请输入声音名称" }
        require(!voiceDesign || instructions.isNotBlank()) { "请填写音色描述与朗读风格" }
        require(name.isNotBlank()) { "请输入配置名称" }
        require(instructions.length <= 16_384 && name.length <= 100 && id.isNotBlank() && id.length <= 100) { "配置名称或朗读风格过长" }
        require(protocol != SpeechApiProtocol.MIMO || model != "mimo-v2.5-tts-voiceclone") { "当前不支持需要音频样本的音色复刻模型" }
        val path = address.encodedPath.trimEnd('/')
        val suffix = if (protocol == SpeechApiProtocol.MIMO) "/chat/completions" else "/audio/speech"
        endpoint = when {
            path.endsWith(suffix) -> address.toString().trimEnd('/')
            path.isEmpty() -> address.newBuilder().encodedPath("/v1$suffix").build().toString()
            path.endsWith("/v1") -> address.newBuilder().encodedPath(path + suffix).build().toString()
            else -> address.toString()
        }
    }
    override fun toString() = "AiSpeechConfig(redacted)"
    fun request(text: String, roleInstructions: String = ""): ByteArray {
        val effectiveInstructions = listOf(instructions, if (rolePromptEnabled) roleInstructions else "").filter { it.isNotBlank() }.joinToString("\n\n")
        val request = JSONObject().put("model", model)
        if (protocol == SpeechApiProtocol.MIMO) {
            val messages = JSONArray()
            if (effectiveInstructions.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", effectiveInstructions.trim()))
            messages.put(JSONObject().put("role", "assistant").put("content", text))
            val audio = JSONObject().put("format", "wav").put("optimize_text_preview", false)
            if (!voiceDesign && voice.isNotBlank()) audio.put("voice", voice)
            request.put("messages", messages).put("audio", audio).put("stream", false)
        } else {
            request.put("voice", voice).put("input", text).put("response_format", "mp3")
            if (effectiveInstructions.isNotBlank()) request.put("instructions", effectiveInstructions.trim())
        }
        return request.toString().toByteArray(Charsets.UTF_8)
    }
    internal fun toJson(): JSONObject = JSONObject().put("url", url).put("key", key).put("model", model).put("voice", voice)
        .put("instructions", instructions).put("protocol", protocol.name).put("name", name).put("id", id)
        .put("rolePromptEnabled", rolePromptEnabled)
    companion object {
        internal fun fromJson(json: JSONObject): AiSpeechConfig = AiSpeechConfig(json.getString("url"), json.getString("key"),
            json.getString("model"), json.optString("voice", ""), json.optString("instructions", ""),
            SpeechApiProtocol.valueOf(json.optString("protocol", "OPENAI")), json.optString("name", "默认配置"), json.optString("id", "legacy"),
            json.optBoolean("rolePromptEnabled", false))
    }
}

internal class AiSpeechProfiles(val configurations: List<AiSpeechConfig>, val selectedId: String?) {
    fun selected() = configurations.firstOrNull { it.id == selectedId } ?: configurations.firstOrNull()
    fun toJson() = JSONObject().put("version", 2).put("selectedId", selectedId)
        .put("configurations", JSONArray().apply { configurations.forEach { put(it.toJson()) } })
    companion object {
        fun fromJson(json: JSONObject): AiSpeechProfiles {
            if (!json.has("configurations")) {
                val legacy = AiSpeechConfig.fromJson(json)
                return AiSpeechProfiles(listOf(legacy), legacy.id)
            }
            val entries = json.getJSONArray("configurations")
            require(entries.length() <= 100) { "AI 语音配置数量过多" }
            return AiSpeechProfiles(List(entries.length()) { AiSpeechConfig.fromJson(entries.getJSONObject(it)) }, json.optString("selectedId", ""))
        }
    }
}

/** A blank password may reuse only this profile's credentials for the same API service. */
internal fun resolveAiSpeechConfigDraft(draft: AiSpeechConfig, profiles: List<AiSpeechConfig>): AiSpeechConfig {
    if (draft.key.isNotBlank()) return draft
    val previous = profiles.firstOrNull { it.id == draft.id } ?: return draft
    val oldAddress = previous.endpoint.toHttpUrlOrNull() ?: return draft
    val newAddress = draft.endpoint.toHttpUrlOrNull() ?: return draft
    if (oldAddress.scheme != newAddress.scheme || oldAddress.host != newAddress.host || oldAddress.port != newAddress.port) return draft
    fun servicePath(path: String): String? {
        val suffix = listOf("/audio/speech", "/chat/completions").firstOrNull { path.trimEnd('/').endsWith(it) } ?: return null
        return path.trimEnd('/').removeSuffix(suffix)
    }
    if (oldAddress.encodedPath != newAddress.encodedPath &&
        (servicePath(oldAddress.encodedPath) == null || servicePath(oldAddress.encodedPath) != servicePath(newAddress.encodedPath))) return draft
    return AiSpeechConfig(draft.url, previous.key, draft.model, draft.voice, draft.instructions, draft.protocol, draft.name, draft.id, draft.rolePromptEnabled)
}

internal val AiSpeechConfig.requiresApiKey: Boolean
    get() = protocol == SpeechApiProtocol.MIMO && endpoint.toHttpUrlOrNull()?.host == "api.xiaomimimo.com"

/** API credentials stay on this phone and are never included in book backups. */
class AiSpeechConfigStore(context: Context, private val secretKey: (() -> SecretKey)? = null) {
    private val file = AtomicFile(File(context.filesDir, "ai-speech.encrypted"))
    private fun key(): SecretKey {
        secretKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("EReadAiSpeech", null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("EReadAiSpeech", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun readProfiles(): AiSpeechProfiles {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AiSpeechProfiles(emptyList(), null)
        val bytes = file.readFully()
        require(bytes.size > 12) { "AI 语音配置无法读取，请重新保存" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        return AiSpeechProfiles.fromJson(json)
    }
    fun read(): AiSpeechConfig? = synchronized(lock) { readProfiles().selected() }
    fun profiles(): List<AiSpeechConfig> = synchronized(lock) { readProfiles().configurations }
    fun resolve(draft: AiSpeechConfig): AiSpeechConfig = synchronized(lock) { resolveAiSpeechConfigDraft(draft, readProfiles().configurations) }
    fun select(id: String) = synchronized(lock) {
        val profiles = readProfiles()
        require(profiles.configurations.any { it.id == id }) { "AI 语音配置不存在" }
        writeProfiles(AiSpeechProfiles(profiles.configurations, id))
    }
    fun write(config: AiSpeechConfig) = save(config)
    fun save(config: AiSpeechConfig) = synchronized(lock) {
        val profiles = readProfiles().configurations
        val saved = resolveAiSpeechConfigDraft(config, profiles)
        val configurations = profiles.filterNot { it.id == config.id } + saved
        require(configurations.size <= 100) { "最多保存 100 条 AI 语音配置" }
        writeProfiles(AiSpeechProfiles(configurations, config.id))
    }
    private fun writeProfiles(profiles: AiSpeechProfiles) {
        val json = profiles.toJson()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object { private val lock = Any() }
}
