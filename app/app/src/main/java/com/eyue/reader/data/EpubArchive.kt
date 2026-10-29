package com.eyue.reader.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubSection(val title: String, val html: String, val path: String, val css: String = "",
                       val sourceStart: Int = 0, val sourceEnd: Int = html.length, val tocParentTitle: String? = null,
                       val sourceNoteTargets: Map<String, String> = emptyMap(), val sourceAnchor: String? = null)
data class ParsedEpub(
    val title: String,
    val author: String,
    val sections: List<EpubSection>,
    val coverPath: String?,
    val imagePaths: List<String>,
    val navigation: List<EpubNavigationEntry> = emptyList()
)

data class EpubNavigationEntry(val id: String, val title: String, val path: String?, val fragment: String,
                               val parentId: String? = null)

/** EPUB package/spine parsing is independent of Android, so real ZIP fixtures can be tested. */
object EpubArchive {
    private data class TocLabel(val title: String, val fragment: String, val parentTitle: String? = null)

    fun read(file: File, fallbackTitle: String = file.nameWithoutExtension, catalogOnly: Boolean = false,
             onlyPaths: Set<String>? = null, mergeLegacyToc: Boolean = false, onDirectoryReady: () -> Unit = {},
             onProgress: (Float) -> Unit = {}): ParsedEpub = readArchive(file, fallbackTitle, catalogOnly, onlyPaths,
        mergeLegacyToc, onDirectoryReady, onProgress)

    /** The consumer finishes/stages a chapter before the next document is read. Only its index is retained. */
    internal fun visit(file: File, fallbackTitle: String, onDirectoryReady: () -> Unit, onProgress: (Float) -> Unit,
                       diagnostics: ImportDiagnostics? = null,
                       consume: (EpubSection, SourceHtmlScan?, String, Set<String>) -> Unit): ParsedEpub =
        readArchive(file, fallbackTitle, false, null, false, onDirectoryReady, onProgress, consume, diagnostics)

    private fun readArchive(file: File, fallbackTitle: String, catalogOnly: Boolean, onlyPaths: Set<String>?,
                            mergeLegacyToc: Boolean, onDirectoryReady: () -> Unit, onProgress: (Float) -> Unit,
                            consume: ((EpubSection, SourceHtmlScan?, String, Set<String>) -> Unit)? = null,
                            diagnostics: ImportDiagnostics? = null): ParsedEpub = ZipFile(file).use { zip ->
        val catalogStarted = System.nanoTime()
        onProgress(0f)
        val entries = zip.entries().asSequence().associateBy { safePath(it.name) }
        fun xml(path: String): Element = parseXml(zip.getInputStream(entries[path]
            ?: throw IllegalArgumentException("EPUB 缺少文件：$path")).use { it.readBytes() })
        val container = xml("META-INF/container.xml")
        val packagePath = safePath(container.descendants("rootfile").firstOrNull()
            ?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("EPUB 未声明内容文件"))
        val opf = xml(packagePath)
        val metadata = opf.descendants("metadata").firstOrNull()
        val title = metadata?.descendants("title")?.firstOrNull()?.textContent?.trim().orEmpty()
            .ifEmpty { fallbackTitle }
        val author = metadata?.descendants("creator")?.map { it.textContent.trim() }
            ?.filter { it.isNotEmpty() }?.joinToString("、").orEmpty()
        val manifest = opf.descendants("manifest").firstOrNull()?.descendants("item").orEmpty()
        val byId = manifest.associateBy { it.getAttribute("id") }
        val images = manifest.filter { it.getAttribute("media-type").startsWith("image/") }
            .map { resolve(packagePath, it.getAttribute("href")) }.filter { entries.containsKey(it) }
        val imagePaths = images.toSet()
        val labels = mutableMapOf<String, TocLabel>()
        val anchors = mutableMapOf<String, MutableList<TocLabel>>()
        val navigation = mutableListOf<EpubNavigationEntry>()
        fun navigationEntry(base: String, href: String, title: String, parentId: String?): String? {
            if (title.isBlank() || isNoteReference("", title)) return null
            val path = href.takeIf(String::isNotBlank)?.let { runCatching { resolve(base, it) }.getOrNull() }?.takeIf { it in entries }
            val fragment = URLDecoder.decode(href.substringAfter('#', "").replace("+", "%2B"), "UTF-8")
            if (isFootnoteTarget("id='$fragment'")) return null
            val id = "epub-toc-${navigation.size}"
            navigation += EpubNavigationEntry(id, title, path, fragment, parentId)
            return id
        }
        fun addLabel(base: String, href: String, title: String, parentTitle: String? = null) {
            val fragment = URLDecoder.decode(href.substringAfter('#', "").replace("+", "%2B"), "UTF-8")
            if (isNoteReference("", title) || isFootnoteTarget("id='$fragment'")) return
            val path = resolve(base, href)
            if (title.isBlank() || path !in entries) return
            if (fragment.isNotBlank()) {
                val list = anchors.getOrPut(path) { mutableListOf() }
                if (list.none { it.fragment == fragment }) list += TocLabel(title, fragment, parentTitle)
            } else labels.putIfAbsent(path, TocLabel(title, fragment, parentTitle))
        }
        val navItem = manifest.firstOrNull { it.getAttribute("properties").split(' ').contains("nav") }
        if (navItem != null) {
            val navPath = resolve(packagePath, navItem.getAttribute("href"))
            val navRoot = xml(navPath)
            val navs = navRoot.descendants("nav")
            val nav = navs.firstOrNull {
                it.getAttributeNS("http://www.idpf.org/2007/ops", "type").split(' ').contains("toc") ||
                    it.getAttribute("epub:type").split(' ').contains("toc")
            } ?: navs.firstOrNull {
                val types = (it.getAttributeNS("http://www.idpf.org/2007/ops", "type") + " " +
                    it.getAttribute("epub:type")).split(Regex("\\s+"))
                types.none { type -> type in setOf("footnotes", "endnotes", "page-list", "landmarks") } &&
                    it.getAttribute("role") !in setOf("doc-endnotes", "doc-footnote", "doc-endnote")
            }
            fun navAttributes(node: Element) = "epub:type='${node.getAttributeNS("http://www.idpf.org/2007/ops", "type")} ${node.getAttribute("epub:type")}' role='${node.getAttribute("role")}'"
            fun outline(node: Element, parentId: String?) {
                if (node.elementName() == "li") {
                    val label = node.childElements().firstOrNull { it.elementName() in setOf("a", "span") }
                    val id = label?.takeUnless { isNoteReference(navAttributes(it), it.textContent) }
                        ?.let { navigationEntry(navPath, it.getAttribute("href"), it.textContent.trim(), parentId) } ?: parentId
                    node.childElements().filter { it !== label }.forEach { outline(it, id) }
                } else if (node.elementName() == "a") {
                    if (!isNoteReference(navAttributes(node), node.textContent)) navigationEntry(navPath, node.getAttribute("href"), node.textContent.trim(), parentId)
                }
                else node.childElements().forEach { outline(it, parentId) }
            }
            nav?.let { outline(it, null) }
            nav?.descendants("a")?.forEach {
                val href = it.getAttribute("href")
                val attributes = navAttributes(it)
                val parentTitle = generateSequence(it.parentNode) { node -> node.parentNode }
                    .takeWhile { node -> node !== nav }.filterIsInstance<Element>()
                    .filter { node -> node.elementName() == "li" }.drop(1)
                    .mapNotNull { point -> point.childElements().firstOrNull { child -> child.elementName() in setOf("a", "span") }
                        ?.textContent?.trim()?.takeIf(String::isNotBlank) }.lastOrNull()
                if (href.isNotBlank() && !isNoteReference(attributes, it.textContent)) addLabel(navPath, href, it.textContent.trim(), parentTitle)
            }
        }
        val spine = opf.descendants("spine").firstOrNull()
            ?: throw IllegalArgumentException("EPUB 缺少阅读顺序")
        val cssCache = mutableMapOf<String, String>()
        val linkedStylesheets = mutableMapOf<String, String>()
        val chapterStylesheets = mutableMapOf<Pair<List<String>, String>, String>()
        fun stylesheet(path: String, visited: MutableSet<String>): String {
            if (!visited.add(path) || visited.size > 32) return ""
            val entry = entries[path] ?: return ""
            val css = cssCache.getOrPut(path) { zip.getInputStream(entry).use { decodeMarkup(it.readBytes()) } }
            val imports = Regex("@import\\s+(?:url\\(\\s*)?[\"']?([^\"'\\s;)]+)", RegexOption.IGNORE_CASE).findAll(css)
                .mapNotNull { runCatching { resolve(path, it.groupValues[1]) }.getOrNull() }
                .joinToString("\n") { stylesheet(it, visited) }
            return "$imports\n$css"
        }
        val ncx = byId[spine.getAttribute("toc")]
            ?: manifest.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" }
        if (ncx != null && (mergeLegacyToc || labels.isEmpty() && anchors.isEmpty())) {
            val ncxPath = resolve(packagePath, ncx.getAttribute("href"))
            val useNcxOutline = navigation.none { it.path != null }
            if (useNcxOutline) navigation.clear()
            val nodeIds = java.util.IdentityHashMap<Element, String>()
            xml(ncxPath).descendants("navPoint").forEach { point ->
                val src = point.descendants("content").firstOrNull()?.getAttribute("src").orEmpty()
                val label = point.descendants("navLabel").firstOrNull()?.textContent?.trim().orEmpty()
                if (useNcxOutline) {
                    val parentId = generateSequence(point.parentNode) { it.parentNode }.filterIsInstance<Element>()
                        .firstOrNull { it.elementName() == "navPoint" }?.let { nodeIds[it] }
                    navigationEntry(ncxPath, src, label, parentId)?.let { nodeIds[point] = it }
                }
                val parentTitle = generateSequence(point.parentNode) { it.parentNode }.filterIsInstance<Element>()
                    .filter { it.elementName() == "navPoint" }
                    .mapNotNull { parent -> parent.childElements().firstOrNull { it.elementName() == "navLabel" }
                        ?.textContent?.trim()?.takeIf(String::isNotBlank) }.lastOrNull()
                if (src.isNotBlank() && label.isNotBlank()) addLabel(ncxPath, src, label, parentTitle)
            }
        }
        val references = spine.descendants("itemref").filter { it.getAttribute("linear") != "no" }
        diagnostics?.elapsed("epub.catalog", catalogStarted)
        onDirectoryReady()
        val sourceDocuments = mutableMapOf<String, String>()
        val documentReferences = mutableMapOf<String, List<SourceNoteReference>>()
        fun document(path: String): String {
            val value = sourceDocuments.getOrPut(path) {
                val entry = entries[path] ?: throw IllegalArgumentException("EPUB 章节文件缺失：$path")
                diagnostics?.measure("epub.read") { zip.getInputStream(entry).use { decodeMarkup(it.readBytes()) } }
                    ?: zip.getInputStream(entry).use { decodeMarkup(it.readBytes()) }
            }
            if (consume != null) while (sourceDocuments.size > 2) sourceDocuments.remove(sourceDocuments.keys.first())
            diagnostics?.maximum("cachedSourceDocuments", sourceDocuments.size.toLong())
            return value
        }
        fun noteReferencesFor(section: EpubSection, refs: List<SourceNoteReference>) = refs
            .filter { it.tag.start in section.sourceStart until section.sourceEnd }.mapNotNull { reference ->
                val href = reference.href ?: return@mapNotNull null
                val fragment = sourceNoteFragment(href) ?: return@mapNotNull null
                val path = runCatching { if (href.substringBefore('#').isBlank()) section.path else resolve(section.path, href) }.getOrNull()
                    ?.takeIf { it in entries } ?: return@mapNotNull null
                Triple(reference.target, path, fragment)
            }
        val sections = references
            .flatMapIndexed { index, ref ->
                onProgress(index.toFloat() / references.size.coerceAtLeast(1))
                val item = byId[ref.getAttribute("idref")] ?: return@flatMapIndexed emptyList()
                val path = resolve(packagePath, item.getAttribute("href"))
                if (onlyPaths != null && path !in onlyPaths) return@flatMapIndexed emptyList()
                require(entries.containsKey(path)) { "EPUB 章节文件缺失：$path" }
                // Multiple TOC anchors in one document need that document's boundaries checked,
                // otherwise a missing/footnote anchor could duplicate an entire chapter later.
                if (catalogOnly && anchors[path].orEmpty().size <= 1) {
                    val targets = anchors[path].orEmpty()
                    val names = listOfNotNull(targets.firstOrNull() ?: labels[path])
                    return@flatMapIndexed (names.ifEmpty { listOf(TocLabel("第 ${index + 1} 章", "")) }).map { label ->
                        EpubSection(label.title, "", path, tocParentTitle = label.parentTitle,
                            sourceAnchor = label.fragment.takeIf(String::isNotBlank))
                    }
                }
                val html = document(path)
                val scanStarted = System.nanoTime()
                val scan = SourceHtmlScan(html) { onProgress(index.toFloat() / references.size.coerceAtLeast(1)) }
                diagnostics?.elapsed("epub.scan", scanStarted)
                diagnostics?.count("sourceDocuments")
                val linkedPaths = scan.tags.filter { it.name == "link" && !it.closing }.mapNotNull { link ->
                    val attributes = link.attributes
                    if (!attribute(attributes, "rel").orEmpty().split(Regex("\\s+")).any { it.equals("stylesheet", true) }) null
                    else attribute(attributes, "href")?.let { runCatching { resolve(path, it) }.getOrNull() }
                }
                val embeddedCss = scan.elements(setOf("style"), visible = false)
                    .joinToString("\n") { maskHtmlComments(html.substring(it.tag.end, it.contentEnd)) }
                val css = chapterStylesheets.getOrPut(linkedPaths to embeddedCss) {
                    val linkedCss = linkedPaths.joinToString("\n") { path -> linkedStylesheets.getOrPut(path) { stylesheet(path, mutableSetOf()) } }
                    "$linkedCss\n$embeddedCss"
                }
                val locations = mutableMapOf<String, Int>()
                if (anchors[path].orEmpty().isNotEmpty()) scan.visibleTags.filterNot { it.closing }.forEach { tag ->
                    listOfNotNull(sourceHtmlAttribute(tag.attributes, "id"), sourceHtmlAttribute(tag.attributes, "name"))
                        .forEach { locations.putIfAbsent(it.lowercase(), tag.start) }
                }
                val candidates = anchors[path].orEmpty().map { label ->
                    label to locations[label.fragment.lowercase()]
                }.filter { (_, offset) -> offset == null || !scan.notes.contains(offset) }
                val notesAt = System.nanoTime()
                val refs = scan.noteReferences
                diagnostics?.elapsed("epub.notes", notesAt)
                if (consume == null) documentReferences[path] = refs
                val located = candidates.mapNotNull { (label, offset) -> offset?.let { it to label } }
                    .distinctBy { it.first }.sortedBy { it.first }
                val parts = if (located.size > 1) {
                    located.mapIndexed { anchorIndex, (_, label) ->
                        val start = if (anchorIndex == 0) 0 else located[anchorIndex].first
                        val end = located.getOrNull(anchorIndex + 1)?.first ?: html.length
                        EpubSection(label.title, if (consume == null) scan.bodyFragment(start, end) else "", path, css, start, end, label.parentTitle,
                            sourceAnchor = label.fragment)
                    }
                } else {
                    val label = located.firstOrNull()?.second ?: labels[path] ?: candidates.firstOrNull()?.first
                    listOf(EpubSection(label?.title.orEmpty().ifBlank { "第 ${index + 1} 章" }, html, path, css,
                        tocParentTitle = label?.parentTitle, sourceAnchor = label?.fragment?.takeIf(String::isNotBlank)))
                }
                if (consume == null) parts else {
                    val refsByPart = parts.map { noteReferencesFor(it, refs) }
                    val targetsAt = System.nanoTime()
                    val targets = refsByPart.flatten().groupBy { it.second }.mapValues { (targetPath, refs) ->
                        sourceNoteTargets(document(targetPath), refs.associate { "#${it.third}" to it.third },
                            checkActive = { onProgress(index.toFloat() / references.size.coerceAtLeast(1)) },
                            tags = if (targetPath == path) scan.tags.asSequence() else null)
                    }
                    diagnostics?.elapsed("epub.notes", targetsAt)
                    parts.mapIndexed { partIndex, part ->
                        val notes = refsByPart[partIndex].mapNotNull { (key, targetPath, fragment) ->
                            targets[targetPath]?.get("#$fragment")?.let { key to it }
                        }.toMap()
                        val input = if (parts.size == 1) part else part.copy(html = scan.bodyFragment(part.sourceStart, part.sourceEnd))
                        consume(input.copy(sourceNoteTargets = notes), if (input.html === html) scan else null,
                            ebookHtmlFragment(html, part.sourceStart, part.sourceEnd, scan.tags), imagePaths)
                        part.copy(html = "", css = "", sourceNoteTargets = emptyMap())
                    }
                }
            }
        require(sections.isNotEmpty()) { "EPUB 没有可阅读章节" }
        val sectionReferences = sections.map { section -> noteReferencesFor(section, documentReferences[section.path].orEmpty()) }
        val noteTargets = sectionReferences.flatten().groupBy { it.second }.mapValues { (path, targets) ->
            sourceNoteTargets(document(path), targets.associate { "#${it.third}" to it.third })
        }
        val notedSections = sections.mapIndexed { index, section -> section.copy(sourceNoteTargets = sectionReferences[index].mapNotNull { (key, path, fragment) ->
            noteTargets[path]?.get("#$fragment")?.let { key to it }
        }.toMap()) }
        val coverId = metadata?.descendants("meta")?.firstOrNull { it.getAttribute("name") == "cover" }
            ?.getAttribute("content")
        val cover = manifest.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") }
            ?: byId[coverId]
        onProgress(1f)
        ParsedEpub(title, author, notedSections,
            cover?.let { resolve(packagePath, it.getAttribute("href")) }?.takeIf { entries.containsKey(it) }, images, navigation)
    }

    /** Existing books may have retained extra NCX anchors; never move their saved offsets. */
    internal fun readForChapters(file: File, chapterCount: Int, catalogOnly: Boolean = false, onProgress: (Float) -> Unit = {}): ParsedEpub {
        val parsed = read(file, catalogOnly = catalogOnly, onProgress = onProgress)
        if (parsed.sections.size == chapterCount) return parsed
        val legacy = read(file, catalogOnly = catalogOnly, mergeLegacyToc = true, onProgress = { onProgress(1f) })
        return legacy.takeIf { it.sections.size == chapterCount } ?: parsed
    }

    fun copyEntry(archive: File, path: String, destination: File) {
        ZipFile(archive).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { safePath(it.name) == safePath(path) }
                ?: throw IllegalArgumentException("EPUB 图片缺失")
            destination.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }

    fun resolve(base: String, reference: String): String {
        val href = URLDecoder.decode(reference.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        require(!href.startsWith('/') && !href.contains(':')) { "EPUB 包含外部或绝对资源路径" }
        val segments = mutableListOf<String>()
        val path = base.substringBeforeLast('/', "").let { if (it.isBlank()) href else "$it/$href" }
        path.replace('\\', '/').split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { require(segments.isNotEmpty()) { "EPUB 资源路径越界" }; segments.removeAt(segments.lastIndex) }
                else -> segments += it
            }
        }
        return safePath(segments.joinToString("/"))
    }

    fun safePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith('/') && !normalized.contains(':') && !normalized.contains('\u0000')) {
            "EPUB 包含不安全路径"
        }
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "EPUB 包含越界路径" }
        return parts.joinToString("/")
    }

    private fun decodeMarkup(bytes: ByteArray): String {
        val prefix = bytes.take(500).toByteArray().toString(Charsets.ISO_8859_1)
        val encoding = Regex("(?:encoding\\s*=\\s*|charset\\s*=\\s*)[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(prefix)?.groupValues?.get(1)
        return if (encoding != null) bytes.toString(java.nio.charset.Charset.forName(encoding)) else TextParser.decode(bytes)
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
            .find(attributes)?.let { it.groupValues.drop(1).firstOrNull { value -> value.isNotEmpty() }.orEmpty() }

    private fun parseXml(bytes: ByteArray): Element {
        val probe = decodeMarkup(bytes).uppercase()
        require(!probe.contains("<!ENTITY")) { "EPUB XML 不允许自定义实体" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return builder.parse(bytes.inputStream()).documentElement
    }

    private fun Element.elementName() = localName ?: tagName.substringAfter(':')

    private fun Element.childElements(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }

    private fun Element.descendants(name: String): List<Element> {
        val output = mutableListOf<Element>()
        fun visit(node: Node) {
            for (index in 0 until node.childNodes.length) {
                val child = node.childNodes.item(index)
                if (child is Element) {
                    if ((child.localName ?: child.tagName.substringAfter(':')) == name) output += child
                    visit(child)
                }
            }
        }
        visit(this)
        return output
    }
}
