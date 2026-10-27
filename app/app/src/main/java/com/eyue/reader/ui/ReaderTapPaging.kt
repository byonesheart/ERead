package com.eyue.reader.ui

import com.eyue.reader.data.TapPagingMode

/** The middle third keeps the toolbar accessible in either click layout. */
internal fun readerTapPageDirection(mode: TapPagingMode, x: Float, y: Float): Int {
    val position = when (mode) {
        TapPagingMode.OFF -> return 0
        TapPagingMode.HORIZONTAL -> x
        TapPagingMode.VERTICAL -> y
    }
    if (!position.isFinite()) return 0
    return when {
        position < 1f / 3f -> -1
        position > 2f / 3f -> 1
        else -> 0
    }
}
