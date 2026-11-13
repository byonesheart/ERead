package com.eyue.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

private val dialogueClosers = mapOf('“' to '”', '「' to '」', '『' to '』', '﹁' to '﹂', '﹃' to '﹄', '"' to '"')

internal fun AnnotatedString.Builder.colorDialogue(text: String, start: Int, end: Int, color: Long) {
    if (start >= end || start < 0 || end > text.length) return
    val paragraphStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val paragraphEnd = text.indexOf('\n', end).takeIf { it >= 0 } ?: text.length
    val style = SpanStyle(color = Color(color))
    val quotes = ArrayDeque<Pair<Char, Int>>()
    for (index in paragraphStart until paragraphEnd) {
        val char = text[index]
        if (char == '\n' || char == '\r') quotes.clear()
        else if (quotes.lastOrNull()?.first == char) {
            val opening = quotes.removeLast().second
            val from = maxOf(start, opening)
            val to = minOf(end, index + 1)
            if (index > opening + 1 && from < to) addStyle(style, from - start, to - start)
        } else dialogueClosers[char]?.let { quotes.addLast(it to index) }
    }
}
