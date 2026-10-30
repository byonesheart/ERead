package com.eyue.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal class ReaderPaperDrag(val origin: ReaderPageLocation, val target: ReaderPageLocation,
    val direction: Int, initialFraction: Float) {
    var fraction by mutableFloatStateOf(initialFraction)
}

/** Only the paper's outer edge is grabbed; middle drags retain selection and ordinary swipe behavior. */
internal fun Modifier.readerPaperDrag(enabled: Boolean, onDrag: (Int, Float) -> Unit,
    onRelease: (Boolean) -> Unit): Modifier = composed {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestRelease by rememberUpdatedState(onRelease)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val edge = (size.width * .08f).coerceIn(24.dp.toPx(), 40.dp.toPx())
            val direction = when { down.position.x <= edge -> -1; down.position.x >= size.width - edge -> 1; else -> 0 }
            if (direction == 0) return@awaitEachGesture
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
                    if (!active && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    if (!active && -delta.x * direction > viewConfiguration.touchSlop) active = true
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
