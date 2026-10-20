package com.eyue.reader.ui

internal fun readerSpeechParagraph(text: String, offset: Int): IntRange? {
    if (text.isBlank()) return null
    val point = offset.coerceIn(0, text.lastIndex)
    val start = text.lastIndexOf('\n', (point - 1).coerceAtLeast(-1)) + 1
    val end = text.indexOf('\n', point).takeIf { it >= 0 } ?: text.length
    return (start until end).takeIf { !it.isEmpty() }
}

internal fun readerSpeechSentenceStart(text: String, offset: Int): Int {
    val paragraph = readerSpeechParagraph(text, offset) ?: return 0
    val point = offset.coerceIn(paragraph.first, paragraph.last)
    val terminators = "。！？!?；;."
    var start = paragraph.first
    var cursor = start
    while (cursor <= point) {
        val decimal = text[cursor] == '.' && cursor > paragraph.first && cursor < paragraph.last &&
            text[cursor - 1].isDigit() && text[cursor + 1].isDigit()
        if (text[cursor] in terminators && !decimal) {
            var end = cursor + 1
            while (end <= paragraph.last && (text[end] in terminators || text[end] in "”」』\"'")) end++
            if (point < end) break
            while (end <= paragraph.last && text[end].isWhitespace()) end++
            if (end <= point) start = end
            cursor = end
        } else cursor++
    }
    while (start < point && text[start].isWhitespace()) start++
    return start
}

internal fun readerSpeechStep(text: String, offset: Int, direction: Int): Int {
    val range = readerSpeechParagraph(text, offset) ?: return 0
    return if (direction > 0) (range.last + 2).coerceAtMost(text.length)
        else if (offset > range.first + 1) range.first
        else (text.lastIndexOf('\n', (range.first - 2).coerceAtLeast(-1)) + 1).coerceAtLeast(0)
}
