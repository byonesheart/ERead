package com.eyue.reader.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechResponseRobolectricTest {
    private val config = AiSpeechConfig("https://voice.example.org/v1", "secret", "mimo-v2.5-tts", "", protocol = SpeechApiProtocol.MIMO)
    private fun response(data: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
        .put("audio", JSONObject().put("data", data))))).toString().toByteArray()
    @Test fun mimoJsonIsDecodedToActualWaveAudio() {
        val audio = "RIFF0000WAVEdata".toByteArray()
        val target = File.createTempFile("speech", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            AiSpeechResponse.write(config, ByteArrayInputStream(response(Base64.getEncoder().encodeToString(audio))), target) {}
            assertArrayEquals(audio, target.readBytes())
        } finally { target.delete() }
    }
    @Test fun malformedOrNonAudioResponsesAreRejectedWithoutLeakingResponseText() {
        val target = File.createTempFile("speech", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val error = assertThrows(IOException::class.java) { AiSpeechResponse.write(config,
                ByteArrayInputStream("{\"error\":\"private-key\"}".toByteArray()), target) {} }
            assertFalse(error.message.orEmpty().contains("private-key"))
            assertThrows(IOException::class.java) { AiSpeechResponse.write(config, ByteArrayInputStream(response("invalid!")), target) {} }
            assertThrows(IllegalArgumentException::class.java) { AiSpeechResponse.write(config,
                ByteArrayInputStream(response(Base64.getEncoder().encodeToString("not audio".toByteArray()))), target) {} }
        } finally { target.delete() }
    }
    @Test fun openAiAudioIsCopiedWithoutJsonDecodingAndSupportsCancellation() {
        val target = File.createTempFile("speech", ".mp3", RuntimeEnvironment.getApplication().cacheDir)
        val openAi = AiSpeechConfig("https://voice.example.org/v1", "", "tts", "voice")
        try {
            AiSpeechResponse.write(openAi, ByteArrayInputStream(byteArrayOf(1, 2, 3)), target) {}
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { AiSpeechResponse.write(openAi,
                ByteArrayInputStream(ByteArray(40_000)), target) { throw kotlinx.coroutines.CancellationException() } }
        } finally { target.delete() }
    }
    @Test fun emptyMimoAudioIsDistinguishedFromInvalidResponses() {
        val target = File.createTempFile("speech", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val empty = listOf("", "{\"choices\":[]}", "{\"choices\":[{\"message\":{}}]}",
                "{\"choices\":[{\"message\":{\"audio\":null}}]}", response("").toString(Charsets.UTF_8),
                "{\"choices\":[{\"message\":{\"audio\":{\"data\":null}}}]}")
            empty.forEach { body ->
                assertThrows(EmptyMimoAudioException::class.java) { AiSpeechResponse.write(config, ByteArrayInputStream(body.toByteArray()), target) {} }
            }
            val invalid = listOf("{}", "<html>error</html>", "{\"choices\":[{}]}",
                "{\"choices\":[{\"message\":{\"audio\":\"unexpected\"}}]}",
                "{\"choices\":[{\"message\":{\"audio\":{\"data\":123}}}]}")
            invalid.forEach { body ->
                val error = assertThrows(IOException::class.java) { AiSpeechResponse.write(config, ByteArrayInputStream(body.toByteArray()), target) {} }
                assertFalse(error is EmptyMimoAudioException)
            }
        } finally { target.delete() }
    }
    @Test fun serviceErrorsRefusalsAndIncompleteGenerationAreNeverClassifiedAsEmptyAudio() {
        val target = File.createTempFile("speech", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val bodies = listOf("{\"error\":{\"message\":\"private-key\"}}",
                "{\"choices\":[{\"message\":{\"refusal\":\"private-key\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{}}]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{}}]}")
            bodies.forEach { body ->
                val error = assertThrows(IOException::class.java) { AiSpeechResponse.write(config, ByteArrayInputStream(body.toByteArray()), target) {} }
                assertFalse(error is EmptyMimoAudioException)
                assertFalse(error.message.orEmpty().contains("private-key"))
            }
        } finally { target.delete() }
    }
    @Test fun validAudioAllowsExplicitNullRefusalAndFinishReason() {
        val audio = "RIFF0000WAVEdata".toByteArray()
        val body = JSONObject(response(Base64.getEncoder().encodeToString(audio)).toString(Charsets.UTF_8))
        val choice = body.getJSONArray("choices").getJSONObject(0).put("finish_reason", JSONObject.NULL)
        choice.getJSONObject("message").put("refusal", JSONObject.NULL)
        val target = File.createTempFile("speech", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            AiSpeechResponse.write(config, ByteArrayInputStream(body.toString().toByteArray()), target) {}
            assertArrayEquals(audio, target.readBytes())
        } finally { target.delete() }
    }
}
