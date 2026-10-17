package com.eyue.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class AiSpeechTestResult(val elapsedMillis: Long, val audioBytes: Long)

/** Retry the same request; cancelling a conversion or reading session must never start a new attempt. */
internal suspend fun <T> retryAiSpeechSynthesis(operation: suspend () -> T): T {
    var failures = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { return operation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (++failures == 4) throw IOException("AI 语音生成失败（已重试 3 次，共 4 次）：${failure.message ?: "服务未返回有效音频"}", failure)
            delay(300)
        }
    }
}

/** Both connection tests and reading use this exact request, authentication and audio decoder. */
internal class AiSpeechClient(private val client: OkHttpClient = defaultClient()) {
    suspend fun test(config: AiSpeechConfig, target: File): AiSpeechTestResult =
        synthesize(config, "你好，这是 E 阅的语音连接测试。", target)

    suspend fun synthesize(config: AiSpeechConfig, text: String, target: File, roleInstructions: String = ""): AiSpeechTestResult {
        require(!config.requiresApiKey || config.key.isNotBlank()) { "此 MiMo 配置未保存 API 密钥，请重新填写后测试或保存" }
        val request = Request.Builder().url(config.endpoint)
            .post(config.request(text, roleInstructions).toRequestBody("application/json".toMediaType()))
        if (config.key.isNotBlank()) {
            if (config.protocol == SpeechApiProtocol.MIMO) request.header("api-key", config.key)
            else request.header("Authorization", "Bearer ${config.key}")
        }
        val started = System.nanoTime()
        val builtRequest = request.build()
        return retryAiSpeechSynthesis { execute(config, builtRequest, target, started) }
    }

    private suspend fun execute(config: AiSpeechConfig, request: Request, target: File, started: Long): AiSpeechTestResult {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    target.delete()
                    if (continuation.isActive) continuation.resumeWithException(IOException(
                        if (error is SocketTimeoutException) "AI 语音请求超时，请重试" else "无法连接 AI 语音服务，请检查服务器地址与网络"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!response.isSuccessful) throw responseError(response, config)
                            val body = response.body ?: throw IOException("AI 语音没有返回音频")
                            val type = body.contentType()
                            if (config.protocol == SpeechApiProtocol.OPENAI && (type?.type == "text" ||
                                (type?.type == "application" && (type.subtype.contains("json") || type.subtype.contains("xml"))))) {
                                throw IOException("语音接口返回的不是音频，请检查所选协议")
                            }
                            require(body.contentLength() <= 24 * 1024 * 1024) { "语音响应过大，请检查接口" }
                            body.byteStream().use { input ->
                                AiSpeechResponse.write(config, input, target) {
                                    if (!continuation.isActive) throw CancellationException()
                                }
                            }
                            requireAudio(target)
                        }
                        if (continuation.isActive) continuation.resume(AiSpeechTestResult(
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), target.length())) { _, _, _ -> target.delete() }
                        else target.delete()
                    } catch (error: Exception) {
                        target.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private fun responseError(response: Response, config: AiSpeechConfig): IOException {
        // Never include headers, arbitrary response bodies or a credential echoed by the server.
        val detail = runCatching {
            val json = JSONObject(response.peekBody(16 * 1024L).string())
            val error = json.optJSONObject("error") ?: json
            val code = error.optString("code", "").takeIf { it.matches(Regex("[a-zA-Z0-9_.:-]{1,80}")) }.orEmpty()
            val message = error.optString("message", "")
            listOf(code, message).filter(String::isNotBlank).joinToString("：")
        }.getOrDefault("")
        var safeDetail = detail
        if (config.key.isNotBlank()) safeDetail = safeDetail.replace(config.key, "[已隐藏]", ignoreCase = true)
        safeDetail = safeDetail.replace(Regex("(?i)bearer\\s+[^\\s,;\"']+"), "Bearer [已隐藏]")
            .replace(Regex("(?i)sk-[a-z0-9_-]+"), "[已隐藏]")
            .replace(Regex("[\\r\\n\\p{Cntrl}]+"), " ").take(240)
        val summary = when (response.code) {
            401 -> "AI 语音鉴权失败（401）：服务未接受密钥，请检查当前配置的密钥、服务器和账号权限"
            403 -> "AI 语音访问被拒绝（403）：请检查密钥的模型权限与账号状态"
            429 -> "AI 语音请求受限（429）：请稍后重试并检查服务额度"
            else -> "AI 语音服务返回 ${response.code}"
        }
        return IOException(summary + if (safeDetail.isNotBlank()) "；$safeDetail" else "")
    }

    private fun requireAudio(target: File) {
        val header = ByteArray(12)
        val count = target.inputStream().use { it.read(header) }
        val wav = count == 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        val mp3 = count >= 3 && (header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) ||
            ((header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0))
        require(wav || mp3) { "语音接口未返回可识别的 WAV/MP3 音频，请检查模型和协议" }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
