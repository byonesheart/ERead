package com.eyue.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal fun readerSliderValue(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float =
    (range.start + ((value.coerceIn(range) - range.start) / step).roundToInt() * step).coerceIn(range)

/** A track tap does nothing. A deliberate horizontal drag previews locally and submits once on release. */
@Composable
internal fun ReaderAdjustableSlider(label: String, description: String, value: Float,
    range: ClosedFloatingPointRange<Float>, step: Float, onCommit: (Float) -> Unit,
    display: (Float) -> String, trailing: (@Composable () -> Unit)? = null) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    val latestValue by rememberUpdatedState(value.coerceIn(range))
    val latestCommit by rememberUpdatedState(onCommit)
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(34.dp), fontSize = 12.sp)
        IconButton(onClick = { onCommit(readerSliderValue(value - step, range, step)) },
            modifier = Modifier.size(40.dp).semantics { contentDescription = "$description 减小" }) { Text("−", fontSize = 20.sp) }
        Canvas(Modifier.weight(1f).height(44.dp)
            .progressSemantics(draft, range)
            .semantics {
                contentDescription = description
                setProgress { proposed -> latestCommit(readerSliderValue(proposed, range, step)); true }
            }.pointerInput(range, step) {
                var moved = false
                var unrounded = latestValue
                detectHorizontalDragGestures(onDragStart = { moved = true; unrounded = latestValue },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        val padding = 10.dp.toPx()
                        unrounded = (unrounded + amount / (size.width - 2 * padding).coerceAtLeast(1f) *
                            (range.endInclusive - range.start)).coerceIn(range)
                        draft = readerSliderValue(unrounded, range, step)
                    }, onDragEnd = {
                        if (moved && draft != latestValue) latestCommit(draft)
                        moved = false
                    }, onDragCancel = { draft = latestValue; moved = false })
            }) {
            val inset = 10.dp.toPx()
            val fraction = ((draft - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val thumb = inset + (size.width - inset * 2).coerceAtLeast(0f) * fraction
            drawLine(track, Offset(inset, center.y), Offset(size.width - inset, center.y), 3.dp.toPx())
            drawLine(color, Offset(inset, center.y), Offset(thumb, center.y), 3.dp.toPx())
            drawCircle(color, 6.dp.toPx(), Offset(thumb, center.y))
        }
        IconButton(onClick = { onCommit(readerSliderValue(value + step, range, step)) },
            modifier = Modifier.size(40.dp).semantics { contentDescription = "$description 增大" }) { Text("+", fontSize = 19.sp) }
        Text(display(draft), Modifier.width(if (trailing == null) 38.dp else 32.dp), fontSize = 11.sp, color = color)
        trailing?.invoke()
    }
}
