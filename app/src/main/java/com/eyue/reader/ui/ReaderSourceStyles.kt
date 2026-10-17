package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.SourceTextAlignment
import com.eyue.reader.data.SourceTextStyle
import java.util.TreeMap

private data class ReaderStyleValues(
    val scale: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null,
    val alignment: SourceTextAlignment? = null, val firstIndent: Float? = null, val restIndent: Float? = null,
    val paragraphScale: Float? = null
) {
    fun with(source: SourceTextStyle) = copy(scale = source.fontScale ?: scale, bold = source.bold ?: bold,
        italic = source.italic ?: italic,
        alignment = if (source.paragraph) source.alignment ?: alignment else alignment,
        firstIndent = if (source.paragraph) source.firstLineIndentEm ?: firstIndent else firstIndent,
        restIndent = if (source.paragraph) source.restLineIndentEm ?: restIndent else restIndent,
        paragraphScale = if (source.paragraph) source.fontScale ?: paragraphScale else paragraphScale)
}

private data class ReaderStyleRun(val start: Int, val end: Int, val values: ReaderStyleValues)
private data class ReaderParagraphRun(val start: Int, val end: Int, val values: ReaderStyleValues, val lineScale: Float)
private data class ReaderStyleEdge(val offset: Int, val order: Int, val enters: Boolean)

/** Canonical nonoverlapping runs retain source cascade order without scanning every source range per page. */
internal class ReaderSourceStyles(text: String, sources: List<SourceTextStyle>, checkActive: () -> Unit = {}) {
    private val spans: List<ReaderStyleRun>
    private val paragraphs: List<ReaderParagraphRun>
    val maximumScale: Float = sources.mapNotNull { it.fontScale }.maxOrNull()?.coerceAtLeast(1f) ?: 1f

    init {
        val edges = ArrayList<ReaderStyleEdge>(sources.size * 2)
        sources.forEachIndexed { index, style ->
            val start = style.start.coerceIn(0, text.length)
            val end = style.end.coerceIn(start, text.length)
            if (end > start) { edges += ReaderStyleEdge(start, index, true); edges += ReaderStyleEdge(end, index, false) }
        }
        edges.sortWith(compareBy<ReaderStyleEdge> { it.offset }.thenBy { it.enters })
        val active = TreeMap<Int, SourceTextStyle>()
        val runs = ArrayList<ReaderStyleRun>()
        var edge = 0
        while (edge < edges.size) {
            if (edge % 256 == 0) checkActive()
            val start = edges[edge].offset
            while (edge < edges.size && edges[edge].offset == start) {
                val event = edges[edge++]
                if (event.enters) active[event.order] = sources[event.order] else active.remove(event.order)
            }
            val end = edges.getOrNull(edge)?.offset ?: text.length
            if (end > start && active.isNotEmpty()) {
                val values = active.values.fold(ReaderStyleValues()) { result, source -> result.with(source) }
                val previous = runs.lastOrNull()
                if (previous?.end == start && previous.values == values) runs[runs.lastIndex] = previous.copy(end = end)
                else runs += ReaderStyleRun(start, end, values)
            }
        }
        spans = runs
        val paragraphRuns = ArrayList<ReaderParagraphRun>()
        if (sources.any { it.paragraph || it.fontScale != null }) {
            var start = 0
            var runIndex = 0
            while (start < text.length) {
                if (paragraphRuns.size % 256 == 0) checkActive()
                val newline = text.indexOf('\n', start)
                val end = if (newline < 0) text.length else newline + 1
                val visibleEnd = if (newline < 0) end else newline
                while (runIndex < runs.size && runs[runIndex].end <= start) runIndex++
                var index = runIndex
                var cursor = start
                var scale = 0f
                var paragraph = ReaderStyleValues()
                while (index < runs.size && runs[index].start < visibleEnd) {
                    val run = runs[index++]
                    if (run.start > cursor) scale = maxOf(scale, 1f)
                    scale = maxOf(scale, run.values.scale ?: 1f)
                    cursor = maxOf(cursor, minOf(run.end, visibleEnd))
                    if (run.values.alignment != null || run.values.firstIndent != null || run.values.restIndent != null || run.values.paragraphScale != null)
                        paragraph = run.values
                }
                if (cursor < visibleEnd || scale == 0f) scale = maxOf(scale, 1f)
                if (scale != 1f || paragraph.alignment != null || paragraph.firstIndent != null || paragraph.restIndent != null)
                    paragraphRuns += ReaderParagraphRun(start, end, paragraph, scale)
                start = end
            }
        }
        paragraphs = paragraphRuns
    }

    fun apply(text: String, start: Int, end: Int, base: TextStyle, trimLastNewline: Boolean = true): AnnotatedString {
        val raw = text.substring(start, end)
        val visible = if (trimLastNewline) raw.removeSuffix("\n") else raw
        val visibleEnd = start + visible.length
        val firstSpan = firstOverlapping(spans.size, { spans[it].end }, start)
        val firstParagraph = firstOverlapping(paragraphs.size, { paragraphs[it].end }, start)
        if ((firstSpan == spans.size || spans[firstSpan].start >= visibleEnd) &&
            (firstParagraph == paragraphs.size || paragraphs[firstParagraph].start >= visibleEnd)) return AnnotatedString(visible)
        val result = AnnotatedString.Builder(visible)
        var index = firstSpan
        while (index < spans.size && spans[index].start < visibleEnd) {
            val run = spans[index++]
            val values = run.values
            if (values.scale == null && values.bold == null && values.italic == null) continue
            val span = SpanStyle(fontSize = values.scale?.let { (base.fontSize.value * it).sp } ?: TextUnit.Unspecified,
                fontWeight = values.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
                fontStyle = values.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal })
            result.addStyle(span, maxOf(run.start, start) - start, minOf(run.end, visibleEnd) - start)
        }
        index = firstParagraph
        while (index < paragraphs.size && paragraphs[index].start < visibleEnd) {
            val run = paragraphs[index++]
            val values = run.values
            // Import normalizes indentation to the ordinary body font, independently of source fontScale.
            val size = base.fontSize.value
            val rest = values.restIndent?.let { (it * size).sp } ?: base.textIndent?.restLine ?: 0.sp
            val first = if (start > run.start) rest else values.firstIndent?.let { (it * size).sp } ?: base.textIndent?.firstLine ?: 0.sp
            result.addStyle(ParagraphStyle(textAlign = when (values.alignment) {
                SourceTextAlignment.START -> TextAlign.Start; SourceTextAlignment.CENTER -> TextAlign.Center
                SourceTextAlignment.END -> TextAlign.End; SourceTextAlignment.JUSTIFY -> TextAlign.Justify
                null -> TextAlign.Unspecified
            }, textIndent = if (values.firstIndent != null || values.restIndent != null) TextIndent(first, rest) else null,
                lineHeight = if (run.lineScale == 1f) TextUnit.Unspecified else (base.lineHeight.value * run.lineScale).sp),
                maxOf(run.start, start) - start, minOf(run.end, visibleEnd) - start)
        }
        return result.toAnnotatedString()
    }
}

private inline fun firstOverlapping(size: Int, end: (Int) -> Int, start: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (end(middle) <= start) low = middle + 1 else high = middle
    }
    return low
}
