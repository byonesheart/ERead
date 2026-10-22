package com.eyue.reader.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.loadChapterContent
import com.eyue.reader.data.ChineseTextConverter
import com.eyue.reader.data.readerSourceStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val ReaderBodyTopPadding = 26.dp
internal val ReaderBodyBottomPadding = 32.dp
internal val ReaderTextHorizontalPadding = 24.dp
internal val ReaderTextVerticalPadding = 18.dp

/** Physical page margins stay the same when the text direction changes. */
internal fun readerContentPadding(settings: ReaderSettings): PaddingValues {
    fun margin(value: Float, fallback: Float) = (if (value.isFinite()) value.coerceIn(0f, 64f) else fallback).dp
    return PaddingValues.Absolute(left = margin(settings.marginLeft, 24f), right = margin(settings.marginRight, 24f),
        top = margin(settings.marginTop, 18f), bottom = margin(settings.marginBottom, 18f))
}

/** Input is the measured safe-drawing area, before the reader's permanent status/progress gutters. */
internal fun readerBodyViewport(density: Density, safeDrawingViewport: IntSize): IntSize = with(density) {
    IntSize(safeDrawingViewport.width.coerceAtLeast(0),
        (safeDrawingViewport.height - ReaderBodyTopPadding.roundToPx() - ReaderBodyBottomPadding.roundToPx()).coerceAtLeast(0))
}

internal data class ReaderLayoutSpec(
    val textStyle: TextStyle,
    val textWidth: Int,
    val textHeight: Int,
    val density: Density,
    val layoutDirection: LayoutDirection
)

/** Body viewport is the same measured size as ReaderScreen's reader_body, after its outer gutters. */
internal fun readerLayoutSpec(settings: ReaderSettings, density: Density, direction: LayoutDirection,
                              bodyViewport: IntSize, context: Context): ReaderLayoutSpec {
    val family = readerFontFamily(settings)
    val calibration = readerFontCalibration(context, settings)
    val workerDensity = Density(density.density, density.fontScale)
    val padding = readerContentPadding(settings)
    return ReaderLayoutSpec(
        textStyle = TextStyle(fontFamily = family, fontSize = (settings.fontSize * calibration.sizeScale).sp,
            lineHeight = (settings.fontSize * settings.lineHeight).sp,
            letterSpacing = (settings.letterSpacing + settings.fontSize * calibration.trackingEm).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            fontWeight = if (settings.bold) FontWeight.Bold else FontWeight.Normal),
        textWidth = (bodyViewport.width - with(workerDensity) {
            padding.calculateLeftPadding(direction).roundToPx() + padding.calculateRightPadding(direction).roundToPx()
        }).coerceAtLeast(1),
        textHeight = (bodyViewport.height - with(workerDensity) {
            padding.calculateTopPadding().roundToPx() + padding.calculateBottomPadding().roundToPx()
        }).coerceAtLeast(1),
        density = workerDensity, layoutDirection = direction)
}

/** Prepares the resume chapter only, with the same typography and cache used while reading. */
internal suspend fun prepaginateBook(context: Context, book: Book, settings: ReaderSettings, spec: ReaderLayoutSpec,
                                    onProgress: suspend (Float) -> Unit = {}) {
    if (book.mode != LibraryMode.NOVEL || book.format !in setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI)) return
    if (book.chapters.isEmpty()) return
    onProgress(0f)
    val chapterIndex = book.chapterIndex.coerceIn(book.chapters.indices)
    val prepared = loadChapterContent(context, book, chapterIndex)
    val chineseConverter = ChineseTextConverter.load(context, settings.chineseConversion)
    withContext(Dispatchers.Default) {
        val worker = currentCoroutineContext()
        val style = ReaderSourceStyles(prepared.text, prepared.chapter.readerSourceStyles(settings.useSourceCss)) { worker.ensureActive() }
        readerCachedChapterPages(context, book, chapterIndex, settings, spec.density, spec.layoutDirection,
            spec.textWidth, spec.textHeight, prepared.text, prepared.chapter) {
            readerPaginate(context, listOf(prepared.text), spec.textStyle, spec.density, spec.layoutDirection,
                spec.textWidth, spec.textHeight, inlineImages = listOf(prepared.chapter.inlineImages),
                sourceStyles = listOf(style), paragraphSpacing = settings.paragraphSpacing, chineseConverter = chineseConverter, onProgress = onProgress).single()
        }
        worker.ensureActive()
    }
    // Cache hits do not invoke the pagination lambda, but must still finish the visible preparation stage.
    onProgress(1f)
}
