package com.eyue.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.*
import com.eyue.reader.data.Annotation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationFiltersRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val book = Book(id = "book", title = "筛选书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "第一章")))
    private val annotations = AnnotationKind.entries.map { kind -> Annotation(id = kind.name, bookId = book.id,
        chapterIndex = 0, pageIndex = 0, quote = "摘录-${kind.name}",
        note = if (kind == AnnotationKind.NOTE) "独立笔记" else "", kind = kind) }

    @Test fun allFiveCompactFiltersFitOneRowAndReportTheChosenKind() {
        val chosen = mutableListOf<AnnotationKind?>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { MaterialTheme {
            Box(Modifier.width(244.dp)) {
                var kind by remember { mutableStateOf<AnnotationKind?>(null) }
                AnnotationFilterRow(kind, { kind = it; chosen += it })
            }
        } } }
        val ids = listOf("ALL", "BOOKMARK", "NOTE", "HIGHLIGHT", "UNDERLINE")
        val bounds = ids.map { compose.onNodeWithTag("annotation_filter_$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.last().right <= 244f)
        bounds.forEach { assertEquals(bounds.first().center.y, it.center.y, 1f); assertTrue(it.height <= 36f) }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right < right.left) }
        compose.onNodeWithTag("annotation_filter_ALL").assertIsSelected()
        compose.onNodeWithTag("annotation_filter_UNDERLINE").performClick().assertIsSelected()
        compose.onNodeWithTag("annotation_filter_NOTE").performClick().assertIsSelected()
        compose.onNodeWithTag("annotation_filter_ALL").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(AnnotationKind.UNDERLINE, AnnotationKind.NOTE, null), chosen) }
    }

    @Test fun narrowWindowsAndLargeFontsScrollToTheUnderlineWithoutVerticalLabels() {
        var chosen: AnnotationKind? = null
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { MaterialTheme {
            Box(Modifier.width(164.dp)) { AnnotationFilterRow(null, { chosen = it }) }
        } } }
        compose.onNodeWithTag("annotation_filter_UNDERLINE").performScrollTo().assertIsDisplayed().performClick()
        val bounds = compose.onNodeWithTag("annotation_filter_UNDERLINE").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0f && bounds.right <= 164f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("划线", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().didOverflowHeight)
        compose.runOnIdle { assertEquals(AnnotationKind.UNDERLINE, chosen) }
    }

    @Test fun externalNotebookCanFilterUnderlineRecordsAndOpenOnlyTheMatchingAnnotation() {
        var opened: Annotation? = null
        compose.setContent { MaterialTheme { NotebookDialog(LibraryState(books = listOf(book), annotations = annotations), {}, {}, { opened = it }) } }
        compose.onNodeWithTag("notebook_close").assertIsDisplayed()
        compose.onNodeWithTag("notebook_annotation_filter_UNDERLINE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("摘录-UNDERLINE").assertIsDisplayed().performClick()
        compose.onNodeWithText("摘录-BOOKMARK").assertDoesNotExist()
        compose.onNodeWithText("摘录-NOTE").assertDoesNotExist()
        compose.onNodeWithText("摘录-HIGHLIGHT").assertDoesNotExist()
        compose.runOnIdle { assertEquals(AnnotationKind.UNDERLINE, opened?.kind) }
    }

    @Test fun externalNotebookHasBoundedScrollingContentAndAReachableFooterWithLargeFonts() {
        var dismissed = false
        val records = (0 until 30).map { annotations[it % annotations.size].copy(id = "entry-$it") }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { MaterialTheme {
            NotebookDialog(LibraryState(books = listOf(book), annotations = records), { dismissed = true }, {}, {})
        } } }
        val dialog = compose.onNodeWithTag("notebook_dialog").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("notebook_annotation_list").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("notebook_close").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.width <= 360f && dialog.height <= 800f)
        assertTrue(list.height > 0f && list.bottom <= close.top)
        compose.onNodeWithTag("notebook_annotation_filter_UNDERLINE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("notebook_annotation_list").performScrollToIndex(6)
        compose.onNodeWithTag("notebook_close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun readerAnnotationFiltersUseTheSameCompactChoicesAndFilterTheirList() {
        var opened: Annotation? = null
        compose.setContent { MaterialTheme {
            var kind by remember { mutableStateOf<AnnotationKind?>(null) }
            Column(Modifier.width(320.dp)) {
                ReaderAnnotationsPanel(book, false, annotations, kind, { kind = it }, "", {}, { opened = it }, {})
            }
        } }
        compose.onNodeWithTag("reader_annotation_filter_UNDERLINE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("摘录-UNDERLINE").assertIsDisplayed().performClick()
        compose.onNodeWithText("摘录-BOOKMARK").assertDoesNotExist()
        compose.onNodeWithText("摘录-HIGHLIGHT").assertDoesNotExist()
        compose.runOnIdle { assertEquals(AnnotationKind.UNDERLINE, opened?.kind) }
    }
}
