package com.eyue.reader.data

import java.net.URLDecoder

internal data class SourceNoteReference(val tag: SourceHtmlTag, val contentEnd: Int, val end: Int,
                                      val label: String, val target: String, val href: String?, val filepos: Int?)

private val noteNumber = Regex("^[0-9]{1,3}$")
private val superscriptTag = Regex("<sup\\b", RegexOption.IGNORE_CASE)
private val precedingSuperscript = Regex("<sup\\b[^>]*>$", RegexOption.IGNORE_CASE)
private val numericNoteEntity = Regex("&#(?:x([0-9a-f]+)|([0-9]+));", RegexOption.IGNORE_CASE)
private val noteBlocks = setOf("p", "div", "li", "aside", "dd", "dt", "blockquote")
private val backlinkTarget = Regex("(?:fnref|noteref|back|return)[_:.\\-]?[0-9]+.*")
private val backlinkLabel = Regex("[↩↵↥↑]+|返回(?:正文|原文)?|回到正文")
private val noteTokenSpace = Regex("\\s+")
private val backlinkTypes = setOf("doc-backlink", "backlink", "footnote-backref", "footnote-back", "reversefootnote", "returnlink")

private fun isNoteBacklink(attributes: String, href: String, label: String): Boolean =
    listOf("role", "epub:type", "class", "rev").any {
        sourceHtmlAttribute(attributes, it).orEmpty().lowercase().split(noteTokenSpace).any { token -> token in backlinkTypes }
    } || backlinkTarget.matches(href.lowercase().substringAfter('#')) || backlinkLabel.matches(label)

internal fun sourceNoteFragment(href: String): String? = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
    ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }

/** Candidate references only; normal navigation links never become notes merely because they have a target. */
internal fun sourceNoteReferences(html: String, checkActive: () -> Unit = {}): List<SourceNoteReference> {
    val scanned = hideHtmlContent(maskHtmlComments(html), true, checkActive)
    val references = sourceHtmlElements(scanned, setOf("a"), checkActive).mapNotNull { link ->
        val attributes = link.tag.attributes
        val href = sourceHtmlAttribute(attributes, "href")?.replace("&amp;", "&")
        val position = sourceHtmlAttribute(attributes, "filepos")?.toIntOrNull()?.takeIf { it >= 0 }
        if (href == null && position == null) return@mapNotNull null
        val inside = scanned.substring(link.tag.end, link.contentEnd)
        if (inside.length > 4096) return@mapNotNull null
        val label = sourceHtmlPlainText(inside, checkActive).replace(numericNoteEntity) { match ->
            val point = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                ?: match.groupValues[2].toIntOrNull()
            point?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
        }.trim()
        if (isNoteBacklink(attributes, href.orEmpty(), label)) return@mapNotNull null
        val fragment = href?.let(::sourceNoteFragment)
        val noteTarget = fragment?.let { isFootnoteTarget("id='$it'") } == true
        val superscript = superscriptTag.containsMatchIn(inside) ||
            scanned.substring(maxOf(0, link.tag.start - 100), link.tag.start).trimEnd().let {
                it.endsWith("<sup>", true) || precedingSuperscript.containsMatchIn(it)
            }
        val image = sourceHtmlTags(inside, checkActive).any { !it.closing && it.name in setOf("img", "image") }
        if (!isNoteReference(attributes, label) && !noteTarget && !(superscript && (noteNumber.matches(label) || image)))
            return@mapNotNull null
        val target = if (href != null && fragment != null) href else if (position != null) "filepos:$position" else return@mapNotNull null
        SourceNoteReference(link.tag, link.contentEnd, link.end, label, target, href, position)
    }
    if (references.map { it.label }.distinct().size == references.size) return references
    // Calibre uses the same [n] label for both the body reference and the return link at the
    // start of its note paragraph. Generic filepos IDs alone cannot distinguish the two.
    val blocks = sourceHtmlElements(scanned, noteBlocks, checkActive)
    val idOffsets = mutableMapOf<String, Int>()
    sourceHtmlTags(scanned, checkActive).filter { !it.closing }.forEach { tag ->
        listOfNotNull(sourceHtmlAttribute(tag.attributes, "id"), sourceHtmlAttribute(tag.attributes, "name"))
            .forEach { idOffsets.putIfAbsent(it, tag.start) }
    }
    val previous = mutableMapOf<String, SourceNoteReference>()
    var blockIndex = 0
    return references.filter { reference ->
        while (blockIndex < blocks.size && blocks[blockIndex].end <= reference.tag.start) blockIndex++
        val block = blocks.getOrNull(blockIndex)?.takeIf { it.tag.end <= reference.tag.start && reference.end <= it.contentEnd }
        val earlier = previous[reference.label]
        val returnsToBody = if (block == null || earlier == null) false else {
            val fragment = reference.href?.let(::sourceNoteFragment)
            val blockIds = listOfNotNull(sourceHtmlAttribute(block.tag.attributes, "id"), sourceHtmlAttribute(block.tag.attributes, "name"))
            val reciprocal = earlier.href?.let(::sourceNoteFragment) in blockIds &&
                fragment?.let { idOffsets[it]?.let { offset -> offset < block.tag.start } } == true
            val nativePosition = reference.filepos?.let { back -> earlier.filepos?.let { back < it } } == true
            (reciprocal || nativePosition) &&
                sourceHtmlPlainText(scanned.substring(block.tag.end, reference.tag.start), checkActive).isBlank() &&
                sourceHtmlPlainText(scanned.substring(reference.end, block.contentEnd), checkActive).isNotBlank()
        }
        if (!returnsToBody) previous[reference.label] = reference
        !returnsToBody
    }
}

/** One document scan builds only the requested local note bodies, including empty named-anchor targets. */
internal fun sourceNoteTargets(html: String, fragments: Map<String, String>, positions: Map<String, Int> = emptyMap(),
                               checkActive: () -> Unit = {}): Map<String, String> {
    if (fragments.isEmpty() && positions.isEmpty()) return emptyMap()
    data class Scope(val tag: SourceHtmlTag, val keys: MutableSet<String> = mutableSetOf())
    val requestedIds = fragments.entries.groupBy({ it.value }, { it.key })
    val offsets = positions.entries.sortedBy { it.value }
    val output = mutableMapOf<String, String>()
    val stack = mutableListOf<Scope>()
    val stackPositions = mutableMapOf<String, MutableList<Int>>()
    val pending = mutableSetOf<String>()
    var offsetIndex = 0
    fun parent(): Scope? = stack.lastOrNull { it.tag.name in noteBlocks }
    fun store(scope: Scope, end: Int) {
        if (scope.keys.isEmpty() || end <= scope.tag.end || end - scope.tag.end > 256 * 1024) return
        val body = html.substring(scope.tag.end, end)
        scope.keys.forEach { output.putIfAbsent(it, body) }
    }
    sourceHtmlTags(html, checkActive).forEach { tag ->
        val keys = mutableSetOf<String>()
        while (offsetIndex < offsets.size && offsets[offsetIndex].value <= tag.start) {
            val target = offsets[offsetIndex++]
            if (target.value == tag.start && !tag.closing) keys += target.key
            else parent()?.keys?.add(target.key) ?: pending.add(target.key)
        }
        if (tag.closing) {
            val start = stackPositions[tag.name]?.lastOrNull() ?: return@forEach
            for (index in stack.lastIndex downTo start) {
                val scope = stack.removeAt(index)
                stackPositions[scope.tag.name]?.let { it.removeAt(it.lastIndex) }
                store(scope, tag.start)
            }
        } else {
            listOfNotNull(sourceHtmlAttribute(tag.attributes, "id"), sourceHtmlAttribute(tag.attributes, "name"))
                .forEach { requestedIds[it]?.let(keys::addAll) }
            if (tag.selfClosing || isHtmlAnchorMarker(tag)) {
                if (keys.isNotEmpty()) parent()?.keys?.addAll(keys) ?: pending.addAll(keys)
            } else {
                if (tag.name in noteBlocks && pending.isNotEmpty()) { keys += pending; pending.clear() }
                stackPositions.getOrPut(tag.name) { mutableListOf() } += stack.size
                stack += Scope(tag, keys)
            }
        }
    }
    stack.forEach { store(it, html.length) }
    return output
}

internal fun sourceLocalNoteTargets(html: String, references: List<SourceNoteReference>,
                                    checkActive: () -> Unit = {}): Map<String, String> {
    if (references.isEmpty()) return emptyMap()
    val fragments = references.mapNotNull { ref ->
        ref.href?.takeIf { !it.substringBefore('#').contains(':') && !it.startsWith("//") }
            ?.let(::sourceNoteFragment)?.let { ref.target to it }
    }.toMap()
    val local = sourceNoteTargets(html, fragments, checkActive = checkActive)
    val native = references.filter { it.filepos != null }.groupBy { it.label }
    val qualified = references.filter { it.href?.startsWith('#') == false && it.target in local }
    val blocks = if (native.isNotEmpty() || qualified.isNotEmpty()) sourceHtmlElements(html, noteBlocks, checkActive) else emptyList()
    val ids = mutableMapOf<String, Int>()
    if (qualified.isNotEmpty()) sourceHtmlTags(html, checkActive).filter { !it.closing }.forEach { tag ->
        listOfNotNull(sourceHtmlAttribute(tag.attributes, "id"), sourceHtmlAttribute(tag.attributes, "name"))
            .forEach { ids.putIfAbsent(it, tag.start) }
    }
    val targets = mutableMapOf<String, String>()
    references.forEach { reference ->
        local[reference.target]?.let { body ->
            // Old manifests did not store cross-document targets. A numbered return link is
            // sufficient evidence that a qualified href actually targets this chapter's note.
            val link = leadingNoteReturn(body, reference.label)
            val backHref = link?.let { sourceHtmlAttribute(it.tag.attributes, "href") }
            val backOffset = backHref?.let(::sourceNoteFragment)?.let(ids::get)
            val forwardBlock = if (backOffset == null) null else blocks.firstOrNull {
                it.tag.end <= reference.tag.start && reference.end <= it.contentEnd && backOffset in it.tag.start until it.end
            }
            val sameDocument = backHref?.substringBefore('#')?.let { it.isEmpty() || it == reference.href?.substringBefore('#') } == true
            if (reference.href?.startsWith('#') == true || forwardBlock != null && sameDocument)
                targets[reference.target] = body
        }
    }
    if (native.isNotEmpty()) blocks.forEach { block ->
        if (block.contentEnd - block.tag.end > 256 * 1024) return@forEach
        val body = html.substring(block.tag.end, block.contentEnd)
        val link = sourceHtmlElements(body, setOf("a"), checkActive).firstOrNull() ?: return@forEach
        val label = sourceHtmlPlainText(body.substring(link.tag.end, link.contentEnd), checkActive).trim()
        val back = sourceHtmlAttribute(link.tag.attributes, "filepos")?.toIntOrNull() ?: return@forEach
        val reference = native[label]?.lastOrNull { it.tag.start < block.tag.start && back < requireNotNull(it.filepos) }
            ?: return@forEach
        if (leadingNoteReturn(body, label) != null) targets.putIfAbsent(reference.target, body)
    }
    return targets
}

private fun leadingNoteReturn(html: String, label: String): SourceHtmlElement? =
    sourceHtmlElements(html, setOf("a")).firstOrNull()?.takeIf { link ->
        sourceHtmlPlainText(html.substring(0, link.tag.start)).isBlank() &&
            sourceHtmlPlainText(html.substring(link.tag.end, link.contentEnd)).trim() == label &&
            (sourceHtmlAttribute(link.tag.attributes, "href")?.contains('#') == true ||
                sourceHtmlAttribute(link.tag.attributes, "filepos") != null)
    }

internal fun removeNoteBacklinks(html: String, referenceLabel: String? = null): String {
    val links = sourceHtmlElements(html, setOf("a")).filter { link ->
        val attrs = link.tag.attributes
        val href = sourceHtmlAttribute(attrs, "href").orEmpty()
        val label = sourceHtmlPlainText(html.substring(link.tag.end, link.contentEnd)).trim()
        isNoteBacklink(attrs, href, label) || referenceLabel != null && label == referenceLabel &&
            (href.isNotEmpty() || sourceHtmlAttribute(attrs, "filepos") != null) &&
            sourceHtmlPlainText(html.substring(0, link.tag.start)).isBlank()
    }
    if (links.isEmpty()) return html
    return buildString {
        var cursor = 0
        links.forEach { append(html, cursor, it.tag.start); cursor = it.end }
        append(html, cursor, html.length)
    }
}
