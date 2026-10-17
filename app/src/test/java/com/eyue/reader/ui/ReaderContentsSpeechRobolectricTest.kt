package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.Book
import com.eyue.reader.data.AiSpeechBatchProgress
import com.eyue.reader.data.AiSpeechChapterProgress
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderContentsSpeechRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val novel = Book(id = "speech-contents", title = "选章生成", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
        chapters = List(4) { Chapter(id = "chapter-$it", title = "正文第 ${it + 1} 章", characterCount = 100, textPath = "unreadable-$it") })

    @Test fun rowsAndCheckboxesSelectChaptersAndCancelRestoresNormalNavigation() {
        var generated: Set<Int>? = null
        var jumped: Pair<Int, Int>? = null
        var rules = 0
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            onChapterRules = { rules++ }, onGenerateSpeech = { generated = it }) { chapter, page -> jumped = chapter to page } } }
        compose.onNodeWithTag("reader_contents_rules").performClick()
        compose.runOnIdle { assertEquals(1, rules) }
        compose.onNodeWithTag("reader_contents_generate_speech").performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").assertIsNotEnabled()
        compose.onNodeWithTag("reader_contents_entry_chapter-0").performClick()
        compose.onNodeWithTag("reader_contents_select_2").performClick()
        compose.runOnIdle { assertNull(jumped) }
        compose.onNodeWithTag("reader_contents_select_0").assertIsOn()
        compose.onNodeWithTag("reader_contents_select_2").assertIsOn()
        compose.onNodeWithTag("reader_contents_speech_generate").performClick()
        compose.runOnIdle { assertEquals(setOf(0, 2), generated) }
        compose.onNodeWithTag("reader_contents_speech_select_all").performClick()
        compose.onNodeWithText("已选 4 章 · 生成").assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_speech_select_all").performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").assertIsNotEnabled()
        compose.onNodeWithTag("reader_contents_speech_cancel").performClick()
        compose.onNodeWithTag("reader_contents_speech_actions").assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_entry_chapter-1").performClick()
        compose.runOnIdle { assertEquals(1 to 0, jumped) }
    }

    @Test fun aRunningGenerationDisablesSubmittingAndEnteringAnotherBatch() {
        var busy by mutableStateOf(false)
        var generations = 0
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            onGenerateSpeech = { generations++; busy = true }, speechGenerationBusy = busy) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_generate_speech").performClick()
        compose.onNodeWithTag("reader_contents_select_0").performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("reader_contents_generate_speech").assertIsNotEnabled()
        compose.onNodeWithTag("reader_contents_select_0").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, generations) }
    }

    @Test fun comicsDoNotExposeTheSpeechEntryEvenWithACallback() {
        var jumped: Pair<Int, Int>? = null
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel.copy(mode = LibraryMode.COMIC), 0, 0,
            ReaderSettings(), {}, onGenerateSpeech = {}) { chapter, page -> jumped = chapter to page } } }
        compose.onNodeWithTag("reader_contents_generate_speech").assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_entry_chapter-2").performClick()
        compose.runOnIdle { assertEquals(2 to 0, jumped) }
    }

    @Test fun completedChaptersShowVoiceBadgesAndRefreshAfterCacheChanges() {
        var cached by mutableStateOf(setOf(0, 2))
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            cachedSpeechChapters = cached) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_entry_chapter-0").performScrollTo()
        compose.onNodeWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_entry_chapter-2").performScrollTo()
        compose.onNodeWithTag("reader_contents_cached_speech_2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_cached_speech_1", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { cached = setOf(1) }
        compose.onNodeWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_entry_chapter-1").performScrollTo()
        compose.onNodeWithTag("reader_contents_cached_speech_1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun chapterRowsShowActiveProgressQueuedStateAndChecksOnlyForCompleteCurrentCaches() {
        var cached by mutableStateOf(setOf(0))
        var progress by mutableStateOf(AiSpeechBatchProgress(novel.id, novel.title, chapterIndex = 1,
            chapterCompleted = 2, chapterTotal = 4, selectedChapters = setOf(0, 1, 2), completedChapters = setOf(0)))
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            cachedSpeechChapters = cached, speechGeneration = progress) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_speech_progress_1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("50%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_speech_waiting_2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_cached_speech_1", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            progress = progress.copy(chapterCompleted = 4, completedChapters = setOf(0, 1))
            cached = setOf(0, 1)
        }
        compose.onNodeWithTag("reader_contents_speech_progress_1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_cached_speech_1", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { progress = progress.copy(bookId = "other-book") }
        compose.onNodeWithTag("reader_contents_speech_waiting_2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun completeBookAndChapterHeadphonesContainACheckAndRefreshWhenCacheBecomesIncomplete() {
        var complete by mutableStateOf(true)
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            onGenerateSpeech = {}, cachedSpeechChapters = if (complete) setOf(0, 1, 2, 3) else emptySet(),
            speechBookComplete = complete) { _, _ -> } } }
        val status = compose.onNodeWithTag("reader_contents_book_speech_status", useUnmergedTree = true)
        status.assertContentDescriptionEquals("全书当前音色语音已缓存")
        val check = compose.onNode(hasTestTag("reader_speech_complete_mark") and
            hasAnyAncestor(hasTestTag("reader_contents_book_speech_status")), useUnmergedTree = true).assertIsDisplayed()
        val iconBounds = status.fetchSemanticsNode().boundsInRoot
        val checkBounds = check.fetchSemanticsNode().boundsInRoot
        assertTrue(checkBounds.left >= iconBounds.left && checkBounds.right <= iconBounds.right)
        assertTrue(checkBounds.top >= iconBounds.top && checkBounds.bottom <= iconBounds.bottom)
        compose.onNode(hasTestTag("reader_speech_complete_mark") and
            hasAnyAncestor(hasTestTag("reader_contents_cached_speech_0")), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { complete = false }
        status.assertContentDescriptionEquals("批量生成 AI 语音")
        compose.onAllNodesWithTag("reader_speech_complete_mark", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun parallelChapterProgressHasHeadphonesBelowEachBarAndCanStopOnlyThisBook() {
        var stops = 0
        var progress by mutableStateOf(AiSpeechBatchProgress(novel.id, novel.title,
            selectedChapters = setOf(0, 1, 2), chapterProgress = mapOf(
                0 to AiSpeechChapterProgress(1, 4), 1 to AiSpeechChapterProgress(2, 4))))
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            onGenerateSpeech = {}, speechGenerationBusy = true, speechGeneration = progress,
            onCancelSpeech = { stops++; progress = progress.copy(running = false, cancelled = true) }) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_stop_speech").assertIsDisplayed()
        listOf(0, 1).forEach { chapter ->
            compose.onNodeWithTag("reader_contents_entry_chapter-$chapter").performScrollTo()
            val bar = compose.onNodeWithTag("reader_contents_speech_progress_bar_$chapter", useUnmergedTree = true)
                .assertIsDisplayed().assertHeightIsEqualTo(2.dp)
            val icon = compose.onNodeWithTag("reader_contents_generating_speech_icon_$chapter", useUnmergedTree = true).assertIsDisplayed()
            // Inspect the explicit 2dp container rather than the indicator's expanded accessibility bounds.
            val barBounds = bar.getUnclippedBoundsInRoot()
            val iconBounds = icon.getUnclippedBoundsInRoot()
            assertTrue("Headphone $iconBounds must be below progress bar $barBounds", iconBounds.top >= barBounds.bottom)
        }
        compose.onNodeWithTag("reader_contents_stop_speech").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithTag("reader_contents_stop_speech").assertDoesNotExist()
        compose.runOnIdle { progress = progress.copy(bookId = "other-book", running = true, cancelled = false) }
        compose.onNodeWithTag("reader_contents_stop_speech").assertDoesNotExist()
    }

    @Test fun anotherVoiceCacheRemainsVisibleAndIsDistinctFromTheCurrentConfigurationCompletionMark() {
        var current by mutableStateOf(emptySet<Int>())
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            cachedSpeechChapters = current, otherCachedSpeechChapters = setOf(0)) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_other_cached_speech_0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("本章其他配置语音已缓存", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithTag("reader_speech_complete_mark", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { current = setOf(0) }
        compose.onNodeWithTag("reader_contents_other_cached_speech_0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w240dp-h640dp")
    fun speechSelectionButtonsWrapInsideANarrowPhoneWindow() {
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {}, onGenerateSpeech = {}) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_generate_speech").performClick()
        val actions = compose.onNodeWithTag("reader_contents_speech_actions").fetchSemanticsNode().boundsInRoot
        val buttons = listOf("select_all", "generate", "cancel").map {
            compose.onNodeWithTag("reader_contents_speech_$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        buttons.forEach { assertTrue(it.left >= actions.left && it.right <= actions.right) }
        assertTrue(buttons.map { it.top }.distinct().size > 1)
        compose.onNodeWithTag("reader_contents_speech_select_all").performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").assertIsEnabled()
        compose.onNodeWithTag("reader_contents_speech_cancel").performClick()
        compose.onNodeWithTag("reader_contents_speech_actions").assertDoesNotExist()
    }
}
