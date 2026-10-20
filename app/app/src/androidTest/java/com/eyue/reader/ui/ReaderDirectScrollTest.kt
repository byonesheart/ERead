package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReaderDirectScrollTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private var settings by mutableStateOf(ReaderSettings())
    private data class Position(val chapter: Int, val block: Int, val offset: Int, val progress: Float)
    private val position = AtomicReference<Position?>()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "direct-scroll-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        settings = ReaderSettings(readingMode = ReadingMode.SCROLL, showTime = false, showBattery = false)
        position.set(null)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun largeMobiChapterOpensAtItsTailAndTypographyChangesKeepTheScrollAnchor() {
        // Reader input is the already parsed MOBI chapter, as produced by DocumentImporter.
        // Generate it incrementally so the fixture does not hold a second ten-megabyte String.
        val marker = "LARGE_MOBI_TAIL_ANCHOR"
        val line = "An ordinary long chapter line preserves its original text and reading position.\n"
        val textFile = File(root, "large-mobi-chapter.txt")
        var anchorOffset = 0
        textFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            val repeats = (10 * 1024 * 1024 / line.length) + 1
            repeat(repeats) { writer.write(line) }
            anchorOffset = repeats * line.length
            writer.write("$marker\n")
            repeat(150) { writer.write(line) }
        }
        assertTrue(textFile.length() > 10L * 1024 * 1024)
        val book = Book(id = UUID.randomUUID().toString(), title = "大单章 MOBI", format = BookFormat.MOBI,
            mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "单一大章节", textPath = textFile.absolutePath)),
            characterOffset = anchorOffset, progress = .99f, contentVersion = 4)
        showReader(book)
        awaitAnchor(marker)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        val initial = requireNotNull(position.get())
        assertEquals(0, initial.chapter)
        assertTrue(initial.block > 1_000)
        assertTrue(abs(initial.offset - anchorOffset) < 256)
        assertTrue(initial.progress > .95f)
        assertNoWholeBookPaginationCache()

        compose.runOnIdle { settings = settings.copy(fontSize = 27f) }
        awaitAnchor(marker)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        assertPreservedAnchor(initial, anchorOffset)
        assertNoWholeBookPaginationCache()

        compose.runOnIdle { settings = settings.copy(lineHeight = 2.1f, letterSpacing = .5f) }
        awaitAnchor(marker)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        assertPreservedAnchor(initial, anchorOffset)
        assertNoWholeBookPaginationCache()
    }

    @Test fun directScrollingContinuesAcrossChapterBoundariesAndReportsPages() {
        val first = "First chapter body line before its natural boundary.\n".repeat(90) + "FIRST_CHAPTER_END\n"
        val second = "SECOND_CHAPTER_BEGIN\n" + "Second chapter body continues without opening the table of contents.\n".repeat(130)
        val book = Book(id = UUID.randomUUID().toString(), title = "连续滚动", format = BookFormat.TXT,
            mode = LibraryMode.NOVEL, chapters = listOf(chapter("第一章", first), chapter("第二章", second)),
            characterOffset = first.length - "FIRST_CHAPTER_END\n".length, contentVersion = 4)
        showReader(book)
        awaitAnchor("FIRST_CHAPTER_END")
        assertEquals(0, requireNotNull(position.get()).chapter)
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) {
            position.get()?.chapter == 1 && compose.onAllNodesWithText("Second chapter body", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("页", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_page_progress").assertIsDisplayed()
        assertNoWholeBookPaginationCache()
    }

    @Test fun fullyVisibleShortBookReportsCompletionWithoutMovingItsCharacterAnchor() {
        val marker = "SHORT_BOOK_FIRST_LINE"
        val text = "$marker\n全文在屏幕内，书签和朗读仍从第一行开始。"
        val book = Book(id = UUID.randomUUID().toString(), title = "短书进度", format = BookFormat.MOBI,
            mode = LibraryMode.NOVEL, chapters = listOf(chapter("正文", text)), contentVersion = 4)
        showReader(book)
        awaitAnchor(marker)
        compose.waitUntil(10_000) { position.get()?.progress == 1f }
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        compose.onNodeWithText("100%", substring = true).assertIsDisplayed()
        val current = requireNotNull(position.get())
        assertEquals(0, current.chapter)
        assertEquals(0, current.block)
        assertEquals("完成进度不能把可见位置跳到书尾", 0, current.offset)
        assertNoWholeBookPaginationCache()
    }

    @Test fun tapsInsidePartiallyVisibleTallTextBlocksKeepTheReadingPosition() {
        val line = "合成段落保留阅读位置，单击只显示或隐藏工具栏，不应将长文字块或光标拉回屏幕。\n"
        val text = line.repeat(160)
        val anchor = 1_400
        val book = Book(id = UUID.randomUUID().toString(), title = "滚动点击回归", format = BookFormat.MOBI,
            mode = LibraryMode.NOVEL, chapters = listOf(chapter("正文", text)), characterOffset = anchor)
        showReader(book)
        compose.waitUntil(10_000) {
            position.get()?.let { it.block > 0 && abs(it.offset - anchor) < line.length * 2 } == true &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        val before = requireNotNull(position.get())
        repeat(3) {
            compose.onNodeWithTag("reader_body").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * .5f, height * .4f))
            }
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitForIdle()
            assertEquals("显示/隐藏工具栏不能触发焦点滚动", before, position.get())
        }
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { (position.get()?.offset ?: 0) > before.offset }
        assertNoWholeBookPaginationCache()
    }

    private fun chapter(title: String, text: String): Chapter = Chapter(title = title,
        textPath = File(root, "chapter-${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath)

    private fun showReader(book: Book) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ReaderScreen(book, settings, emptyList(), {}, { chapter, block, offset, progress, _ ->
                        position.set(Position(chapter, block, offset, progress))
                    }, { settings = it }, {}, {}, {}, emptyFlow())
                }
            }
        }
    }

    private fun awaitAnchor(marker: String) {
        compose.waitUntil(10_000) {
            if (position.get() == null || compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty()) false
            else {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(marker, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.any { it.layoutInput.style.fontSize.value == settings.fontSize &&
                    abs(it.layoutInput.style.lineHeight.value - settings.fontSize * settings.lineHeight) < .01f }
            }
        }
    }

    private fun assertPreservedAnchor(initial: Position, anchorOffset: Int) {
        val current = requireNotNull(position.get())
        assertEquals(initial.chapter, current.chapter)
        assertEquals(initial.block, current.block)
        assertTrue(abs(current.offset - anchorOffset) < 256)
    }

    private fun assertNoWholeBookPaginationCache() {
        assertTrue(File(context.cacheDir, "reader-page-cache").listFiles().orEmpty().isEmpty())
    }
}
