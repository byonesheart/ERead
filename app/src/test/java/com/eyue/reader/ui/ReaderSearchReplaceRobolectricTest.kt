package com.eyue.reader.ui

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.SourceTextStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSearchReplaceRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val book = Book(title = "Search book", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "First chapter"), Chapter(title = "Second chapter")))
    private val document = ReaderDocument(listOf("MOON moon and another moon", "Last moon"), emptyList(), emptyList())

    @Test fun replacingASingleCaseInsensitiveMatchPreservesActualSourceTextAndLocation() {
        val replacements = mutableListOf<Triple<ReaderSearchResult, String, String>>()
        var jumps = 0
        val query = mutableStateOf("moon")
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(book, document, false, query.value, { query.value = it },
                onReplaceMatch = { result, expected, replacement -> replacements += Triple(result, expected, replacement) },
                onReplaceAll = { _, _ -> }) { jumps++ }
        } } }
        awaitTag("reader_search_replace_0_0_0")
        compose.onNodeWithTag("reader_search_replacement").performTextInput("sun")
        compose.onNodeWithTag("reader_search_replace_0_0_0").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, replacements.size)
            assertEquals(0, replacements.single().first.chapter)
            assertEquals(0, replacements.single().first.offset)
            assertEquals("MOON", replacements.single().second)
            assertEquals("sun", replacements.single().third)
            assertEquals(0, jumps)
        }
    }

    @Test fun editingOneOccurrenceStartsWithItsOriginalCaseAndAllowsAnEmptyReplacement() {
        var saved: Triple<ReaderSearchResult, String, String>? = null
        var jumps = 0
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(book, document, false, "moon", {},
                onReplaceMatch = { result, expected, replacement -> saved = Triple(result, expected, replacement) }) { jumps++ }
        } } }
        awaitTag("reader_search_edit_0_0_0")
        compose.onNodeWithTag("reader_search_edit_0_0_0").performScrollTo().performClick()
        compose.onNodeWithTag("reader_search_edit_text").assertTextContains("MOON")
        compose.onNodeWithTag("reader_search_edit_text").performTextReplacement("")
        compose.onNodeWithTag("reader_search_edit_confirm").performClick()
        compose.onNodeWithTag("reader_search_edit_text").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("MOON", saved?.second)
            assertEquals("", saved?.third)
            assertEquals(0, saved?.first?.offset)
            assertEquals(0, jumps)
        }
    }

    @Test fun replaceAllRequiresConfirmationAndIsNotLimitedToOneHundredDisplayedMatches() {
        var call: Pair<String, String>? = null
        val many = ReaderDocument(listOf("moon ".repeat(110), "MOON"), emptyList(), emptyList())
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(book, many, false, "  moon  ", {}, onReplaceAll = { query, replacement -> call = query to replacement }) { }
        } } }
        awaitTag("reader_search_result_0_0_0")
        awaitNode(hasText("显示前 100 个结果；全部替换会处理整本书的所有匹配"))
        compose.onNodeWithText("显示前 100 个结果；全部替换会处理整本书的所有匹配").assertExists()
        compose.onNodeWithTag("reader_search_replace_all").performClick()
        compose.runOnIdle { assertNull(call) }
        compose.onNodeWithText("替换为：删除匹配文字").assertExists()
        compose.onNodeWithTag("reader_search_replace_all_confirm").performClick()
        compose.runOnIdle { assertEquals("moon" to "", call) }
    }

    @Test fun pdfComicsAndCloudOnlyBooksKeepSearchButDoNotExposeReplacement() {
        val current = mutableStateOf(book.copy(format = BookFormat.PDF))
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(current.value, document, false, "moon", {},
                onReplaceMatch = { _, _, _ -> fail("Read-only book was edited") }, onReplaceAll = { _, _ -> fail("Read-only book was edited") }) { }
        } } }
        awaitTag("reader_search_result_0_0_0")
        compose.onNodeWithTag("reader_search_replacement").assertDoesNotExist()
        compose.onNodeWithTag("reader_search_edit_0_0_0").assertDoesNotExist()
        compose.runOnIdle { current.value = book.copy(mode = LibraryMode.COMIC) }
        compose.onNodeWithTag("reader_search_replacement").assertDoesNotExist()
        compose.runOnIdle { current.value = book.copy(cloudOnly = true) }
        compose.onNodeWithTag("reader_search_replacement").assertDoesNotExist()
    }

    @Test fun replacingDisablesInputsAndActionsAndBookVersionChangesRefreshOffsetsWithoutLosingReplacement() {
        val current = mutableStateOf(book)
        val texts = mutableStateOf(document)
        val busy = mutableStateOf(false)
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(current.value, texts.value, false, "moon", {},
                onReplaceMatch = { _, _, _ -> }, onReplaceAll = { _, _ -> }, replacing = busy.value,
                initialReplacement = "sun") { }
        } } }
        awaitTag("reader_search_replace_0_0_0")
        compose.onNodeWithTag("reader_search_replacement").assertTextContains("sun")
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("reader_search_query").assertIsNotEnabled()
        compose.onNodeWithTag("reader_search_replacement").assertIsNotEnabled()
        compose.onNodeWithTag("reader_search_replace_all").assertIsNotEnabled()
        compose.onNodeWithTag("reader_search_replace_0_0_0").assertIsNotEnabled()
        compose.runOnIdle {
            current.value = book.copy(contentVersion = book.contentVersion + 1)
            texts.value = document.copy(text = listOf("prefix moon", "Nothing matches"))
            busy.value = false
        }
        awaitTag("reader_search_replace_0_0_7")
        compose.onNodeWithTag("reader_search_replace_0_0_0").assertDoesNotExist()
        compose.onNodeWithTag("reader_search_replacement").assertTextContains("sun")
    }

    @Test fun blankSearchCannotTriggerReplacementAndLiteralPunctuationIsNotARegularExpression() {
        val query = mutableStateOf(" ")
        var jumps = 0
        val literal = document.copy(text = listOf("[moon] moon", "Elsewhere"))
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(book, literal, false, query.value, { query.value = it }, onReplaceAll = { _, _ -> }) { jumps++ }
        } } }
        compose.onNodeWithTag("reader_search_replace_all").assertIsNotEnabled()
        compose.onNodeWithTag("reader_search_query").performTextReplacement("[moon]")
        awaitTag("reader_search_result_0_0_0")
        awaitNode(hasText("找到 1 处结果"))
        compose.onNodeWithText("找到 1 处结果").assertExists()
        compose.onNodeWithTag("reader_search_result_0_0_0").performClick()
        compose.runOnIdle { assertEquals(0, jumps) }
        compose.onNodeWithTag("reader_search_result_0_0_0").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, jumps) }
    }

    @Test fun singleTapKeepsSearchOpenAndDoubleTapJumpsToTheExactMatch() {
        var jumped: ReaderSearchResult? = null
        val one = document.copy(text = listOf("prefix moon suffix", "No match"))
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(book, one, false, "moon", {}) { jumped = it }
        } } }
        awaitTag("reader_search_result_0_0_7")
        compose.onNodeWithText("搜索整本书，双击结果返回原文").assertExists()
        compose.onNodeWithTag("reader_search_result_0_0_7").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertNull(jumped) }
        compose.onNodeWithTag("reader_search_result_0_0_7").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(0, jumped?.chapter); assertEquals(7, jumped?.offset) }
    }

    @Test fun everyResultShowsItsChapterSectionAndSourceTitles() {
        val nested = book.copy(chapters = listOf(
            Chapter(id = "one", title = "第一章 启程", tocParentTitle = "第一卷 起点"),
            Chapter(id = "two", title = "第二章 相遇", tocParentTitle = "第一卷 起点")))
        val text = document.copy(text = listOf("No match", "moon"))
        compose.setContent { MaterialTheme { Column { ReaderSearchPanel(nested, text, false, "moon", {}) {} } } }
        awaitTag("reader_search_result_1_0_0")
        val label = readerSearchLocationIndex(nested.chapters).label(1, 0)
        compose.onNodeWithTag("reader_search_location_1_0_0", useUnmergedTree = true).assertTextEquals(label)
        assertTrue(label.contains("第1章"))
        assertTrue(label.contains("第2节"))
        assertTrue(label.contains("第一卷 起点"))
        assertTrue(label.contains("第二章 相遇"))
    }

    @Test fun preparingUnchangedChapterStylesKeepsTheExistingResultAvailableForDoubleTap() {
        val current = mutableStateOf(book)
        val texts = listOf("moon", "No match")
        val source = ReaderDocument(listOf("", ""), emptyList(), emptyList(), readText = { texts[it] })
        val prepared = mutableStateOf(source)
        var jumped: ReaderSearchResult? = null
        compose.setContent { MaterialTheme { Column {
            ReaderSearchPanel(current.value, prepared.value, false, "moon", {}) { jumped = it }
        } } }
        awaitTag("reader_search_result_0_0_0")
        compose.runOnIdle {
            current.value = book.copy(chapters = book.chapters.mapIndexed { index, chapter ->
                if (index == 0) chapter.copy(sourceStyles = listOf(SourceTextStyle(0, 4, fontScale = .8f))) else chapter
            })
            prepared.value = source.copy(text = listOf("moon", ""))
        }
        compose.onNodeWithTag("reader_search_result_0_0_0").assertExists().performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(0, jumped?.chapter); assertEquals(0, jumped?.offset) }
    }

    private fun awaitTag(tag: String) = awaitNode(hasTestTag(tag))

    private fun awaitNode(matcher: SemanticsMatcher) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeBy(50)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
            if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(10)
        }
        compose.onNode(matcher).assertExists()
    }
}
