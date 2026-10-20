package com.eyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ReaderCompactAction(val id: String, val label: String,
    val selected: Boolean? = null, val enabled: Boolean = true, val onClick: () -> Unit)

internal fun readerVisibleMenuCount(widths: List<Int>, availableWidth: Int, overflowWidth: Int,
    spacing: Int = 0): Int {
    if (widths.sum() + spacing * (widths.size - 1).coerceAtLeast(0) <= availableWidth) return widths.size
    var used = overflowWidth
    var count = 0
    for (width in widths) {
        if (used + width + spacing > availableWidth) break
        used += width + spacing
        count++
    }
    return count
}

/** Measure the rendered labels at the current font scale before reserving the overflow control. */
@Composable
internal fun ReaderCompactActionMenu(actions: List<ReaderCompactAction>, modifier: Modifier = Modifier,
    moreTag: String = "reader_selection_more") {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val menuWidth = maxWidth
        val widths = actions.map { action ->
            measurer.measure(AnnotatedString(action.label), labelStyle, maxLines = 1, softWrap = false).size.width +
                with(density) { 16.dp.roundToPx() }
        }.map { it.coerceAtLeast(with(density) { 32.dp.roundToPx() }) }
        val moreWidth = with(density) { 32.dp.roundToPx() }
        val available = constraints.maxWidth
        val visibleCount = readerVisibleMenuCount(widths, available, moreWidth)
        val rowWidth = (widths.take(visibleCount).sum() + if (visibleCount < actions.size) moreWidth else 0)
            .coerceAtMost(available)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            Row(Modifier.width(with(density) { rowWidth.toDp() }), verticalAlignment = Alignment.CenterVertically) {
                actions.take(visibleCount).forEachIndexed { index, action ->
                    val selection = action.selected?.let { value -> Modifier.semantics { selected = value } } ?: Modifier
                    TextButton(onClick = action.onClick, enabled = action.enabled,
                        modifier = Modifier.width(with(density) { widths[index].toDp() }).heightIn(min = 32.dp)
                            .then(selection).testTag("reader_action_${action.id}")
                            .background(if (action.selected == true) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0f), RoundedCornerShape(8.dp)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(action.label, style = labelStyle, maxLines = 1, softWrap = false)
                    }
                }
                if (visibleCount < actions.size) Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier
                        .width(with(density) { moreWidth.coerceAtMost(available).coerceAtLeast(1).toDp() })
                        .heightIn(min = 32.dp).testTag(moreTag)) {
                        Icon(Icons.Outlined.MoreVert, "更多操作", Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(max = menuWidth)) {
                        actions.drop(visibleCount).forEach { action ->
                            val selection = action.selected?.let { value -> Modifier.semantics { selected = value } } ?: Modifier
                            DropdownMenuItem(text = { Text(action.label, style = labelStyle, maxLines = 1,
                                overflow = TextOverflow.Ellipsis) }, onClick = { expanded = false; action.onClick() },
                                modifier = selection.testTag("reader_action_${action.id}"), enabled = action.enabled)
                        }
                    }
                }
            }
        }
    }
}
