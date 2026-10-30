package com.eyue.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
class ReaderPaperDragRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun edgeGrabPreviewsBeforeReleaseAndCanCommitReverseOrCancel() {
        val preview = mutableListOf<Pair<Int, Float>>()
        val commits = mutableListOf<Boolean>()
        compose.setContent { Box(Modifier.size(300.dp, 400.dp).testTag("paper")
            .readerPaperDrag(true, { direction, fraction -> preview += direction to fraction }, { commits += it })) }
        val paper = compose.onNodeWithTag("paper")
        paper.performTouchInput { down(Offset(width - 2f, center.y)); moveBy(Offset(-width * .4f, 0f)) }
        compose.runOnIdle { assertTrue(preview.last().first == 1); assertEquals(.4f, preview.last().second, .02f); assertTrue(commits.isEmpty()) }
        paper.performTouchInput { up() }
        assertEquals(listOf(true), commits)
        paper.performTouchInput { down(Offset(2f, center.y)); moveBy(Offset(width * .1f, 0f)); up() }
        assertEquals(-1, preview.last().first)
        assertEquals(listOf(true, false), commits)
        paper.performTouchInput { down(Offset(2f, center.y)); moveBy(Offset(width * .5f, 0f)); cancel() }
        assertEquals(listOf(true, false, false), commits)
        val count = preview.size
        paper.performTouchInput { down(center); moveBy(Offset(-width * .4f, 0f)); up() }
        assertEquals(count, preview.size)
    }
}
