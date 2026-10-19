package com.eyue.reader.data

/** EPUB/MOBI edits keep the source chapter order while updating cached text and its anchors. */
internal class EbookTextEdit(
    private val chapterIndex: Int,
    private val original: String,
    replacements: List<TextReplacement>
) {
    constructor(chapterIndex: Int, original: String, start: Int, end: Int, replacement: String) :
        this(chapterIndex, original, listOf(TextReplacement(start, end, replacement)))

    private val edits = TextReplacementPlan(original, replacements)
    val text: String = edits.text

    init {
        require(text.isNotBlank()) { "章节正文不能为空" }
    }

    private fun offset(value: Int): Int {
        return edits.offset(value)
    }

    fun position(chapter: Int, characterOffset: Int): Pair<Int, Int> =
        chapter to if (chapter == chapterIndex) offset(characterOffset) else characterOffset

    fun chapter(chapter: Chapter, textPath: String): Chapter {
        // Paragraph formatting belongs to complete paragraphs; edited paragraphs use reader body settings.
        val newlines = original.indices.filter { original[it] == '\n' }
        val paragraphs = mutableListOf<Pair<Int, Int>>()
        edits.replacements.forEach { edit ->
            val before = newlines.binarySearch(edit.start - 1).let { if (it >= 0) it else -it - 2 }
            val after = newlines.binarySearch(maxOf(edit.start, edit.end - 1)).let { if (it >= 0) it else -it - 1 }
            val start = (newlines.getOrNull(before) ?: -1) + 1
            val end = newlines.getOrNull(after) ?: original.length
            val previous = paragraphs.lastOrNull()
            if (previous != null && start <= previous.second) paragraphs[paragraphs.lastIndex] = previous.first to maxOf(previous.second, end)
            else paragraphs += start to end
        }
        fun touchesParagraph(style: SourceTextStyle): Boolean {
            var low = 0
            var high = paragraphs.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (paragraphs[middle].second <= style.start) low = middle + 1 else high = middle
            }
            return paragraphs.getOrNull(low)?.let { it.first < style.end } == true
        }
        val styles = chapter.sourceStyles.flatMap { style ->
            if (style.paragraph && touchesParagraph(style)) emptyList()
            else {
                // Split inline styles around every replacement; new text uses the reader's body formatting.
                buildList {
                    var cursor = style.start
                    var index = edits.firstEndingAfter(style.start)
                    while (index < edits.replacements.size && edits.replacements[index].start < style.end) {
                        val edit = edits.replacements[index++]
                        if (cursor < edit.start) add(style.copy(start = edits.offset(cursor, afterInsertion = true), end = offset(edit.start)))
                        cursor = maxOf(cursor, edit.end)
                    }
                    if (cursor < style.end) add(style.copy(start = edits.offset(cursor, afterInsertion = true), end = offset(style.end)))
                }
            }
        }.filter { it.end > it.start }
        val footnotes = chapter.sourceFootnotes.mapNotNull { footnote ->
            val imageReference = footnote.start == footnote.end && footnote.imagePath != null
            if (footnote.start < 0 || footnote.end < footnote.start || footnote.end > original.length ||
                footnote.end == footnote.start && !imageReference) return@mapNotNull null
            var index = edits.firstEndingAfter(footnote.start)
            while (index < edits.replacements.size && edits.replacements[index].start < footnote.end) {
                val edit = edits.replacements[index++]
                if (edit.end > footnote.start && edit.end > edit.start &&
                    edit.text != original.substring(edit.start, edit.end)) return@mapNotNull null
            }
            val from = edits.offset(footnote.start, afterInsertion = !imageReference)
            val until = offset(footnote.end)
            if (imageReference) footnote.copy(start = from, end = from)
            else if (until <= from || text.substring(from, until) != original.substring(footnote.start, footnote.end)) null
            else footnote.copy(start = from, end = until)
        }
        return chapter.copy(textPath = textPath, characterCount = text.length, lazySourcePath = null,
            inlineImages = chapter.inlineImages.map { it.copy(offset = offset(it.offset)) }, sourceStyles = styles,
            sourceFootnotes = footnotes)
    }

    fun book(book: Book, updatedChapter: Chapter, chapterLengths: List<Int>): Book {
        val chapters = book.chapters.toMutableList().apply { this[chapterIndex] = updatedChapter }
        val position = position(book.chapterIndex, book.characterOffset)
        val lengths = chapterLengths.toMutableList().apply { this[chapterIndex] = text.length }
        val progress = (lengths.take(position.first).sum().toFloat() + position.second) / lengths.sum().coerceAtLeast(1)
        return book.copy(chapters = chapters, chapterIndex = position.first, characterOffset = position.second,
            pageIndex = 0, progress = progress.coerceIn(0f, 1f), contentVersion = book.contentVersion + 1, hasTextEdits = true)
    }

    fun annotation(annotation: Annotation): Annotation {
        if (annotation.chapterIndex != chapterIndex) return annotation
        val newStart = offset(annotation.characterOffset)
        val oldEnd = if (annotation.endOffset > annotation.characterOffset) annotation.endOffset
            else if (annotation.kind == AnnotationKind.NOTE) annotation.characterOffset + annotation.quote.length else 0
        val newEnd = if (oldEnd > annotation.characterOffset) maxOf(newStart, offset(oldEnd)) else 0
        return annotation.copy(characterOffset = newStart, endOffset = newEnd, pageIndex = 0,
            quote = if (newEnd > newStart) text.substring(newStart, newEnd) else annotation.quote)
    }
}

/** Rebuild the catalog from edited caches so changing a rule cannot silently restore the original text. */
internal fun splitEditedTextChapters(chapters: List<ParsedTextChapter>, rule: String): List<ParsedTextChapter> {
    val source = chapters.joinToString("\n\n") { it.text }
    val oldStarts = chapters.runningFold(0) { offset, chapter -> offset + chapter.text.length + 2 }.dropLast(1)
    val parsed = TextParser.split(source, rule)
    var cursor = 0
    val newStarts = parsed.map { chapter ->
        val start = source.indexOf(chapter.text, cursor)
        check(start >= 0) { "修改后的目录定位失败" }
        cursor = start + chapter.text.length
        start
    }
    val images = parsed.map { mutableListOf<InlineImage>() }
    val footnotes = parsed.map { mutableListOf<SourceFootnote>() }
    chapters.forEachIndexed { index, chapter -> chapter.inlineImages.forEach { image ->
        val position = oldStarts[index] + image.offset
        val target = newStarts.indexOfLast { it <= position }.coerceAtLeast(0)
        images[target] += image.copy(offset = (position - newStarts[target]).coerceIn(0, parsed[target].text.length))
    } }
    chapters.forEachIndexed { index, chapter -> chapter.sourceFootnotes.forEach { footnote ->
        if (footnote.start >= 0 && footnote.end <= chapter.text.length &&
            (footnote.end > footnote.start || footnote.end == footnote.start && footnote.imagePath != null)) {
            val from = oldStarts[index] + footnote.start
            val until = oldStarts[index] + footnote.end
            val target = newStarts.binarySearch(from).let { if (it >= 0) it else -it - 2 }
            if (target >= 0 && until <= newStarts[target] + parsed[target].text.length) {
                footnotes[target] += footnote.copy(start = from - newStarts[target], end = until - newStarts[target])
            }
        }
    } }
    return parsed.mapIndexed { target, chapter ->
        val start = newStarts[target]
        val end = start + chapter.text.length
        val styles = chapters.flatMapIndexed { index, old -> old.sourceStyles.mapNotNull { style ->
            val from = maxOf(start, oldStarts[index] + style.start)
            val until = minOf(end, oldStarts[index] + style.end)
            if (until > from) style.copy(start = from - start, end = until - start) else null
        } }
        chapter.copy(inlineImages = images[target], sourceStyles = styles, sourceFootnotes = footnotes[target])
    }
}
