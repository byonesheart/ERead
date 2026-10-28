package com.eyue.reader.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.eyue.reader.data.Book
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real text layouts and gestures, with the audio engine held at explicit source offsets. */
@RunWith(RobolectricTestRunner::class)
// API 28's simulated window has no native SurfaceSession for the system magnifier.
// Selection, gestures, menus and speech assertions still run against the real Compose UI.
@Config(sdk = [28], shadows = [ReaderNarrationInteractionRobolectricTest.NoSurfaceMagnifier::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderNarrationInteractionRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private val voice = ControllableSpeech()
    private val selectedVoice = mutableStateOf(voice)
    private val settings = mutableStateOf(ReaderSettings())
    private val annotations = mutableStateOf<List<Annotation>>(emptyList())
    private val viewportHeight = mutableStateOf(640.dp)
    private data class Position(val chapter: Int, val page: Int, val offset: Int)
    private val position = AtomicReference<Position?>()
    private var audio: ServiceController<ReaderAudioService>? = null
    private var audioStartId = 0
    private var autoDispatchAudio = true
    private val chapters = (0..5).map { chapter ->
        (0 until 18).joinToString("\n") { paragraph ->
            "CH${chapter}_P${paragraph.toString().padStart(2, '0')}. " +
                "The quiet harbor opens a new route. A reader can follow this unique paragraph through every page."
        }
    }

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "reader-narration-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            ReaderAudioService.owner?.stop()
            audio?.destroy()
            audio = null
        }
        root.deleteRecursively()
    }

    @Test fun spokenParagraphHighlightMovesAndSpeechAdvancesAcrossPagesAndChapters() {
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        emit(0, paragraphOffset(0, 0) + 12)
        assertHighlight(0, paragraphOffset(0, 0) + 12)
        emit(0, paragraphOffset(0, 1) + 12)
        assertHighlight(0, paragraphOffset(0, 1) + 12)

        val nextPage = voice.source.first { it.chapter == 0 && it.page > 0 }
        emit(0, nextPage.offset + 3)
        await { position.get()?.page == nextPage.page }
        assertHighlight(0, nextPage.offset + 3)

        emit(1, paragraphOffset(1, 2) + 12)
        await { position.get()?.chapter == 1 }
        assertHighlight(1, paragraphOffset(1, 2) + 12)
        assertEquals("Page and chapter transitions must use the existing audio queue", 1, voice.starts)
    }

    @Test fun pagedAiNarrationUsesWholeChaptersForBatchCacheAndStillFollowsPages() {
        voice.chapterSegments = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        assertEquals(1, voice.source.size)
        assertEquals(listOf(chapters[0]), voice.source.map { it.text })
        val nextChapter = runBlocking { requireNotNull(voice.chapterSource).loadChapter(1) }.single()
        assertEquals(chapters[1], nextChapter.text)
        val nextOffset = paragraphOffset(0, 8) + 12
        emit(0, nextOffset)
        await { position.get()?.let { it.chapter == 0 && it.page > 0 && it.offset == nextOffset } == true }
        assertHighlight(0, nextOffset)
        assertEquals(1, voice.starts)
    }

    @Test fun speechBarSettingsOpensWithoutInterruptingTheCurrentReading() {
        var opened = 0
        showReader(ReadingMode.PAGED, follow = false, onSpeechSettings = { opened++ })
        compose.onNodeWithTag("reader_speech_settings").assertDoesNotExist()
        startListening()
        compose.onNodeWithTag("reader_speech_settings").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, voice.starts)
            assertTrue(voice.playing.value)
        }
        closeTools()
        compose.onNodeWithTag("reader_speech_settings").assertDoesNotExist()
    }

    @Test fun shortNotePreviewKeepsNarrationPlayingAndDefersPageFollowingUntilClosed() {
        verifyNotePreviewKeepsNarration("随手记下", dialog = false)
    }

    @Test fun longNotePreviewKeepsNarrationPlayingAndDefersPageFollowingUntilClosed() {
        verifyNotePreviewKeepsNarration("完整记录这段话的想法，在查看长篇笔记期间仍应继续朗读。", dialog = true)
    }

    private fun verifyNotePreviewKeepsNarration(note: String, dialog: Boolean) {
        val start = chapters[0].indexOf("quiet")
        val item = Annotation(bookId = "", chapterIndex = 0, pageIndex = 0,
            characterOffset = start, endOffset = start + 5, quote = "quiet", note = note, kind = AnnotationKind.NOTE)
        showReader(ReadingMode.PAGED, follow = true, notes = listOf(item))
        startListening()
        closeTools()
        tapTextWord("quiet")
        compose.onNodeWithTag("reader_note_popup").assertIsDisplayed()
        compose.onNodeWithText(note, substring = false).assertIsDisplayed()
        val previous = requireNotNull(position.get())
        val target = voice.source.first { it.chapter == 0 && it.page > previous.page }
        emit(0, target.offset + 3)
        assertTrue(voice.playing.value)
        assertEquals(0, voice.pauses)
        assertEquals(1, voice.starts)
        assertEquals(previous.page, position.get()?.page)
        compose.onNodeWithText(note, substring = false).assertIsDisplayed()
        if (dialog) compose.onNodeWithText("关闭", substring = false).performClick()
        else compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        await { position.get()?.page == target.page }
        assertTrue(voice.playing.value)
        assertEquals(1, voice.starts)
    }

    @Config(shadows = [NoSurfaceMagnifier::class])
    @Test fun writingANoteKeepsSelectionAndDraftWhileNarrationAdvancesAcrossPages() {
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        closeTools()
        tapTextWord("quiet", longPress = true)
        compose.onNodeWithTag("reader_action_note").assertIsDisplayed()
        val previous = requireNotNull(position.get())
        val target = voice.source.first { it.chapter == 0 && it.page > previous.page }
        emit(0, target.offset + 3)
        compose.onNodeWithTag("reader_action_note").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_note_input").performTextInput("朗读继续时写下的笔记")
        emit(0, target.offset + 8)
        compose.onNodeWithTag("reader_note_input").assertTextContains("朗读继续时写下的笔记")
        assertTrue(voice.playing.value)
        assertEquals(0, voice.pauses)
        assertEquals(previous.page, position.get()?.page)
        compose.onNodeWithTag("reader_action_save").performScrollTo().performClick()
        await { position.get()?.page == target.page }
        assertEquals("朗读继续时写下的笔记", annotations.value.single().note)
        assertTrue(voice.playing.value)
        assertEquals(1, voice.starts)
    }

    @Test fun changingEngineKeepsTheSpeechAnchorAndNewEngineOwnsNavigation() {
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        val offset = paragraphOffset(0, 1) + 12
        emit(0, offset)
        val audioSession = ReaderAudioService.owner
        val nextVoice = ControllableSpeech()
        compose.runOnUiThread { selectedVoice.value = nextVoice }
        drain()
        assertTrue(voice.released)
        assertFalse(voice.playing.value)
        await { nextVoice.starts == 1 && nextVoice.playing.value }
        assertTrue("The engine switch must retain the existing media session", audioSession === ReaderAudioService.owner)
        assertEquals(offset, nextVoice.source.first().offset)
        compose.onNodeWithText("同步").performClick()
        closeTools()
        compose.mainClock.autoAdvance = false
        val previousPauses = voice.pauses
        swipeToNextPage()
        assertFalse(nextVoice.playing.value)
        assertEquals(previousPauses, voice.pauses)
        advanceBy(1_200)
        await { nextVoice.starts == 2 && nextVoice.playing.value }
        assertEquals(1, voice.starts)
        assertEquals(requireNotNull(position.get()).offset, nextVoice.source.first().offset)
    }

    @Test fun changingEngineWhilePausedKeepsItPausedUntilExplicitResumeAtTheOriginalAnchor() {
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        val offset = paragraphOffset(0, 1) + 12
        emit(0, offset)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        val nextVoice = ControllableSpeech()
        compose.runOnUiThread { selectedVoice.value = nextVoice }
        drain()
        advanceBy(1_200)
        assertTrue(voice.released)
        assertFalse(nextVoice.playing.value)
        assertEquals(0, nextVoice.starts)
        compose.runOnUiThread { requireNotNull(audio).get().callbacks.onPlay() }
        await { nextVoice.starts == 1 && nextVoice.playing.value }
        assertEquals(offset, nextVoice.source.first().offset)
    }

    @Test fun changingEngineBeforeServiceStartReplacesThePendingAudioWithoutStartingTheOldEngine() {
        showReader(ReadingMode.PAGED, follow = false)
        autoDispatchAudio = false
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("朗读").performClick()
        await { ReaderAudioService.owner?.pending != null }
        val session = ReaderAudioService.owner
        val nextVoice = ControllableSpeech()
        compose.runOnUiThread { selectedVoice.value = nextVoice }
        await { voice.released && ReaderAudioService.owner?.pending != null }
        assertEquals(0, voice.starts)
        assertEquals(0, nextVoice.starts)
        assertTrue(session === ReaderAudioService.owner)
        autoDispatchAudio = true
        await { nextVoice.starts == 1 && nextVoice.playing.value }
        assertEquals(0, voice.starts)
        assertQueueStartsAtCurrentPage(nextVoice)
    }

    @Test fun independentPromptCanConfirmOrReturnAndDisappearsAfterManualMovementSettles() {
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        closeTools()
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()
        assertTrue(voice.playing.value)
        compose.mainClock.autoAdvance = false

        swipeToNextPage()
        compose.onNodeWithTag("reader_speech_prompt").assertIsDisplayed()
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()
        compose.onNodeWithText("确认").assertIsDisplayed()
        compose.onNodeWithText("返回原位置").assertIsDisplayed()
        val latestSpoken = paragraphOffset(0, 4) + 10
        emit(0, latestSpoken)
        compose.onNodeWithText("返回原位置").performClick()
        await { position.get()?.offset == latestSpoken }
        assertEquals("Returning the page must not restart audio", 1, voice.starts)
        assertTrue(voice.playing.value)

        swipeToNextPage()
        val confirmed = requireNotNull(position.get())
        compose.onNodeWithText("确认").performClick()
        await { voice.starts == 2 && voice.playing.value }
        assertEquals(confirmed.chapter, voice.source.first().chapter)
        assertEquals(confirmed.offset, voice.source.first().offset)
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()

        swipeToNextPage()
        compose.onNodeWithTag("reader_speech_prompt").assertIsDisplayed()
        advanceBy(1_200)
        compose.onNodeWithTag("reader_speech_prompt").assertDoesNotExist()
        assertTrue(voice.playing.value)
        assertEquals(2, voice.starts)
    }

    @Test fun synchronizedPagingAndScrollingWaitOneSecondAfterManualMovementBeforeRestarting() {
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        compose.onNodeWithText("同步").performClick().assertIsSelected()
        closeTools()
        compose.mainClock.autoAdvance = false
        swipeToNextPage()
        assertFalse(voice.playing.value)
        advanceBy(600)
        assertEquals("Do not resume during the one-second settling interval", 1, voice.starts)
        assertFalse(voice.playing.value)
        advanceBy(600)
        await { voice.starts == 2 && voice.playing.value }
        assertQueueStartsAtCurrentPage()

        compose.runOnUiThread { settings.value = settings.value.copy(readingMode = ReadingMode.SCROLL) }
        await { compose.onAllNodesWithTag("reader_scroll").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_scroll").performTouchInput {
            down(Offset(width / 2f, height * .6f))
            // Cross touch slop before the text field's long-press selection timeout.
            moveBy(Offset(0f, -48f), delayMillis = 16)
            moveTo(Offset(width / 2f, height * .4f), delayMillis = 16)
        }
        drain()
        assertFalse(voice.playing.value)
        advanceBy(1_200)
        assertEquals("An ongoing drag must stay paused, even after one second", 2, voice.starts)
        compose.onNodeWithTag("reader_scroll").performTouchInput {
            advanceEventTime(600)
            moveBy(Offset(0f, -8f))
            up()
        }
        drain()
        advanceBy(600)
        assertEquals(2, voice.starts)
        assertFalse(voice.playing.value)
        advanceBy(600)
        await { voice.starts == 3 && voice.playing.value }
        assertQueueStartsAtCurrentPage()
    }

    @Test fun synchronizedPagedResumeAfterPausedChapterNavigationStartsAtTheCurrentPage() {
        assertSynchronizedResumeAfterPausedNavigation(ReadingMode.PAGED)
    }

    @Test fun synchronizedScrollResumeFromSystemPlayerAfterPausedChapterNavigationStartsAtTheCurrentPage() {
        assertSynchronizedResumeAfterPausedNavigation(ReadingMode.SCROLL, fromSystemPlayer = true)
    }

    private fun assertSynchronizedResumeAfterPausedNavigation(mode: ReadingMode, fromSystemPlayer: Boolean = false) {
        voice.resumable = true
        showReader(mode, follow = true)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        drain()
        assertFalse(voice.playing.value)
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        advanceBy(1_200)
        assertFalse("Moving while paused must not start audio automatically", voice.playing.value)
        assertEquals(1, voice.starts)
        val destination = requireNotNull(position.get())
        if (fromSystemPlayer) compose.runOnUiThread { requireNotNull(audio).get().callbacks.onPlay() }
        else compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.playing.value }
        assertEquals("Manual navigation invalidates the old paused queue", 0, voice.resumes)
        assertEquals(2, voice.starts)
        assertEquals(destination.chapter, voice.source.first().chapter)
        assertEquals(destination.offset, voice.source.first().offset)
        assertEquals(destination.chapter, position.get()?.chapter)
    }

    @Test fun synchronizedPauseWithoutNavigationResumesTheExistingAudioQueue() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        val offset = paragraphOffset(0, 2) + 12
        emit(0, offset)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.playing.value }
        assertEquals(1, voice.starts)
        assertEquals(1, voice.resumes)
        assertEquals(offset, position.get()?.offset)
    }

    @Test fun returningToTheVisibleSpokenNodeAfterManualNavigationRestartsItsSentence() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        val offset = 12
        emit(0, offset)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        compose.onNodeWithContentDescription("上一章").performClick()
        await { position.get()?.let { it.chapter == 0 && it.offset == 0 } == true }
        compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.playing.value }
        val sentenceStart = chapters[0].indexOf("The quiet")
        assertEquals("The visible node must restart at its sentence, not the paused word", 2, voice.starts)
        assertEquals(0, voice.resumes)
        assertEquals(sentenceStart, voice.source.first().offset)
        assertEquals(sentenceStart, position.get()?.offset)
    }

    @Test fun aVisibleSpokenNodeInAnotherParagraphOfTheSamePageRestartsItsOwnSentence() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        val offset = paragraphOffset(0, 1) + 12
        emit(0, offset)
        assertEquals("The second paragraph must still be on the first visible page", 0, position.get()?.page)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        compose.onNodeWithContentDescription("上一章").performClick()
        await { position.get()?.let { it.chapter == 0 && it.offset == 0 } == true }
        compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.starts == 2 && voice.playing.value }
        assertEquals(0, voice.resumes)
        assertEquals(chapters[0].indexOf("The quiet", paragraphOffset(0, 1)), voice.source.first().offset)
    }

    @Test fun aSmallDragWithinTheSamePageRestartsTheSpokenSentenceAfterOneSecond() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        emit(0, 12)
        closeTools()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader_body").performTouchInput {
            down(Offset(width * .6f, height * .3f))
            moveBy(Offset(-28f, 0f), 16)
            moveBy(Offset(-5f, 0f), 16)
            up()
        }
        drain()
        advanceBy(600)
        assertFalse(voice.playing.value)
        assertEquals(1, voice.starts)
        advanceBy(600)
        await { voice.playing.value }
        assertEquals(2, voice.starts)
        assertEquals(0, voice.resumes)
        assertEquals(chapters[0].indexOf("The quiet"), voice.source.first().offset)
        assertEquals(chapters[0].indexOf("The quiet"), position.get()?.offset)
    }

    @Test fun scrollingWithinTheSameParagraphRestartsTheVisibleSpokenSentence() {
        voice.resumable = true
        showReader(ReadingMode.SCROLL, follow = true)
        startListening()
        emit(0, 70)
        closeTools()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader_scroll").performTouchInput {
            down(Offset(width / 2f, height * .6f))
            moveBy(Offset(0f, -28f), 16)
            moveBy(Offset(0f, -5f), 16)
            up()
        }
        drain()
        advanceBy(1_200)
        await { voice.playing.value }
        assertEquals("The visible paused node restarts its sentence after the scroll settles", 2, voice.starts)
        assertEquals(0, voice.resumes)
        assertEquals(chapters[0].indexOf("A reader"), voice.source.first().offset)
        emit(0, 75)
        assertTrue(voice.playing.value)
    }

    @Test fun enablingSyncDuringIndependentPlaybackKeepsTheQueueRunningAfterEarlierNavigation() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        val starts = voice.starts
        val pauses = voice.pauses
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        compose.onNodeWithText("同步").performClick()
        advanceBy(1500)
        assertTrue("Changing sync must not pause the active queue", voice.playing.value)
        assertEquals(starts, voice.starts)
        assertEquals(pauses, voice.pauses)
        emit(0, paragraphOffset(0, 3) + 12)
        await { position.get()?.chapter == 0 }
        assertEquals(paragraphOffset(0, 3) + 12, position.get()?.offset)
    }

    @Test fun disablingSyncWhileNavigationIsPendingResumesTheOriginalQueueWithoutReplaying() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        assertFalse(voice.playing.value)
        compose.onNodeWithText("同步").performClick()
        advanceBy(1500)
        assertTrue("Disabling sync must not strand a queue paused for navigation", voice.playing.value)
        assertEquals(1, voice.starts)
        assertEquals(1, voice.resumes)
        assertEquals(1, position.get()?.chapter)
    }

    @Test fun togglingSyncRepeatedlyDuringPlaybackNeverRestartsAnEarlierParagraph() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        val pauses = voice.pauses
        for (paragraph in 1..4) {
            val offset = paragraphOffset(0, paragraph) + 12
            emit(0, offset)
            compose.onNodeWithText("同步").performClick()
            advanceBy(1500)
            assertTrue(voice.playing.value)
            assertEquals(1, voice.starts)
            assertEquals(pauses, voice.pauses)
        }
    }

    @Test fun disablingSyncNeverResumesAnExplicitUserPause() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithText("同步").performClick()
        advanceBy(1500)
        assertFalse(voice.playing.value)
        assertEquals(1, voice.starts)
        assertEquals(0, voice.resumes)
    }

    @Test fun enablingSyncAfterPausedNavigationKeepsTheNewPageUntilExplicitResume() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        compose.onNodeWithText("同步").performClick()
        advanceBy(1_200)
        assertEquals(1, position.get()?.chapter)
        assertFalse(voice.playing.value)
        compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.playing.value }
        assertEquals(1, voice.source.first().chapter)
        assertEquals(0, voice.resumes)
    }

    @Test fun independentResumeAfterPausedNavigationRetainsTheOriginalNarrationPosition() {
        voice.resumable = true
        showReader(ReadingMode.PAGED, follow = false)
        startListening()
        emit(0, paragraphOffset(0, 2) + 12)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithContentDescription("下一章").performClick()
        await { position.get()?.chapter == 1 }
        compose.onNodeWithContentDescription("继续朗读").performClick()
        await { voice.playing.value }
        assertEquals(1, voice.starts)
        assertEquals(1, voice.resumes)
        assertEquals(0, voice.source.first().chapter)
        assertEquals(1, position.get()?.chapter)
    }

    @Test fun synchronizedScrollStartFromLaterChapterAndNaturalChapterFollowKeepQueueOrder() {
        showReader(ReadingMode.SCROLL, follow = true, startChapter = 3, chapterCount = 6)
        startListening()
        assertEquals(3, voice.source.first().chapter)
        assertEquals(listOf(3), voice.source.map { it.chapter }.distinct())
        assertEquals(6, requireNotNull(voice.chapterSource).chapterCount)
        assertEquals(3, position.get()?.chapter)
        emit(3, paragraphOffset(3, 17) + 12)
        await { position.get()?.let { it.chapter == 3 && it.offset == paragraphOffset(3, 17) + 12 } == true }
        emit(4, paragraphOffset(4, 0) + 12)
        await { position.get()?.chapter == 4 }
        advanceBy(1_200)
        assertEquals("Automatic following must never replace the narration queue", 1, voice.starts)
        assertEquals(4, position.get()?.chapter)
    }

    @Test fun manualNavigationReplacesThePendingServiceStartWithTheSettledPageAnchor() {
        showReader(ReadingMode.PAGED, follow = true)
        autoDispatchAudio = false
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("朗读").performClick()
        await { ReaderAudioService.owner?.pending != null }
        assertEquals(0, voice.starts)
        closeTools()
        compose.mainClock.autoAdvance = false

        swipeToNextPage()
        val destination = requireNotNull(position.get())
        assertTrue(destination.page > 0)
        assertTrue("Navigation must cancel audio still waiting for the service", ReaderAudioService.owner?.pending == null)
        assertEquals(0, voice.starts)
        advanceBy(600)
        assertTrue("The previous pending callback must stay cancelled during settling", ReaderAudioService.owner?.pending == null)
        assertEquals(0, voice.starts)
        advanceBy(600)
        await { ReaderAudioService.owner?.pending != null }
        assertEquals("Do not start audio before the service delivers the replacement request", 0, voice.starts)

        autoDispatchAudio = true
        await { voice.starts == 1 && voice.playing.value }
        assertEquals(destination.chapter, voice.source.first().chapter)
        assertEquals(destination.offset, voice.source.first().offset)
        assertQueueStartsAtCurrentPage()
    }

    @Test fun viewportReflowAndBackgroundResumeKeepAudioQueueAndHighlightTheLatestCallback() {
        showReader(ReadingMode.PAGED, follow = true)
        startListening()
        emit(0, paragraphOffset(0, 2) + 10)
        assertHighlight(0, paragraphOffset(0, 2) + 10)
        compose.runOnUiThread { viewportHeight.value = 500.dp }
        await { compose.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot.height < 500f }
        emit(0, paragraphOffset(0, 5) + 10)
        assertHighlight(0, paragraphOffset(0, 5) + 10)
        assertEquals("Viewport reflow must not replay an old paragraph", 1, voice.starts)

        runBlocking { voice.ensureChapter(1) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { voice.emit(1, paragraphOffset(1, 3) + 10) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { position.get()?.chapter == 1 }
        assertHighlight(1, paragraphOffset(1, 3) + 10)
        assertEquals("Returning from the background must retain the current audio queue", 1, voice.starts)
        assertTrue(voice.playing.value)
    }

    private fun showReader(mode: ReadingMode, follow: Boolean, onSpeechSettings: () -> Unit = {}, startChapter: Int = 0,
        chapterCount: Int = 2, notes: List<Annotation> = emptyList()) {
        val book = Book(title = "朗读交互回归", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapterIndex = startChapter,
            chapters = chapters.take(chapterCount).mapIndexed { index, text ->
                Chapter(title = "第 ${index + 1} 章", characterCount = text.length,
                    textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath)
            })
        annotations.value = notes.map { it.copy(bookId = book.id) }
        settings.value = ReaderSettings(readingMode = mode, ttsFollowPage = follow, fontSize = 24f, lineHeight = 1.4f,
            showTime = false, showBattery = false)
        compose.setContent {
            val current = selectedVoice.value
            val factory = remember(current) { { _: android.content.Context -> current as ReaderSpeechController } }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalReaderSpeechFactory provides factory) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(viewportHeight.value)) {
                        ReaderScreen(book, settings.value, annotations.value, {},
                            { chapter, page, offset, _, _ -> position.set(Position(chapter, page, offset)) },
                            { settings.value = it }, { annotations.value = annotations.value + it },
                            { id -> annotations.value = annotations.value.filterNot { it.id == id } }, {}, emptyFlow(), onSpeechSettings = onSpeechSettings)
                    }
                }
            }
        }
        await {
            position.get() != null && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun startListening() {
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("朗读").performClick()
        await { voice.starts == 1 && voice.playing.value }
        compose.onNodeWithTag("reader_speech_bar").assertIsDisplayed()
    }

    private fun closeTools() {
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        drain()
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
    }

    private fun swipeToNextPage() {
        val previous = requireNotNull(position.get())
        compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft(durationMillis = 160) }
        await { position.get()?.let { it.chapter != previous.chapter || it.page > previous.page } == true }
    }

    private fun emit(chapter: Int, offset: Int) {
        runBlocking { voice.ensureChapter(chapter) }
        compose.runOnUiThread { voice.emit(chapter, offset) }
        drain()
    }

    private fun tapTextWord(word: String, longPress: Boolean = false) {
        val results = mutableListOf<TextLayoutResult>()
        val text = compose.onNodeWithContentDescription("阅读正文")
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val index = layout.layoutInput.text.text.indexOf(word).also { require(it >= 0) }
        val point = layout.getBoundingBox(index + 1).center
        text.performTouchInput { if (longPress) longClick(point) else click(point) }
        drain()
    }

    private fun paragraphOffset(chapter: Int, paragraph: Int): Int =
        chapters[chapter].indexOf("CH${chapter}_P${paragraph.toString().padStart(2, '0')}.").also { require(it >= 0) }

    private fun assertHighlight(chapter: Int, offset: Int) {
        await { position.get()?.let { it.chapter == chapter && it.offset == offset } == true }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val rendered = results.single().layoutInput.text
        val plain = rendered.text.replace('\u200B', '\n')
        val pageStart = chapters[chapter].indexOf(plain)
        assertTrue("Displayed text must come from the actual source chapter", pageStart >= 0)
        assertEquals("The numbered source must identify a unique page", pageStart, chapters[chapter].lastIndexOf(plain))
        val paragraphStart = chapters[chapter].lastIndexOf('\n', offset - 1) + 1
        val paragraphEnd = chapters[chapter].indexOf('\n', offset).takeIf { it >= 0 } ?: chapters[chapter].length
        val highlighted = rendered.spanStyles.filter { it.item.background != Color.Unspecified && it.item.background.alpha > 0f }
        assertEquals("Exactly the current spoken paragraph should have a background", 1, highlighted.size)
        assertEquals((paragraphStart - pageStart).coerceIn(0, rendered.length), highlighted.single().start)
        assertEquals((paragraphEnd - pageStart).coerceIn(0, rendered.length), highlighted.single().end)
    }

    private fun assertQueueStartsAtCurrentPage(controller: ControllableSpeech = voice) {
        val actual = requireNotNull(position.get())
        assertEquals(actual.chapter, controller.source.first().chapter)
        assertEquals(actual.offset, controller.source.first().offset)
    }

    /** Robolectric does not deliver startForegroundService intents automatically. */
    private fun dispatchAudioService() = compose.runOnUiThread {
        if (autoDispatchAudio && ReaderAudioService.owner?.pending != null) {
            if (audio == null) audio = Robolectric.buildService(ReaderAudioService::class.java).create()
            audio!!.get().onStartCommand(null, 0, ++audioStartId)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun drain() {
        dispatchAudioService()
        compose.mainClock.advanceTimeByFrame()
        dispatchAudioService()
        compose.waitForIdle()
    }

    private fun advanceBy(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)) }
        drain()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(20_000) {
            drain()
            condition()
        }
    }

    private class ControllableSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        var chapterSegments = false
        override val usesChapterSegments: Boolean get() = chapterSegments
        @Volatile var starts = 0
            private set
        var pauses = 0
            private set
        var released = false
            private set
        var resumable = false
        var resumes = 0
            private set
        private var lastLocation: Pair<ReaderSpeechSegment, Int>? = null
        var source = emptyList<ReaderSpeechSegment>()
            private set
        var chapterSource: ReaderSpeechSource? = null
            private set
        private var callback: ((ReaderSpeechSegment, Int) -> Unit)? = null
        override fun start(source: ReaderSpeechSource, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            start(source.initial, speed, onLocation)
            chapterSource = source
        }
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            check(!released) { "Released speech engine must never restart" }
            this.source = source
            chapterSource = null
            callback = onLocation
            starts++
            playing.value = true
            source.firstOrNull()?.let { lastLocation = it to it.offset; onLocation(it, it.offset) }
        }
        suspend fun ensureChapter(chapter: Int) {
            if (source.none { it.chapter == chapter }) {
                source = source + requireNotNull(chapterSource).loadChapter(chapter)
            }
        }
        fun emit(chapter: Int, offset: Int) {
            val segment = source.lastOrNull { it.chapter == chapter && it.offset <= offset }
                ?: throw AssertionError("No queued segment for chapter $chapter at $offset")
            require(offset <= segment.offset + segment.text.length)
            lastLocation = segment to offset
            callback?.invoke(segment, offset)
        }
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() { error.value = null }
        override fun pause(): Boolean { pauses++; playing.value = false; return true }
        override fun resume(): Boolean {
            resumes++
            if (!resumable) return false
            playing.value = true
            lastLocation?.let { callback?.invoke(it.first, it.second) }
            return true
        }
        override fun stop() { playing.value = false }
        override fun release() { stop(); released = true }
    }

    /** Android 28's magnifier needs a real Surface; selection itself still uses the real long press. */
    @Implements(android.widget.Magnifier::class)
    class NoSurfaceMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
        @Implementation fun dismiss() = Unit
        @Implementation fun update() = Unit
    }
}
