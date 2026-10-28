package com.eyue.reader.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Timers belong to the visible reader; speed preferences never start reading by themselves. */
@Composable
internal fun ReaderAutoAdvance(
    running: Boolean, mode: ReadingMode, pageSeconds: Float, scrollSpeed: Float,
    pixelsPerDp: Float, sourceEbook: Boolean, controller: EbookReaderController,
    listState: LazyListState, onNext: () -> Unit
) {
    val next by rememberUpdatedState(onNext)
    DisposableEffect(controller, running, mode, scrollSpeed, sourceEbook) {
        controller.autoScroll(if (running && sourceEbook && mode == ReadingMode.SCROLL) scrollSpeed else 0f)
        onDispose { controller.autoScroll(0f) }
    }
    LaunchedEffect(running, mode, pageSeconds, scrollSpeed, pixelsPerDp, sourceEbook, listState) {
        if (!running) return@LaunchedEffect
        if (mode != ReadingMode.SCROLL) {
            while (isActive) { delay((pageSeconds.coerceIn(2f, 120f) * 1000).toLong()); next() }
        } else if (!sourceEbook) {
            listState.scroll {
                var previous = withFrameNanos { it }
                while (coroutineContext.isActive) {
                    val now = withFrameNanos { it }
                    // A stalled frame must not turn into a large jump on the next frame.
                    val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                    previous = now
                    scrollBy(scrollSpeed.coerceIn(8f, 160f) * pixelsPerDp * seconds)
                }
            }
        }
    }
}
