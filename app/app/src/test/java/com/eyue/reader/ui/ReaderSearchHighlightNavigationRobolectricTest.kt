package com.eyue.reader.ui

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSearchHighlightNavigationRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "search-highlight-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { check(root.deleteRecursively()) }

    @Test fun automaticSearchScrollRetainsHighlightAndManualScrollingClearsIt() = verify(ReadingMode.SCROLL)
    @Test fun automaticSearchPageLocationRetainsHighlightAndManualPagingClearsIt() = verify(ReadingMode.PAGED)
    @Test fun searchReturnRestoresTheOriginalScrolledPosition() = verifyReturn(ReadingMode.SCROLL)
    @Test fun searchReturnRestoresTheOriginalPagedPosition() = verifyReturn(ReadingMode.PAGED)

    private fun verify(mode: ReadingMode) {
        val chapters = listOf("Unrelated first chapter.", "Before needle and more text follow.\n".repeat(120)).mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath)
        }
        val book = Book(title = "搜索定位", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = chapters)
        val chapter = AtomicReference<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalReaderSpeechFactory provides { SilentSpeech() }) { MaterialTheme {
                ReaderScreen(book, ReaderSettings(readingMode = mode, showTime = false, showBattery = false), emptyList(), {},
                    { index, _, _, _, _ -> chapter.set(index) }, {}, {}, {}, {}, emptyFlow(), resumeSearch = ReaderSearchDraft("needle", ""))
            } }
        }
        await { compose.onAllNodesWithTag("reader_search_result_1_0_7").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_search_result_1_0_7").performTouchInput { doubleClick() }
        await({ "chapter=${chapter.get()}, searchResults=${compose.onAllNodesWithTag("reader_search_result_1_0_7").fetchSemanticsNodes().size}, " +
            "loading=${compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().size}, text=" +
            layouts().joinToString { "${it.layoutInput.text.text.take(60)} (spans=${it.layoutInput.text.spanStyles.size})" } }) {
            chapter.get() == 1 && layouts().any { it.layoutInput.text.spanStyles.any { span -> span.item.background != Color.Unspecified } }
        }
        compose.mainClock.advanceTimeBy(1_000)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        compose.waitForIdle()
        assertTrue("Automatic search restoration must preserve the highlight", hasHighlight())
        compose.onNodeWithTag("reader_search_return").assertIsDisplayed()
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
        if (mode == ReadingMode.SCROLL) compose.onNodeWithTag("reader_scroll").performTouchInput {
            swipeUp(startY = height * .8f, endY = height * .6f)
        } else compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
        await { layouts().any { it.layoutInput.text.text.contains("needle") } && !hasHighlight() }
        compose.onNodeWithTag("reader_search_return").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, chapter.get()) }
    }

    private fun verifyReturn(mode: ReadingMode) {
        val chapters = listOf((0 until 160).joinToString("\n") { "Original reading paragraph $it continues here." },
            "Before needle and more text follow.\n".repeat(120)).mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "return-$index.txt").apply { writeText(text) }.absolutePath)
        }
        val book = Book(title = "搜索返回", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = chapters, characterOffset = 1800)
        val position = AtomicReference<Pair<Int, Int>>()
        compose.setContent {
            CompositionLocalProvider(LocalReaderSpeechFactory provides { SilentSpeech() }) { MaterialTheme {
                ReaderScreen(book, ReaderSettings(readingMode = mode, showTime = false, showBattery = false), emptyList(), {},
                    { chapter, _, offset, _, _ -> position.set(chapter to offset) }, {}, {}, {}, {}, emptyFlow(),
                    resumeSearch = ReaderSearchDraft("needle", ""))
            } }
        }
        await { position.get()?.first == 0 &&
            compose.onAllNodesWithTag("reader_search_result_1_0_7").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        compose.waitForIdle()
        val original = requireNotNull(position.get())
        assertTrue("The saved origin should exercise a nonzero text offset", original.second > 0)
        compose.onNodeWithTag("reader_search_result_1_0_7").performTouchInput { doubleClick() }
        await { position.get()?.first == 1 && compose.onAllNodesWithTag("reader_search_return").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_search_return").assertIsDisplayed().performClick()
        await({ "origin=$original actual=${position.get()}" }) { position.get() == original }
        compose.onNodeWithTag("reader_search_return").assertDoesNotExist()
        assertFalse("Returning should clear the search-only highlight", hasHighlight())
    }

    private fun hasHighlight() = layouts().any { layout -> layout.layoutInput.text.spanStyles.any { it.item.background != Color.Unspecified } }
    private fun layouts(): List<TextLayoutResult> {
        val result = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodesWithContentDescription("阅读正文")[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        }
        return result
    }
    private fun await(diagnostics: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeBy(50)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("Reader interaction did not reach its expected state: ${diagnostics()}", predicate())
    }
    private class SilentSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() = Unit
        override fun pause() = false
        override fun resume() = false
        override fun stop() = Unit
        override fun release() = Unit
    }
}
