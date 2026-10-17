package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.eyue.reader.data.UnderlineStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAnnotationControlsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactMenuWidthFollowsOnlyItsActualButtons() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { MaterialTheme {
            ReaderCompactActionMenu(listOf(ReaderCompactAction("note", "笔记") {}, ReaderCompactAction("copy", "复制") {}),
                Modifier.testTag("adaptive_menu"))
        } } }
        val menu = compose.onNodeWithTag("adaptive_menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithTag("reader_action_note").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val copy = compose.onNodeWithTag("reader_action_copy").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(note.left, menu.left, 1f)
        assertEquals(copy.right, menu.right, 1f)
        assertEquals(note.center.y, copy.center.y, 1f)
        assertTrue("Two short actions should not occupy the whole screen: $menu", menu.width < 150f)
        compose.onNodeWithTag("reader_selection_more").assertDoesNotExist()
    }

    @Test fun selectionBubbleHugsTheMenuAndResizesWhenActionsChange() {
        var count by mutableIntStateOf(2)
        val actions = listOf("note" to "笔记", "highlight" to "标记", "underline" to "划线", "edit" to "编辑", "copy" to "复制")
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { MaterialTheme {
            ReaderAnnotationPopup(Rect(120f, 250f, 180f, 280f), {}) {
                ReaderCompactActionMenu(actions.take(count).map { (id, label) -> ReaderCompactAction(id, label) {} },
                    Modifier.testTag("adaptive_menu"))
            }
        } } }
        fun bubbleWidth(): Float {
            val bubble = compose.onNodeWithTag("reader_annotation_bubble").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithTag("adaptive_menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Bubble should add only its horizontal content padding", menu.width + 24f, bubble.width, 1f)
            assertTrue(bubble.width < 300f)
            return bubble.width
        }
        val small = bubbleWidth()
        compose.runOnIdle { count = 5 }
        val larger = bubbleWidth()
        assertTrue(larger > small)
        compose.runOnIdle { count = 2 }
        assertEquals(small, bubbleWidth(), 1f)
    }

    @Test fun underlineChoicesUseLinePreviewsAndKeepCompactBoundsAtLargeFontScale() {
        var selected by mutableStateOf(UnderlineStyle.STRAIGHT)
        val changes = mutableListOf<UnderlineStyle>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { MaterialTheme {
            ReaderUnderlineStylePicker(selected) { selected = it; changes += it }
        } } }
        val row = compose.onNodeWithTag("reader_underline_styles").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val straight = compose.onNodeWithContentDescription("直线").assertIsSelected()
        val wavy = compose.onNodeWithContentDescription("波浪线").assertIsNotSelected()
        val straightBounds = straight.fetchSemanticsNode().boundsInRoot
        val wavyBounds = wavy.fetchSemanticsNode().boundsInRoot
        assertEquals(104f, row.width, 1f)
        assertEquals(36f, row.height, 1f)
        assertTrue(straightBounds.right <= wavyBounds.left)
        compose.onNodeWithText("直线").assertDoesNotExist()
        compose.onNodeWithText("波浪线").assertDoesNotExist()
        compose.onNodeWithTag("reader_underline_preview_STRAIGHT", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reader_underline_preview_WAVY", useUnmergedTree = true).assertExists()
        wavy.performClick().assertIsSelected()
        straight.assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(UnderlineStyle.WAVY, UnderlineStyle.STRAIGHT), changes) }
    }
}
