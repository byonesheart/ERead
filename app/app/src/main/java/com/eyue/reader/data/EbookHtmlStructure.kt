package com.eyue.reader.data

private val ebookParagraphBreaks = setOf("address", "article", "aside", "blockquote", "div", "dl", "fieldset", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre", "section", "table", "ul")
private val ebookDocumentTags = setOf("html", "head", "body")

/** [start] and [end] refer to the original document's UTF-16 offsets, before any rewriting. */
internal fun ebookHtmlFragment(raw: String, start: Int, end: Int): String {
    require(start in 0..raw.length && end in start..raw.length) { "Invalid ebook HTML section: $start..$end / ${raw.length}" }
    if (start == 0 && end == raw.length) return raw

    var html: SourceHtmlTag? = null
    var head: SourceHtmlTag? = null
    var headEnd: SourceHtmlTag? = null
    var body: SourceHtmlTag? = null
    val prefixStack = mutableListOf<SourceHtmlTag>()
    for (tag in sourceHtmlTags(raw)) {
        if (!tag.closing && tag.name == "body") { body = tag; break }
        if (!tag.closing && tag.name == "html" && html == null) html = tag
        if (!tag.closing && tag.name == "head" && head == null) head = tag
        if (tag.closing && tag.name == "head" && headEnd == null) headEnd = tag
        updateEbookHtmlStack(prefixStack, tag)
    }
    val bodyStart = body?.end ?: headEnd?.end ?: html?.end ?: 0
    val prefix = buildString {
        if (html == null) append("<html>")
        if (body != null) {
            append(raw, 0, body!!.start)
            // Old MOBI can omit </head> or </style>; don't let it swallow the chapter.
            if (head != null && headEnd == null) {
                prefixStack.asReversed().takeWhile { it.name != "html" }.forEach { append("</${it.name}>") }
            }
            append(raw, body!!.start, body!!.end)
        } else {
            append(raw, 0, bodyStart)
            append("<body>")
        }
    }
    var from = start.coerceAtLeast(bodyStart)
    var until = end.coerceAtLeast(bodyStart)
    // TOC anchors normally lie on tag boundaries. Recover safely if a malformed index doesn't.
    for (tag in sourceHtmlTags(raw)) {
        if (tag.start >= until) break
        if (from > tag.start && from < tag.end) from = tag.start.coerceAtLeast(bodyStart)
        if (until > tag.start && until < tag.end) until = tag.end
    }
    val stack = mutableListOf<SourceHtmlTag>()
    val result = StringBuilder(prefix)
    var cursor = from
    var started = false
    for (tag in sourceHtmlTags(raw)) {
        if (tag.end <= bodyStart) continue
        if (tag.start >= until) break
        if (tag.closing && tag.name in setOf("body", "html")) {
            until = tag.start.coerceAtLeast(from)
            break
        }
        if (tag.end <= from) {
            if (tag.name !in ebookDocumentTags) updateEbookHtmlStack(stack, tag)
            continue
        }
        if (!started) {
            if (!tag.closing && tag.start == from) {
                val implicit = ebookImplicitClose(stack, tag.name)
                if (implicit >= 0) while (stack.size > implicit) stack.removeAt(stack.lastIndex)
            }
            stack.forEach { result.append(raw, it.start, it.end) }
            started = true
        }
        if (tag.name in ebookDocumentTags) {
            result.append(raw, cursor, tag.start)
            cursor = tag.end
            continue
        }
        result.append(raw, cursor, tag.start)
        if (tag.closing) {
            val match = stack.indexOfLast { it.name == tag.name }
            if (match >= 0) {
                for (index in stack.lastIndex downTo match + 1) result.append("</${stack[index].name}>")
                result.append(raw, tag.start, tag.end)
                while (stack.size > match) stack.removeAt(stack.lastIndex)
            }
        } else {
            val implicit = ebookImplicitClose(stack, tag.name)
            if (implicit >= 0) {
                for (index in stack.lastIndex downTo implicit) result.append("</${stack[index].name}>")
                while (stack.size > implicit) stack.removeAt(stack.lastIndex)
            }
            result.append(raw, tag.start, tag.end)
            if (!tag.selfClosing) stack += tag
        }
        cursor = tag.end
    }
    if (!started) stack.forEach { result.append(raw, it.start, it.end) }
    if (cursor < until) result.append(raw, cursor, until)
    stack.asReversed().forEach { result.append("</${it.name}>") }
    return result.append("</body></html>").toString()
}

private fun ebookImplicitClose(stack: List<SourceHtmlTag>, name: String): Int {
    if (name in ebookParagraphBreaks) {
        val paragraph = stack.indexOfLast { it.name == "p" }
        if (paragraph >= 0) return paragraph
    }
    val peers = when (name) {
        "li" -> setOf("li", "ul", "ol")
        "dt", "dd" -> setOf("dt", "dd", "dl")
        "td", "th" -> setOf("td", "th", "tr", "table")
        "tr" -> setOf("tr", "table")
        else -> return -1
    }
    val peer = stack.indexOfLast { it.name in peers }
    return peer.takeIf { it >= 0 && stack[it].name in when (name) {
        "li" -> setOf("li")
        "dt", "dd" -> setOf("dt", "dd")
        "td", "th" -> setOf("td", "th")
        else -> setOf("tr")
    } } ?: -1
}

private fun updateEbookHtmlStack(stack: MutableList<SourceHtmlTag>, tag: SourceHtmlTag) {
    val match = if (tag.closing) stack.indexOfLast { it.name == tag.name } else ebookImplicitClose(stack, tag.name)
    if (match >= 0) while (stack.size > match) stack.removeAt(stack.lastIndex)
    if (!tag.closing && !tag.selfClosing) stack += tag
}

private val ebookAttribute = Regex("([\\w:-]+)(\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
private val ebookKindleEmbed = Regex("^kindle:embed:([0-9a-v]+)(?:[?].*)?$", RegexOption.IGNORE_CASE)

/** Resource numbers are MOBI's one-based recindex / base-32 kindle:embed values. */
internal fun ebookRewriteMobiResources(html: String, imageUrl: (Int) -> String?): String {
    val result = StringBuilder(html.length)
    var cursor = 0
    for (tag in sourceHtmlTags(html)) {
        if (tag.closing || tag.name !in setOf("img", "image", "a")) continue
        val replacements = mutableMapOf<String, String>()
        if (tag.name == "a") {
            sourceHtmlAttribute(tag.attributes, "filepos")?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { replacements["href"] = "#eread-filepos-$it" }
        } else {
            val reference = sourceHtmlAttribute(tag.attributes, "recindex")?.toIntOrNull()
                ?: listOf("src", "xlink:href", "href").firstNotNullOfOrNull { name ->
                    sourceHtmlAttribute(tag.attributes, name)?.let { ebookKindleEmbed.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull(32) }
                }
            reference?.takeIf { it > 0 }?.let(imageUrl)?.let { url ->
                replacements[if (tag.name == "image") "xlink:href" else "src"] = url
                if (tag.name == "image" && sourceHtmlAttribute(tag.attributes, "href") != null) replacements["href"] = url
            }
        }
        if (replacements.isEmpty()) continue
        var attributes = ebookAttribute.replace(tag.attributes) { match ->
            val name = match.groupValues[1].lowercase()
            replacements.remove(name)?.let { "${match.groupValues[1]}=\"${ebookHtmlAttribute(it)}\"" } ?: match.value
        }
        val slash = attributes.trimEnd().endsWith('/')
        if (slash) attributes = attributes.trimEnd().dropLast(1)
        replacements.forEach { (name, value) -> attributes += " $name=\"${ebookHtmlAttribute(value)}\"" }
        result.append(html, cursor, tag.start).append('<').append(tag.name).append(attributes)
        if (slash) result.append('/')
        result.append('>')
        cursor = tag.end
    }
    return result.append(html, cursor, html.length).toString()
}

private fun ebookHtmlAttribute(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
