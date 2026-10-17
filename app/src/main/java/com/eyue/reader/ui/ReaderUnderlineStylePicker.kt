package com.eyue.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.UnderlineStyle

@Composable
internal fun ReaderUnderlineStylePicker(selected: UnderlineStyle, onSelect: (UnderlineStyle) -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("reader_underline_styles")) {
            UnderlineStyle.entries.forEach { style ->
                val active = selected == style
                val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                OutlinedButton(onClick = { onSelect(style) }, modifier = Modifier.size(width = 48.dp, height = 36.dp)
                    .testTag(if (style == UnderlineStyle.STRAIGHT) "reader_action_straight" else "reader_action_wavy")
                    .semantics { contentDescription = if (style == UnderlineStyle.STRAIGHT) "直线" else "波浪线"; this.selected = active },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    border = BorderStroke(if (active) 2.dp else 1.dp, if (active) color else MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface)) {
                    Canvas(Modifier.size(width = 28.dp, height = 12.dp)
                        .testTag("reader_underline_preview_${style.name}")) {
                        val y = size.height / 2f
                        val width = 1.7.dp.toPx()
                        if (style == UnderlineStyle.STRAIGHT) drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = width)
                        else {
                            val path = Path().apply {
                                moveTo(0f, y)
                                readerUnderlineCurves(0f, size.width, y, 1.8.dp.toPx(), 3.5.dp.toPx()).forEach {
                                    quadraticBezierTo(it.control.x, it.control.y, it.end.x, it.end.y)
                                }
                            }
                            drawPath(path, color, style = Stroke(width = width))
                        }
                    }
                }
            }
        }
    }
}
