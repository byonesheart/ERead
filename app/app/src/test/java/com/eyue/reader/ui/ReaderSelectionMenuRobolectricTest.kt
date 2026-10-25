package com.eyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSelectionMenuRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Config(shadows = [NoSurfaceMagnifier::class])
    @Test fun aRealLongPressShowsAnnotationActionsWithTheNewContextMenuAndSourceOffsets() {
        var selected: ReaderSelectedPassage? = null
        var selectedKind: AnnotationKind? = null
        compose.setContent { MaterialTheme {
            ReaderSelectableText(AnnotatedString("A selected passage with more words."), TextStyle(fontSize = 18.sp),
                2, 3, 200, 0, onAction = { passage, kind -> selected = passage; selectedKind = kind },
                modifier = Modifier.width(280.dp))
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        val text = compose.onNodeWithContentDescription("阅读正文")
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val word = layouts.single().getBoundingBox(4).center
        text.performTouchInput { longClick(word) }
        compose.onNodeWithTag("reader_action_note").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(AnnotationKind.NOTE, selectedKind)
            assertEquals("selected", selected?.quote)
            assertEquals(202, selected?.start)
            assertEquals(210, selected?.end)
            assertEquals(2, selected?.chapter)
            assertEquals(3, selected?.page)
        }
        compose.onNodeWithTag("reader_selection_menu").assertDoesNotExist()
    }

    @Test fun repeatedNativeToolbarRequestsCannotCoverAnAnnotationEditor() {
        var toolbar: TextToolbar? = null
        var latestPassage: ReaderSelectedPassage? = null
        var latestKind: AnnotationKind? = null
        compose.setContent {
            var selectionReset by remember { mutableIntStateOf(0) }
            var selection by remember { mutableStateOf<ReaderSelectedPassage?>(null) }
            var kind by remember { mutableStateOf(AnnotationKind.NOTE) }
            MaterialTheme {
                Column {
                    ReaderSelectableText(AnnotatedString("A selected passage with more words."), TextStyle(fontSize = 18.sp),
                        0, 0, 0, selectionReset, onAction = { passage, action ->
                            latestPassage = passage; latestKind = action; selection = passage; kind = action
                        }, modifier = Modifier.width(280.dp).composed {
                            val provided = LocalTextToolbar.current
                            SideEffect { toolbar = provided }
                            this
                        })
                }
                selection?.let { passage -> ReaderSelectionEditor(ReaderSettings(), passage, kind, { kind = it },
                    "", {}, 0xffefb85a, {}, {}, { selection = null; selectionReset++ }) }
            }
        }
        listOf("note" to AnnotationKind.NOTE, "highlight" to AnnotationKind.HIGHLIGHT,
            "underline" to AnnotationKind.UNDERLINE).forEach { (action, kind) ->
            compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.SetSelection) {
                assertTrue(it(2, 10, false))
            }
            compose.runOnIdle { toolbar!!.showMenu(Rect(120f, 250f, 200f, 280f), null, null, null, null) }
            compose.onNodeWithTag("reader_selection_menu").assertIsDisplayed()
            compose.onNodeWithTag("reader_action_$action").performClick()
            compose.runOnIdle {
                // Reproduce the selection manager re-requesting its menu after the action.
                toolbar!!.showMenu(Rect(120f, 250f, 200f, 280f), null, null, null, null)
                toolbar!!.showMenu(Rect(120f, 250f, 200f, 280f), null, null, null, null)
                assertEquals(TextToolbarStatus.Hidden, toolbar!!.status)
                assertEquals(kind, latestKind)
                assertEquals("selected", latestPassage?.quote)
                assertEquals(2, latestPassage?.start)
                assertEquals(10, latestPassage?.end)
            }
            compose.onNodeWithTag("reader_annotation_bubble").assertIsDisplayed()
            compose.onNodeWithTag("reader_selection_menu").assertDoesNotExist()
            compose.onNodeWithTag("reader_action_cancel").performScrollTo().performClick()
            compose.onNodeWithTag("reader_annotation_bubble").assertDoesNotExist()
        }
    }

    @Test fun narrowMenusKeepOneRowAndShowRemainingActionsVertically() {
        var chosen = ""
        val actions = listOf("note" to "笔记", "highlight" to "标记", "underline" to "划线", "edit" to "编辑", "copy" to "复制")
        compose.setContent { MaterialTheme {
            ReaderCompactActionMenu(actions.map { (id, label) -> ReaderCompactAction(id, label) { chosen = id } },
                Modifier.width(132.dp))
        } }
        val note = compose.onNodeWithTag("reader_action_note").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("reader_selection_more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(note.center.y, more.center.y, 1f)
        compose.onNodeWithTag("reader_action_edit").assertDoesNotExist()
        compose.onNodeWithTag("reader_selection_more").performClick()
        val underline = compose.onNodeWithTag("reader_action_underline").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithTag("reader_action_edit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val copy = compose.onNodeWithTag("reader_action_copy").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(underline.bottom <= edit.top)
        assertTrue(edit.bottom <= copy.top)
        compose.onNodeWithTag("reader_action_copy").performClick()
        compose.runOnIdle { assertEquals("copy", chosen) }
    }

    @Test fun largeSystemFontsReserveTheMoreControlInsteadOfClippingActions() {
        var chosen = false
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            MaterialTheme { ReaderCompactActionMenu(listOf(
                ReaderCompactAction("note", "笔记") {},
                ReaderCompactAction("long", "较长的编辑操作名称") { chosen = true },
                ReaderCompactAction("copy", "复制") {}
            ), Modifier.width(150.dp)) }
        } }
        val more = compose.onNodeWithTag("reader_selection_more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(more.right <= 150f)
        compose.onNodeWithTag("reader_action_long").assertDoesNotExist()
        compose.onNodeWithTag("reader_selection_more").performClick()
        compose.onNodeWithTag("reader_action_long").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(chosen) }
    }

    @Test fun menuPartitionReservesOverflowAndKeepsActionPriority() {
        assertEquals(3, readerVisibleMenuCount(listOf(40, 40, 40), 120, 32))
        assertEquals(2, readerVisibleMenuCount(listOf(40, 40, 40, 40), 112, 32))
        assertEquals(0, readerVisibleMenuCount(listOf(160, 40), 100, 32))
    }

    /** Selection gestures remain real; Android 28's magnifier alone requires a device Surface. */
    @Implements(android.widget.Magnifier::class)
    class NoSurfaceMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    }
}
