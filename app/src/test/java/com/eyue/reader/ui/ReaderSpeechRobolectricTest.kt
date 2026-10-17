package com.eyue.reader.ui

import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ReaderSpeechRobolectricTest.HeldSpeechShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderSpeechRobolectricTest {
    private lateinit var speech: ReaderSpeech
    private lateinit var engine: HeldSpeechShadow
    private val positions = mutableListOf<Pair<Int, Int>>()

    @Before fun setUp() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        speech = ReaderSpeech(RuntimeEnvironment.getApplication())
        engine = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()) as HeldSpeechShadow
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
    }

    @After fun tearDown() { speech.release(); idle() }

    @Test fun rangeTimingMapsToOriginalChapterOffsetAcrossUtteranceChunks() {
        speech.start(listOf(ReaderSpeechSegment(2, 0, 37, "A".repeat(600))), 1f) { segment, offset -> positions += segment.chapter to offset }
        val firstId = requireNotNull(engine.currentId)
        val firstLength = engine.spoken.last().length
        engine.utteranceProgressListener.onRangeStart(firstId, 13, 18, 0)
        idle()
        assertEquals(2 to 50, positions.last())
        engine.utteranceProgressListener.onDone(firstId)
        idle()
        val secondId = requireNotNull(engine.currentId)
        engine.utteranceProgressListener.onRangeStart(secondId, 7, 12, 0)
        idle()
        assertEquals(2 to (37 + firstLength + 7), positions.last())
        val count = positions.size
        engine.utteranceProgressListener.onRangeStart(firstId, 88, 90, 0)
        engine.utteranceProgressListener.onError(firstId)
        idle()
        assertEquals(count, positions.size)
        assertTrue(speech.playing.value)
    }

    @Test fun stoppingRejectsAlreadyQueuedRangeEventsAndRestartUsesTheNewReadingAnchor() {
        speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "A".repeat(600))), 1f) { segment, offset -> positions += segment.chapter to offset }
        val previousId = requireNotNull(engine.currentId)
        engine.utteranceProgressListener.onRangeStart(previousId, 90, 95, 0)
        speech.stop()
        assertFalse(speech.playing.value)
        positions.clear()
        speech.start(listOf(ReaderSpeechSegment(3, 4, 219, "A different passage after manual navigation.")), 1f) { segment, offset -> positions += segment.chapter to offset }
        idle()
        assertEquals(listOf(3 to 219), positions)
        engine.utteranceProgressListener.onRangeStart(requireNotNull(engine.currentId), 2, 6, 0)
        idle()
        assertEquals(3 to 221, positions.last())
    }

    @Test fun enginesWithoutRangeTimingAdvanceAcrossChaptersWithoutSplittingSurrogatePairs() {
        val first = "A".repeat(239) + "😀" + "B".repeat(300)
        val second = "Second chapter.\nOriginal paragraph text."
        speech.start(listOf(ReaderSpeechSegment(0, 0, 11, first), ReaderSpeechSegment(1, 0, 0, second)), 1f) { segment, offset -> positions += segment.chapter to offset }
        var utterances = 0
        while (speech.playing.value && utterances++ < 20) {
            engine.utteranceProgressListener.onDone(requireNotNull(engine.currentId))
            idle()
        }
        assertFalse(speech.playing.value)
        assertEquals(first + second, engine.spoken.joinToString(""))
        assertTrue(engine.spoken.all { it.length <= 240 && !Character.isHighSurrogate(it.last()) && !it.contains('\u200B') })
        assertTrue(positions.contains(0 to (11 + first.length)))
        assertTrue(positions.contains(1 to 0))
        assertEquals(1 to second.length, positions.last())
    }

    @Test fun speedChangeRestartsAtTheSpokenAnchorAndRejectsOldCallbacks() {
        speech.start(listOf(ReaderSpeechSegment(2, 0, 37, "A".repeat(600))), 1f) { segment, offset -> positions += segment.chapter to offset }
        val oldId = requireNotNull(engine.currentId)
        engine.utteranceProgressListener.onRangeStart(oldId, 42, 46, 0)
        idle()
        speech.setSpeed(1.5f)
        assertEquals(2 to 79, positions.last())
        val newId = requireNotNull(engine.currentId)
        assertNotEquals(oldId, newId)
        val count = positions.size
        engine.utteranceProgressListener.onDone(oldId)
        idle()
        assertEquals(count, positions.size)
        assertEquals(newId, engine.currentId)
        assertTrue(speech.playing.value)
    }

    @Test fun pauseResumesAtTheLastEngineRangeAndRejectsEventsFromBeforePause() {
        speech.start(listOf(ReaderSpeechSegment(2, 0, 37, "A".repeat(600))), 1f) { segment, offset -> positions += segment.chapter to offset }
        val oldId = requireNotNull(engine.currentId)
        engine.utteranceProgressListener.onRangeStart(oldId, 42, 46, 0)
        idle()
        assertTrue(speech.pause())
        assertTrue(speech.pause())
        val count = positions.size
        engine.utteranceProgressListener.onRangeStart(oldId, 80, 85, 0)
        engine.utteranceProgressListener.onDone(oldId)
        idle()
        assertEquals(count, positions.size)
        assertFalse(speech.playing.value)
        val utterances = engine.spoken.size
        assertTrue(speech.resume())
        assertEquals(2 to 79, positions.last())
        assertEquals(utterances + 1, engine.spoken.size)
        assertNotEquals(oldId, engine.currentId)
        idle()
        assertEquals(utterances + 1, engine.spoken.size)
        speech.stop()
        assertFalse(speech.resume())
    }

    @Test fun enginesWithoutRangeTimingKeepParagraphsInSeparateUtterances() {
        val text = "第一段。\n" + "第二段内容。".repeat(30)
        speech.start(listOf(ReaderSpeechSegment(1, 0, 9, text)), 1f) { segment, offset -> positions += segment.chapter to offset }
        assertEquals("第一段。\n", engine.spoken.single())
        engine.utteranceProgressListener.onDone(engine.currentId)
        idle()
        assertEquals(1 to 14, positions.last())
        assertTrue(engine.spoken.last().startsWith("第二段内容。"))
    }

    @Test fun asynchronousEngineFailurePublishesErrorBeforeReportingThatPlaybackStopped() = runBlocking {
        val stoppedErrors = mutableListOf<String?>()
        val observer = launch(Dispatchers.Unconfined) {
            speech.playing.drop(1).filter { !it }.collect { stoppedErrors += speech.error.value }
        }
        try {
            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "失败段落")), 1f) { _, _ -> }
            engine.utteranceProgressListener.onError(requireNotNull(engine.currentId))
            idle()
            assertEquals(listOf("朗读失败，请检查系统语音是否安装完整。"), stoppedErrors)
            assertFalse(speech.playing.value)
            assertEquals(1, engine.spoken.size)
        } finally { observer.cancel() }
    }

    @Test fun aRejectedSpeakCallCannotLookLikeSuccessfulCompletionToAnImmediateObserver() = runBlocking {
        val stoppedErrors = mutableListOf<String?>()
        val observer = launch(Dispatchers.Unconfined) {
            speech.playing.drop(1).filter { !it }.collect { stoppedErrors += speech.error.value }
        }
        try {
            engine.speakResult = TextToSpeech.ERROR
            speech.start(listOf(ReaderSpeechSegment(0, 0, 0, "失败段落")), 1f) { _, _ -> }
            assertEquals(listOf("系统语音无法开始朗读。"), stoppedErrors)
            assertFalse(speech.playing.value)
        } finally { observer.cancel() }
    }

    private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }

    /** No automatic completion: tests supply the actual engine callbacks in a controlled order. */
    @Implements(TextToSpeech::class)
    class HeldSpeechShadow : ShadowTextToSpeech() {
        var currentId: String? = null
        var speakResult = TextToSpeech.SUCCESS
        val spoken = mutableListOf<String>()

        @Implementation
        public override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            currentId = utteranceId
            spoken += text.toString()
            return speakResult
        }
    }
}
