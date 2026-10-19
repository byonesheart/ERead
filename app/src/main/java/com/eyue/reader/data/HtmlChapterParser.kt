package com.eyue.reader.data

import android.text.Html
import java.util.UUID

/** Image events use the UTF-16 offsets of the final text, including duplicate adjacent images. */
internal object HtmlChapterParser {
    private val attributes = Regex("(?:^|\\s)([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    fun read(title: String, html: String, css: String = "", checkActive: () -> Unit = {}, image: (String) -> String?): ParsedTextChapter {
        return readContent(title, html, css, true, checkActive, emptyMap(), image)
    }

    fun readWithNotes(title: String, html: String, css: String = "", checkActive: () -> Unit = {},
                      noteTargets: Map<String, String>, image: (String) -> String?): ParsedTextChapter =
        readContent(title, html, css, true, checkActive, noteTargets, image)

    /** The import pass extracts stable text offsets without projecting any CSS selectors. */
    fun readPlain(title: String, html: String, checkActive: () -> Unit = {}, image: (String) -> String?): ParsedTextChapter =
        readContent(title, html, "", false, checkActive, emptyMap(), image)

    fun readPlainWithNotes(title: String, html: String, checkActive: () -> Unit = {},
                           noteTargets: Map<String, String>, image: (String) -> String?): ParsedTextChapter =
        readContent(title, html, "", false, checkActive, noteTargets, image)

    private fun readContent(title: String, html: String, css: String, includeStyles: Boolean,
                            checkActive: () -> Unit, noteTargets: Map<String, String>, image: (String) -> String?): ParsedTextChapter {
        val prefix = "\uE000${UUID.randomUUID()}_"
        val paths = mutableListOf<String>()
        val references = sourceNoteReferences(html, checkActive)
        val targets = sourceLocalNoteTargets(html, references, checkActive) + noteTargets
        val bodies = mutableMapOf<String, String>()
        val notes = references.mapNotNull { reference ->
            targets[reference.target]?.let { body ->
                val content = bodies.getOrPut(reference.target) {
                    normalizeHtmlText(Html.fromHtml(hideHtmlContent(removeHtmlComments(removeNoteBacklinks(body)), false, checkActive),
                        Html.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH or Html.FROM_HTML_SEPARATOR_LINE_BREAK_DIV).toString(), intArrayOf(), checkActive).text
                }
                content.takeIf(String::isNotBlank)?.let { reference to it }
            }
        }
        val notedHtml = if (notes.isEmpty()) html else buildString(html.length + notes.size * 100) {
            var cursor = 0
            notes.forEachIndexed { index, (reference, _) ->
                append(html, cursor, reference.tag.end).append("${prefix}N${index}B\uE001")
                append(html, reference.tag.end, reference.contentEnd).append("${prefix}N${index}E\uE001")
                cursor = reference.contentEnd
            }
            append(html, cursor, html.length)
        }
        val source = if (includeStyles) SourceStyleParser.mark(notedHtml, css, "${prefix}S", checkActive)
            else MarkedSourceHtml(hideHtmlContent(removeHtmlComments(notedHtml), false, checkActive), emptyList())
        val marked = buildString(source.html.length) {
            var cursor = 0
            sourceHtmlTags(source.html, checkActive).filter { !it.closing && it.name in setOf("img", "image") }.forEach { tag ->
                append(source.html, cursor, tag.start)
                image(tag.attributes)?.let { path ->
                    paths += path
                    append("${prefix}I${paths.lastIndex}\uE001")
                }
                cursor = tag.end
            }
            append(source.html, cursor, source.html.length)
        }
        val plain = Html.fromHtml(marked, Html.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH or
            Html.FROM_HTML_SEPARATOR_LINE_BREAK_DIV).toString()
        checkActive()
        val result = StringBuilder()
        val images = mutableListOf<InlineImage>()
        val starts = IntArray(source.styles.size)
        val ends = IntArray(source.styles.size)
        val noteStarts = IntArray(notes.size)
        val noteEnds = IntArray(notes.size)
        val noteImages = arrayOfNulls<String>(notes.size)
        val activeNotes = mutableSetOf<Int>()
        var cursor = 0
        Regex("${Regex.escape(prefix)}([ISN])(\\d+)([BE]?)\uE001").findAll(plain).forEach { marker ->
            result.append(plain, cursor, marker.range.first)
            val index = marker.groupValues[2].toInt()
            if (marker.groupValues[1] == "I") {
                images += InlineImage(result.length, paths[index])
                activeNotes.forEach { if (noteImages[it] == null) noteImages[it] = paths[index] }
            } else if (marker.groupValues[1] == "N") {
                if (marker.groupValues[3] == "B") { noteStarts[index] = result.length; activeNotes += index }
                else { noteEnds[index] = result.length; activeNotes -= index }
            }
            else if (marker.groupValues[3] == "B") starts[index] = result.length else ends[index] = result.length
            cursor = marker.range.last + 1
        }
        result.append(plain, cursor, plain.length)
        val normalized = normalizeHtmlText(result.toString(), starts + ends + noteStarts + noteEnds + images.map { it.offset }.toIntArray(), checkActive)
        val text = normalized.text
        val styles = source.styles.mapIndexedNotNull { index, style ->
            val start = normalized.offset(starts[index])
            val end = normalized.offset(ends[index])
            if (end <= start) null else style.copy(start = start, end = end)
        }
        val footnotes = notes.mapIndexedNotNull { index, (reference, content) ->
            val start = normalized.offset(noteStarts[index])
            val end = normalized.offset(noteEnds[index])
            if (end <= start && noteImages[index] == null) null else SourceFootnote(start, end,
                text.substring(start, end).ifBlank { reference.label.ifBlank { "注释" } }, content, noteImages[index])
        }
        return ParsedTextChapter(title, text, images.map { it.copy(offset = normalized.offset(it.offset)) }, styles, sourceFootnotes = footnotes)
    }

    fun merge(chapters: List<ParsedTextChapter>): ParsedTextChapter {
        val text = StringBuilder()
        val images = mutableListOf<InlineImage>()
        val styles = mutableListOf<SourceTextStyle>()
        val footnotes = mutableListOf<SourceFootnote>()
        chapters.forEachIndexed { index, chapter ->
            if (index > 0) text.append("\n\n")
            images += chapter.inlineImages.map { it.copy(offset = text.length + it.offset) }
            styles += chapter.sourceStyles.map { it.copy(start = text.length + it.start, end = text.length + it.end) }
            footnotes += chapter.sourceFootnotes.map { it.copy(start = text.length + it.start, end = text.length + it.end) }
            text.append(chapter.text)
        }
        return ParsedTextChapter("正文", text.toString(), images, styles, sourceFootnotes = footnotes)
    }

    fun split(content: ParsedTextChapter, rule: String): List<ParsedTextChapter> {
        val chapters = TextParser.split(content.text, rule)
        var cursor = 0
        val starts = chapters.map { chapter ->
            content.text.indexOf(chapter.text, cursor).coerceAtLeast(cursor).also { cursor = it + chapter.text.length }
        }
        val images = List(chapters.size) { mutableListOf<InlineImage>() }
        var index = 0
        content.inlineImages.forEach { image ->
            while (index < starts.lastIndex && starts[index + 1] <= image.offset) index++
            images[index] += image.copy(offset = (image.offset - starts[index]).coerceIn(0, chapters[index].text.length))
        }
        val styles = List(chapters.size) { mutableListOf<SourceTextStyle>() }
        content.sourceStyles.forEach { style ->
            var chapter = starts.binarySearch(style.start).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
            while (chapter < chapters.size && starts[chapter] < style.end) {
                val start = starts[chapter]
                val end = start + chapters[chapter].text.length
                if (style.end > start && style.start < end) styles[chapter] += style.copy(
                    start = (style.start - start).coerceAtLeast(0), end = (style.end - start).coerceAtMost(end - start))
                chapter++
            }
        }
        val notes = List(chapters.size) { mutableListOf<SourceFootnote>() }
        content.sourceFootnotes.forEach { note ->
            val chapter = starts.binarySearch(note.start).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
            val start = starts[chapter]
            if (note.end <= start + chapters[chapter].text.length) notes[chapter] += note.copy(start = note.start - start, end = note.end - start)
        }
        return chapters.mapIndexed { index, chapter -> chapter.copy(inlineImages = images[index], sourceStyles = styles[index], sourceFootnotes = notes[index]) }
    }

    fun attribute(attributes: String, name: String): String? =
        this.attributes.findAll(attributes).firstOrNull { it.groupValues[1].equals(name, true) }
            ?.let { it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty() }
            ?.let { if ('&' in it || '<' in it) Html.fromHtml(it.replace("<", "&lt;"), Html.FROM_HTML_MODE_LEGACY).toString() else it }
}
