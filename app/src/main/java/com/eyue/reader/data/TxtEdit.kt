package com.eyue.reader.data

/** An edit is mapped in the combined source, so repeated quotes cannot move a note to another chapter. */
internal class TxtEdit(oldChapters: List<String>, replacementsByChapter: Map<Int, List<TextReplacement>>, rule: String) {
    constructor(oldChapters: List<String>, chapter: Int, start: Int, end: Int, replacement: String, rule: String) :
        this(oldChapters, mapOf(chapter to listOf(TextReplacement(start, end, replacement))), rule)

    private val oldStarts = oldChapters.runningFold(0) { offset, text -> offset + text.length + 2 }.dropLast(1)
    private val oldLengths = oldChapters.map { it.length }
    private val edits: TextReplacementPlan
    val source: String
    val chapters: List<ParsedTextChapter>
    private val newStarts: List<Int>

    init {
        val replacements = replacementsByChapter.flatMap { (chapter, changes) ->
            require(chapter in oldChapters.indices) { "编辑位置无效，请重新打开文本" }
            changes.map { edit ->
                require(edit.start in 0..oldLengths[chapter] && edit.end in edit.start..oldLengths[chapter]) {
                    "编辑位置无效，请重新打开文本"
                }
                edit.copy(start = oldStarts[chapter] + edit.start, end = oldStarts[chapter] + edit.end)
            }
        }
        val original = oldChapters.joinToString("\n\n")
        edits = TextReplacementPlan(original, replacements)
        source = edits.text
        require(source.isNotBlank()) { "正文不能为空" }
        chapters = TextParser.split(source, rule)
        var cursor = 0
        newStarts = chapters.map { parsed ->
            val found = source.indexOf(parsed.text, cursor)
            check(found >= 0) { "修改后的目录定位失败" }
            cursor = found + parsed.text.length
            found
        }
    }

    fun position(chapter: Int, offset: Int): Pair<Int, Int> {
        val index = chapter.coerceIn(0, oldStarts.lastIndex)
        val original = oldStarts[index] + offset.coerceIn(0, oldLengths[index])
        val mapped = edits.offset(original)
        val target = newStarts.binarySearch(mapped).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        return target to (mapped - newStarts[target]).coerceIn(0, chapters[target].text.length)
    }

    fun annotation(annotation: Annotation): Annotation {
        val (chapter, offset) = position(annotation.chapterIndex, annotation.characterOffset)
        val oldEnd = if (annotation.endOffset > annotation.characterOffset) annotation.endOffset
            else if (annotation.kind == AnnotationKind.NOTE) annotation.characterOffset + annotation.quote.length else 0
        val end = if (oldEnd > annotation.characterOffset) position(annotation.chapterIndex, oldEnd) else null
        val endOffset = end?.let { if (it.first == chapter) maxOf(offset, it.second) else chapters[chapter].text.length } ?: 0
        return annotation.copy(chapterIndex = chapter, characterOffset = offset, pageIndex = 0, endOffset = endOffset,
            quote = if (endOffset > offset) chapters[chapter].text.substring(offset, endOffset) else annotation.quote)
    }
}
