package com.eyue.reader.ui

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class ReaderBubblePlacement(val offset: IntOffset, val above: Boolean, val tailX: Float)

/** All rectangles use window pixels; visibleBounds already excludes system bars and the keyboard. */
internal fun readerAnnotationBubblePlacement(anchor: Rect, visibleBounds: IntRect, popupSize: IntSize,
    margin: Int = 8, tail: Int = 10): ReaderBubblePlacement {
    val left = visibleBounds.left + margin
    val right = (visibleBounds.right - margin - popupSize.width).coerceAtLeast(left)
    val top = visibleBounds.top + margin
    val bottom = (visibleBounds.bottom - margin - popupSize.height).coerceAtLeast(top)
    val above = anchor.top - popupSize.height >= top
    val x = (anchor.center.x - popupSize.width / 2f).roundToInt().coerceIn(left, right)
    val y = (if (above) anchor.top - popupSize.height else anchor.bottom).roundToInt().coerceIn(top, bottom)
    val tailMargin = (tail * 2).coerceAtMost(popupSize.width / 2).toFloat()
    return ReaderBubblePlacement(IntOffset(x, y), above,
        (anchor.center.x - x).coerceIn(tailMargin, (popupSize.width - tailMargin).coerceAtLeast(tailMargin)))
}

@Composable
internal fun ReaderAnnotationPopup(anchor: Rect, onDismiss: () -> Unit, focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val window = LocalWindowInfo.current.containerSize
    // Reading the IME inset invalidates the visible display frame when the editor keyboard changes.
    val ime = WindowInsets.ime.getBottom(density)
    val visible = remember(view, window, ime) {
        val frame = AndroidRect()
        view.getWindowVisibleDisplayFrame(frame)
        val screen = IntArray(2); val inWindow = IntArray(2)
        view.getLocationOnScreen(screen); view.getLocationInWindow(inWindow)
        val originX = screen[0] - inWindow[0]; val originY = screen[1] - inWindow[1]
        if (frame.width() > 0 && frame.height() > 0) IntRect(frame.left - originX, frame.top - originY,
            frame.right - originX, frame.bottom - originY) else IntRect(0, 0, window.width, window.height)
    }
    val margin = with(density) { 8.dp.roundToPx() }
    val tail = with(density) { 10.dp.roundToPx() }
    var placement by remember(anchor) { mutableStateOf(ReaderBubblePlacement(IntOffset.Zero, true, 20f)) }
    val provider = remember(anchor, visible, margin, tail) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val bounds = IntRect(visible.left.coerceIn(0, windowSize.width), visible.top.coerceIn(0, windowSize.height),
                    visible.right.coerceIn(0, windowSize.width), visible.bottom.coerceIn(0, windowSize.height))
                return readerAnnotationBubblePlacement(anchor, bounds, popupContentSize, margin, tail).also { placement = it }.offset
            }
        }
    }
    val color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val availableWidth = with(density) { (visible.width - margin * 2).coerceAtLeast(1).toDp() }
    val visibleTop = visible.top + margin
    val visibleBottom = (visible.bottom - margin).coerceAtLeast(visibleTop)
    val aboveSpace = anchor.top.coerceIn(visibleTop.toFloat(), visibleBottom.toFloat()) - visibleTop
    val belowSpace = visibleBottom - anchor.bottom.coerceIn(visibleTop.toFloat(), visibleBottom.toFloat())
    val availableHeight = with(density) { (maxOf(aboveSpace, belowSpace).toInt() - tail).coerceAtLeast(1).toDp() }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable, dismissOnClickOutside = true)) {
        Box(Modifier.widthIn(max = minOf(360.dp, availableWidth)).testTag("reader_annotation_bubble")) {
            // The tail follows the measured body; it must not expand a short menu to the maximum width.
            Canvas(Modifier.matchParentSize()) {
            val middle = placement.tailX.coerceIn(0f, size.width)
            val half = with(density) { 9.dp.toPx() }
            val height = tail.toFloat()
            val triangle = Path().apply {
                if (placement.above) {
                    moveTo(middle - half, size.height - height); lineTo(middle + half, size.height - height); lineTo(middle, size.height)
                } else { moveTo(middle, 0f); lineTo(middle + half, height); lineTo(middle - half, height) }
                close()
            }
            drawPath(triangle, color)
            }
            Surface(modifier = Modifier.padding(top = if (placement.above) 0.dp else 10.dp,
                bottom = if (placement.above) 10.dp else 0.dp), color = color, contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}
