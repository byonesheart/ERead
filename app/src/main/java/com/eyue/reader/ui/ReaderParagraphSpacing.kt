package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

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
internal fun readerParagraphText(text: AnnotatedString, style: TextStyle, gapEm: Float = ReaderParagraphGapEm): TransformedText {
    val positions = ArrayList<Int>()
    for (index in 1 until text.length - 1) {
        if (text[index] == '\n' && readerParagraphGapAfter(text.text, index + 1)) positions += index
    }
    if (positions.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val displayed = text.text.toCharArray()
    positions.forEach { displayed[it] = '\u200B' }
    // ParagraphStyle supplies both paragraph boundaries itself. A literal newline here would
    // create another empty line; replacing its glyph keeps every UTF-16 coordinate unchanged.
    val result = AnnotatedString.Builder(AnnotatedString(String(displayed), text.spanStyles, text.paragraphStyles))
    text.getStringAnnotations(0, text.length).forEach { result.addStringAnnotation(it.tag, it.item, it.start, it.end) }
    val gapStyle = ParagraphStyle(lineHeight = (style.fontSize.value * gapEm.coerceIn(0f, 2f)).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None))
    positions.forEach { start ->
        result.addStyle(SpanStyle(fontSize = 1.sp), start, start + 1)
        result.addStyle(gapStyle, start, start + 1)
    }
    return TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
}
