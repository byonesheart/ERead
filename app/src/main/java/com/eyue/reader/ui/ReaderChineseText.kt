package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import com.eyue.reader.data.ChineseTextConverter

/** Keep every selection, source style and note anchored to its original UTF-16 position. */
internal fun readerChineseText(source: AnnotatedString, converter: ChineseTextConverter?, context: String? = null,
                               sourceStart: Int = 0): TransformedText {
    if (converter == null) return TransformedText(source, OffsetMapping.Identity)
    val converted = if (context == null) converter.convert(source.text) else converter.convertRange(context, sourceStart, sourceStart + source.length)
    if (converted.text == source.text) return TransformedText(source, OffsetMapping.Identity)
    val mapping = if (converted.forward == null) OffsetMapping.Identity else object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = converted.forward[offset.coerceIn(0, source.length)]
        override fun transformedToOriginal(offset: Int) = converted.reverse!![offset.coerceIn(0, converted.text.length)]
    }
    val result = AnnotatedString.Builder(converted.text)
    source.spanStyles.forEach { result.addStyle(it.item, mapping.originalToTransformed(it.start), mapping.originalToTransformed(it.end)) }
    source.paragraphStyles.forEach { result.addStyle(it.item, mapping.originalToTransformed(it.start), mapping.originalToTransformed(it.end)) }
    source.getStringAnnotations(0, source.length).forEach {
        result.addStringAnnotation(it.tag, it.item, mapping.originalToTransformed(it.start), mapping.originalToTransformed(it.end))
    }
    return TransformedText(result.toAnnotatedString(), mapping)
}
