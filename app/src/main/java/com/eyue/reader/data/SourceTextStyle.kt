package com.eyue.reader.data

import kotlin.math.abs

enum class SourceTextAlignment { START, CENTER, END, JUSTIFY }

/** UTF-16 [start, end) in the saved chapter. Later non-null properties override earlier ones.
 * Sizes and total first/rest-line indents are relative to the reader's body font size.
 * Source font families are always supplied by the reader. CSS spacing uses body-font em units. */
data class SourceTextStyle(
    val start: Int,
    val end: Int,
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val alignment: SourceTextAlignment? = null,
    val firstLineIndentEm: Float? = null,
    val restLineIndentEm: Float? = null,
    val paragraph: Boolean = false,
    val lineHeightEm: Float? = null,
    val letterSpacingEm: Float? = null,
    val spaceBeforeEm: Float? = null,
    val spaceAfterEm: Float? = null
)

fun Chapter.readerSourceStyles(useSourceCss: Boolean): List<SourceTextStyle> =
    if (useSourceCss && sourceCssStyles.isNotEmpty()) sourceCssStyles else sourceStyles

internal data class MarkedSourceHtml(val html: String, val styles: List<SourceTextStyle>,
                                     val sourceCssStyles: List<SourceTextStyle> = emptyList())

/** The common ebook subset: tags, class/id selectors, descendants, inline declarations.
 * It does not fetch resources or implement a browser layout engine. */
internal object SourceStyleParser {
    private data class Element(val tag: String, val id: String, val classes: Set<String>, val parent: Element?,
                               val properties: Properties, val declarations: Map<String, String>,
                               val opening: Int, val openingEnd: Int, var closing: Int, val order: Int,
                               val selectorKey: Int = 0)
    private data class SelectorKey(val parent: Int, val tag: String, val id: String, val classes: Set<String>)
    private data class Properties(val font: Float = 16f, val bold: Boolean = false, val italic: Boolean = false,
                                  val alignment: SourceTextAlignment = SourceTextAlignment.START,
                                  val firstIndent: Float = 0f, val margin: Float = 0f, val special: Boolean = false,
                                  val lineHeight: Float? = null, val lineFactor: Float? = null, val letterSpacing: Float? = null)
    private data class SelectorPart(val tag: String, val id: String?, val classes: Set<String>, val directParent: Boolean = false)
    private data class Rule(val parts: List<SelectorPart>, val declarations: Map<String, String>, val specificity: Int, val order: Int)
    private val blocks = setOf("p", "div", "blockquote", "center", "pre", "address", "dd", "dt", "li", "h1", "h2", "h3", "h4", "h5", "h6")
    private val headingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val inlineTags = setOf("span", "b", "strong", "i", "em", "cite", "big", "small", "font", "a", "code", "samp", "kbd")
    private val emptyTags = setOf("br", "hr", "img", "image", "link", "meta", "input", "source", "wbr", "area", "base", "embed", "param", "col")
    private val whitespace = Regex("\\s+")
    private val namedSelector = Regex("([.#])([\\w-]+)")
    private val tagSelector = Regex("(?:^|[\\s>])([a-zA-Z][\\w-]*)")
    private val cssComments = Regex("/\\*[\\s\\S]*?(?:\\*/|$)")
    private val cssImports = Regex("@import\\b[^;\r\n]*(?:;|(?=[\r\n])|$)", RegexOption.IGNORE_CASE)
    private val cssLength = Regex("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(px|pt|em|rem|%)?$", RegexOption.IGNORE_CASE)
    private val attributesPattern = Regex("(?:^|\\s)([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val relevantProperties = setOf("font-size", "font-weight", "font-style", "text-align", "text-indent", "margin-left", "padding-left")
    private var cachedSheet: String? = null
    private var cachedRules: List<Rule> = emptyList()

    fun mark(html: String, css: String, prefix: String, checkActive: () -> Unit = {}): MarkedSourceHtml =
        markContent(html, css, prefix, false, checkActive)

    fun markWithSourceCss(html: String, css: String, prefix: String, checkActive: () -> Unit = {}): MarkedSourceHtml =
        markContent(html, css, prefix, true, checkActive)

    private fun markContent(html: String, css: String, prefix: String, includeSourceCss: Boolean, checkActive: () -> Unit): MarkedSourceHtml {
        checkActive()
        val visibleHtml = removeHtmlComments(html)
        val sheet = css + "\n" + sourceHtmlElements(visibleHtml, setOf("style"), checkActive)
            .joinToString("\n") { visibleHtml.substring(it.tag.end, it.contentEnd) }
        val cleaned = hideHtmlContent(visibleHtml, preserveOffsets = false, checkActive = checkActive)
        val rules = rules(sheet, checkActive)
        val selectorIds = rules.flatMap { rule -> rule.parts.mapNotNull { it.id } }.toSet()
        val selectorClasses = rules.flatMap { rule -> rule.parts.flatMap { it.classes } }.toSet()
        val selectorKeys = mutableMapOf<SelectorKey, Int>()
        var nextSelectorKey = 1
        val matchedDeclarations = mutableMapOf<Int, Map<String, String>>()
        val elements = mutableListOf<Element>()
        val stack = mutableListOf<Element>()
        val positions = mutableMapOf<String, MutableList<Int>>()
        val ancestorNames = mutableMapOf<String, Int>()
        fun names(element: Element) = listOf("t:${element.tag}", "i:${element.id}") + element.classes.map { "c:$it" }
        fun closeFrom(index: Int, closing: Int) {
            for (cursor in stack.lastIndex downTo index) {
                val element = stack.removeAt(cursor)
                positions[element.tag]?.let { it.removeAt(it.lastIndex) }
                names(element).forEach { name ->
                    val count = ancestorNames.getValue(name) - 1
                    if (count == 0) ancestorNames.remove(name) else ancestorNames[name] = count
                }
                element.closing = closing
            }
        }
        val blockParents = mutableSetOf<Int>()
        var rootFont = 16f
        sourceHtmlTags(cleaned, checkActive).forEach { token ->
            val tag = token.name
            if (token.closing) {
                val index = positions[tag]?.lastOrNull() ?: -1
                if (index >= 0) closeFrom(index, token.start)
            } else if (tag !in emptyTags && !isHtmlAnchorMarker(token)) {
                // HTML permits omitted </p> tags; keep their ranges from swallowing the next paragraph.
                if (tag in blocks && stack.lastOrNull()?.tag == "p") closeFrom(stack.lastIndex, token.start)
                // Headings cannot contain another block. Malformed ebook headings must not nest to EOF.
                if (tag in blocks) {
                    val heading = headingTags.maxOf { positions[it]?.lastOrNull() ?: -1 }
                    if (heading >= 0) closeFrom(heading, token.start)
                }
                val attributes = token.attributes
                val values = attributesPattern.findAll(attributes).associate {
                    it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
                }
                val parent = stack.lastOrNull()
                if (tag in blocks) parent?.let { blockParents += it.order }
                val id = values["id"].orEmpty()
                val classes = values["class"].orEmpty().split(whitespace).filter { it.isNotBlank() }.toSet()
                // Integer ancestry identities avoid copying every ancestor name for each nested tag.
                // Both caches stay bounded; uncached identities remain unique (never alias declarations).
                val key = SelectorKey(parent?.selectorKey ?: 0, tag, if (id in selectorIds) id else "", classes.intersect(selectorClasses))
                val selectorKey = if (rules.isEmpty()) 0 else selectorKeys[key] ?: (nextSelectorKey++).also {
                    if (selectorKeys.size < 512) selectorKeys[key] = it
                }
                val probe = Element(tag, id, classes, parent, Properties(), emptyMap(), token.start,
                    token.end, cleaned.length, elements.size, selectorKey)
                val fromSheet = matchedDeclarations[selectorKey] ?: mutableMapOf<String, String>().apply {
                    rules.forEach { if (matches(probe, it.parts, ancestorNames, checkActive)) putAll(it.declarations) }
                }.also { if (matchedDeclarations.size < 512) matchedDeclarations[selectorKey] = it }
                val declared = fromSheet.toMutableMap()
                values["style"]?.let { declared.putAll(declarations(it)) }
                values["align"]?.let { declared.putIfAbsent("text-align", it) }
                if (tag == "font") values["size"]?.toIntOrNull()?.let {
                    declared.putIfAbsent("font-size", "${listOf(.625f, .8125f, 1f, 1.125f, 1.5f, 2f, 3f)[it.coerceIn(1, 7) - 1]}em")
                }
                val properties = properties(tag, parent?.properties ?: Properties(), declared, rootFont)
                val element = probe.copy(properties = properties, declarations = declared)
                if (tag == "body") rootFont = properties.font
                elements += element
                if (!token.selfClosing) {
                    positions.getOrPut(tag) { mutableListOf() } += stack.size
                    names(element).forEach { name -> ancestorNames[name] = (ancestorNames[name] ?: 0) + 1 }
                    stack += element
                }
            }
        }
        val body = elements.firstOrNull { it.tag == "body" }?.properties ?: Properties()
        // A common plain-p class is ordinary body formatting too (e.g. calibre's default paragraph class).
        val ordinary = elements.filter { it.tag == "p" && !it.properties.special && !it.properties.bold && !it.properties.italic &&
            it.properties.alignment in setOf(SourceTextAlignment.START, SourceTextAlignment.JUSTIFY) }
            .groupBy { it.properties.copy(lineHeight = null, lineFactor = null, letterSpacing = null) }
            .maxByOrNull { it.value.size }?.key ?: body
        val styles = mutableListOf<SourceTextStyle>()
        val cssStyles = mutableListOf<SourceTextStyle>()
        val insertions = mutableMapOf<Int, MutableList<Pair<Int, String>>>()
        elements.forEach { element ->
            if (element.order % 256 == 0) checkActive()
            val p = element.properties
            val paragraph = element.tag in blocks
            val ordinaryAlignment = element.tag == "p" && !p.special &&
                p.alignment in setOf(SourceTextAlignment.START, SourceTextAlignment.JUSTIFY) &&
                ordinary.alignment in setOf(SourceTextAlignment.START, SourceTextAlignment.JUSTIFY)
            val different = abs(p.font - ordinary.font) > .01f || p.bold != ordinary.bold || p.italic != ordinary.italic ||
                p.alignment != ordinary.alignment && !ordinaryAlignment || abs(p.firstIndent - ordinary.firstIndent) > .01f || abs(p.margin - ordinary.margin) > .01f
            val relevantDeclarations = element.declarations.keys.any { it in relevantProperties }
            val wrapper = element.tag == "div" && element.order in blockParents
            val style = if (paragraph && !wrapper && (p.special || different && (element.tag == "p" || relevantDeclarations))) SourceTextStyle(0, 0,
                fontScale = (p.font / ordinary.font).coerceIn(.25f, 4f), bold = p.bold, italic = p.italic, alignment = p.alignment,
                firstLineIndentEm = ((p.margin + p.firstIndent) / ordinary.font).coerceIn(-8f, 12f),
                restLineIndentEm = (p.margin / ordinary.font).coerceIn(-8f, 12f), paragraph = true)
            else if (element.tag in inlineTags) SourceTextStyle(0, 0,
                fontScale = if ("font-size" in element.declarations || element.tag in setOf("big", "small")) (p.font / ordinary.font).coerceIn(.25f, 4f) else null,
                bold = if ("font-weight" in element.declarations || element.tag in setOf("b", "strong")) p.bold else null,
                italic = if ("font-style" in element.declarations || element.tag in setOf("i", "em", "cite")) p.italic else null)
            else null
            if (style != null && (style.paragraph || style.fontScale != null || style.bold != null || style.italic != null)) {
                val index = styles.size
                styles += style
                insertions.getOrPut(element.openingEnd) { mutableListOf() } += element.order to "$prefix${index}B\uE001"
                insertions.getOrPut(element.closing) { mutableListOf() } += -element.order - 1 to "$prefix${index}E\uE001"
            }
            if (includeSourceCss && !wrapper && (paragraph || element.tag in inlineTags)) {
                fun space(side: String): Float? {
                    val margin = element.declarations["margin-$side"] ?: element.declarations["margin"]?.split(whitespace)?.let { values ->
                        when (side) { "top" -> values.firstOrNull(); else -> values.getOrNull(2) ?: values.firstOrNull() }
                    }
                    return length(margin, p.font, rootFont)?.div(ordinary.font)?.coerceIn(0f, 8f)
                }
                val full = if (paragraph) SourceTextStyle(0, 0, fontScale = (p.font / ordinary.font).coerceIn(.25f, 4f),
                    bold = p.bold, italic = p.italic, alignment = p.alignment,
                    firstLineIndentEm = ((p.margin + p.firstIndent) / ordinary.font).coerceIn(-8f, 12f),
                    restLineIndentEm = (p.margin / ordinary.font).coerceIn(-8f, 12f), paragraph = true,
                    lineHeightEm = (p.lineFactor?.times(p.font) ?: p.lineHeight)?.div(ordinary.font)?.coerceIn(.25f, 8f),
                    letterSpacingEm = p.letterSpacing?.div(ordinary.font)?.coerceIn(-.5f, 2f),
                    spaceBeforeEm = space("top"), spaceAfterEm = space("bottom"))
                else SourceTextStyle(0, 0,
                    fontScale = style?.fontScale, bold = style?.bold, italic = style?.italic,
                    letterSpacingEm = if ("letter-spacing" in element.declarations) p.letterSpacing?.div(ordinary.font)?.coerceIn(-.5f, 2f) else null)
                if (full.paragraph || full.fontScale != null || full.bold != null || full.italic != null || full.letterSpacingEm != null) {
                    val index = cssStyles.size
                    cssStyles += full
                    insertions.getOrPut(element.openingEnd) { mutableListOf() } += element.order to "${prefix}C${index}B\uE001"
                    insertions.getOrPut(element.closing) { mutableListOf() } += -element.order - 1 to "${prefix}C${index}E\uE001"
                }
            }
        }
        val marked = StringBuilder()
        var cursor = 0
        insertions.toSortedMap().forEach { (position, events) ->
            marked.append(cleaned, cursor, position)
            events.sortedBy { it.first }.forEach { marked.append(it.second) }
            cursor = position
        }
        marked.append(cleaned, cursor, cleaned.length)
        return MarkedSourceHtml(marked.toString(), styles, cssStyles)
    }

    private fun properties(tag: String, parent: Properties, declared: Map<String, String>, rootFont: Float): Properties {
        val heading = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
        val defaultScale = when { heading -> listOf(2f, 1.5f, 1.17f, 1f, .83f, .67f)[tag.last().digitToInt() - 1]
            tag == "big" -> 1.2f; tag == "small" -> .833f; else -> 1f }
        val font = length(declared["font-size"], parent.font, rootFont) ?: (parent.font * defaultScale)
        val bold = when (val weight = declared["font-weight"]?.lowercase()) {
            "normal", "lighter" -> false; "bold", "bolder" -> true
            else -> weight?.toIntOrNull()?.let { it >= 600 } ?: (parent.bold || heading || tag in setOf("strong", "b"))
        }
        val italic = when (declared["font-style"]?.lowercase()) {
            "normal" -> false; "italic", "oblique" -> true; else -> parent.italic || tag in setOf("em", "i", "cite", "address")
        }
        val align = alignment(declared["text-align"]) ?: if (tag == "center") SourceTextAlignment.CENTER else parent.alignment
        val margin = (length(declared["margin-left"], font, rootFont) ?: if (tag == "blockquote") font else 0f) +
            (length(declared["padding-left"], font, rootFont) ?: 0f)
        val height = declared["line-height"]?.lowercase()
        val factor = height?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        val absoluteHeight = if (height == "normal") null else length(height, font, rootFont)
        val letter = declared["letter-spacing"]
        return Properties(font.coerceIn(4f, 256f), bold, italic, align,
            length(declared["text-indent"], font, rootFont) ?: parent.firstIndent, parent.margin + margin,
            parent.special || heading || tag in setOf("blockquote", "center", "pre", "address", "dd", "dt"),
            lineHeight = if (height == null) parent.lineHeight else if (factor == null) absoluteHeight else null,
            lineFactor = if (height == null) parent.lineFactor else factor,
            letterSpacing = if (letter == null) parent.letterSpacing else if (letter == "normal") 0f else length(letter, font, rootFont))
    }

    private fun alignment(value: String?): SourceTextAlignment? = when (value?.lowercase()) {
        "left", "start" -> SourceTextAlignment.START; "center" -> SourceTextAlignment.CENTER
        "right", "end" -> SourceTextAlignment.END; "justify" -> SourceTextAlignment.JUSTIFY; else -> null
    }
    private fun length(value: String?, font: Float, rootFont: Float): Float? {
        if (value == null) return null
        val named = mapOf("xx-small" to .6f, "x-small" to .75f, "small" to .89f, "medium" to 1f, "large" to 1.2f,
            "x-large" to 1.5f, "xx-large" to 2f, "smaller" to .833f, "larger" to 1.2f)
        named[value.lowercase()]?.let { return font * it }
        val match = cssLength.matchEntire(value.trim()) ?: return null
        val number = match.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (match.groupValues[2].lowercase()) {
            "em" -> number * font; "rem" -> number * rootFont; "%" -> number / 100f * font
            "pt" -> number * 4f / 3f; else -> number
        }
    }
    private fun declarations(css: String): Map<String, String> = css.split(';').mapNotNull { declaration ->
        val name = declaration.substringBefore(':').trim().lowercase()
        val value = declaration.substringAfter(':', "").substringBefore("!important").trim()
        if (value.isBlank()) null else name to value
    }.toMap()
    @Synchronized
    private fun rules(css: String, checkActive: () -> Unit): List<Rule> {
        if (css == cachedSheet) return cachedRules
        val cleaned = css.replace(cssComments, "").replace(cssImports, "")
        val collected = mutableListOf<Rule>()
        var selectorStart = 0
        var opening = -1
        var order = 0
        var declarationSelector = 0
        cleaned.forEachIndexed { cursor, char ->
            if (cursor % 4096 == 0) checkActive()
            if (char == '{') {
                declarationSelector = selectorStart
                opening = cursor
                selectorStart = cursor + 1
            } else if (char == '}') {
                if (opening >= 0) {
                    val selectorText = cleaned.substring(declarationSelector, opening)
                    val declared = declarations(cleaned.substring(opening + 1, cursor))
                    selectorText.split(',').map { it.trim() }.filter { !it.startsWith('@') }.forEach { selector ->
                        val parts = selectorParts(selector)
                        if (parts.isNotEmpty()) collected += Rule(parts, declared, selector.count { it == '#' } * 100 +
                            selector.count { it == '.' } * 10 + tagSelector.findAll(selector).count(), order)
                    }
                    order++
                }
                opening = -1
                selectorStart = cursor + 1
            }
        }
        val parsed = collected.sortedWith(compareBy<Rule> { it.specificity }.thenBy { it.order })
        if (css.length <= 256 * 1024) { cachedSheet = css; cachedRules = parsed }
        return parsed
    }
    private fun selectorParts(selector: String): List<SelectorPart> {
        // Unsupported pseudo/attribute/sibling selectors are skipped rather than applied too widely.
        if (selector.any { it in ":[+~" }) return emptyList()
        var direct = false
        return selector.replace(">", " > ").trim().split(whitespace).filter { it.isNotBlank() }.mapNotNull { token ->
            if (token == ">") { direct = true; null } else {
                val names = namedSelector.findAll(token).toList()
                SelectorPart(token.takeWhile { it != '.' && it != '#' }.lowercase(),
                    names.firstOrNull { it.groupValues[1] == "#" }?.groupValues?.get(2),
                    names.filter { it.groupValues[1] == "." }.map { it.groupValues[2] }.toSet(), direct).also { direct = false }
            }
        }
    }
    private fun matches(element: Element, parts: List<SelectorPart>, ancestorNames: Map<String, Int>, checkActive: () -> Unit): Boolean {
        fun simple(node: Element, part: SelectorPart): Boolean =
            (part.tag.isEmpty() || part.tag == "*" || part.tag == node.tag) &&
                (part.id == null || part.id == node.id) && node.classes.containsAll(part.classes)
        var current: Element? = element
        var index = parts.lastIndex
        if (!simple(element, parts[index--])) return false
        // Fail absent ancestors immediately instead of walking a deep, unclosed tag stack for every rule.
        for (ancestor in 0..index) {
            val part = parts[ancestor]
            if (part.tag.isNotEmpty() && part.tag != "*" && "t:${part.tag}" !in ancestorNames ||
                part.id != null && "i:${part.id}" !in ancestorNames || part.classes.any { "c:$it" !in ancestorNames }) return false
        }
        var visited = 0
        while (index >= 0) {
            if (parts[index + 1].directParent) {
                current = current?.parent
                if (current == null || !simple(current, parts[index--])) return false
            } else {
                current = current?.parent
                while (current != null && !simple(current, parts[index])) {
                    if (++visited % 256 == 0) checkActive()
                    current = current.parent
                }
                if (current == null) return false
                index--
            }
        }
        return true
    }
}
