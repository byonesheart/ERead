package com.eyue.reader.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64

internal class EmptyMimoAudioException : IOException("MiMo 未返回有效音频")

internal object AiSpeechResponse {
    private const val MAX_AUDIO = 16 * 1024 * 1024
    private const val MAX_JSON = 24 * 1024 * 1024
    fun write(config: AiSpeechConfig, input: InputStream, target: File, checkActive: () -> Unit) {
        if (config.protocol == SpeechApiProtocol.OPENAI) {
            target.outputStream().use { output -> copyBounded(input, MAX_AUDIO, checkActive) { bytes, count -> output.write(bytes, 0, count) } }
            require(target.length() > 0) { "AI 语音没有返回音频" }
            return
        }
        val bytes = ByteArrayOutputStream().apply { copyBounded(input, MAX_JSON, checkActive) { bytes, count -> write(bytes, 0, count) } }.toByteArray()
        if (bytes.isEmpty()) throw EmptyMimoAudioException()
        val response = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw IOException("MiMo 响应格式无效，请检查协议与服务器地址") }
        if (response.has("error") && !response.isNull("error")) {
            throw IOException("MiMo 语音服务返回错误，请检查模型、权限与额度")
        }
        val choices = response.optJSONArray("choices") ?: throw IOException("MiMo 响应格式无效，请检查协议与模型")
        if (choices.length() == 0) throw EmptyMimoAudioException()
        val choice = choices.optJSONObject(0) ?: throw IOException("MiMo 响应格式无效，请检查协议与模型")
        val finish = if (choice.isNull("finish_reason")) "" else choice.optString("finish_reason", "")
        if (finish.isNotBlank() && finish != "stop") {
            throw IOException(if (finish == "content_filter") "MiMo 拒绝合成这段文字（内容限制）" else "MiMo 语音生成未正常完成，请检查模型与文本")
        }
        val message = choice.optJSONObject("message") ?: throw IOException("MiMo 响应格式无效，请检查协议与模型")
        if (!message.isNull("refusal") && message.optString("refusal", "").isNotBlank()) throw IOException("MiMo 拒绝合成这段文字")
        val audioObject = message.opt("audio")
        if (audioObject == null || audioObject == JSONObject.NULL) throw EmptyMimoAudioException()
        val audioFields = audioObject as? JSONObject ?: throw IOException("MiMo 音频数据格式无效")
        val audioData = audioFields.opt("data")
        if (audioData == null || audioData == JSONObject.NULL || audioData is String && audioData.isBlank()) throw EmptyMimoAudioException()
        val encoded = audioData as? String ?: throw IOException("MiMo 音频数据格式无效")
        checkActive()
        require(encoded.length <= (MAX_AUDIO + 2) / 3 * 4) { "语音响应过大，请检查接口" }
        val audio = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IOException("MiMo 音频编码无效") }
        require(audio.size in 12..MAX_AUDIO && audio.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            audio.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "MiMo 未返回 WAV 音频" }
        checkActive()
        target.writeBytes(audio)
    }
    private fun copyBounded(input: InputStream, maximum: Int, checkActive: () -> Unit, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "语音响应过大，请检查接口" }
            write(buffer, count)
        }
    }
}
