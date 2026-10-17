package com.eyue.reader.data

internal class NormalizedHtmlText(val text: String, private val positions: IntArray, private val offsets: IntArray) {
    val mappedBoundaryCount: Int get() = positions.size
    fun offset(position: Int): Int = offsets[positions.binarySearch(position)]
}

/** Only images and source-style endpoints need offsets; ordinary text needs no per-character map. */
internal fun normalizeHtmlText(raw: String, boundaries: IntArray, checkActive: () -> Unit = {}): NormalizedHtmlText {
    val positions = boundaries.distinct().sorted().toIntArray()
    val offsets = IntArray(positions.size)
    val body = StringBuilder(raw.length)
    var boundary = 0
    var newlines = 0
    fun record(position: Int) {
        if (boundary < positions.size && positions[boundary] == position) offsets[boundary++] = body.length
    }
    record(0)
    raw.forEachIndexed { index, original ->
        if (index % 4096 == 0) checkActive()
        val char = if (original == '\u00A0') ' ' else original
        if (!(char in " \t" && body.lastOrNull() == '\n') && !(char == '\n' && newlines >= 2)) body.append(char)
        if (char == '\n') newlines++ else if (char !in " \t" || body.lastOrNull() != '\n') newlines = 0
        record(index + 1)
    }
    var leading = 0
    while (leading < body.length && body[leading].isWhitespace()) leading++
    var end = body.length
    while (end > leading && body[end - 1].isWhitespace()) end--
    val text = body.substring(leading, end)
    for (index in offsets.indices) offsets[index] = (offsets[index] - leading).coerceIn(0, text.length)
    checkActive()
    return NormalizedHtmlText(text, positions, offsets)
}
