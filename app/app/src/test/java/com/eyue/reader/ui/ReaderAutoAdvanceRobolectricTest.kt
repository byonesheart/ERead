package com.eyue.reader.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ReadingMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderAutoAdvanceRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageTimerHonorsSpeedPauseAndRestartWithoutCatchingUp() {
        var running by mutableStateOf(false)
        var interval by mutableFloatStateOf(2f)
        var turns = 0
        compose.setContent {
            val state = rememberLazyListState()
            val controller = remember { EbookReaderController() }
            ReaderAutoAdvance(running, ReadingMode.PAGED, interval, 32f, 1f, false, controller, state) { turns++ }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { running = true }
        advanceBy(1900)
        compose.runOnIdle { assertEquals(0, turns) }
        advanceBy(200)
        compose.runOnIdle { assertEquals(1, turns); running = false }
        advanceBy(10_000)
        compose.runOnIdle { assertEquals(1, turns); interval = 4f; running = true }
        advanceBy(3900)
        compose.runOnIdle { assertEquals(1, turns) }
        advanceBy(200)
        compose.runOnIdle { assertEquals(2, turns); running = false }
    }

    @Test fun nativeScrollRunsAtTheSelectedRateAndStopsWhileBlocked() {
        var running by mutableStateOf(false)
        var speed by mutableFloatStateOf(16f)
        lateinit var state: LazyListState
        compose.setContent { MaterialTheme {
            state = rememberLazyListState()
            val controller = remember { EbookReaderController() }
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                items(100) { Text("自动滚动第 $it 行", Modifier.height(40.dp)) }
            }
            ReaderAutoAdvance(running, ReadingMode.SCROLL, 10f, speed, 1f, false, controller, state) {}
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { running = true }
        advanceBy(2100)
        var slow = 0
        compose.runOnIdle { slow = state.firstVisibleItemScrollOffset; assertTrue("slow offset=$slow", slow in 28..36); speed = 64f }
        advanceBy(1000)
        var stopped = 0
        compose.runOnIdle { stopped = state.firstVisibleItemIndex * 40 + state.firstVisibleItemScrollOffset; running = false }
        assertTrue("faster offset=$stopped, slow=$slow", stopped - slow in 55..70)
        advanceBy(16)
        compose.runOnIdle { stopped = state.firstVisibleItemIndex * 40 + state.firstVisibleItemScrollOffset }
        advanceBy(3000)
        compose.runOnIdle { assertEquals(stopped, state.firstVisibleItemIndex * 40 + state.firstVisibleItemScrollOffset) }
    }

    @Test fun ebookControllerStopsTheCachedBrowserBeforeDetaching() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0) { commands += it }
        controller.loaded()
        controller.autoScroll(64f)
        controller.unbind()
        assertEquals(listOf(64.0, 0.0), commands.filter { it.optString("type") == "autoScroll" }.map { it.getDouble("speed") })
        val count = commands.size
        controller.autoScroll(64f)
        assertEquals(count, commands.size)
    }

    @Test fun automaticScrollRestartsAtTheSameSpeedWhenTheNextChapterBinds() {
        val controller = EbookReaderController()
        val old = mutableListOf<JSONObject>()
        val next = mutableListOf<JSONObject>()
        controller.bind(0) { old += it }; controller.loaded(); controller.autoScroll(32f)
        controller.bind(1) { next += it }
        controller.loaded(); controller.autoScroll(32f)
        assertEquals(0.0, old.last().getDouble("speed"), .001)
        assertEquals(32.0, next.last().getDouble("speed"), .001)
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
