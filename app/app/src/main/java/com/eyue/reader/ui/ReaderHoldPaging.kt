package com.eyue.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.eyue.reader.data.TapPagingMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Observe the same gesture as text/WebView; only a stationary edge hold consumes its release. */
internal fun Modifier.readerHoldPaging(bookId: String, mode: TapPagingMode, enabled: Boolean,
    onHolding: (Boolean) -> Unit = {}, onPage: (Int) -> Unit): Modifier = composed {
    val latestPage by rememberUpdatedState(onPage)
    val latestHolding by rememberUpdatedState(onHolding)
    pointerInput(bookId, mode, enabled) {
        if (!enabled || mode == TapPagingMode.OFF) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val direction = readerTapPageDirection(mode, down.position.x / size.width.coerceAtLeast(1),
                    down.position.y / size.height.coerceAtLeast(1))
                if (direction == 0) return@awaitEachGesture
                var held = false
                val repeat = launch {
                    // Start before text selection; repeat at a bounded rate through the reader's normal ready checks.
                    delay((viewConfiguration.longPressTimeoutMillis - 100).coerceAtLeast(200))
                    held = true
                    latestHolding(true)
                    while (true) { latestPage(direction); delay(350) }
                }
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.size != 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (held) change.consume()
                    } while (change.pressed)
                } finally { repeat.cancel(); if (held) latestHolding(false) }
            }
        }
    }
}
