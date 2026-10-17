package com.eyue.reader.ui

internal fun readerSpeechSameParagraph(text: String, currentOffset: Int, spokenOffset: Int): Boolean {
    if (currentOffset !in text.indices || spokenOffset !in text.indices) return false
    val paragraph = readerSpeechParagraph(text, currentOffset) ?: return false
    return spokenOffset in paragraph
}

internal fun readerSpeechParagraph(text: String, offset: Int): IntRange? {
    if (text.isBlank()) return null
    val point = offset.coerceIn(0, text.lastIndex)
    val start = text.lastIndexOf('\n', (point - 1).coerceAtLeast(-1)) + 1
    val end = text.indexOf('\n', point).takeIf { it >= 0 } ?: text.length
    return (start until end).takeIf { !it.isEmpty() }
}

internal fun readerSpeechStep(text: String, offset: Int, direction: Int): Int {
    val range = readerSpeechParagraph(text, offset) ?: return 0
    return if (direction > 0) (range.last + 2).coerceAtMost(text.length)
        else if (offset > range.first + 1) range.first
        else (text.lastIndexOf('\n', (range.first - 2).coerceAtLeast(-1)) + 1).coerceAtLeast(0)
}
