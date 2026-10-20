package com.eyue.reader.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.UnderlineStyle

internal const val ReaderUnderlineAnnotationTag = "reader-underline"

/** The tag follows the text's UTF-16 ranges through the paragraph visual transformation. */
internal fun AnnotatedString.Builder.addReaderUnderline(start: Int, end: Int, style: UnderlineStyle,
    color: Color = Color.Unspecified) {
    if (end <= start) return
    addStringAnnotation(ReaderUnderlineAnnotationTag,
        "${style.name}|${if (color == Color.Unspecified) "" else color.toArgb()}", start, end)
}

internal data class ReaderUnderlineSegment(val left: Float, val right: Float, val y: Float, val line: Int)
internal data class ReaderUnderlineCurve(val control: Offset, val end: Offset)

/** Glyph boxes handle wrapped lines, indented paragraphs, surrogate pairs and bidi runs. */
internal fun readerUnderlineSegments(layout: TextLayoutResult, start: Int, end: Int): List<ReaderUnderlineSegment> {
    val text = layout.layoutInput.text.text
    val first = start.coerceIn(0, text.length)
    val last = end.coerceIn(first, text.length)
    if (first == last) return emptyList()
    val segments = mutableListOf<ReaderUnderlineSegment>()
    for (line in layout.getLineForOffset(first)..layout.getLineForOffset(last - 1)) {
        val lineStart = maxOf(first, layout.getLineStart(line))
        val lineEnd = minOf(last, layout.getLineEnd(line, visibleEnd = true))
        val boxes = (lineStart until lineEnd).asSequence()
            .filter { text[it] != '\n' && text[it] != '\r' && text[it] != '\u200B' }
            .map(layout::getBoundingBox).filter { it.width > 0f }.sortedBy { it.left }.toList()
        if (boxes.isEmpty()) continue
        val baseline = layout.getLineBaseline(line)
        val y = minOf(layout.getLineBottom(line) - 2.5f,
            baseline + maxOf(1f, (layout.getLineBottom(line) - baseline) * .35f))
        var left = boxes.first().left
        var right = boxes.first().right
        for (box in boxes.drop(1)) {
            if (box.left <= right + .5f) right = maxOf(right, box.right)
            else {
                segments += ReaderUnderlineSegment(left, right, y, line)
                left = box.left; right = box.right
            }
        }
        segments += ReaderUnderlineSegment(left, right, y, line)
    }
    return segments
}

/** Alternating quadratic arcs form a continuous wave rather than a zigzag. */
internal fun readerUnderlineCurves(left: Float, right: Float, y: Float, amplitude: Float,
    halfWave: Float): List<ReaderUnderlineCurve> {
    if (right <= left || halfWave <= 0f || amplitude <= 0f) return emptyList()
    val curves = mutableListOf<ReaderUnderlineCurve>()
    var x = left
    var direction = 1f
    while (x < right) {
        val end = minOf(right, x + halfWave)
        if (end <= x) break
        val height = amplitude * 2f * ((end - x) / halfWave).coerceAtMost(1f)
        curves += ReaderUnderlineCurve(Offset((x + end) / 2f, y + height * direction), Offset(end, y))
        direction = -direction
        x = end
    }
    return curves
}

internal fun Modifier.readerAnnotationUnderlines(defaultColor: Color,
    layout: () -> TextLayoutResult?): Modifier = drawWithContent {
    drawContent()
    val textLayout = layout() ?: return@drawWithContent
    val text = textLayout.layoutInput.text
    val width = 1.3.dp.toPx()
    text.getStringAnnotations(ReaderUnderlineAnnotationTag, 0, text.length).forEach { annotation ->
        val fields = annotation.item.split('|', limit = 2)
        val style = runCatching { UnderlineStyle.valueOf(fields.first()) }.getOrNull() ?: return@forEach
        val color = fields.getOrNull(1)?.toIntOrNull()?.let(::Color)
            ?: defaultColor.takeUnless { it == Color.Unspecified } ?: Color.Black
        readerUnderlineSegments(textLayout, annotation.start, annotation.end).forEach { segment ->
            if (style == UnderlineStyle.STRAIGHT) {
                drawLine(color, Offset(segment.left, segment.y), Offset(segment.right, segment.y), strokeWidth = width)
            } else {
                val path = Path().apply {
                    moveTo(segment.left, segment.y)
                    readerUnderlineCurves(segment.left, segment.right, segment.y, 1.1.dp.toPx(), 3.dp.toPx()).forEach {
                        quadraticBezierTo(it.control.x, it.control.y, it.end.x, it.end.y)
                    }
                }
                drawPath(path, color, style = Stroke(width = width))
            }
        }
    }
}
