package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderChapterNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private data class Position(val chapter: Int, val offset: Int)
    private val position = AtomicReference<Position?>()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "chapter-navigation-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        position.set(null)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun distantChapterWithBackgroundPaginationLoadsItsTextBeforeRendering() {
        val marker = "DISTANT_CHAPTER_BODY"
        val chapters = (0 until 10).map { index ->
            textChapter("章节 ${index + 1}", (if (index == 9) "$marker\n" else "章节正文 $index\n") +
                "普通段落需要按屏幕宽度换行，翻页后仍然能够连续阅读。\n".repeat(45))
        }
        showReader(Book(title = "后台分页跳转测试", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters), ReadingMode.PAGED)
        awaitPosition(0)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("总页数统计中", substring = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithTag("reader_contents_list").performScrollToNode(hasText(chapters[9].title))
        compose.onNodeWithText(chapters[9].title).performClick()
        awaitPosition(9)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        compose.onNodeWithText("正文排版失败", substring = true).assertDoesNotExist()
    }

    @Test fun insertingPrefetchedPreviousChapterBlocksPreservesDeepScrollAnchor() {
        val marker = "SAVED_DEEP_SCROLL_ANCHOR"
        val line = "An ordinary paragraph keeps the precise reading position.\n"
        val prefix = line.repeat(35)
        val current = "$prefix$marker\n" + line.repeat(100)
        val previous = "前章正文用于增加大量滚动块，异步插入后不应改变在读位置。\n".repeat(20_000)
        val chapters = (0 until 10).map { index ->
            textChapter("章节 ${index + 1}", when (index) {
                5 -> previous
                6 -> current
                else -> "其他章节的正文。\n".repeat(12)
            })
        }
        showReader(Book(title = "相邻章节预加载测试", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = chapters, chapterIndex = 6, characterOffset = prefix.length, progress = .6f), ReadingMode.SCROLL)
        awaitPosition(6, prefix.length)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()

        // The previous chapter initially occupies one placeholder. Its actual blocks are
        // inserted ahead of the viewport; accessibility exposes the resulting item index.
        val previousBlocks = readerScrollBlocks(previous, emptyList()).size
        assertTrue(previousBlocks > 100)
        compose.waitUntil(20_000) {
            val range = compose.onNodeWithTag("reader_scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            range.value() >= previousBlocks
        }
        compose.waitForIdle()
        assertEquals(Position(6, prefix.length), position.get())
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
    }

    private fun textChapter(title: String, text: String) = Chapter(title = title,
        textPath = File(root, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath,
        characterCount = text.length)

    private fun showReader(book: Book, mode: ReadingMode) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(readingMode = mode, showTime = false, showBattery = false),
                        emptyList(), {}, { chapter, _, offset, _, _ -> position.set(Position(chapter, offset)) },
                        {}, {}, {}, {}, emptyFlow())
                }
            }
        }
    }

    private fun awaitPosition(chapter: Int, offset: Int? = null) {
        compose.waitUntil(15_000) {
            position.get()?.let { it.chapter == chapter && (offset == null || it.offset == offset) } == true &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }
}
