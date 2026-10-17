package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderNoteAndTxtEditTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private data class Position(val chapter: Int, val page: Int, val offset: Int)
    private data class Edit(val chapter: Int, val start: Int, val end: Int, val replacement: String)
    private val position = AtomicReference<Position?>()
    private val edit = AtomicReference<Edit?>()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "reader-note-edit-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun shortTapOnAnnotatedTextShowsNotesWithoutChangingScrollPositionOrOpeningTools() {
        verifyNoteTap(ReadingMode.SCROLL)
    }

    @Test fun pagedTextTapAlsoFindsLegacyQuoteNotesAndIgnoresOtherBooks() {
        verifyNoteTap(ReadingMode.PAGED, legacy = true)
    }

    @Test fun draggingAnnotatedTextScrollsWithoutOpeningItsNote() {
        val text = "Annotated paragraph has a thought.\n".repeat(100)
        val book = book(text, BookFormat.MOBI)
        show(book, listOf(note(book, text, 0, 30)), ReadingMode.SCROLL)
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.onNodeWithTag("reader_note_popup").assertDoesNotExist()
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
    }

    @Test fun longPressTxtSelectionCanReplaceItsExactUtf16RangeWithoutTouchingTheOriginal() {
        val text = "Before😀 editable After."
        val book = book(text, BookFormat.TXT)
        show(book, emptyList(), ReadingMode.SCROLL)
        val start = text.indexOf("editable")
        val layout = layout()
        compose.onNodeWithContentDescription("阅读正文").performTouchInput { longClick(layout.getBoundingBox(start + 2).center) }
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("reader_txt_editor").performTextReplacement("替换😀")
        compose.onNodeWithText("保存").performClick()
        assertEquals(Edit(0, start, start + "editable".length, "替换😀"), edit.get())
        assertEquals(text, File(book.chapters.single().textPath!!).readText())
    }

    @Test fun txtToolbarEditorExposesTheWholeCurrentChapterIncludingParagraphBreaks() {
        val text = "第一个段落😀。\n第二个段落。"
        val book = book(text, BookFormat.TXT)
        show(book, emptyList(), ReadingMode.SCROLL)
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .5f, height * .65f)) }
        compose.onNodeWithContentDescription("编辑当前章节").performClick()
        compose.onNodeWithTag("reader_txt_editor").performTextReplacement("新正文\n第二段。")
        compose.onNodeWithText("保存").performClick()
        assertEquals(Edit(0, 0, text.length, "新正文\n第二段。"), edit.get())
        assertEquals(text, File(book.chapters.single().textPath!!).readText())
    }

    private fun verifyNoteTap(mode: ReadingMode, legacy: Boolean = false) {
        val text = "Before😀 annotated words follow.\nA second paragraph."
        val book = book(text, BookFormat.MOBI)
        val start = text.indexOf("annotated")
        val first = note(book, text, start, start + "annotated words".length)
        val second = first.copy(id = "second", note = "第二条想法", endOffset = if (legacy) 0 else first.endOffset)
        show(book, listOf(first, second, first.copy(id = "other", bookId = "other-book", note = "不属于此书")), mode)
        val before = position.get()
        val notePoint = layout().getBoundingBox(start + 2).center
        compose.onNodeWithContentDescription("阅读正文").performTouchInput { click(notePoint) }
        compose.onNodeWithTag("reader_note_popup").assertIsDisplayed()
        compose.onNodeWithText("这一句的笔记").assertIsDisplayed()
        compose.onNodeWithText("第二条想法").assertIsDisplayed()
        compose.onNodeWithText("不属于此书").assertDoesNotExist()
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        assertEquals(before, position.get())
        compose.onNodeWithTag("reader_note_popup").assertDoesNotExist()
        val unmarked = text.indexOf("Before")
        val unmarkedPoint = layout().getBoundingBox(unmarked).center
        compose.onNodeWithContentDescription("阅读正文").performTouchInput { click(unmarkedPoint) }
        compose.onNodeWithContentDescription("目录").assertIsDisplayed()
    }

    private fun book(text: String, format: BookFormat): Book = Book(id = "book-${UUID.randomUUID()}",
        title = "点击笔记和编辑", format = format, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "正文", textPath = File(root, "text.txt").apply { writeText(text) }.absolutePath)))

    private fun note(book: Book, text: String, start: Int, end: Int): Annotation = Annotation(id = "first", bookId = book.id,
        chapterIndex = 0, pageIndex = 0, characterOffset = start, endOffset = end, quote = text.substring(start, end),
        note = "这一句的笔记", kind = AnnotationKind.NOTE)

    private fun show(book: Book, notes: List<Annotation>, mode: ReadingMode) {
        val settings = ReaderSettings(readingMode = mode, showTime = false, showBattery = false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ReaderScreen(book, settings, notes, {}, { chapter, page, offset, _, _ ->
                        position.set(Position(chapter, page, offset))
                    }, {}, {}, {}, {}, emptyFlow(), onEditTxt = { chapter, start, end, replacement ->
                        edit.set(Edit(chapter, start, end, replacement))
                    })
                }
            }
        }
        compose.waitUntil(10_000) { position.get() != null && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithContentDescription("阅读正文")[0].assertIsDisplayed()
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first()
    }
}
