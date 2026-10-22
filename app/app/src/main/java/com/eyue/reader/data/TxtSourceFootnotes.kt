package com.eyue.reader.data

private val txtNoteMarker = Regex("\\[[0-9]{1,4}]")
private val txtNoteDefinition = Regex("^[ \\t\\u3000]*(\\[[0-9]{1,4}])[ \\t\\u3000]+(\\S.*)$")

/** Attach only unambiguous chapter-local references; the original text and edit offsets stay intact. */
internal fun txtSourceFootnotes(text: String, checkActive: () -> Unit = {}): List<SourceFootnote> {
    checkActive()
    if ('[' !in text) return emptyList()
    data class Line(val start: Int, val end: Int, val text: String, val definition: MatchResult?)
    data class Definition(val start: Int, val end: Int, val label: String, val content: String)

    val lines = mutableListOf<Line>()
    var cursor = 0
    while (cursor < text.length) {
        checkActive()
        val end = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        val line = text.substring(cursor, end).trimEnd('\r')
        lines += Line(cursor, end, line, txtNoteDefinition.matchEntire(line))
        cursor = end + 1
    }
    val definitions = lines.mapIndexedNotNull { index, line ->
        checkActive()
        val match = line.definition ?: return@mapIndexedNotNull null
        var last = index
        while (last + 1 < lines.size && lines[last + 1].text.isNotBlank() && lines[last + 1].definition == null) {
            checkActive()
            last++
        }
        val contentStart = line.start + match.groups[2]!!.range.first
        Definition(line.start, lines[last].end, match.groupValues[1], text.substring(contentStart, lines[last].end).trim())
    }
    if (definitions.isEmpty()) return emptyList()
    val blocks = mutableListOf<MutableList<Definition>>()
    definitions.forEach { definition ->
        checkActive()
        val previous = blocks.lastOrNull()?.lastOrNull()
        if (previous == null || (previous.end until definition.start).any { !text[it].isWhitespace() })
            blocks += mutableListOf(definition)
        else blocks.last() += definition
    }
    val unique = blocks.map { definitionsInBlock ->
        definitionsInBlock.groupBy { it.label }.mapNotNull { (label, values) ->
            values.singleOrNull()?.let { label to it }
        }.toMap()
    }
    var blockIndex = 0
    return txtNoteMarker.findAll(text).mapNotNull { match ->
        checkActive()
        while (blockIndex < blocks.size && blocks[blockIndex].last().end <= match.range.first) blockIndex++
        if (blockIndex >= blocks.size || match.range.first >= blocks[blockIndex].first().start) return@mapNotNull null
        val definition = unique[blockIndex][match.value] ?: return@mapNotNull null
        SourceFootnote(match.range.first, match.range.last + 1, match.value, definition.content)
    }.toList()
}
