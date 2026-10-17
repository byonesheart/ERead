package com.eyue.reader.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubSection(val title: String, val html: String, val path: String, val css: String = "",
                       val sourceStart: Int = 0, val sourceEnd: Int = html.length)
data class ParsedEpub(
    val title: String,
    val author: String,
    val sections: List<EpubSection>,
    val coverPath: String?,
    val imagePaths: List<String>
)

/** EPUB package/spine parsing is independent of Android, so real ZIP fixtures can be tested. */
object EpubArchive {
    private data class TocLabel(val title: String, val fragment: String)

    fun read(file: File, fallbackTitle: String = file.nameWithoutExtension, onProgress: (Float) -> Unit = {}): ParsedEpub = ZipFile(file).use { zip ->
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
        val labels = mutableMapOf<String, String>()
        val anchors = mutableMapOf<String, MutableList<TocLabel>>()
        fun addLabel(base: String, href: String, title: String) {
            val fragment = URLDecoder.decode(href.substringAfter('#', "").replace("+", "%2B"), "UTF-8")
            if (isNoteReference("", title) || isFootnoteTarget("id='$fragment'")) return
            val path = resolve(base, href)
            if (fragment.isNotBlank()) {
                val list = anchors.getOrPut(path) { mutableListOf() }
                if (list.none { it.fragment == fragment }) list += TocLabel(title, fragment)
            } else labels.putIfAbsent(path, title)
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
            nav?.descendants("a")?.forEach {
                val href = it.getAttribute("href")
                val attributes = "epub:type='${it.getAttributeNS("http://www.idpf.org/2007/ops", "type")} ${it.getAttribute("epub:type")}' role='${it.getAttribute("role")}'"
                if (href.isNotBlank() && !isNoteReference(attributes, it.textContent)) addLabel(navPath, href, it.textContent.trim())
            }
        }
        val spine = opf.descendants("spine").firstOrNull()
            ?: throw IllegalArgumentException("EPUB 缺少阅读顺序")
        val cssCache = mutableMapOf<String, String>()
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
        if (ncx != null) {
            val ncxPath = resolve(packagePath, ncx.getAttribute("href"))
            xml(ncxPath).descendants("navPoint").forEach { point ->
                val src = point.descendants("content").firstOrNull()?.getAttribute("src").orEmpty()
                val label = point.descendants("navLabel").firstOrNull()?.textContent?.trim().orEmpty()
                if (src.isNotBlank() && label.isNotBlank()) addLabel(ncxPath, src, label)
            }
        }
        val references = spine.descendants("itemref").filter { it.getAttribute("linear") != "no" }
        val sections = references
            .flatMapIndexed { index, ref ->
                onProgress(index.toFloat() / references.size.coerceAtLeast(1))
                val item = byId[ref.getAttribute("idref")] ?: return@flatMapIndexed emptyList()
                val path = resolve(packagePath, item.getAttribute("href"))
                val entry = entries[path] ?: throw IllegalArgumentException("EPUB 章节文件缺失：$path")
                val html = zip.getInputStream(entry).use { decodeMarkup(it.readBytes()) }
                val scanned = maskHtmlComments(html)
                val notes = SourceHtmlNotes(scanned)
                val bodyTag = sourceBodyTag(html)
                val linkedCss = Regex("<link\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(scanned).mapNotNull { link ->
                    val attributes = link.groupValues[1]
                    if (!attribute(attributes, "rel").orEmpty().split(Regex("\\s+")).any { it.equals("stylesheet", true) }) null
                    else attribute(attributes, "href")?.let { runCatching { resolve(path, it) }.getOrNull() }
                }.joinToString("\n") { stylesheet(it, mutableSetOf()) }
                val embeddedCss = Regex("<style\\b[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE).findAll(scanned)
                    .joinToString("\n") { it.groupValues[1] }
                val css = "$linkedCss\n$embeddedCss"
                val candidates = anchors[path].orEmpty().map { label ->
                    val expression = Regex("<[^>]+\\b(?:id|name)\\s*=\\s*[\"']${Regex.escape(label.fragment)}[\"'][^>]*>",
                        RegexOption.IGNORE_CASE)
                    label to expression.find(scanned)?.range?.first
                }.filter { (_, offset) -> offset == null || !notes.contains(offset) }
                val located = candidates.mapNotNull { (label, offset) -> offset?.let { it to label.title } }
                    .distinctBy { it.first }.sortedBy { it.first }
                if (located.size > 1) {
                    located.mapIndexed { anchorIndex, (_, label) ->
                        val start = if (anchorIndex == 0) 0 else located[anchorIndex].first
                        val end = located.getOrNull(anchorIndex + 1)?.first ?: html.length
                        EpubSection(label, sourceBodyFragment(html.substring(start, end), bodyTag), path, css, start, end)
                    }
                } else listOf(EpubSection((located.firstOrNull()?.second ?: labels[path] ?: candidates.firstOrNull()?.first?.title)
                    .orEmpty().ifBlank { "第 ${index + 1} 章" }, html, path, css))
            }
        require(sections.isNotEmpty()) { "EPUB 没有可阅读章节" }
        val coverId = metadata?.descendants("meta")?.firstOrNull { it.getAttribute("name") == "cover" }
            ?.getAttribute("content")
        val cover = manifest.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") }
            ?: byId[coverId]
        val images = manifest.filter { it.getAttribute("media-type").startsWith("image/") }
            .map { resolve(packagePath, it.getAttribute("href")) }.filter { entries.containsKey(it) }
        onProgress(1f)
        ParsedEpub(title, author, sections,
            cover?.let { resolve(packagePath, it.getAttribute("href")) }?.takeIf { entries.containsKey(it) }, images)
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
