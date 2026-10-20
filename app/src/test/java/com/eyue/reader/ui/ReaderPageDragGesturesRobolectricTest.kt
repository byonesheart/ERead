package com.eyue.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageDragGesturesRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun horizontalDragTurnsWhileFingerIsDownAndOnlyOncePerGesture() = assertImmediateTurn(false)
    @Test fun verticalDragTurnsWhileFingerIsDownAndOnlyOncePerGesture() = assertImmediateTurn(true)

    private fun assertImmediateTurn(vertical: Boolean) {
        val turns = CopyOnWriteArrayList<Int>()
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(300.dp, 400.dp).testTag("pages").pointerInput(vertical) {
                readerPageDragGestures(vertical, { starts.incrementAndGet() }, { turns += it }, { finishes.incrementAndGet() })
            })
        }
        val pages = compose.onNodeWithTag("pages")
        pages.performTouchInput { down(center); moveBy(if (vertical) Offset(0f, -5f) else Offset(-5f, 0f)); up() }
        assertEquals("A short drag must keep the current page", emptyList<Int>(), turns.toList())
        pages.performTouchInput {
            down(center)
            moveBy(if (vertical) Offset(0f, -height * .3f) else Offset(-width * .3f, 0f), 16)
        }
        compose.waitForIdle()
        assertEquals("Crossing the threshold must turn before any pointer-up event", listOf(1), turns.toList())
        assertEquals(1, starts.get())
        assertEquals("The gesture is still active", 0, finishes.get())
        pages.performTouchInput {
            moveBy(if (vertical) Offset(0f, -height * .1f) else Offset(-width * .1f, 0f), 16)
            moveBy(if (vertical) Offset(0f, height * .2f) else Offset(width * .2f, 0f), 16)
            up()
        }
        assertEquals("Continuing/reversing the same drag and lifting must not turn again", listOf(1), turns.toList())
        assertEquals(1, finishes.get())
        pages.performTouchInput {
            down(center)
            moveBy(if (vertical) Offset(0f, height * .3f) else Offset(width * .3f, 0f), 16)
        }
        assertEquals("A new opposite gesture must work without waiting for release", listOf(1, -1), turns.toList())
        pages.performTouchInput { cancel() }
        assertEquals(listOf(1, -1), turns.toList())
        assertEquals(2, finishes.get())
    }

    @Test fun dragConsumedByTextSelectionDoesNotTurnThePage() {
        val turns = AtomicInteger()
        compose.setContent {
            Box(Modifier.size(300.dp, 400.dp).pointerInput(Unit) {
                readerPageDragGestures(false, {}, { turns.incrementAndGet() }, {})
            }) {
                Box(Modifier.fillMaxSize().testTag("selection").pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                            pressed = event.changes.any { it.pressed }
                        }
                    }
                })
            }
        }
        compose.onNodeWithTag("selection").performTouchInput {
            down(Offset(width * .8f, height / 2f))
            moveTo(Offset(width * .2f, height / 2f), 16)
            up()
        }
        assertEquals("Selection retains pointer consumption priority", 0, turns.get())
    }
}
