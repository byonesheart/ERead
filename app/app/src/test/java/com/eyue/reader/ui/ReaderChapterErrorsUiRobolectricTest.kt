package com.eyue.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.HtmlChapterParser
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.saveLazyChapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChapterErrorsUiRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var currentBook: MutableState<Book>
    private var mounted by mutableStateOf(true)
    private val savedPositions = AtomicInteger()
    private val speech = SilentSpeech()

    @Before fun setup() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "chapter-errors-ui-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        root.deleteRecursively()
    }

    @Test fun missingLegacyTextReportsItsChapterAndRetryReadsTheRepairedFile() {
        assertBrokenChapterCanRecover(corrupt = false, knownLength = false)
    }

    @Test fun malformedUtf8ReportsDamageInsteadOfAnEmptyChapterAndCanBeRetried() {
        assertBrokenChapterCanRecover(corrupt = true, knownLength = true)
    }

    @Test fun aMissingDistantChapterMakesFullTextSearchFailInsteadOfReportingNoMatches() {
        val chapters = (0..3).map { index ->
            val text = if (index == 0) "VISIBLE_BODY\n当前章节完整，可以正常打开阅读。" else "第 $index 章的正常正文。"
            val file = File(root, "chapter-$index.txt")
            if (index != 3) file.writeText(text)
            Chapter(title = if (index == 3) "第四章 缺失缓存" else "第 ${index + 1} 章",
                textPath = file.absolutePath, characterCount = text.length)
        }
        show(Book(title = "完整搜索错误", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = chapters))
        awaitNode(hasText("VISIBLE_BODY", substring = true))
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        awaitNode(hasContentDescription("全文搜索与替换"))
        compose.onNodeWithContentDescription("全文搜索与替换").performClick()
        compose.onNodeWithTag("reader_search_query").performTextInput("这里没有这个关键词")
        awaitNode(hasText("搜索失败", substring = true))
        compose.onNodeWithText("搜索失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第四章 缺失缓存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("找到 0 处结果").assertDoesNotExist()
    }

    @Test fun choosingTheSameChapterInContentsRetriesARepairedSourceInsteadOfLoadingForever() {
        val html = "<html><body><p>SOURCE_REPAIRED</p><p>修复源排版后选择当前章，应重新准备并恢复阅读。</p></body></html>"
        val parsed = HtmlChapterParser.readPlain("第一章 源排版", html) { null }
        val chapter = saveLazyChapter(root, 0, parsed, html, "", emptyMap())
        val source = File(requireNotNull(chapter.lazySourcePath))
        val originalSource = source.readText()
        source.writeText("broken source json")
        show(Book(title = "同章重试", format = BookFormat.MOBI, mode = LibraryMode.NOVEL, chapters = listOf(chapter)))
        awaitNode(hasTestTag("reader_retry"))
        compose.onNodeWithText("无法读取「第一章 源排版」", substring = true).assertIsDisplayed()
        source.writeText(originalSource)
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(16f, 16f)) }
        awaitNode(hasContentDescription("目录"))
        compose.onNodeWithContentDescription("目录").performClick()
        awaitNode(hasTestTag("reader_contents_entry_${chapter.id}"))
        compose.onNodeWithTag("reader_contents_entry_${chapter.id}").performClick()
        awaitNode(hasText("SOURCE_REPAIRED", substring = true))
        compose.onNodeWithText("SOURCE_REPAIRED", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_retry").assertDoesNotExist()
        compose.onNodeWithTag("reader_loading").assertDoesNotExist()
    }

    @Test fun startingPdfNarrationPreservesTheOriginalOcrReadFailureWithoutStartingTheEngine() {
        val image = File(root, "page.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val ocr = File(root, "valid-ocr.txt").apply { writeText("PDF 页面拥有正常文字层。") }
        val chapter = Chapter(title = "第一章 OCR", pages = listOf(PageSource(image.absolutePath, textPath = ocr.absolutePath)))
        val book = Book(title = "OCR 缓存错误", format = BookFormat.PDF, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        show(book)
        awaitNode(hasTestTag("reader_image_0_0"))
        compose.onNodeWithTag("reader_image_0_0").performTouchInput { click() }
        awaitNode(hasContentDescription("目录"))
        compose.runOnIdle {
            currentBook.value = book.copy(chapters = listOf(chapter.copy(pages = listOf(chapter.pages.single().copy(
                textPath = File(root, "missing-ocr.txt").absolutePath)))))
        }
        awaitNode(hasTestTag("reader_retry"))
        compose.onNodeWithText("无法读取「第一章 OCR · 第 1 页」", substring = true).assertIsDisplayed()
        awaitNode(hasContentDescription("朗读"))
        compose.onNodeWithContentDescription("朗读").performClick()
        awaitNode(hasText("朗读失败"))
        compose.onNode(hasText("无法读取「第一章 OCR · 第 1 页」", substring = true) and
            hasAnyAncestor(isDialog()), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("没有可朗读", substring = true).assertDoesNotExist()
        assertEquals("A failed OCR read must be reported before starting audio", 0, speech.starts.get())
    }

    private fun assertBrokenChapterCanRecover(corrupt: Boolean, knownLength: Boolean) {
        val text = "REPAIRED_BODY\n修复正文缓存后，重试应显示原文并正常阅读。"
        val file = File(root, "broken.txt")
        if (corrupt) file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val chapter = Chapter(title = "故障章节", textPath = file.absolutePath,
            characterCount = if (knownLength) text.length else -1)
        show(Book(title = "错误不能变空章", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = listOf(chapter)))
        awaitNode(hasTestTag("reader_retry"))
        compose.onNodeWithText("无法读取「故障章节」", substring = true).assertIsDisplayed()
        compose.onNodeWithText("这一章没有可阅读的内容").assertDoesNotExist()
        assertEquals("A failed read must not publish a new reading position", 0, savedPositions.get())
        file.writeText(text)
        compose.onNodeWithTag("reader_retry").performClick()
        awaitNode(hasText("REPAIRED_BODY", substring = true))
        compose.onNodeWithText("REPAIRED_BODY", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reader_retry").assertDoesNotExist()
        compose.onNodeWithTag("reader_loading").assertDoesNotExist()
    }

    private fun show(book: Book) {
        currentBook = mutableStateOf(book)
        val factory: (Context) -> ReaderSpeechController = { speech }
        compose.setContent {
            if (mounted) MaterialTheme {
                var settings by remember { mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED,
                    showTime = false, showBattery = false)) }
                CompositionLocalProvider(LocalReaderSpeechFactory provides factory) {
                    ReaderScreen(currentBook.value, settings, emptyList(), {}, { _, _, _, _, _ -> savedPositions.incrementAndGet() },
                        { settings = it }, {}, {}, {}, emptyFlow())
                }
            }
        }
    }

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

    private class SilentSpeech : ReaderSpeechController {
        val starts = AtomicInteger()
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) { starts.incrementAndGet() }
        override fun setSpeed(speed: Float) {}
        override fun clearError() {}
        override fun stop() {}
        override fun release() {}
    }
}
