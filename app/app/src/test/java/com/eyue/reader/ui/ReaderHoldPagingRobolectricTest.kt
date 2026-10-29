package com.eyue.reader.ui

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.MotionEvent
import android.view.View
import com.eyue.reader.data.TapPagingMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderHoldPagingRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stationaryEdgeRepeatsAcrossRecompositionAndReleaseDoesNotAddAClick() {
        var page by mutableIntStateOf(0); var taps = 0
        val holding = mutableListOf<Boolean>()
        compose.setContent { Box(Modifier.size(300.dp).readerHoldPaging("book", TapPagingMode.HORIZONTAL, true, onHolding = { holding += it }) { page += it }
            .clickable { taps++ }.testTag("body")) { Text("Page $page") } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("body").performTouchInput { down(Offset(width * .9f, height * .5f)) }
        advanceBy(350); compose.runOnIdle { assertEquals(0, page) }
        advanceBy(800)
        var stopped = 0
        compose.runOnIdle { assertTrue("page=$page", page >= 2); stopped = page }
        compose.onNodeWithTag("body").performTouchInput { up() }
        advanceBy(1500); compose.runOnIdle { assertEquals(stopped, page); assertEquals(0, taps); assertEquals(listOf(true, false), holding) }
    }

    @Test fun verticalHoldCancelsWhenFingerMovesAndMiddleKeepsNormalClicks() {
        var turns = 0; var taps = 0
        compose.setContent { Box(Modifier.size(300.dp).readerHoldPaging("book", TapPagingMode.VERTICAL, true) { turns += it }
            .clickable { taps++ }.testTag("body")) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("body").performTouchInput { down(Offset(center.x, height * .1f)) }
        advanceBy(500); compose.runOnIdle { assertEquals(-1, turns) }
        compose.onNodeWithTag("body").performTouchInput { moveTo(Offset(center.x, height * .3f)) }
        advanceBy(1500); compose.runOnIdle { assertEquals(-1, turns) }
        compose.onNodeWithTag("body").performTouchInput { cancel() }
        compose.onNodeWithTag("body").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun disablingPagingOrOpeningControlsCancelsTheRunningHold() {
        var enabled by mutableStateOf(true); var turns = 0
        compose.setContent { Box(Modifier.size(300.dp).readerHoldPaging("book", TapPagingMode.HORIZONTAL, enabled) { turns += it }
            .testTag("body")) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("body").performTouchInput { down(Offset(width * .9f, center.y)) }
        advanceBy(500); compose.runOnIdle { assertEquals(1, turns); enabled = false }
        advanceBy(1500); compose.runOnIdle { assertEquals(1, turns) }
        compose.onNodeWithTag("body").performTouchInput { cancel() }
    }

    @Test fun aNativeViewReceivesNormalShortTapsButItsReleaseIsCancelledAfterHolding() {
        val actions = mutableListOf<Int>(); var turns = 0
        compose.setContent { Box(Modifier.size(300.dp).readerHoldPaging("webview-book", TapPagingMode.HORIZONTAL, true) { turns += it }
            .testTag("body")) {
            AndroidView(factory = { context -> View(context).apply { setOnTouchListener { _, event -> actions += event.actionMasked; true } } })
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("body").performTouchInput { click(Offset(width * .9f, center.y)) }
        compose.runOnIdle { assertTrue(actions.contains(MotionEvent.ACTION_UP)); actions.clear() }
        compose.onNodeWithTag("body").performTouchInput { down(Offset(width * .9f, center.y)) }
        advanceBy(900)
        compose.onNodeWithTag("body").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("turns=$turns", turns >= 2)
            assertTrue(actions.toString(), actions.contains(MotionEvent.ACTION_CANCEL))
            assertFalse(actions.toString(), actions.contains(MotionEvent.ACTION_UP))
        }
    }

    private fun advanceBy(milliseconds: Long) {
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idle() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idle() }
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)) }
        compose.waitForIdle()
    }
}
