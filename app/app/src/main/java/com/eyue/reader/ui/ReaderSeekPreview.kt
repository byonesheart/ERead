package com.eyue.reader.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.eyue.reader.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

internal data class ReaderSeekPage(val location: ReaderScrollLocation, val page: Int, val count: Int, val failed: Boolean = false)

internal fun readerRasterSeekLocation(counts: List<Int>, fraction: Float): ReaderPageLocation? {
    val total = counts.sum()
    if (total <= 0) return null
    var remaining = (fraction.coerceIn(0f, 1f) * (total - 1)).roundToInt()
    counts.forEachIndexed { chapter, count ->
        if (remaining < count) return ReaderPageLocation(chapter, remaining)
        remaining -= count
    }
    return null
}

/** One cancellable, offscreen target chapter. It never navigates the visible reader or retains a full-book page index. */
@Composable
internal fun EbookSeekPreview(store: EbookSourceStore, chapters: List<Chapter>, target: ReaderScrollLocation,
    readText: suspend (Int) -> String, settings: ReaderSettings, foreground: Color, dualPage: Boolean,
    eink: Boolean, converter: ChineseTextConverter?, modifier: Modifier = Modifier, onPage: (ReaderSeekPage?) -> Unit) {
    var prepared by remember(store, target.chapter) { mutableStateOf<Pair<Chapter, String>?>(null) }
    val controller = remember(store, target.chapter) { EbookReaderController() }
    val latestTarget by rememberUpdatedState(target)
    val latestOnPage by rememberUpdatedState(onPage)
    LaunchedEffect(store, target.chapter) {
        delay(120)
        try {
            val text = readText(target.chapter)
            val chapter = withContext(Dispatchers.IO) {
                val work = currentCoroutineContext()
                DeferredEbookContent.ensure(chapters[target.chapter]) { work.ensureActive() }
            }
            prepared = chapter to text
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestOnPage(ReaderSeekPage(latestTarget, 0, 0, failed = true)) }
    }
    val image = target.imageIndex?.let { chapters[target.chapter].inlineImages.getOrNull(it)?.path }
    LaunchedEffect(controller, target.offset, image) { controller.navigate(target.offset, image, expectedChapterIndex = target.chapter) }
    prepared?.let { (chapter, canonical) -> key(store, target.chapter) {
        EbookReaderSurface(store, target.chapter, chapter, canonical, settings.copy(continuousEbookScroll = false),
            emptyList(), controller, modifier.alpha(0f).zIndex(-2f).clearAndSetSemantics {}, dualPage, eink,
            foreground, chineseConverter = converter, contentReady = true, initialOffset = target.offset,
            initialImagePath = image, preload = true, selectionMenuEnabled = false,
            onViewport = { state ->
                val location = latestTarget
                val path = location.imageIndex?.let { chapters[location.chapter].inlineImages.getOrNull(it)?.path }
                if (state.error != null) latestOnPage(ReaderSeekPage(location, 0, 0, failed = true))
                if (state.ready && (path != null && path in state.visibleImagePaths || path == null && location.offset in state.offset..state.endOffset))
                    latestOnPage(ReaderSeekPage(location, readerVisiblePageEnd(state.pageIndex, state.pageCount, dualPage), state.pageCount.coerceAtLeast(1)))
            }, onTap = {}, onManualStart = {}, onManualEnd = {}, onChapterStep = {}, onSelectionActive = {},
            onAction = { _, _ -> }, onAnnotationTap = { _, _ -> }, onFootnote = { _, _ -> })
    } }
}
