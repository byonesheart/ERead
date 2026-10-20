package com.eyue.reader.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import java.time.Duration
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.AiSpeechCache
import com.eyue.reader.data.SpeechApiProtocol
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AiReaderSpeechRobolectricTest.HeldPlayerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AiReaderSpeechRobolectricTest {
    private lateinit var speech: AiReaderSpeech
    private val positions = mutableListOf<Int>()
    private val source = listOf(ReaderSpeechSegment(2, 0, 37, "A".repeat(100)))

    @Before fun setUp() { HeldPlayerShadow.instances.clear() }
    @After fun tearDown() { if (::speech.isInitialized) speech.release(); idle() }

    @Test fun locationStartsAfterAudioStartsAndTracksActualPlaybackPosition() {
        val downloaded = CompletableDeferred<Unit>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, target ->
            downloaded.await()
            target.writeBytes(byteArrayOf(1))
        }
        speech.start(source, 1f) { _, offset -> positions += offset }
        idle()
        assertTrue(positions.isEmpty())
        assertTrue(HeldPlayerShadow.instances.isEmpty())
        downloaded.complete(Unit)
        await { HeldPlayerShadow.instances.size == 1 }
        val player = HeldPlayerShadow.instances.single()
        assertTrue(positions.isEmpty())
        player.onPrepared.onPrepared(player.real)
        assertEquals(listOf(37), positions)
        player.position = 5_000
        advance()
        assertEquals(87, positions.last())
        speech.setSpeed(1.5f)
        assertEquals(1, player.starts)
        assertEquals(1.5f, player.rate, 0f)
        assertNull(speech.error.value)
    }

    @Test fun preloadsTwoUpcomingParagraphsIncludingTheNextChapterWithoutAdvancingPlayback() {
        val requested = mutableListOf<String>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { text, target ->
            requested += text; target.writeBytes(byteArrayOf(1))
        }
        speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "One\nTwo"),
            ReaderSpeechSegment(1, 0, 0, "Three\nFour\nFive")), 1f) { _, offset -> positions += offset }
        await { requested.size == 3 && HeldPlayerShadow.instances.size == 1 }
        assertEquals(listOf("One", "Two", "Three"), requested)
        assertTrue(positions.isEmpty())
        val first = HeldPlayerShadow.instances.single()
        first.onPrepared.onPrepared(first.real)
        assertEquals(listOf(0), positions)
        assertEquals(3, requested.size)
        first.onComplete.onCompletion(first.real)
        await { requested.size == 4 && HeldPlayerShadow.instances.size == 2 }
        assertEquals("Four", requested.last())
    }

    @Test fun aSlowNextChapterDoesNotDelayTheCurrentChapterAndNeverLoadsEarlierOrDistantChapters() {
        val requestedChapters = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val nextChapter = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, target -> target.writeBytes(byteArrayOf(1)) }
        speech.start(ReaderSpeechSource(98, 1_000, listOf(ReaderSpeechSegment(98, 0, 4_000, "Current tail"))) { chapter ->
            requestedChapters += chapter
            try { nextChapter.await() } finally { cancelled.complete(Unit) }
            listOf(ReaderSpeechSegment(chapter, 0, 0, "Next"))
        }, 1f) { segment, offset -> positions += offset; assertEquals(98, segment.chapter) }
        await { HeldPlayerShadow.instances.size == 1 && requestedChapters.isNotEmpty() }
        val current = HeldPlayerShadow.instances.single()
        current.onPrepared.onPrepared(current.real)
        assertEquals(listOf(4_000), positions)
        assertEquals(listOf(99), requestedChapters)
        assertTrue(current.started)
        speech.stop()
        await { cancelled.isCompleted }
        assertFalse(speech.playing.value)
        assertEquals(1, HeldPlayerShadow.instances.size)
    }

    @Test fun chapterPrefetchStaysWithinTwoUpcomingChunks() {
        val requestedChapters = java.util.concurrent.CopyOnWriteArrayList<Int>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, target -> target.writeBytes(byteArrayOf(1)) }
        speech.start(ReaderSpeechSource(100, 1_000, listOf(ReaderSpeechSegment(100, 0, 0,
            "First\nSecond\nThird\nFourth"))) { chapter ->
            requestedChapters += chapter
            listOf(ReaderSpeechSegment(chapter, 0, 0, "Later"))
        }, 1f) { _, _ -> }
        await { HeldPlayerShadow.instances.size == 1 }
        idle()
        assertTrue(requestedChapters.isEmpty())
        val current = HeldPlayerShadow.instances.single()
        current.onPrepared.onPrepared(current.real)
        current.onComplete.onCompletion(current.real)
        await { HeldPlayerShadow.instances.size == 2 }
        assertTrue(requestedChapters.isEmpty())
    }

    @Test fun readyCurrentChapterChunksKeepPlayingWhileTheFollowingChapterIsLoading() {
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val loadingNext = CompletableDeferred<Unit>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { text, target ->
            requested += text; target.writeBytes(byteArrayOf(1))
        }
        speech.start(ReaderSpeechSource(98, 100, listOf(ReaderSpeechSegment(98, 0, 0, "One\nTwo"))) {
            loadingNext.complete(Unit)
            awaitCancellation()
        }, 1f) { _, _ -> }
        await { HeldPlayerShadow.instances.size == 1 && loadingNext.isCompleted && requested.size == 2 }
        HeldPlayerShadow.instances.single().let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
        await { HeldPlayerShadow.instances.size == 2 }
        HeldPlayerShadow.instances.last().onPrepared.onPrepared(HeldPlayerShadow.instances.last().real)
        assertEquals(listOf("One", "Two"), requested)
        assertTrue(HeldPlayerShadow.instances.last().started)
        assertNull(speech.error.value)
    }

    @Test fun multiChapterPlaybackAdvancesABoundedTwoChapterPrefetchWindow() {
        val loaded = java.util.concurrent.CopyOnWriteArrayList<Int>()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, target -> target.writeBytes(byteArrayOf(1)) }
        speech.start(ReaderSpeechSource(100, 1_000, listOf(ReaderSpeechSegment(100, 0, 0, "Chapter 100"))) { chapter ->
            loaded += chapter
            listOf(ReaderSpeechSegment(chapter, 0, 0, "Chapter $chapter"))
        }, 1f) { _, _ -> }
        await { HeldPlayerShadow.instances.size == 1 && loaded.size == 2 }
        assertEquals(listOf(101, 102), loaded)
        repeat(3) { index ->
            HeldPlayerShadow.instances[index].let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
            await { HeldPlayerShadow.instances.size == index + 2 && loaded.size == index + 3 }
            assertEquals((101..index + 103).toList(), loaded)
        }
    }

    @Test fun stoppingCancelsPendingPrefetchAndDeletesItsTemporaryAudio() {
        val pending = mutableListOf<java.io.File>()
        var cancelled = 0
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { text, target ->
            target.writeBytes(byteArrayOf(1))
            if (text != "One") {
                pending += target
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "One\nTwo\nThree\nFour")), 1f) { _, _ -> }
        await { pending.size == 2 && HeldPlayerShadow.instances.size == 1 }
        speech.stop()
        await { cancelled == 2 && pending.none { it.exists() } }
        assertFalse(speech.playing.value)
        assertNull(speech.error.value)
        assertEquals(1, HeldPlayerShadow.instances.size)
    }

    @Test fun aPrefetchFailureDoesNotInterruptTheCurrentParagraph() {
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { text, target ->
            if (text == "Two") error("Next paragraph failed")
            target.writeBytes(byteArrayOf(1))
        }
        speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "One\nTwo\nThree")), 1f) { _, _ -> }
        await { HeldPlayerShadow.instances.size == 1 }
        val first = HeldPlayerShadow.instances.single()
        first.onPrepared.onPrepared(first.real)
        idle()
        assertTrue(speech.playing.value)
        assertNull(speech.error.value)
        first.onComplete.onCompletion(first.real)
        await { speech.error.value != null }
        assertTrue(speech.error.value.orEmpty().contains("Next paragraph failed"))
        assertTrue(speech.error.value.orEmpty().contains("已重试 3 次，共 4 次"))
    }

    @Test fun failedRequestsStopAfterFourAttemptsWhenSystemFallbackIsDisabled() {
        var requests = 0
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, _ -> requests++; error("MiMo 拒绝合成这段文字") }
        speech.start(source, 1f) { _, offset -> positions += offset }
        await { speech.error.value != null }
        assertEquals(4, requests)
        assertFalse(speech.playing.value)
        assertTrue(speech.error.value.orEmpty().contains("已重试 3 次，共 4 次"))
        assertTrue(speech.error.value.orEmpty().contains("MiMo 拒绝"))
        assertTrue(positions.isEmpty())
    }

    @Test fun failedChunkUsesSystemVoiceAtItsOwnOffsetThenReturnsToAiAndDoesNotCacheSystemAudio() {
        val context = RuntimeEnvironment.getApplication()
        val book = UUID.randomUUID().toString()
        val cache = AiSpeechCache(context)
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "tts", "voice")
        val fallback = HeldSystemSpeech()
        val requests = mutableMapOf<String, Int>()
        val callbacks = mutableListOf<Pair<ReaderSpeechSegment, Int>>()
        val segment = ReaderSpeechSegment(3, 2, 120, "Good\n拒绝😀\nTail")
        speech = AiReaderSpeech(context, book, { config }, fallbackEnabled = true, fallbackFactory = { fallback }) { text, target ->
            requests[text] = (requests[text] ?: 0) + 1
            if (text == "拒绝😀") error("MiMo 拒绝合成这段文字")
            target.writeBytes(byteArrayOf(1))
        }
        try {
            speech.start(listOf(segment), 1f) { current, offset -> callbacks += current to offset }
            await { HeldPlayerShadow.instances.size == 1 }
            val first = HeldPlayerShadow.instances.single()
            first.onPrepared.onPrepared(first.real)
            first.onComplete.onCompletion(first.real)
            await { fallback.starts == 1 }
            assertEquals(listOf(ReaderSpeechSegment(3, 2, 125, "拒绝😀")), fallback.source)
            assertEquals(4, requests["拒绝😀"])
            assertEquals(segment to 125, callbacks.last())
            fallback.emit(2)
            assertEquals(segment to 127, callbacks.last())
            assertTrue(speech.pause())
            idle()
            assertFalse(fallback.playing.value)
            assertFalse(speech.playing.value)
            val pausedLocation = callbacks.last()
            fallback.emit(4)
            advance()
            assertEquals(pausedLocation, callbacks.last())
            assertEquals(1, HeldPlayerShadow.instances.size)
            speech.setSpeed(1.6f)
            assertEquals(1.6f, fallback.rate, 0f)
            assertTrue(speech.resume())
            idle()
            assertTrue(fallback.playing.value)
            assertTrue(speech.playing.value)
            fallback.complete()
            await { HeldPlayerShadow.instances.size == 2 }
            val last = HeldPlayerShadow.instances.last()
            last.onPrepared.onPrepared(last.real)
            assertEquals(segment to 130, callbacks.last())
            last.onComplete.onCompletion(last.real)
            await { !speech.playing.value }
            assertNull(speech.error.value)
            assertNull(cache.find(book, AiSpeechCache.key(config, "拒绝😀"), "mp3"))
            assertEquals(2L, cache.bytes(book))
            assertEquals(1, requests["Good"])
            assertEquals(1, requests["Tail"])
        } finally { speech.stop(); cache.clear(book) }
    }

    @Test fun pausingDuringRetriesDefersSystemReplacementAndStoppingRejectsItsCallbacks() {
        val fallback = HeldSystemSpeech()
        var requests = 0
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication(), fallbackEnabled = true, fallbackFactory = { fallback }) { _, _ ->
            requests++; error("服务错误")
        }
        speech.start(source, 1f) { _, offset -> positions += offset }
        await { requests == 1 }
        assertTrue(speech.pause())
        await { requests == 4 }
        assertEquals(4, requests)
        assertEquals(0, fallback.starts)
        assertTrue(positions.isEmpty())
        assertTrue(speech.resume())
        await { fallback.starts == 1 }
        assertEquals(listOf(37), positions)
        speech.stop()
        assertFalse(fallback.playing.value)
        val previous = positions.toList()
        fallback.emit(12)
        fallback.complete()
        advance()
        assertEquals(previous, positions)
        assertFalse(speech.playing.value)
        assertNull(speech.error.value)
        assertFalse(speech.resume())
        speech.release()
        assertTrue(fallback.released)
    }

    @Test fun unavailableSystemReplacementReportsFailureRatherThanSkippingTheChunk() {
        val fallback = HeldSystemSpeech(fail = true)
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication(), fallbackEnabled = true, fallbackFactory = { fallback }) { _, _ ->
            error("MiMo 拒绝合成这段文字")
        }
        speech.start(source, 1f) { _, offset -> positions += offset }
        await { speech.error.value != null }
        assertFalse(speech.playing.value)
        assertTrue(speech.error.value.orEmpty().contains("已重试 3 次，共 4 次"))
        assertTrue(speech.error.value.orEmpty().contains("系统语音替代失败：未安装系统语音"))
        assertTrue(positions.isEmpty())
    }

    @Test fun systemReplacementErrorDuringPlaybackDoesNotAdvanceToTheNextAiChunk() {
        val fallback = HeldSystemSpeech()
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication(), fallbackEnabled = true, fallbackFactory = { fallback }) { text, target ->
            if (text == "Reject") error("AI 服务拒绝")
            target.writeBytes(byteArrayOf(1))
        }
        speech.start(listOf(ReaderSpeechSegment(1, 0, 20, "Reject\nNext")), 1f) { _, offset -> positions += offset }
        await { fallback.starts == 1 }
        assertEquals(listOf(20), positions)
        fallback.failDuringPlayback()
        await { speech.error.value != null }
        assertFalse(speech.playing.value)
        assertTrue(speech.error.value.orEmpty().contains("系统语音替代失败：系统播放错误"))
        assertEquals(listOf(20), positions)
        assertTrue(HeldPlayerShadow.instances.isEmpty())
    }

    @Test fun identicalUpcomingParagraphsShareOneSynthesisRequest() {
        val context = RuntimeEnvironment.getApplication()
        val book = UUID.randomUUID().toString()
        val cache = AiSpeechCache(context)
        var requests = 0
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "tts", "voice")
        speech = AiReaderSpeech(context, bookId = book, configProvider = { config }) { _, target ->
            requests++; target.writeBytes(byteArrayOf(1))
        }
        try {
            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "First\nRepeated\nRepeated\nLast")), 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == 1 && cache.bytes(book) == 2L }
            assertEquals(2, requests)
        } finally { speech.stop(); cache.clear(book) }
    }

    @Test fun pausingWhilePreparingDoesNotStartOrAdvanceUntilResumed() {
        createSpeech()
        speech.start(source, 1f) { _, offset -> positions += offset }
        await { HeldPlayerShadow.instances.size == 1 }
        val player = HeldPlayerShadow.instances.single()
        assertTrue(speech.pause())
        player.onPrepared.onPrepared(player.real)
        advance()
        assertTrue(positions.isEmpty())
        assertFalse(speech.playing.value)
        assertFalse(player.started)
        assertEquals(0, player.starts)
        assertTrue(speech.resume())
        assertEquals(listOf(37), positions)
        player.position = 4_000
        advance()
        assertEquals(77, positions.last())
        assertTrue(speech.pause())
        val count = positions.size
        player.position = 7_000
        advance()
        assertEquals(count, positions.size)
        assertTrue(speech.resume())
        assertEquals(107, positions.last())
        assertEquals(2, player.starts)
        assertEquals(1, HeldPlayerShadow.instances.size)
    }

    @Test fun stoppingAndStartingAnotherBookRejectsOldPlayerCallbacks() {
        createSpeech()
        speech.start(source, 1f) { _, offset -> positions += offset }
        await { HeldPlayerShadow.instances.size == 1 }
        val old = HeldPlayerShadow.instances.single()
        speech.stop()
        old.onPrepared.onPrepared(old.real)
        old.onComplete.onCompletion(old.real)
        advance()
        assertTrue(positions.isEmpty())
        assertFalse(speech.resume())
        speech.start(listOf(ReaderSpeechSegment(3, 0, 500, "New audio")), 1f) { _, offset -> positions += offset }
        await { HeldPlayerShadow.instances.size == 2 }
        val current = HeldPlayerShadow.instances.last()
        current.onPrepared.onPrepared(current.real)
        assertEquals(listOf(500), positions)
        old.onComplete.onCompletion(old.real)
        old.onError.onError(old.real, 1, 1)
        advance()
        assertEquals(listOf(500), positions)
        assertTrue(speech.playing.value)
        assertNull(speech.error.value)
        speech.stop()
        val count = positions.size
        advance()
        assertEquals(count, positions.size)
    }

    @Test fun completedMimoAudioIsReusedPerBookUntilClearedOrTextOrStyleChanges() {
        val context = RuntimeEnvironment.getApplication()
        val book = UUID.randomUUID().toString()
        val otherBook = UUID.randomUUID().toString()
        val cache = AiSpeechCache(context)
        val requests = AtomicInteger()
        val configuration = AtomicReference(AiSpeechConfig("https://voice.example.org/v1", "test-secret", "mimo-v2.5-tts-voicedesign", "",
            "温柔女声", SpeechApiProtocol.MIMO))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            assertEquals("/v1/chat/completions", chain.request().url.encodedPath)
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("audio", JSONObject()
                .put("data", Base64.getEncoder().encodeToString("RIFF0000WAVEdata".toByteArray()))))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        speech = AiReaderSpeech(context, bookId = book, configProvider = { configuration.get() }, client = client)
        val otherSpeech = AiReaderSpeech(context, bookId = otherBook, configProvider = { configuration.get() }, client = client)
        fun start(controller: AiReaderSpeech = speech, content: List<ReaderSpeechSegment> = source): HeldPlayerShadow {
            val expected = HeldPlayerShadow.instances.size + 1
            controller.start(content, 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == expected }
            return HeldPlayerShadow.instances.last().also { it.onPrepared.onPrepared(it.real) }
        }
        try {
            val first = start()
            first.onComplete.onCompletion(first.real)
            await { !speech.playing.value }
            assertEquals(1, requests.get())
            assertTrue(cache.bytes(book) > 0L)
            val replay = start()
            assertEquals(1, requests.get())
            speech.setSpeed(1.8f)
            assertEquals(1, requests.get())
            assertEquals(1.8f, replay.rate, 0f)
            speech.stop()
            start(otherSpeech)
            assertEquals(2, requests.get())
            assertTrue(cache.bytes(otherBook) > 0L)
            otherSpeech.stop()
            cache.clear(book)
            assertEquals(0L, cache.bytes(book))
            assertTrue(cache.bytes(otherBook) > 0L)
            start()
            assertEquals(3, requests.get())
            speech.stop()
            start(content = listOf(source.single().copy(text = "修改后的正文")))
            assertEquals(4, requests.get())
            speech.stop()
            val previous = configuration.get()
            configuration.set(AiSpeechConfig(previous.url, previous.key, previous.model, "", "沉稳男声", SpeechApiProtocol.MIMO))
            val styled = start()
            assertEquals(5, requests.get())
            assertNull(speech.error.value)
            val beforeFailure = cache.bytes(book)
            styled.onError.onError(styled.real, 1, 1)
            await { !speech.playing.value }
            assertTrue(cache.bytes(book) < beforeFailure)
            assertTrue(cache.bytes(book) > 0L)
            start()
            assertEquals(6, requests.get())
            speech.stop()
            start()
            assertEquals(6, requests.get())
        } finally { otherSpeech.release(); speech.stop(); cache.clear(book); cache.clear(otherBook) }
    }

    @Test fun matchingBookRolesReachTheSpeechApiAndOnlyAffectedPlaybackCachesChange() {
        val context = RuntimeEnvironment.getApplication()
        val book = UUID.randomUUID().toString()
        val cache = AiSpeechCache(context)
        val requests = AtomicInteger()
        val instructions = AtomicReference("")
        val roles = AtomicReference(listOf(com.eyue.reader.data.BookVoiceRole("张三", "张三", "低沉青年男声")))
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "mimo-v2.5-tts", "voice", "旁白自然", SpeechApiProtocol.MIMO,
            rolePromptEnabled = true)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val buffer = okio.Buffer().apply { chain.request().body!!.writeTo(this) }
            val messages = JSONObject(buffer.readUtf8()).getJSONArray("messages")
            instructions.set(messages.getJSONObject(0).getString("content"))
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("audio", JSONObject()
                .put("data", Base64.getEncoder().encodeToString("RIFF0000WAVEdata".toByteArray()))))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        speech = AiReaderSpeech(context, bookId = book, configProvider = { config }, client = client, rolesProvider = { roles.get() },
            commonRolesProvider = { emptyList() })
        fun play(text: String, offset: Int = 0, roleContext: String? = null) {
            val expected = HeldPlayerShadow.instances.size + 1
            speech.start(listOf(ReaderSpeechSegment(0, 0, offset, text, roleContext)), 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == expected }
            speech.stop()
        }
        try {
            play("张三走来了。")
            assertTrue(instructions.get().contains("低沉青年男声"))
            play("张三走来了。")
            assertEquals(1, requests.get())
            roles.set(listOf(roles.get().single().copy(voice = "清亮女声")))
            play("张三走来了。")
            assertEquals(2, requests.get())
            assertTrue(instructions.get().contains("清亮女声"))
            play("没有角色的旁白。")
            assertEquals("旁白自然", instructions.get())
            roles.set(listOf(roles.get().single().copy(description = "新的描述")))
            play("没有角色的旁白。")
            assertEquals(3, requests.get())
            val fullParagraph = "张三说：从这里开始朗读。"
            val offset = fullParagraph.indexOf("从这里")
            play(fullParagraph.substring(offset), offset, fullParagraph)
            assertEquals(4, requests.get())
            assertTrue(instructions.get().contains("清亮女声"))
            assertTrue(instructions.get().contains("新的描述"))
        } finally { speech.stop(); cache.clear(book) }
    }

    private fun createSpeech() {
        speech = AiReaderSpeech(RuntimeEnvironment.getApplication()) { _, target -> target.writeBytes(byteArrayOf(1)) }
    }

    @Test fun finishingTheWholeChapterPublishesItsVerifiedCacheCompletionAutomatically() {
        val context = RuntimeEnvironment.getApplication()
        val text = "First paragraph\nSecond paragraph"
        val file = java.io.File.createTempFile("live-complete", ".txt", context.cacheDir).apply { writeText(text) }
        val book = com.eyue.reader.data.Book(title = "Live completion", format = com.eyue.reader.data.BookFormat.TXT,
            mode = com.eyue.reader.data.LibraryMode.NOVEL, chapters = listOf(com.eyue.reader.data.Chapter(title = "One", textPath = file.absolutePath)))
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "tts", "voice")
        val changes = AtomicInteger()
        val cache = AiSpeechCache(context)
        speech = AiReaderSpeech(context, book.id, configProvider = { config }, bookProvider = { book },
            onCacheChanged = { changes.incrementAndGet() }) { _, target -> target.writeBytes(byteArrayOf(1)) }
        try {
            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, text)), 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == 1 }
            assertEquals(0, changes.get())
            HeldPlayerShadow.instances[0].let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
            await { HeldPlayerShadow.instances.size == 2 }
            assertEquals(0, changes.get())
            HeldPlayerShadow.instances[1].let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
            await { changes.get() == 1 }
            val status = kotlinx.coroutines.runBlocking { cache.chapterCacheStatus(book, config, emptyList()) }
            assertEquals(setOf(0), status.currentChapters)
            val other = AiSpeechConfig.fromJson(config.toJson().put("voice", "another"))
            assertEquals(setOf(0), kotlinx.coroutines.runBlocking { cache.chapterCacheStatus(book, other, emptyList()) }.otherChapters)
        } finally { speech.stop(); cache.clear(book.id); file.delete() }
    }

    @Test fun aCompletedChapterGetsItsBadgeWhileTheFollowingChapterIsStillLoading() {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File.createTempFile("live-complete-waiting", ".txt", context.cacheDir).apply { writeText("Current") }
        val book = com.eyue.reader.data.Book(title = "Completion before prefetch", format = com.eyue.reader.data.BookFormat.TXT,
            mode = com.eyue.reader.data.LibraryMode.NOVEL, chapters = listOf(
                com.eyue.reader.data.Chapter(title = "Current", textPath = file.absolutePath),
                com.eyue.reader.data.Chapter(title = "Following", textPath = "not-yet-read.txt")))
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "tts", "voice")
        val changes = AtomicInteger()
        val cache = AiSpeechCache(context)
        speech = AiReaderSpeech(context, book.id, configProvider = { config }, bookProvider = { book },
            onCacheChanged = { changes.incrementAndGet() }) { _, target -> target.writeBytes(byteArrayOf(1)) }
        try {
            speech.start(ReaderSpeechSource(0, 2, listOf(ReaderSpeechSegment(0, 0, 0, "Current"))) {
                awaitCancellation()
            }, 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == 1 }
            HeldPlayerShadow.instances.single().let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
            await { changes.get() == 1 }
            assertEquals(setOf(0), kotlinx.coroutines.runBlocking { cache.completedChapters(book, config) })
            assertTrue(speech.playing.value)
        } finally { speech.stop(); cache.clear(book.id); file.delete() }
    }

    @Test fun invalidCachedAudioRefreshesTheRevisionAndRemovesItsCompletedChapterBadge() {
        val context = RuntimeEnvironment.getApplication()
        val text = "Cached chapter"
        val file = java.io.File.createTempFile("invalid-cached-speech", ".txt", context.cacheDir).apply { writeText(text) }
        val book = com.eyue.reader.data.Book(title = "Invalid cached audio", format = com.eyue.reader.data.BookFormat.TXT,
            mode = com.eyue.reader.data.LibraryMode.NOVEL, chapters = listOf(
                com.eyue.reader.data.Chapter(title = "Chapter", textPath = file.absolutePath)))
        val config = AiSpeechConfig("https://voice.example.org/v1", "test-secret", "tts", "voice")
        val revision = AtomicInteger()
        val requests = AtomicInteger()
        val cache = AiSpeechCache(context)
        speech = AiReaderSpeech(context, book.id, configProvider = { config }, bookProvider = { book },
            onCacheChanged = { revision.incrementAndGet() }) { _, target ->
            requests.incrementAndGet(); target.writeBytes(byteArrayOf(1))
        }
        fun status() = readerSpeechCacheStatus(book, kotlinx.coroutines.runBlocking { cache.chapterCacheStatus(book, config, emptyList()) })
        try {
            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, text)), 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == 1 }
            HeldPlayerShadow.instances.single().let { it.onPrepared.onPrepared(it.real); it.onComplete.onCompletion(it.real) }
            await { revision.get() == 1 && !speech.playing.value }
            val completed = status()
            assertEquals(setOf(0), completed.chapters)
            assertTrue(completed.complete)

            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, text)), 1f) { _, _ -> }
            await { HeldPlayerShadow.instances.size == 2 }
            assertEquals(1, requests.get())
            HeldPlayerShadow.instances.last().let { it.onPrepared.onPrepared(it.real); it.onError.onError(it.real, 1, 1) }
            await { revision.get() == 2 && speech.error.value != null && !speech.playing.value }
            val refreshed = status()
            assertTrue(refreshed.chapters.isEmpty())
            assertFalse(refreshed.complete)
            assertNull(cache.find(book.id, AiSpeechCache.key(config, text), "mp3"))
        } finally { speech.stop(); cache.clear(book.id); file.delete() }
    }
    private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    private fun advance() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(220)) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("AI speech operation did not settle: ${speech.error.value}", condition())
    }

    private class HeldSystemSpeech(private val fail: Boolean = false) : ReaderSpeechController {
        private val active = MutableStateFlow(false)
        private val failure = MutableStateFlow<String?>(null)
        override val playing = active.asStateFlow()
        override val error = failure.asStateFlow()
        var starts = 0
        var rate = 1f
        var released = false
        var source = emptyList<ReaderSpeechSegment>()
        private var callback: ((ReaderSpeechSegment, Int) -> Unit)? = null
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            this.source = source; this.rate = speed; callback = onLocation; starts++
            if (fail) { failure.value = "未安装系统语音"; return }
            active.value = true
            emit(0)
        }
        fun emit(relative: Int) { source.firstOrNull()?.let { callback?.invoke(it, it.offset + relative) } }
        fun complete() { active.value = false }
        fun failDuringPlayback() { failure.value = "系统播放错误"; active.value = false }
        override fun setSpeed(speed: Float) { this.rate = speed }
        override fun clearError() { failure.value = null }
        override fun pause(): Boolean { active.value = false; return true }
        override fun resume(): Boolean { active.value = true; return true }
        override fun stop() { active.value = false }
        override fun release() { stop(); released = true }
    }

    /** Hold preparation and audio position so tests exercise real controller callbacks deterministically. */
    @Implements(MediaPlayer::class)
    class HeldPlayerShadow {
        @RealObject lateinit var real: MediaPlayer
        lateinit var onPrepared: MediaPlayer.OnPreparedListener
        lateinit var onComplete: MediaPlayer.OnCompletionListener
        lateinit var onError: MediaPlayer.OnErrorListener
        var position = 0
        var started = false
        var starts = 0
        var rate = 1f
        @Implementation fun __constructor__() { instances += this }
        @Implementation fun setAudioAttributes(attributes: AudioAttributes) = Unit
        @Implementation fun setDataSource(path: String) = Unit
        @Implementation fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener) { onPrepared = listener }
        @Implementation fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener) { onComplete = listener }
        @Implementation fun setOnErrorListener(listener: MediaPlayer.OnErrorListener) { onError = listener }
        @Implementation fun prepareAsync() = Unit
        @Implementation fun setPlaybackParams(params: PlaybackParams) { rate = params.speed; started = true }
        @Implementation fun start() { started = true; starts++ }
        @Implementation fun pause() { started = false }
        @Implementation fun release() { started = false }
        @Implementation fun isPlaying() = started
        @Implementation fun getCurrentPosition() = position
        @Implementation fun getDuration() = 10_000
        companion object {
            val instances = mutableListOf<HeldPlayerShadow>()
            @JvmStatic @Implementation fun __staticInitializer__() = Unit
        }
    }
}
