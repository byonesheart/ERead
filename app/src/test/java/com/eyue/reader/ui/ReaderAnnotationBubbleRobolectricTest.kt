package com.eyue.reader.ui

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.UnderlineStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAnnotationBubbleRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private val createdAt = 1_700_000_000_000L
    private val anchor = Rect(140f, 250f, 200f, 280f)

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "annotation-bubble-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun tappingAHighlightShowsItsDateAndAllowsDeletingItWithoutOpeningADialog() {
        verifyTextAnnotationDeletion(AnnotationKind.HIGHLIGHT, ReadingMode.SCROLL)
    }

    @Test fun tappingAWavyUnderlineOnAPagedNovelAllowsDeletingItWithoutANote() {
        verifyTextAnnotationDeletion(AnnotationKind.UNDERLINE, ReadingMode.PAGED)
    }

    @Test fun twentyCharacterNotesUseABubbleAndDisplayTheirCreationDate() {
        val item = annotation(AnnotationKind.NOTE).copy(note = "想".repeat(20))
        compose.setContent { MaterialTheme { ReaderAnnotationPreview(ReaderSettings(), listOf(item), anchor, {}, {}) } }
        compose.onNodeWithTag("reader_annotation_bubble").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(item.note).assertIsDisplayed()
        assertDate(item)
    }

    @Test fun notesLongerThanTwentyCharactersUseADialogWithDateAndDeleteAction() {
        val item = annotation(AnnotationKind.NOTE).copy(note = "想".repeat(21))
        var deleted: String? = null
        compose.setContent { MaterialTheme { ReaderAnnotationPreview(ReaderSettings(), listOf(item), anchor, { deleted = it }, {}) } }
        compose.onNodeWithTag("reader_note_popup").assertIsDisplayed()
        compose.onNodeWithTag("reader_annotation_bubble").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(item.note).assertIsDisplayed()
        assertDate(item)
        compose.onNodeWithTag("reader_annotation_delete_${item.id}").performClick()
        compose.runOnIdle { assertEquals(item.id, deleted) }
    }

    @Test fun underlineEditorUsesANonModalBubbleAndSavesTheSelectedWavyStyle() {
        var savedStyle: UnderlineStyle? = null
        compose.setContent {
            var style by remember { mutableStateOf(UnderlineStyle.STRAIGHT) }
            MaterialTheme {
                ReaderSelectionEditor(ReaderSettings(), ReaderSelectedPassage(0, 0, 0, 4, "选中文字", anchor = anchor),
                    AnnotationKind.UNDERLINE, {}, "", {}, 0xffefb85a, {},
                    { savedStyle = style }, {}, underlineStyle = style, onUnderlineStyle = { style = it })
            }
        }
        compose.onNodeWithTag("reader_annotation_bubble").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription("直线").assertIsSelected()
        compose.onNodeWithContentDescription("波浪线").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("直线").assertIsNotSelected()
        compose.onNodeWithText("直线").assertDoesNotExist()
        compose.onNodeWithText("波浪线").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(UnderlineStyle.WAVY, savedStyle) }
    }

    private fun verifyTextAnnotationDeletion(kind: AnnotationKind, mode: ReadingMode) {
        val text = "Before annotated words follow.\nA second paragraph."
        val start = text.indexOf("annotated")
        val item = annotation(kind).copy(characterOffset = start, endOffset = start + "annotated".length,
            quote = "annotated", underlineStyle = UnderlineStyle.WAVY)
        val book = Book(id = "book", title = "标注删除", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "正文", textPath = File(root, "body.txt").apply { writeText(text) }.absolutePath)))
        val deleted = mutableListOf<String>()
        compose.setContent {
            var items by remember { mutableStateOf(listOf(item)) }
            CompositionLocalProvider(LocalReaderSpeechFactory provides { SilentSpeech() }) {
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(readingMode = mode, showTime = false, showBattery = false), items,
                        {}, { _, _, _, _, _ -> }, {}, {}, { id -> deleted += id; items = items.filterNot { it.id == id } }, {}, emptyFlow())
                }
            }
        }
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val glyph = layouts.first().getBoundingBox(start + 2)
        compose.onNodeWithContentDescription("阅读正文").performTouchInput { click(glyph.center) }
        compose.onNodeWithTag("reader_annotation_bubble").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertDate(item)
        compose.onNodeWithTag("reader_annotation_delete_${item.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(item.id), deleted) }
        compose.onNodeWithTag("reader_annotation_bubble").assertDoesNotExist()
    }

    private fun annotation(kind: AnnotationKind) = Annotation(id = "item", bookId = "book", chapterIndex = 0,
        pageIndex = 0, characterOffset = 0, endOffset = 4, quote = "选中文字", kind = kind, createdAt = createdAt)

    private fun assertDate(item: Annotation) {
        val dateText = compose.onNodeWithTag("reader_annotation_date_${item.id}").assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val expectedDay = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(item.createdAt))
        assertTrue("Creation date should use the annotation timestamp: $dateText", dateText.contains(expectedDay))
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
