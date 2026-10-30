package com.eyue.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

internal class ReaderPaperDrag(val origin: ReaderPageLocation, val target: ReaderPageLocation,
    val direction: Int, initialFraction: Float) {
    var fraction by mutableFloatStateOf(initialFraction)
}

/** A short horizontal gesture grabs the paper; long presses remain available to text selection. */
internal fun Modifier.readerPaperDrag(enabled: Boolean, onDrag: (Int, Float) -> Unit,
    onRelease: (Boolean) -> Unit): Modifier = composed {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var direction = 0
            var active = false
            var released = false
            var fraction = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.size != 1) break
                    // Compose represents ACTION_CANCEL as consumed pointer-up changes.
                    if (!change.pressed && change.isConsumed) break
                    val delta = change.position - down.position
                    if (!active && change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                    if (!active && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    if (!active && abs(delta.x) > viewConfiguration.touchSlop) {
                        active = true; direction = if (delta.x < 0) 1 else -1
                    }
                    if (active) {
                        fraction = (-delta.x * direction / size.width).coerceIn(0f, 1f)
                        change.consume(); latestDrag(direction, fraction)
                    }
                    if (!change.pressed) { released = true; break }
                }
            } finally { if (active) latestRelease(released && fraction >= .22f) }
        }
    }
}
