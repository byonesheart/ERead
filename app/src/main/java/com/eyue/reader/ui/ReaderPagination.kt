package com.eyue.reader.ui

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.ChineseTextConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ReaderPaginationMeasurements {
    var chunks: Int = 0
    var pages: Int = 0
}

/** A measurer belongs to this job only; the reader's UI measurer is never shared across threads. */
internal suspend fun readerPaginate(
    context: Context,
    chapters: List<String>,
    style: TextStyle,
    density: Density,
    direction: LayoutDirection,
    width: Int,
    height: Int,
    inlineImages: List<List<InlineImage>> = emptyList(),
    sourceStyles: List<ReaderSourceStyles> = emptyList(),
    measurements: ReaderPaginationMeasurements? = null,
    paragraphSpacing: Float = ReaderParagraphGapEm,
    chineseConverter: ChineseTextConverter? = null,
    onProgress: suspend (Float) -> Unit = {}
): List<List<ReaderTextPage>> = withContext(Dispatchers.Default) {
    val measurer = TextMeasurer(createFontFamilyResolver(context.applicationContext), density, direction, cacheSize = 0)
    val constraints = Constraints(maxWidth = width, maxHeight = 16_384)
    val lineHeight = with(density) { style.lineHeight.toPx() }.coerceAtLeast(1f)
    val fontSize = with(density) { style.fontSize.toPx() }.coerceAtLeast(1f)
    val total = chapters.sumOf { it.length.toLong() }.coerceAtLeast(1L)
    var completed = 0L
    var reportedAt = 0L
    val result = ArrayList<List<ReaderTextPage>>(chapters.size)

    for ((chapterIndex, text) in chapters.withIndex()) {
        val chapterStyles = sourceStyles.getOrNull(chapterIndex)
        val chunkLines = (16_000 / (lineHeight * (chapterStyles?.maximumScale ?: 1f))).toInt().coerceIn(2, 128)
        val chunkSize = ((width / fontSize).coerceAtLeast(1f) * chunkLines).toInt().coerceIn(256, 6_000)
        val pages = ArrayList<ReaderTextPage>()
        var offset = 0
        fun consumeParagraphBreak(end: Int, limit: Int): Int =
            if (end < limit && readerParagraphGapAfter(text, end + 1)) end + 1 else end
        suspend fun paginateUntil(segmentEnd: Int) {
            while (offset < segmentEnd) {
                currentCoroutineContext().ensureActive()
                var chunkEnd = minOf(segmentEnd, offset + chunkSize)
                if (chunkEnd < segmentEnd && Character.isHighSurrogate(text[chunkEnd - 1])) chunkEnd--
                val chunkStart = offset
                measurements?.let { it.chunks++ }
                val chunkDisplay = readerParagraphText(chapterStyles?.apply(text, chunkStart, chunkEnd, style, trimLastNewline = false)
                    ?: AnnotatedString(text.substring(chunkStart, chunkEnd)), style, paragraphSpacing, chineseConverter, text, chunkStart)
                val layout = measurer.measure(chunkDisplay.text, style = style,
                    constraints = constraints, maxLines = chunkLines)
                val complete = chunkEnd == segmentEnd && !layout.didOverflowHeight
                // An artificial final line may rewrap once the following text is available.
                val usableLines = if (complete) layout.lineCount else (layout.lineCount - 1).coerceAtLeast(1)
                var firstLine = 0
                while (firstLine < usableLines && offset < chunkEnd) {
                    currentCoroutineContext().ensureActive()
                    var lastLine = firstLine
                    val top = layout.getLineTop(firstLine)
                    while (lastLine + 1 < usableLines && layout.getLineBottom(lastLine + 1) - top <= height) lastLine++
                    // Carry only the unfinished page into the next chunk, rather than laying out 6,000 characters per page.
                    if (!complete && lastLine == usableLines - 1 && lastLine + 1 < layout.lineCount &&
                        layout.getLineBottom(lastLine + 1) - top <= height && offset > chunkStart) break
                    val proposedEnd = consumeParagraphBreak((chunkStart + chunkDisplay.offsetMapping.transformedToOriginal(layout.getLineEnd(lastLine, visibleEnd = false))).coerceIn(offset + 1, chunkEnd), chunkEnd)
                    // A page starts a fresh paragraph layout. Check its actual first-line metrics before committing it.
                    // An untrimmed whole chunk already has exactly the same text, styles and constraints as that page.
                    val reuseLayout = offset == chunkStart && proposedEnd == chunkEnd && !layout.didOverflowHeight && text[chunkEnd - 1] != '\n'
                    val pageDisplay = if (reuseLayout) chunkDisplay else readerParagraphText(chapterStyles?.apply(text, offset, proposedEnd, style)
                        ?: AnnotatedString(text.substring(offset, proposedEnd).removeSuffix("\n")), style, paragraphSpacing, chineseConverter, text, offset)
                    val pageLayout = if (reuseLayout) layout else {
                            measurements?.let { it.pages++ }
                            measurer.measure(pageDisplay.text, style = style,
                                constraints = constraints, maxLines = chunkLines)
                        }
                    var fittedLine = 0
                    while (fittedLine + 1 < pageLayout.lineCount && pageLayout.getLineBottom(fittedLine + 1) <= height) fittedLine++
                    val end = if (fittedLine == pageLayout.lineCount - 1 && !pageLayout.didOverflowHeight) proposedEnd
                        else consumeParagraphBreak((offset + pageDisplay.offsetMapping.transformedToOriginal(pageLayout.getLineEnd(fittedLine, visibleEnd = false)).coerceAtLeast(1)).coerceAtMost(proposedEnd), proposedEnd)
                    pages += ReaderTextPage(offset, end)
                    offset = end
                    if (offset >= chunkEnd) break
                    // maxLines can stop MultiParagraph before chunkEnd. An offset at the last
                    // laid-out paragraph's end is not a valid paragraph lookup in that truncated layout.
                    var nextLine = firstLine + 1
                    while (nextLine < usableLines &&
                        (chunkDisplay.offsetMapping.transformedToOriginal(layout.getLineStart(nextLine)) < offset - chunkStart ||
                            chunkDisplay.offsetMapping.transformedToOriginal(layout.getLineEnd(nextLine, visibleEnd = false)) <= offset - chunkStart)) nextLine++
                    // Restart at this precise offset when the visible layout ends or standalone wrapping differs.
                    if (nextLine >= usableLines || chunkDisplay.offsetMapping.transformedToOriginal(layout.getLineStart(nextLine)) != offset - chunkStart) break
                    firstLine = nextLine
                }
                check(offset > chunkStart) { "正文排版未能向前推进" }
                val now = System.nanoTime()
                if (now - reportedAt >= 100_000_000L) {
                    onProgress((completed + offset).toFloat() / total)
                    reportedAt = now
                }
            }
        }
        inlineImages.getOrNull(chapterIndex).orEmpty().withIndex().sortedBy { it.value.offset }.forEach { (imageIndex, image) ->
            val imageOffset = image.offset.coerceIn(offset, text.length)
            paginateUntil(imageOffset)
            pages += ReaderTextPage(imageOffset, imageOffset, imageIndex)
        }
        paginateUntil(text.length)
        result += pages.ifEmpty { listOf(ReaderTextPage(0, 0)) }
        completed += text.length
    }
    currentCoroutineContext().ensureActive()
    onProgress(1f)
    result
}
