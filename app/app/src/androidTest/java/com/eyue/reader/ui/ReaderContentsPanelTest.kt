package com.eyue.reader.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderContentsPanelTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private data class Target(val chapter: Int, val page: Int)
    private val selected = AtomicReference<Target?>()

    @Test fun twoThousandChaptersOpenAtCurrentLocationAndOnlyComposeVisibleRows() {
        val chapters = List(2_000) { index ->
            // Opening the contents must depend on metadata, not readable chapter files.
            Chapter(id = "contents-chapter-$index", title = "目录章 ${index + 1}", textPath = "/unreadable/contents-$index.txt")
        }
        val book = Book(title = "长目录", format = BookFormat.MOBI, mode = LibraryMode.NOVEL, chapters = chapters)
        showContents(book, chapter = 1_500)
        awaitContents()
        compose.onNodeWithText("目录章 1501").assertIsDisplayed()
        compose.onNode(hasText("目录章 1501") and hasText("在读")).assertIsDisplayed()
        compose.onNodeWithText("目录章 1").assertDoesNotExist()
        compose.onNodeWithText("目录章 2000").assertDoesNotExist()
        val composedRows = compose.onAllNodes(hasText("目录章 ", substring = true)).fetchSemanticsNodes().size
        assertTrue("长目录只应组合可见范围，不应创建全部2000行", composedRows in 1 until 100)

        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(1_999)
        compose.onNodeWithText("目录章 2000").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { selected.get() != null }
        assertEquals(Target(1_999, 0), selected.get())
        compose.onNodeWithTag("reader_contents_fullscreen").assertDoesNotExist()
        compose.onNodeWithText("返回阅读正文").assertIsDisplayed()
    }

    @Test fun contentsFillTheWindowAndCloseWithBothToolbarAndSystemBack() {
        val book = Book(title = "全屏目录", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = List(50) { Chapter(title = "章节 ${it + 1}") })
        showContents(book, chapter = 12)
        awaitContents()
        val host = compose.onNodeWithTag("contents_test_host").fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNodeWithTag("reader_contents_fullscreen").fetchSemanticsNode().boundsInRoot
        assertTrue("目录必须占满窗口宽度", dialog.width >= host.width - 2f)
        assertTrue("目录必须占满窗口高度", dialog.height >= host.height - 2f)
        compose.onNodeWithTag("reader_contents_close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader_contents_fullscreen").assertDoesNotExist()
        compose.onNodeWithText("返回阅读正文").assertIsDisplayed()

        compose.runOnIdle { visible = true }
        awaitContents()
        compose.onNodeWithContentDescription("关闭目录").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_contents_fullscreen").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("返回阅读正文").assertIsDisplayed()
    }

    @Test fun nestedComicSectionKeepsItsAbsolutePageAndOwningChapter() {
        val sections = listOf(
            ComicSection("序章", 0, 3, listOf(ComicSection("序章上", 0, 1), ComicSection("序章下", 1, 3))),
            ComicSection("冒险", 3, 7, listOf(ComicSection("高塔", 3, 5), ComicSection("归途", 5, 7)))
        )
        val book = Book(title = "漫画小节", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "第一卷", pages = List(3) { PageSource("/unreadable/first-$it.png") }),
                Chapter(title = "第二卷", pages = List(7) { PageSource("/unreadable/second-$it.png") }, subsections = sections)))
        showContents(book, chapter = 1, page = 4)
        awaitContents()
        compose.onNode(hasText("高塔") and hasText("在读")).assertIsDisplayed()
        compose.onNodeWithText("归途").performScrollTo().performClick()
        compose.waitUntil(10_000) { selected.get() != null }
        assertEquals("小节页码应为所属章的绝对startPage，而非相对父小节", Target(1, 5), selected.get())
        compose.onNodeWithTag("reader_contents_fullscreen").assertDoesNotExist()
    }

    private fun showContents(book: Book, chapter: Int, page: Int = 0) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("contents_test_host")) {
                    Text("返回阅读正文")
                    if (visible) ReaderContentsPanel(book, chapter, page,
                        ReaderSettings(showTime = false, showBattery = false), onClose = { visible = false }) { targetChapter, targetPage ->
                        selected.set(Target(targetChapter, targetPage))
                        visible = false
                    }
                }
            }
        }
    }

    private fun awaitContents() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_contents_fullscreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_contents_fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_list").assertIsDisplayed()
    }
}
