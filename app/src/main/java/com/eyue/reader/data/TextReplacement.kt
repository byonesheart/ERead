package com.eyue.reader.data

internal data class TextReplacement(val start: Int, val end: Int, val text: String)

/** Match the original text once; replacement text never becomes another search input. */
internal fun literalTextReplacements(text: String, query: String, replacement: String): List<TextReplacement> {
    val needle = query.trim()
    if (needle.isEmpty() || '\uFFFC' in needle) return emptyList()
    val inserted = replacement.replace("\r\n", "\n").replace('\r', '\n')
    return buildList {
        var cursor = 0
        while (cursor <= text.length - needle.length) {
            val found = text.indexOf(needle, cursor, ignoreCase = true)
            if (found < 0) break
            add(TextReplacement(found, found + needle.length, inserted))
            cursor = found + needle.length
        }
    }
}

/** Rebuild once and retain prefix deltas for mapping old UTF-16 positions in logarithmic time. */
internal class TextReplacementPlan(original: String, replacements: List<TextReplacement>) {
    val replacements = replacements.sortedBy { it.start }.map {
        it.copy(text = it.text.replace("\r\n", "\n").replace('\r', '\n'))
    }
    private val originalLength = original.length
    private val deltas = IntArray(this.replacements.size + 1)
    val text: String

    init {
        this.replacements.forEachIndexed { index, edit ->
            require(edit.start in 0..original.length && edit.end in edit.start..original.length &&
                (index == 0 || this.replacements[index - 1].let { it.end <= edit.start && it.start < edit.start })) {
                "编辑位置无效，请重新打开文本"
            }
            deltas[index + 1] = deltas[index] + edit.text.length - (edit.end - edit.start)
        }
        text = buildString((original.length + deltas.last()).coerceAtLeast(0)) {
            var cursor = 0
            this@TextReplacementPlan.replacements.forEach { edit ->
                append(original, cursor, edit.start)
                append(edit.text)
                cursor = edit.end
            }
            append(original, cursor, original.length)
        }
    }

    fun offset(value: Int, afterInsertion: Boolean = false): Int {
        val position = value.coerceIn(0, originalLength)
        var low = 0
        var high = replacements.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (replacements[middle].start < position) low = middle + 1 else high = middle
        }
        val previous = low - 1
        val mapped = if (previous < 0) position else replacements[previous].let { edit ->
            if (position >= edit.end) position + deltas[previous + 1]
            else edit.start + deltas[previous] + minOf(position - edit.start, edit.text.length)
        }
        val insertion = replacements.getOrNull(low)
        return mapped + if (afterInsertion && insertion?.start == position && insertion.end == position) insertion.text.length else 0
    }

    fun firstEndingAfter(position: Int): Int {
        var low = 0
        var high = replacements.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (replacements[middle].end <= position) low = middle + 1 else high = middle
        }
        return low
    }
}
