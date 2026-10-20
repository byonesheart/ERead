package com.eyue.reader.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.SourceFootnote
import com.eyue.reader.data.SourceTextStyle
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSourceFootnoteRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "source-footnote-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { check(root.deleteRecursively()) }

    @Test fun epubReferenceInScrollModeShowsTheSourceNoteWithoutMovingOrCreatingUserNotes() {
        verifyTextReference(BookFormat.EPUB, ReadingMode.SCROLL, ChineseConversion.ORIGINAL)
    }

    @Test fun mobiReferenceInPagedTraditionalTextRetainsOriginalOffsetsAndSourceFontScale() {
        verifyTextReference(BookFormat.MOBI, ReadingMode.PAGED, ChineseConversion.TRADITIONAL)
    }

    @Test fun nativeInlineImageReferenceOpensItsSourceNoteByPathAndZeroWidthOffset() {
        val image = File(root, "note-marker.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try { image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        val text = "图片标记后的正文。\n".repeat(10)
        val footnote = SourceFootnote(0, 0, "原文图标", "图标对应的原始注释内容", image.absolutePath)
        val chapter = Chapter(title = "正文", textPath = File(root, "image.txt").apply { writeText(text) }.absolutePath,
            inlineImages = listOf(InlineImage(0, image.absolutePath)), sourceFootnotes = listOf(footnote))
        val book = Book(title = "图片注释", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        val position = AtomicReference<Pair<Int, Int>>()
        showBook(book, ReaderSettings(readingMode = ReadingMode.SCROLL), emptyList(), SilentSpeech(), position) {}
        awaitTag("reader_inline_image_0_0")
        compose.waitUntil(20_000) { position.get() != null }
        val before = position.get()
        compose.onNodeWithTag("reader_inline_image_0_0").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        awaitTag("reader_source_footnote_popup")
        compose.onNodeWithTag("reader_source_footnote_content").assertTextEquals(footnote.content)
        compose.runOnIdle { assertEquals(before, position.get()) }
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
    }

    @Test fun sourceReferenceMatchingUsesHalfOpenTextRangesAndImagePathTogetherWithOffset() {
        val text = SourceFootnote(3, 6, "[1]", "文字注释")
        val first = SourceFootnote(8, 8, "图片1", "第一张图的注释", "same.png")
        val second = SourceFootnote(12, 12, "图片2", "第二张图的注释", "same.png")
        val items = listOf(text, first, second)
        assertEquals(text, readerSourceFootnoteAt(items, 3))
        assertNull(readerSourceFootnoteAt(items, 6))
        assertNull(readerSourceFootnoteAt(items, 8))
        assertEquals(first, readerSourceFootnoteAt(items, 8, "same.png"))
        assertEquals(second, readerSourceFootnoteAt(items, 12, "same.png"))
        assertNull(readerSourceFootnoteAt(items, 10, "same.png"))
        assertNull(readerSourceFootnoteAt(items, 8, "another.png"))
    }

    private fun verifyTextReference(format: BookFormat, mode: ReadingMode, conversion: ChineseConversion) {
        val text = "甲㓆乙[1]随后是头发与发展。\n" + "后续正文用于滚动或翻页，保持原本的段落内容。\n".repeat(100)
        val marker = text.indexOf("[1]")
        val footnote = SourceFootnote(marker, marker + 3, "[1]", "这是一条来自源文件的解释，不属于阅读笔记。")
        val chapter = Chapter(title = "第一章", textPath = File(root, "body.txt").apply { writeText(text) }.absolutePath,
            sourceStyles = listOf(SourceTextStyle(marker, marker + 3, fontScale = .65f)), sourceFootnotes = listOf(footnote))
        val book = Book(id = "footnote-book", title = "原生注释", format = format, mode = LibraryMode.NOVEL,
            chapters = listOf(chapter, Chapter(title = "第二章", textPath = File(root, "next.txt").apply { writeText("第二章原文") }.absolutePath)))
        val ownNote = Annotation(id = "user-note", bookId = book.id, chapterIndex = 0, pageIndex = 0,
            characterOffset = marker, endOffset = marker + 3, quote = "[1]", note = "用户自己写的笔记", kind = AnnotationKind.NOTE)
        val settings = ReaderSettings(readingMode = mode, chineseConversion = conversion, showTime = false, showBattery = false)
        val speech = SilentSpeech()
        val position = AtomicReference<Pair<Int, Int>>()
        var userNoteChanges = 0
        showBook(book, settings, listOf(ownNote), speech, position) { userNoteChanges++ }
        awaitText()
        compose.waitUntil(20_000) { position.get() != null }
        val body = compose.onAllNodesWithContentDescription("阅读正文").onFirst()
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val renderedMarker = layout.layoutInput.text.text.indexOf("[1]")
        assertTrue(renderedMarker >= 0)
        if (conversion == ChineseConversion.TRADITIONAL) {
            assertTrue(layout.layoutInput.text.text.startsWith("甲𠗣乙"))
            assertNotEquals(marker, renderedMarker)
        }
        assertTrue(layout.layoutInput.text.spanStyles.any { it.start <= renderedMarker && it.end > renderedMarker &&
            it.item.fontSize.value == settings.fontSize * .65f })
        assertTrue(layout.layoutInput.text.spanStyles.any { it.start <= renderedMarker && it.end > renderedMarker &&
            it.item.textDecoration == TextDecoration.Underline })
        val before = position.get()
        val pausesBefore = speech.pauses
        body.performTouchInput { click(layout.getBoundingBox(renderedMarker + 1).center) }
        compose.onNodeWithTag("reader_source_footnote_popup").assertIsDisplayed()
        compose.onNodeWithTag("reader_source_footnote_title").assertTextEquals("原文注释")
        compose.onNodeWithTag("reader_source_footnote_content").assertTextEquals(footnote.content)
        compose.onNodeWithTag("reader_annotation_date_user-note").assertDoesNotExist()
        compose.onNodeWithTag("reader_annotation_delete_user-note").assertDoesNotExist()
        compose.onNodeWithTag("reader_note_popup").assertDoesNotExist()
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, position.get()); assertEquals(0, userNoteChanges); assertEquals(pausesBefore, speech.pauses) }
        if (mode == ReadingMode.SCROLL) compose.onNodeWithTag("reader_scroll").performTouchInput {
            swipeUp(startY = height * .85f, endY = height * .6f)
        } else compose.onNodeWithTag("reader_body").performTouchInput {
            swipe(Offset(width * .9f, height * .85f), Offset(width * .1f, height * .85f))
        }
        compose.onNodeWithTag("reader_source_footnote_popup").assertDoesNotExist()
    }

    private fun showBook(book: Book, settings: ReaderSettings, notes: List<Annotation>, speech: SilentSpeech,
        position: AtomicReference<Pair<Int, Int>>, onNoteChange: () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalReaderSpeechFactory provides { speech }) { MaterialTheme {
                ReaderScreen(book, settings, notes, {}, { chapter, page, _, _, _ -> position.set(chapter to page) }, {},
                    { onNoteChange() }, { onNoteChange() }, {}, emptyFlow())
            } }
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(20_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText() = compose.waitUntil(20_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
    }

    private class SilentSpeech : ReaderSpeechController {
        var pauses = 0
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() = Unit
        override fun pause(): Boolean { pauses++; return false }
        override fun resume() = false
        override fun stop() = Unit
        override fun release() = Unit
    }
}
