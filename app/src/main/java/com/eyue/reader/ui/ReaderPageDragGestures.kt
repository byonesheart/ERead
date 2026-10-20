package com.eyue.reader.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Keep normal touch-slop/selection arbitration, but turn as soon as an intentional drag crosses its threshold. */
internal suspend fun PointerInputScope.readerPageDragGestures(vertical: Boolean, onStart: () -> Unit,
    onTurn: (Int) -> Unit, onFinish: () -> Unit) {
    val threshold = ((if (vertical) size.height else size.width) * .1f).coerceIn(24.dp.toPx(), 48.dp.toPx())
    var distance = 0f
    var turned = false
    fun start() { distance = 0f; turned = false; onStart() }
    fun drag(change: PointerInputChange, amount: Float) {
        change.consume()
        if (turned) return
        distance += amount
        if (abs(distance) >= threshold) {
            turned = true
            onTurn(if (distance < 0f) 1 else -1)
        }
    }
    if (vertical) detectVerticalDragGestures(onDragStart = { start() }, onVerticalDrag = ::drag,
        onDragEnd = onFinish, onDragCancel = onFinish)
    else detectHorizontalDragGestures(onDragStart = { start() }, onHorizontalDrag = ::drag,
        onDragEnd = onFinish, onDragCancel = onFinish)
}