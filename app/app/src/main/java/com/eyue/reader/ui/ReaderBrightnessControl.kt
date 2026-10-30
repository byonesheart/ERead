package com.eyue.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A small, isolated control; dragging previews locally and changes window brightness on release. */
@Composable internal fun ReaderBrightnessControl(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var draft by remember(value) { mutableFloatStateOf(if (value < 0f) .5f else value) }
    val latestValue by rememberUpdatedState(if (value < 0f) .5f else value)
    val latestChange by rememberUpdatedState(onChange)
    val colors = MaterialTheme.colorScheme
    Surface(modifier.heightIn(max = 248.dp).testTag("reader_brightness"), shape = appShape(14.dp), color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface, shadowElevation = 3.dp) {
        BoxWithConstraints {
        val buttonSize = if (maxHeight < 210.dp) 24.dp else 36.dp
        val trackHeight = (maxHeight - buttonSize * 3 - 22.dp).coerceIn(12.dp, 120.dp)
        Column(Modifier.width(42.dp).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { onChange((latestValue + .05f).coerceAtMost(1f)) }, Modifier.size(buttonSize)) { Text("+") }
            Canvas(Modifier.width(36.dp).height(trackHeight).progressSemantics(draft, .01f..1f).semantics {
                contentDescription = "阅读亮度"
                setProgress { latestChange(it.coerceIn(.01f, 1f)); true }
            }.pointerInput(Unit) {
                detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                    change.consume(); draft = (draft - amount / (size.height - 20.dp.toPx()).coerceAtLeast(1f)).coerceIn(.01f, 1f)
                }, onDragEnd = { latestChange(draft) }, onDragCancel = { draft = latestValue })
            }) {
                val inset = 10.dp.toPx()
                val y = size.height - inset - (size.height - inset * 2) * draft
                drawLine(colors.outlineVariant, Offset(center.x, inset), Offset(center.x, size.height - inset), 3.dp.toPx())
                drawLine(colors.primary, Offset(center.x, y), Offset(center.x, size.height - inset), 3.dp.toPx())
                drawCircle(colors.primary, 6.dp.toPx(), Offset(center.x, y))
            }
            IconButton(onClick = { onChange((latestValue - .05f).coerceAtLeast(.01f)) }, Modifier.size(buttonSize)) { Text("−") }
            Text(if (value < 0) "自动" else "${(draft * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { onChange(-1f) }, Modifier.size(buttonSize)) {
                AppIcon(Icons.Outlined.BrightnessAuto, "亮度跟随系统", Modifier.size(18.dp))
            }
        }
        }
    }
}
