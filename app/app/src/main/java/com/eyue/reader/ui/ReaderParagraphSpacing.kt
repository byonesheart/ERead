package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.ChineseTextConverter

internal const val ReaderParagraphGapEm = 0.8f

/** A real paragraph break, excluding existing blank/whitespace-only lines. */
internal fun readerParagraphGapAfter(text: String, end: Int): Boolean {
    if (end !in 1 until text.length || text[end - 1] != '\n') return false
    var before = end - 2
    while (before >= 0 && text[before] != '\n' && text[before].isWhitespace()) before--
    if (before < 0 || text[before] == '\n') return false
    var after = end
    while (after < text.length && text[after] != '\n' && text[after].isWhitespace()) after++
    return after < text.length && text[after] != '\n'
}

/** Display-only paragraph gaps keep persisted text, selections and reading positions unchanged. */
internal fun readerParagraphText(source: AnnotatedString, style: TextStyle, gapEm: Float = ReaderParagraphGapEm,
                                 converter: ChineseTextConverter? = null, context: String? = null, sourceStart: Int = 0): TransformedText {
    val converted = readerChineseText(source, converter, context, sourceStart)
    val text = converted.text
    val positions = ArrayList<Int>()
    for (index in 1 until text.length - 1) {
        if (text[index] == '\n' && readerParagraphGapAfter(text.text, index + 1)) positions += index
    }
    if (positions.isEmpty() && style.textIndent == null) return converted
    val displayed = text.text.toCharArray()
    positions.forEach { displayed[it] = '\u200B' }
    // ParagraphStyle supplies both paragraph boundaries itself. A literal newline here would
    // create another empty line; replacing its glyph keeps every UTF-16 coordinate unchanged.
    val result = AnnotatedString.Builder(AnnotatedString(String(displayed), text.spanStyles, text.paragraphStyles))
    text.getStringAnnotations(0, text.length).forEach { result.addStringAnnotation(it.tag, it.item, it.start, it.end) }
    if (style.textIndent != null && sourceStart > 0 && context?.getOrNull(sourceStart - 1) != '\n') {
        val newline = text.text.indexOf('\n').takeIf { it >= 0 } ?: text.length
        // Include literal newlines in their paragraph; a separate ParagraphStyle
        // boundary beside a newline would create an unintended extra blank line.
        val end = if (newline in positions) newline else (newline + 1).coerceAtMost(text.length)
        if (end > 0) result.addStyle(ParagraphStyle(textIndent = TextIndent(0.sp, 0.sp)), 0, end)
    }
    positions.forEach { start ->
        val sourceGap = text.getStringAnnotations(ReaderSourceParagraphGapTag, start, start + 1).lastOrNull()?.item?.toFloatOrNull()
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 8f)
        val gapStyle = ParagraphStyle(lineHeight = (style.fontSize.value * (sourceGap ?: gapEm.coerceIn(0f, 2f))).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None))
        result.addStyle(SpanStyle(fontSize = 1.sp), start, start + 1)
        result.addStyle(gapStyle, start, start + 1)
    }
    return TransformedText(result.toAnnotatedString(), converted.offsetMapping)
}
