package com.eyue.reader.data

private val htmlComments = Regex("<!--[\\s\\S]*?(?:-->|$)")
private val htmlAttributePattern = Regex("(?:^|\\s)([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
private val tokenWhitespace = Regex("\\s+")
private val referenceMarkers = Regex("^(?:[\\[【〔（(]\\s*\\d+\\s*[\\]】〕）)]|[①-⑳*†‡]+|(?:注|注释|脚注|译注|footnote|note)\\s*[\\[【〔（(]?\\d+[\\]】〕）)]?)$", RegexOption.IGNORE_CASE)
private val footnoteIds = Regex("^(?:fn|footnote|endnote|ftn)[_:.\\-]?\\d+(?:[_:.\\-].*)?$", RegexOption.IGNORE_CASE)

internal data class SourceHtmlTag(val name: String, val start: Int, val end: Int,
                                  val attributes: String, val closing: Boolean, val selfClosing: Boolean)
internal data class SourceHtmlElement(val tag: SourceHtmlTag, val contentEnd: Int, val end: Int)
private val htmlEmptyTags = setOf("br", "hr", "img", "image", "link", "meta", "input", "source", "wbr", "area", "base", "embed", "param", "col")

/** Every character is consumed once, including malformed tags and quoted attributes. */
internal fun sourceHtmlTags(html: String, checkActive: () -> Unit = {}, onProgress: (Int) -> Unit = {}): Sequence<SourceHtmlTag> = sequence {
    var cursor = 0
    var checked = -4096
    var unclosedRawText: String? = null
    fun checkpoint(position: Int) {
        if (position - checked >= 4096) { checkActive(); onProgress(position); checked = position }
    }
    while (cursor < html.length) {
        checkpoint(cursor)
        val start = html.indexOf('<', cursor)
        if (start < 0) break
        if (html.startsWith("<!--", start)) {
            val end = html.indexOf("-->", start + 4)
            cursor = if (end < 0) html.length else end + 3
            continue
        }
        var position = start + 1
        val closing = html.getOrNull(position) == '/'
        if (closing) position++
        val nameStart = position
        if (html.getOrNull(position)?.let { it in 'a'..'z' || it in 'A'..'Z' } != true) { cursor = start + 1; continue }
        while (position < html.length && (html[position].isLetterOrDigit() || html[position] in "_:-.")) position++
        val name = html.substring(nameStart, position).lowercase()
        val attributesStart = position
        var quote: Char? = null
        while (position < html.length) {
            checkpoint(position)
            val char = html[position]
            if (quote != null) { if (char == quote) quote = null }
            else if (char == '\'' || char == '"') quote = char
            else if (char == '>' || char == '<') break
            position++
        }
        if (position < html.length && html[position] == '>') {
            val attributes = html.substring(attributesStart, position)
            val selfClosing = name in htmlEmptyTags || attributes.trimEnd().endsWith('/')
            yield(SourceHtmlTag(name, start, position + 1, attributes, closing, selfClosing))
            cursor = position + 1
            // Closed CSS/script is raw text: quoted <body>/<h1> strings are not HTML tags.
            // If its closer is absent, search only once, then permit recovery at a real body.
            if (!closing && !selfClosing && name in setOf("style", "script") && unclosedRawText == null) {
                checkActive()
                var rawEnd = html.indexOf("</$name", cursor, ignoreCase = true)
                while (rawEnd >= 0 && html.getOrNull(rawEnd + name.length + 2)?.let { it == '>' || it.isWhitespace() } != true) {
                    checkpoint(rawEnd)
                    rawEnd = html.indexOf("</$name", rawEnd + name.length + 2, ignoreCase = true)
                }
                if (rawEnd >= 0) cursor = rawEnd else unclosedRawText = name
            } else if (closing && name == unclosedRawText || !closing && name == "body" && unclosedRawText == "style") {
                unclosedRawText = null
            }
        } else cursor = position
    }
    checkActive()
    onProgress(html.length)
}

/** Unclosed elements are discarded, rather than restarting a regex at every opening tag. */
internal fun sourceHtmlElements(html: String, names: Set<String>, checkActive: () -> Unit = {}): List<SourceHtmlElement> {
    val elements = mutableListOf<SourceHtmlElement>()
    var pending: SourceHtmlTag? = null
    sourceHtmlTags(html, checkActive).forEach { tag ->
        if (tag.name in names) {
            if (!tag.closing) pending = if (tag.selfClosing) null else tag
            else pending?.takeIf { it.name == tag.name }?.let {
                elements += SourceHtmlElement(it, tag.start, tag.end)
                pending = null
            }
        }
    }
    return elements
}

internal fun isHtmlAnchorMarker(tag: SourceHtmlTag): Boolean = tag.name == "a" &&
    sourceHtmlAttribute(tag.attributes, "href") == null && sourceHtmlAttribute(tag.attributes, "filepos") == null &&
    (sourceHtmlAttribute(tag.attributes, "name") != null || sourceHtmlAttribute(tag.attributes, "id") != null)

private fun sourceHtmlHiddenRanges(html: String, checkActive: () -> Unit): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var hidden: SourceHtmlTag? = null
    sourceHtmlTags(html, checkActive).forEach { tag ->
        val opening = hidden
        if (opening != null) {
            if (tag.closing && tag.name == opening.name) { ranges += opening.start until tag.end; hidden = null }
            else if (!tag.closing && tag.name == "body" && opening.name != "script") {
                ranges += opening.start until tag.start; hidden = null
            }
        } else if (!tag.closing && !tag.selfClosing && tag.name in setOf("head", "style", "script")) hidden = tag
    }
    hidden?.let { ranges += it.start until html.length }
    return ranges
}

internal fun hideHtmlContent(html: String, preserveOffsets: Boolean, checkActive: () -> Unit = {}): String {
    val ranges = sourceHtmlHiddenRanges(html, checkActive)
    if (ranges.isEmpty()) return html
    val result = StringBuilder(html.length)
    var cursor = 0
    ranges.forEach { range ->
        result.append(html, cursor, range.first)
        if (preserveOffsets) repeat(range.last - range.first + 1) {
            if (it % 4096 == 0) checkActive()
            result.append(' ')
        }
        cursor = range.last + 1
    }
    return result.append(html, cursor, html.length).toString()
}

internal fun sourceHtmlPlainText(html: String, checkActive: () -> Unit = {}): String {
    val result = StringBuilder(html.length)
    var cursor = 0
    sourceHtmlTags(html, checkActive).forEach { tag ->
        result.append(html, cursor, tag.start).append(' ')
        cursor = tag.end
    }
    return result.append(html, cursor, html.length).toString()
}

internal fun removeHtmlComments(html: String): String = html.replace(htmlComments, "")
/** Native MOBI filepos is measured before comment removal: preserve every UTF-16 boundary while locating tags. */
internal fun maskHtmlComments(html: String): String = html.replace(htmlComments) { " ".repeat(it.value.length) }

internal fun sourceHtmlAttribute(attributes: String, name: String): String? = htmlAttributePattern.findAll(attributes)
    .firstOrNull { it.groupValues[1].equals(name, true) }?.let { it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty() }

internal fun isNoteReference(attributes: String, label: String): Boolean {
    val tokens = listOf("epub:type", "role", "rel", "class").flatMap { sourceHtmlAttribute(attributes, it).orEmpty().lowercase().split(tokenWhitespace) }
    return tokens.any { it in setOf("noteref", "doc-noteref", "footnote", "endnote", "footnote-ref", "endnote-ref") } || referenceMarkers.matches(label.trim())
}

internal fun isFootnoteTarget(attributes: String): Boolean {
    val tokens = listOf("epub:type", "role", "class").flatMap { sourceHtmlAttribute(attributes, it).orEmpty().lowercase().split(tokenWhitespace) }
    return tokens.any { it in setOf("footnote", "endnote", "footnotes", "endnotes", "doc-footnote", "doc-endnote", "doc-endnotes") } ||
        listOf("id", "name").any { footnoteIds.matches(sourceHtmlAttribute(attributes, it).orEmpty()) }
}

internal class SourceHtmlNotes(html: String, checkActive: () -> Unit = {}) {
    private data class Scope(val tag: String, val start: Int, val note: Boolean)
    private val ranges = mutableListOf<IntRange>()
    init {
        val stack = mutableListOf<Scope>()
        val positions = mutableMapOf<String, MutableList<Int>>()
        var insideNote = false
        sourceHtmlTags(html, checkActive).forEach { item ->
            val tag = item.name
            if (item.closing) {
                val index = positions[tag]?.lastOrNull() ?: -1
                if (index >= 0) for (cursor in stack.lastIndex downTo index) {
                    val scope = stack.removeAt(cursor)
                    positions[scope.tag]?.let { it.removeAt(it.lastIndex) }
                    if (scope.note) { ranges += scope.start until item.end; insideNote = false }
                }
            } else {
                val note = !insideNote && isFootnoteTarget(item.attributes)
                if (item.selfClosing || isHtmlAnchorMarker(item)) {
                    if (note) ranges += item.start until item.end
                } else {
                    positions.getOrPut(tag) { mutableListOf() } += stack.size
                    stack += Scope(tag, item.start, note)
                    if (note) insideNote = true
                }
            }
        }
        stack.firstOrNull { it.note }?.let { ranges += it.start..html.lastIndex }
    }
    fun contains(offset: Int): Boolean {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle].first <= offset) low = middle + 1 else high = middle
        }
        return low > 0 && offset <= ranges[low - 1].last
    }
}

/** Fragment chapters still inherit the document's body class and inline declarations. */
internal fun sourceBodyTag(html: String, checkActive: () -> Unit = {}): String? =
    sourceHtmlTags(html, checkActive).firstOrNull { it.name == "body" && !it.closing }?.let { html.substring(it.start, it.end) }

internal fun sourceBodyFragment(fragment: String, bodyTag: String?): String =
    if (bodyTag == null || sourceBodyTag(fragment) != null) fragment else "$bodyTag$fragment</body>"
