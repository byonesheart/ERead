package com.eyue.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechClientRobolectricTest {
    private val audio = "RIFF0000WAVEdata".toByteArray()
    private fun config(key: String = "saved-secret") = AiSpeechConfig("https://api.xiaomimimo.com/v1", key,
        "mimo-v2.5-tts", "冰糖", "平静自然", SpeechApiProtocol.MIMO, id = "saved")
    private fun target() = File.createTempFile("speech-client", ".wav", RuntimeEnvironment.getApplication().cacheDir)
    private fun response(audio: ByteArray) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("message", JSONObject().put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(audio)))))).toString()
    private fun OkHttpClient.Builder.reply(code: Int = 200, type: String = "application/json", content: () -> String): OkHttpClient =
        addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(content().toResponseBody(type.toMediaType())).build() }.build()

    @Test fun testUsesTheSavedKeyAndExactMimoSpeechContractAndDecodesAudio() = runBlocking {
        val previous = AiSpeechConfig("https://api.xiaomimimo.com/v1/audio/speech", "saved-secret", "old-tts", "voice", id = "saved")
        val resolved = resolveAiSpeechConfigDraft(config(key = ""), listOf(previous))
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            assertEquals("https://api.xiaomimimo.com/v1/chat/completions", chain.request().url.toString())
            assertEquals("saved-secret", chain.request().header("api-key"))
            assertNull(chain.request().header("Authorization"))
            val buffer = Buffer().apply { chain.request().body!!.writeTo(this) }
            val request = JSONObject(buffer.readUtf8())
            assertEquals("mimo-v2.5-tts", request.getString("model"))
            assertEquals("冰糖", request.getJSONObject("audio").getString("voice"))
            assertEquals("平静自然", request.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("你好，这是 E 阅的语音连接测试。", request.getJSONArray("messages").getJSONObject(1).getString("content"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(audio).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val target = target()
        try {
            val result = AiSpeechClient(client).test(resolved, target)
            assertEquals(1, requests.get())
            assertArrayEquals(audio, target.readBytes())
            assertEquals(audio.size.toLong(), result.audioBytes)
            assertTrue(result.elapsedMillis >= 0)
        } finally { target.delete() }
    }

    @Test fun missingOfficialMimoKeyFailsLocallyBeforeAnyRequestIncludingAfterChangingHost() = runBlocking {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().reply { requests.incrementAndGet(); response(audio) }
        val old = AiSpeechConfig("https://private.example/v1", "private-secret", "tts", "voice", id = "saved")
        val resolved = resolveAiSpeechConfigDraft(config(key = ""), listOf(old))
        val target = target()
        try {
            val error = runCatching { AiSpeechClient(client).test(resolved, target) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains("未保存 API 密钥"))
            assertEquals(0, requests.get())
        } finally { target.delete() }
    }

    @Test fun authenticationErrorsIncludeBoundedUsefulFieldsAndNeverEchoCredentials() = runBlocking {
        val target = target()
        try {
            val content = JSONObject().put("error", JSONObject().put("code", "invalid_api_key")
                .put("message", "Rejected saved-secret; Authorization: Bearer another-secret; sk-redacted123"))
                .put("headers", "do-not-display-this").toString()
            val client = OkHttpClient.Builder().reply(401) { content }
            val error = runCatching { AiSpeechClient(client).test(config(), target) }.exceptionOrNull()!!
            assertTrue(error is IOException)
            assertTrue(error.message.orEmpty().contains("401"))
            assertTrue(error.message.orEmpty().contains("invalid_api_key"))
            assertFalse(error.message.orEmpty().contains("saved-secret"))
            assertFalse(error.message.orEmpty().contains("another-secret"))
            assertFalse(error.message.orEmpty().contains("sk-redacted123"))
            assertFalse(error.message.orEmpty().contains("do-not-display-this"))
            assertFalse(target.exists())
            val forbidden = OkHttpClient.Builder().reply(403) { "{\"message\":\"model permission required\"}" }
            val second = runCatching { AiSpeechClient(forbidden).test(config(), target) }.exceptionOrNull()!!
            assertTrue(second.message.orEmpty().contains("403"))
            assertTrue(second.message.orEmpty().contains("model permission required"))
        } finally { target.delete() }
    }

    @Test fun connectionTestRequiresActualAudioRatherThanAnySuccessfulHttpResponse() = runBlocking {
        val target = target()
        try {
            val malformed = OkHttpClient.Builder().reply { "{\"choices\":[]}" }
            assertNotNull(runCatching { AiSpeechClient(malformed).test(config(), target) }.exceptionOrNull())
            assertFalse(target.exists())
            val openAi = AiSpeechConfig("https://voice.example/v1", "", "tts", "voice")
            val html = OkHttpClient.Builder().reply(type = "text/html") { "<html>OK</html>" }
            assertNotNull(runCatching { AiSpeechClient(html).test(openAi, target) }.exceptionOrNull())
            val invalid = OkHttpClient.Builder().reply(type = "audio/mpeg") { "This is not audio" }
            assertNotNull(runCatching { AiSpeechClient(invalid).test(openAi, target) }.exceptionOrNull())
            assertFalse(target.exists())
        } finally { target.delete() }
    }

    @Test fun openAiUsesBearerAuthenticationAndAllowsAnonymousPrivateServers() = runBlocking {
        val requestedKey = AtomicReference<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedKey.set(chain.request().header("Authorization"))
            assertNull(chain.request().header("api-key"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("ID3abcdefghijk".toResponseBody("audio/mpeg".toMediaType())).build()
        }.build()
        val target = target()
        try {
            AiSpeechClient(client).test(AiSpeechConfig("https://private.example/v1", "token", "tts", "voice"), target)
            assertEquals("Bearer token", requestedKey.get())
            AiSpeechClient(client).test(AiSpeechConfig("https://private.example/v1", "", "tts", "voice"), target)
            assertNull(requestedKey.get())
        } finally { target.delete() }
    }

    @Test fun cancellingAConnectionTestCancelsTheHttpCallAndLeavesNoAudio() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            try {
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < limit) Thread.sleep(5)
                if (chain.call().isCanceled()) cancelled.countDown()
                throw IOException("Canceled")
            } finally { completed.countDown() }
        }.build()
        val target = target()
        target.delete()
        val operation = async(Dispatchers.Default) { AiSpeechClient(client).test(config(), target) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            operation.cancelAndJoin()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(operation.isCancelled)
            assertFalse(target.exists())
        } finally { operation.cancel(); target.delete() }
    }
    @Test fun occasionalEmptyMimoAudioRetriesTheExactTextAndAcceptsTheSecondAudio() = runBlocking {
        val emptyResponses = listOf("", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"audio\":null}}]}", response(ByteArray(0)))
        for (empty in emptyResponses) {
            val requests = AtomicInteger()
            val bodies = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                bodies += Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()
                val content = if (requests.incrementAndGet() == 1) empty else response(audio)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(content.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val target = target()
            try {
                val result = AiSpeechClient(client).synthesize(config(), "这一段不能跳过。", target)
                assertEquals(2, requests.get())
                assertEquals(bodies[0], bodies[1])
                assertEquals("这一段不能跳过。", JSONObject(bodies[0]).getJSONArray("messages").getJSONObject(1).getString("content"))
                assertArrayEquals(audio, target.readBytes())
                assertEquals(audio.size.toLong(), result.audioBytes)
            } finally { target.delete() }
        }
    }
    @Test fun repeatedEmptyMimoAudioStopsAfterThreeRetriesAndRemovesTemporaryOutput() = runBlocking {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().reply { requests.incrementAndGet(); "{\"choices\":[]}" }
        val target = target()
        try {
            val error = runCatching { AiSpeechClient(client).test(config(), target) }.exceptionOrNull()!!
            assertTrue(error is IOException)
            assertTrue(error.message.orEmpty().contains("未返回有效音频"))
            assertTrue(error.message.orEmpty().contains("已重试 3 次，共 4 次"))
            assertEquals(4, requests.get())
            assertFalse(target.exists())
        } finally { target.delete() }
    }
    @Test fun authenticationParametersRefusalsAndInvalidAudioAllRetryTheSameRequestThreeTimes() = runBlocking {
        val responses = listOf(401 to "{\"error\":{\"code\":\"invalid_api_key\"}}", 400 to "{\"error\":{\"code\":\"invalid_model\"}}",
            200 to "{\"error\":{\"code\":\"invalid_model\",\"message\":\"saved-secret\"}}",
            200 to "{\"choices\":[{\"message\":{\"refusal\":\"saved-secret\"}}]}",
            200 to "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{}}]}",
            200 to "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{}}]}",
            200 to "<html>saved-secret</html>", 200 to "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"invalid!\"}}}]}",
            200 to response("not audio".toByteArray()))
        for ((code, content) in responses) {
            val requests = AtomicInteger()
            val client = OkHttpClient.Builder().reply(code) { requests.incrementAndGet(); content }
            val target = target()
            try {
                val error = runCatching { AiSpeechClient(client).test(config(), target) }.exceptionOrNull()!!
                assertEquals(4, requests.get())
                assertTrue(error.message.orEmpty().contains("已重试 3 次，共 4 次"))
                assertFalse(error.message.orEmpty().contains("saved-secret"))
                assertFalse(target.exists())
            } finally { target.delete() }
        }
    }
    @Test fun transportFailuresAndRefusalsMayRecoverOnTheLastAttemptWithoutChangingTheText() = runBlocking {
        val requests = AtomicInteger()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()
            val attempt = requests.incrementAndGet()
            if (attempt == 1) throw IOException("Temporary connection failure")
            val content = if (attempt < 4) "{\"choices\":[{\"message\":{\"refusal\":\"temporary refusal\"}}]}" else response(audio)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val target = target()
        try {
            AiSpeechClient(client).synthesize(config(), "原段落需原样重试", target)
            assertEquals(4, requests.get())
            assertEquals(1, bodies.distinct().size)
            assertArrayEquals(audio, target.readBytes())
        } finally { target.delete() }
    }
    @Test fun cancellingAfterAnEmptyResponsePreventsTheRetryAndLeavesNoAudio() = runBlocking {
        val replied = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().reply {
            requests.incrementAndGet()
            replied.countDown()
            "{\"choices\":[]}"
        }
        val target = target()
        val operation = async(Dispatchers.Default) { AiSpeechClient(client).test(config(), target) }
        try {
            assertTrue(replied.await(5, TimeUnit.SECONDS))
            delay(50)
            operation.cancelAndJoin()
            delay(350)
            assertTrue(operation.isCancelled)
            assertEquals(1, requests.get())
            assertFalse(target.exists())
        } finally { operation.cancel(); target.delete() }
    }
}
