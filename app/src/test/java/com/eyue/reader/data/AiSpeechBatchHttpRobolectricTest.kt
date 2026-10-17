package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechBatchHttpRobolectricTest {
    private val context = RuntimeEnvironment.getApplication()
    private val folder = File(context.cacheDir, "batch-http-${UUID.randomUUID()}").apply { mkdirs() }
    private val books = mutableListOf<Book>()

    @After fun clean() {
        books.forEach { AiSpeechCache(context).clear(it.id) }
        folder.deleteRecursively()
    }

    @Test fun batchRequestsKeepFullParagraphRolesAcrossChunksAndSkipOnlyCompletelyCachedChapters() = runBlocking {
        withTimeout(20_000) {
            for (protocol in SpeechApiProtocol.entries) SpeechServer(protocol).use { server ->
                val longParagraph = "前".repeat(260) + "张三说你好。"
                val ordinary = "没有角色的普通旁白。"
                val source = File(folder, "${protocol.name}.txt").apply { writeText("$longParagraph\n$ordinary") }
                val role = BookVoiceRole("张三", "张三", "低沉青年男声", "沉稳", "战斗时急促", id = "zhang")
                val unrelated = BookVoiceRole("李四", "李四", "高音女声", id = "li")
                val book = Book(title = "Role request integration", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
                    chapters = listOf(Chapter(title = "First chapter", textPath = source.absolutePath)), voiceRoles = listOf(role, unrelated))
                    .also { books += it }
                val config = AiSpeechConfig(server.baseUrl, "offline-test-token", "tts", "voice", "自然旁白", protocol,
                    rolePromptEnabled = true)
                LibraryRepository(context).save(LibraryState(books = listOf(book), commonVoiceRoles = emptyList()))
                val batch = AiSpeechBatch(context)
                val first = batch.generate(book, config) { }
                assertEquals(setOf(0), first.completedChapters)
                val initial = server.requests()
                assertEquals(3, initial.size)
                assertEquals(listOf("前".repeat(240), "前".repeat(20) + "张三说你好。", ordinary), initial.map { it.text(protocol) })
                assertEquals(longParagraph, initial.take(2).joinToString("") { it.text(protocol) })
                for (request in initial) {
                    assertEquals(if (protocol == SpeechApiProtocol.MIMO) "/v1/chat/completions" else "/v1/audio/speech", request.path)
                    assertEquals(if (protocol == SpeechApiProtocol.MIMO) "offline-test-token" else "Bearer offline-test-token",
                        request.headers[if (protocol == SpeechApiProtocol.MIMO) "api-key" else "authorization"])
                    assertFalse(request.instructions(protocol).contains("李四"))
                    assertFalse(request.text(protocol).contains("角色资料"))
                    assertFalse(request.text(protocol).contains(role.voice))
                }
                val firstHint = initial.first().instructions(protocol)
                assertTrue(firstHint.startsWith("自然旁白"))
                assertTrue(firstHint.contains(role.name))
                assertTrue(firstHint.contains(role.voice))
                assertTrue(firstHint.contains(role.description))
                assertTrue(firstHint.contains(role.scenes))
                assertEquals(firstHint, initial[1].instructions(protocol))
                assertEquals("自然旁白", initial[2].instructions(protocol))
                assertEquals(setOf(0), AiSpeechCache(context).completedChapters(book, config))

                batch.generate(book, config) { }
                assertEquals("A complete chapter must make no further HTTP requests", 3, server.requests().size)

                val changed = book.copy(voiceRoles = listOf(role.copy(voice = "清亮青年声"), unrelated))
                assertEquals(emptySet<Int>(), AiSpeechCache(context).completedChapters(changed, config))
                batch.generate(changed, config) { }
                val regenerated = server.requests().drop(3)
                assertEquals("Changing a matching role regenerates every chunk of the partial chapter", 3, regenerated.size)
                assertEquals(initial.map { it.text(protocol) }, regenerated.map { it.text(protocol) })
                regenerated.take(2).forEach {
                    assertTrue(it.instructions(protocol).contains("清亮青年声"))
                    assertFalse(it.instructions(protocol).contains("低沉青年男声"))
                    assertFalse(it.instructions(protocol).contains("李四"))
                }
                assertEquals("自然旁白", regenerated[2].instructions(protocol))
                assertEquals(setOf(0), AiSpeechCache(context).completedChapters(changed, config))
                batch.generate(changed, config) { }
                assertEquals(6, server.requests().size)
            }
        }
        Unit
    }

    private data class RecordedRequest(val path: String, val headers: Map<String, String>, val body: JSONObject) {
        fun text(protocol: SpeechApiProtocol): String = if (protocol == SpeechApiProtocol.MIMO)
            body.getJSONArray("messages").getJSONObject(1).getString("content") else body.getString("input")
        fun instructions(protocol: SpeechApiProtocol): String = if (protocol == SpeechApiProtocol.MIMO)
            body.getJSONArray("messages").getJSONObject(0).getString("content") else body.getString("instructions")
    }

    /** Exercise the production OkHttp client without a paid service or a replacement synthesizer. */
    private class SpeechServer(protocol: SpeechApiProtocol) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        private val received = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        private val failure = AtomicReference<Throwable?>()
        private val response: ByteArray = if (protocol == SpeechApiProtocol.MIMO) JSONObject().put("choices", JSONArray()
            .put(JSONObject().put("message", JSONObject().put("audio", JSONObject().put("data",
                Base64.getEncoder().encodeToString("RIFF0000WAVEdata".toByteArray())))))).toString().toByteArray(Charsets.UTF_8)
            else "ID3test-mp3-data".toByteArray(Charsets.US_ASCII)
        private val mime = if (protocol == SpeechApiProtocol.MIMO) "application/json" else "audio/mpeg"
        private val worker = thread(name = "batch-speech-http", isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val first = readLine(input)
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        require(colon > 0) { "Malformed HTTP header" }
                        headers[line.substring(0, colon).lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
                    }
                    val length = requireNotNull(headers["content-length"]).toInt()
                    require(length in 1..64_000) { "Unexpected request size" }
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0) { "Incomplete HTTP request body" }
                        offset += count
                    }
                    received += RecordedRequest(first.split(' ')[1], headers, JSONObject(bytes.toString(Charsets.UTF_8)))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII))
                        write(response)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                if (!(error is SocketException && server.isClosed)) failure.set(error)
            }
        }
        fun requests(): List<RecordedRequest> {
            failure.get()?.let { throw AssertionError("Local speech server failed", it) }
            return synchronized(received) { received.toList() }
        }
        override fun close() {
            server.close()
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("Local speech server failed", it) }
            check(!worker.isAlive) { "Local speech server did not stop" }
        }
        private fun readLine(input: BufferedInputStream): String {
            val result = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0) { "Incomplete HTTP headers" }
                if (next == '\n'.code) return result.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                require(result.size() < 8_192) { "HTTP header too long" }
                result.write(next)
            }
        }
    }
}
