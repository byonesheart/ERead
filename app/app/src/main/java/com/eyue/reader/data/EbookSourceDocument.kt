package com.eyue.reader.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

internal data class EbookSourceDocument(val key: String, val html: String, val baseUrl: String,
    val canonicalText: String, val noteTargets: Map<String, String> = emptyMap())

internal data class EbookSourceResource(val mimeType: String, val encoding: String? = null, val stream: InputStream)

/** Original DOM/resources are the display source. Canonical text remains the stable annotation index. */
internal class EbookSourceStore(private val context: Context, private val book: Book, private val preview: Boolean = false) : Closeable {
    private val mutex = Mutex()
    private val documents = linkedMapOf<Int, EbookSourceDocument>()
    private val deferredImages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var window: IntRange? = null
    private val streams = mutableSetOf<Closeable>()
    private var directory: File? = null
    @Volatile private var index: JSONObject? = null
    @Volatile private var closed = false
    private val source: String = (if (book.sourceLinked) book.sourceUri ?: book.sourcePath else book.sourcePath ?: book.sourceUri)
        ?: if (book.format == BookFormat.TXT) "" else throw IOException("原文件不存在，请重新选择这本书")
    private val bookKey = digest(book.id).take(24)
    private val origin = "https://eread.local/$bookKey/"
    private val displayRevision = digest(preview.toString() + ":" + book.chapters.joinToString("|") { "${it.textPath}:${it.lazySourcePath}" }).take(16)
    val continuousKey: String get() = "$bookKey:${book.contentVersion}:$displayRevision:continuous"
    val continuousUrl: String get() = origin + "continuous.html"
    private val sourceLock = Any()
    private val temporarySource = File(context.cacheDir, "document-sources/ebook-$bookKey-${UUID.randomUUID()}.tmp")

    private fun <T> sourceDocument(block: (File) -> T): T = synchronized(sourceLock) {
        check(!closed) { "原书阅读资源已经关闭" }
        try { withLocalDocument(context, source, temporarySource, block) }
        finally { if (closed) temporarySource.delete() }
    }

    suspend fun load(chapterIndex: Int, canonicalText: String): EbookSourceDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "原书阅读资源已经关闭" }
            require(chapterIndex in book.chapters.indices) { "原书章节位置无效" }
            val worker = currentCoroutineContext()
            worker.ensureActive()
            val chapter = book.chapters[chapterIndex]
            if (book.format == BookFormat.TXT) {
                synchronized(documents) { documents[chapterIndex] }?.takeIf { it.canonicalText == canonicalText }
                    ?.let { return@withLock it }
                val html = canonicalText.lineSequence().joinToString("\n",
                    "<html><head></head><body>", "</body></html>") { "<p>${SourceHtmlEdit.escape(it)}</p>" }
                return@withLock EbookSourceDocument("$bookKey:${book.contentVersion}:$chapterIndex:${digest(canonicalText)}",
                    html, origin + "archive/chapter-$chapterIndex.html", canonicalText).also { document ->
                    synchronized(documents) {
                        if (window?.contains(chapterIndex) != false) documents[chapterIndex] = document
                        while (documents.size > 3) documents.remove(documents.keys.first())
                    }
                }
            }
            val content = DeferredEbookContent.ensure(book.chapters[chapterIndex]) { worker.ensureActive() }
            content.inlineImages.forEach { image ->
                val file = File(image.path).canonicalFile
                if (book.format == BookFormat.MOBI && file.parentFile == File(requireNotNull(content.textPath)).canonicalFile.parentFile &&
                    file.name.startsWith("mobi-inline-")) deferredImages["images/" + file.name.removePrefix("mobi-inline-")] = file.path
            }
            val catalog = index ?: prepare { worker.ensureActive() }.also { index = it }
            synchronized(documents) { documents.remove(chapterIndex)?.also { documents[chapterIndex] = it } }?.takeIf { it.canonicalText == canonicalText }
                ?.let { return@withLock it }
            val rows = catalog.getJSONArray("chapters")
            val row = rows.getJSONObject(chapterIndex)
            val folder = requireNotNull(directory)
            val savedSource = row.optString("sourceManifest").takeIf(String::isNotEmpty)?.let {
                JSONObject(AtomicFile(File(it)).readFully().toString(Charsets.UTF_8))
            }
            if (row.optBoolean("dynamic") && !File(folder, row.getString("html")).isFile)
                prepareCustomMobiChapter(folder, chapterIndex, row, canonicalText) { worker.ensureActive() }
            if (row.optBoolean("deferred") && !File(folder, row.getString("html")).isFile) {
                val raw = File(folder, "mobi-expanded.html").readText()
                val resources = catalog.getJSONObject("resources")
                val html = ebookRewriteMobiResources(ebookHtmlFragment(raw, row.getInt("start"), row.getInt("end"))) { recIndex ->
                    resources.keys().asSequence().firstOrNull { it.substringAfterLast('/').substringBefore('.').toIntOrNull() == recIndex }
                        ?.let { origin + "archive/$it" }
                }
                write(AtomicFile(File(folder, row.getString("html"))), withCss(html, File(folder, "mobi-css.css").readText()))
            }
            var html = if (savedSource == null) File(folder, row.getString("html")).readText()
            else if (book.format == BookFormat.EPUB) savedSource.getString("html") else {
                val resources = JSONObject(catalog.getJSONObject("resources").toString()).apply { deferredImages.forEach { (path, file) -> put(path, file) } }
                val urls = resources.keys().asSequence().associateBy { it.substringAfterLast('/').substringBefore('.').toInt() }
                withCss(ebookRewriteMobiResources(savedSource.getString("html")) { recIndex ->
                    urls[recIndex]?.let { origin + "archive/$it" }
                }, savedSource.optString("cssPath").takeIf(String::isNotBlank)?.let { File(it).readText() } ?: savedSource.optString("css"))
            }
            val notes = if (savedSource == null) JSONObject(File(folder, row.getString("notes")).readText()).toStringMap()
                else if (book.format == BookFormat.EPUB) savedSource.optJSONObject("noteTargets")?.toStringMap().orEmpty()
                else mobiNoteAliases(savedSource.getString("html"), savedSource.optJSONObject("noteTargets")?.toStringMap().orEmpty()) { worker.ensureActive() }
            val original = SourceHtmlEdit.plain(html, { worker.ensureActive() }, ::sourceImage)
            if (original != canonicalText) {
                if (book.hasTextEdits) {
                    html = (if (preview) SourceHtmlEdit.preview(html, canonicalText, { worker.ensureActive() }, ::sourceImage)
                        else SourceHtmlEdit.replace(html, canonicalText, { worker.ensureActive() }, ::sourceImage)).html
                } else require(original.filterNot(Char::isWhitespace) == canonicalText.filterNot(Char::isWhitespace)) {
                    "原书正文与阅读记录不一致，请重新导入；阅读记录尚未修改"
                }
            }
            worker.ensureActive()
            check(!closed) { "原书阅读资源已经关闭" }
            val path = savedSource?.optString("archivePath")?.takeIf(String::isNotBlank) ?: row.getString("path")
            val document = EbookSourceDocument("$bookKey:${book.contentVersion}:$displayRevision:$chapterIndex:${digest(canonicalText)}", html,
                origin + "archive/" + EpubArchive.safePath(path).split('/').joinToString("/") { encodeSegment(it) }, canonicalText, notes)
            synchronized(documents) {
                if (window?.contains(chapterIndex) != false) documents[chapterIndex] = document
                while (documents.size > 3) documents.remove(documents.keys.first())
            }
            document
        }
    }

    /** Prefetching a neighbor must never recenter the visible chapter's retention window. */
    fun retainWindow(center: Int) = synchronized(documents) {
        window = (center - 1).coerceAtLeast(0)..(center + 1).coerceAtMost(book.chapters.lastIndex)
        documents.keys.removeAll { it !in requireNotNull(window) }
    }

    /** No caller-controlled local paths, providers or remote hosts can be opened here. */
    fun openResource(url: String): EbookSourceResource? {
        if (closed) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != "eread.local" || uri.rawUserInfo != null) return null
        val prefix = "/$bookKey/archive/"
        val path = uri.path?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let {
            runCatching { EpubArchive.safePath(it) }.getOrNull()
        } ?: return null
        if (preview && path.startsWith("preview/")) {
            val file = deferredImages[path]?.let(::File) ?: return null
            return response(path, file.inputStream())
        }
        val catalog = index ?: return null
        if (catalog.getString("format") == BookFormat.EPUB.name) {
            return sourceDocument { archive ->
                val zip = ZipFile(archive)
                val entry = zip.getEntry(path)
                if (entry == null || entry.isDirectory) { zip.close(); null }
                else try { response(path, zip.getInputStream(entry), zip) }
                    catch (error: Throwable) { zip.close(); throw error }
            }
        }
        val relative = deferredImages[path] ?: catalog.optJSONObject("resources")?.optString(path)?.takeIf(String::isNotBlank) ?: return null
        val borrowed = File(relative).takeIf { it.isAbsolute }
        val file = (borrowed ?: File(requireNotNull(directory), relative)).canonicalFile
        if (borrowed != null) require(deferredImages[path] == file.path || book.chapters.any { chapter -> chapter.inlineImages.any { File(it.path).canonicalFile == file } }) {
            "原书资源不属于这本书"
        } else require(file.toPath().startsWith(requireNotNull(directory).canonicalFile.toPath())) { "原书资源路径无效" }
        return response(path, file.inputStream())
    }

    override fun close() {
        closed = true
        synchronized(documents) { documents.clear() }
        val active = synchronized(streams) { streams.toList().also { streams.clear() } }
        active.forEach { runCatching { it.close() } }
        // Never wait for a provider copy/parser on the UI thread. An in-flight
        // sourceDocument also deletes on exit if the file is still in use here.
        temporarySource.delete()
    }

    private fun response(path: String, input: InputStream, owner: Closeable? = null): EbookSourceResource {
        val stream = object : FilterInputStream(input) {
            override fun close() {
                try { super.close() } finally { owner?.close(); synchronized(streams) { streams.remove(this) } }
            }
        }
        synchronized(streams) {
            if (closed) { stream.close(); throw IOException("原书阅读资源已经关闭") }
            streams += stream
        }
        return EbookSourceResource(mime(path), stream = stream)
    }

    private fun prepare(checkActive: () -> Unit): JSONObject {
        require(book.format in setOf(BookFormat.EPUB, BookFormat.MOBI)) { "此格式不使用原书排版" }
        if ((book.format == BookFormat.MOBI || book.format == BookFormat.EPUB && book.contentVersion >= 9) && book.chapters.all {
                it.lazySourcePath?.let { path -> File(path).isFile } == true }) {
            // New imports already saved the exact chapter DOM. Only the requested
            // chapter manifest is read; EPUB resources stay in the original archive.
            directory = File(context.cacheDir, "ebook-source/$bookKey").apply { mkdirs() }
            val resources = JSONObject()
            book.chapters.flatMap { it.inlineImages }.distinctBy { it.path }.forEach { image ->
                val file = File(image.path)
                val name = file.name.removePrefix("mobi-inline-")
                if (name.substringBefore('.').toIntOrNull() != null) resources.put("images/$name", file.absolutePath)
            }
            return JSONObject().put("format", book.format.name).put("resources", resources)
                .put("chapters", JSONArray(book.chapters.map {
                    JSONObject().put("sourceManifest", it.lazySourcePath).put("path", "book.html")
                }))
        }
        requireBookSourceAvailable(context, book)
        val identity = documentSourceIdentity(context, source) + ":source-dom-${if (book.format == BookFormat.MOBI) 2 else 1}:" + book.format + ":" +
            book.chapters.joinToString("|") { "${it.id}:${it.title}" }
        val folder = File(context.cacheDir, "ebook-source/$bookKey/${digest(identity)}").apply { mkdirs() }
        directory = folder
        val manifest = AtomicFile(File(folder, "index.json"))
        val cached = runCatching { JSONObject(manifest.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        if (cached?.optString("identity") == identity && cached.optJSONArray("chapters")?.length() == book.chapters.size &&
            (0 until book.chapters.size).all { i -> val row = cached.getJSONArray("chapters").getJSONObject(i)
                row.optBoolean("dynamic") || (row.optBoolean("deferred") && File(folder, "mobi-expanded.html").isFile ||
                    File(folder, row.getString("html")).isFile) && File(folder, row.getString("notes")).isFile }) return cached
        val result = sourceDocument { file ->
            if (book.format == BookFormat.EPUB) prepareEpub(file, folder, checkActive) else prepareMobi(file, folder, checkActive)
        }.put("identity", identity).put("format", book.format.name)
        checkActive()
        write(manifest, result.toString())
        return result
    }

    private fun prepareEpub(source: File, folder: File, checkActive: () -> Unit): JSONObject {
        val legacyIndex = LegacyEpubIndex.read(context, book)
        val parsed = legacyIndex?.snapshot(source, book, checkActive) ?: EpubArchive.readForChapters(source, book.chapters.size) { checkActive() }
        require(parsed.sections.size == book.chapters.size) { "原书目录与书架章节不一致，请重新导入后使用原书排版" }
        val rows = JSONArray()
        ZipFile(source).use { zip ->
            var previousPath: String? = null
            var raw = ""
            parsed.sections.forEachIndexed { index, section ->
                checkActive()
                if (previousPath != section.path) {
                    raw = zip.getInputStream(zip.getEntry(section.path)).use {
                        if (legacyIndex != null) decodeEpubMarkup(it.readBytes()) else decodeMarkup(it.readBytes())
                    }
                    previousPath = section.path
                }
                val html = ebookHtmlFragment(raw, section.sourceStart, section.sourceEnd)
                rows.put(save(folder, index, section.path, html, section.sourceNoteTargets))
            }
        }
        return JSONObject().put("chapters", rows)
    }

    private fun prepareMobi(source: File, folder: File, checkActive: () -> Unit): JSONObject {
        var raw = ""
        val parsed = MobiArchive.read(source, checkActive = checkActive, captureHtml = { raw = it })
        val resources = JSONObject()
        val existingImages = book.chapters.flatMap { it.inlineImages }.map { File(it.path) }.associateBy { it.name }
        parsed.images.forEach { image ->
            checkActive()
            val name = "images/${image.recIndex}.${image.extension}"
            val existing = existingImages["mobi-inline-${image.recIndex}.${image.extension}"]?.takeIf { it.isFile }
            if (existing != null) resources.put(name, existing.absolutePath) else {
                MobiArchive.copyImage(source, image, File(folder, name))
                resources.put(name, name)
            }
        }
        val filepositions = sourceHtmlTags(raw, checkActive).mapNotNull { tag ->
            if (tag.name == "a" && !tag.closing) sourceHtmlAttribute(tag.attributes, "filepos")?.toIntOrNull() else null
        }.distinct().sorted().toList()
        val positions = mobiCharacterPositions(raw, mobiEncoding(source), filepositions, checkActive)
        val insertions = positions.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        val expanded = buildString(raw.length + positions.size * 50) {
            var cursor = 0
            insertions.forEach { (at, references) ->
                append(raw, cursor, at)
                references.forEach { append("<a id='eread-filepos-$it'></a>") }
                cursor = at
            }
            append(raw, cursor, raw.length)
        }
        fun shifted(offset: Int): Int = offset + insertions.entries.takeWhile { it.key < offset }
            .sumOf { (_, refs) -> refs.sumOf { "<a id='eread-filepos-$it'></a>".length } }
        val rows = JSONArray()
        var nextSection = 0
        val matchedSections = book.chapters.mapNotNull { chapter ->
            val at = (nextSection until parsed.sections.size).firstOrNull { parsed.sections[it].title == chapter.title }
            at?.let { nextSection = it + 1; parsed.sections[it] }
        }
        if (matchedSections.size == book.chapters.size) {
            write(AtomicFile(File(folder, "mobi-expanded.html")), expanded)
            write(AtomicFile(File(folder, "mobi-css.css")), parsed.css)
            matchedSections.forEachIndexed { index, section ->
                checkActive()
                val notes = mobiNoteAliases(section.html, section.sourceNoteTargets, checkActive)
                val noteFile = "chapter-$index.notes.json"
                write(AtomicFile(File(folder, noteFile)), JSONObject(notes).toString())
                rows.put(JSONObject().put("html", "chapter-$index.html").put("notes", noteFile).put("path", "book.html")
                    .put("deferred", true).put("start", shifted(section.sourceStart)).put("end", shifted(section.sourceEnd)))
            }
        } else {
            // Custom rule chapters are mapped on first access, never by reading every
            // canonical chapter at book open. Only their original DOM slices persist.
            write(AtomicFile(File(folder, "mobi-full.html")), raw)
            write(AtomicFile(File(folder, "mobi-css.css")), parsed.css)
            write(AtomicFile(File(folder, "mobi-positions.json")), JSONObject(positions.mapKeys { it.key.toString() }).toString())
            write(AtomicFile(File(folder, "mobi-notes.json")), JSONObject(parsed.sections.flatMap { it.sourceNoteTargets.entries }
                .associate { it.key to it.value }).toString())
            book.chapters.forEachIndexed { index, _ ->
                checkActive()
                rows.put(JSONObject().put("html", "chapter-$index.html").put("notes", "chapter-$index.notes.json")
                    .put("path", "book.html").put("dynamic", true))
            }
        }
        return JSONObject().put("chapters", rows).put("resources", resources)
    }

    private fun prepareCustomMobiChapter(folder: File, chapterIndex: Int, row: JSONObject, text: String, checkActive: () -> Unit) {
        val raw = File(folder, "mobi-full.html").readText()
        val resources = requireNotNull(index).getJSONObject("resources")
        val imageUrls = resources.keys().asSequence().associate { path ->
            path.substringAfterLast('/').substringBefore('.').toInt() to (origin + "archive/$path")
        }
        val image: (String) -> String? = { attributes -> mobiImageIndex(attributes)?.let(imageUrls::get) }
        val canonical = SourceHtmlEdit.plain(raw, checkActive, image)
        val range = findCanonicalRange(canonical, text, 0, checkActive)
        val points = ebookSourceOffsets(raw, canonical, listOf(range.first, range.last + 1), image, checkActive)
        if (range.first == 0) points[0] = 0
        if (range.last + 1 == canonical.length) points[1] = raw.length
        val positions = JSONObject(File(folder, "mobi-positions.json").readText())
        val inside = positions.keys().asSequence().map { it.toInt() to positions.getInt(it) }
            .filter { it.second in points[0] until points[1] }.sortedBy { it.second }.toList()
        val insertions = inside.groupBy({ it.second }, { it.first }).toSortedMap()
        val expanded = buildString(raw.length + inside.size * 50) {
            var cursor = 0
            insertions.forEach { (at, references) ->
                append(raw, cursor, at)
                references.forEach { append("<a id='eread-filepos-$it'></a>") }
                cursor = at
            }
            append(raw, cursor, raw.length)
        }
        val end = points[1] + inside.sumOf { "<a id='eread-filepos-${it.first}'></a>".length }
        val fragment = ebookHtmlFragment(expanded, points[0], end)
        val html = ebookRewriteMobiResources(fragment, imageUrls::get)
        val allNotes = JSONObject(File(folder, "mobi-notes.json").readText()).toStringMap()
        val notes = sourceNoteReferences(fragment, checkActive).mapNotNull { reference ->
            allNotes[reference.target]?.let { reference.target to it }
        }.toMap()
        save(folder, chapterIndex, row.getString("path"), withCss(html, File(folder, "mobi-css.css").readText()),
            mobiNoteAliases(fragment, notes, checkActive))
    }

    private fun mobiNoteAliases(html: String, notes: Map<String, String>, checkActive: () -> Unit): Map<String, String> =
        notes.toMutableMap().apply {
            sourceNoteReferences(html, checkActive).forEach { reference ->
                reference.filepos?.let { position -> notes[reference.target]?.let { put("#eread-filepos-$position", it) } }
            }
        }

    private fun sourceImage(attributes: String): String? =
        sourceHtmlAttribute(attributes, "src") ?: sourceHtmlAttribute(attributes, "xlink:href") ?:
            sourceHtmlAttribute(attributes, "href") ?: mobiImageIndex(attributes)?.toString()

    private fun save(folder: File, index: Int, path: String, html: String, notes: Map<String, String>): JSONObject {
        val body = "chapter-$index.html"
        val noteFile = "chapter-$index.notes.json"
        write(AtomicFile(File(folder, body)), html)
        write(AtomicFile(File(folder, noteFile)), JSONObject(notes).toString())
        return JSONObject().put("html", body).put("notes", noteFile).put("path", path)
    }

    private fun withCss(html: String, css: String): String {
        if (css.isBlank()) return html
        val closing = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)?.range?.first
        val style = "<style>$css</style>"
        return if (closing != null) html.substring(0, closing) + style + html.substring(closing) else "<head>$style</head>$html"
    }

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
}

private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

private fun encodeSegment(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private fun write(file: AtomicFile, text: String) {
    file.baseFile.parentFile?.mkdirs()
    val output = file.startWrite()
    try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
    catch (error: Throwable) { file.failWrite(output); throw error }
}

private fun decodeMarkup(bytes: ByteArray): String {
    val prefix = bytes.take(500).toByteArray().toString(Charsets.ISO_8859_1)
    val encoding = Regex("(?:encoding\\s*=\\s*|charset\\s*=\\s*)[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        .find(prefix)?.groupValues?.get(1)
    return if (encoding != null) bytes.toString(Charset.forName(encoding)) else TextParser.decode(bytes)
}

private fun mobiEncoding(file: File): Charset = RandomAccessFile(file, "r").use { input ->
    input.seek(78)
    val first = input.readInt().toLong() and 0xffffffffL
    input.seek(first + 28)
    if (input.readInt() == 1252) Charset.forName("windows-1252") else Charsets.UTF_8
}

private fun mobiCharacterPositions(raw: String, charset: Charset, positions: List<Int>, checkActive: () -> Unit): Map<Int, Int> {
    if (charset != Charsets.UTF_8) return positions.filter { it in 0..raw.length }.associateWith { it }
    val result = mutableMapOf<Int, Int>()
    var bytes = 0
    var character = 0
    positions.forEach { wanted ->
        checkActive()
        while (bytes < wanted && character < raw.length) {
            val point = raw.codePointAt(character)
            bytes += when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
            character += Character.charCount(point)
        }
        if (bytes == wanted) result[wanted] = character
    }
    return result
}

private fun mobiImageIndex(attributes: String): Int? = sourceHtmlAttribute(attributes, "recindex")?.toIntOrNull()
    ?: (sourceHtmlAttribute(attributes, "src") ?: sourceHtmlAttribute(attributes, "xlink:href"))?.let {
        Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull(32)
    }

private fun findCanonicalRange(source: String, text: String, from: Int, checkActive: () -> Unit): IntRange {
    val exact = source.indexOf(text, from)
    if (exact >= 0) return exact until exact + text.length
    val wanted = text.filterNot(Char::isWhitespace)
    require(wanted.isNotEmpty()) { "此章节没有可映射的原书正文" }
    val prefix = IntArray(wanted.length)
    var matched = 0
    for (index in 1 until wanted.length) {
        while (matched > 0 && wanted[index] != wanted[matched]) matched = prefix[matched - 1]
        if (wanted[index] == wanted[matched]) matched++
        prefix[index] = matched
    }
    val recent = IntArray(wanted.length)
    var count = 0
    matched = 0
    for (index in from until source.length) {
        if (index % 4096 == 0) checkActive()
        val char = source[index]
        if (char.isWhitespace()) continue
        recent[count % recent.size] = index
        count++
        while (matched > 0 && char != wanted[matched]) matched = prefix[matched - 1]
        if (char == wanted[matched]) matched++
        if (matched == wanted.length) return recent[(count - wanted.length) % recent.size]..index
    }
    throw IllegalArgumentException("自定义目录与原书结构不一致，请使用原书目录重新导入")
}

/** Locate a small number of canonical boundaries without changing the document's tags. */
private fun ebookSourceOffsets(html: String, canonical: String, offsets: List<Int>, image: (String) -> String?,
                               checkActive: () -> Unit): MutableList<Int> {
    val visible = hideHtmlContent(maskHtmlComments(html), preserveOffsets = true, checkActive = checkActive)
    var boundaries = IntArray(1_024)
    var boundaryCount = 0
    fun boundary(point: Int) {
        if (boundaryCount == boundaries.size) boundaries = boundaries.copyOf(boundaries.size * 2)
        boundaries[boundaryCount++] = point
    }
    var cursor = 0
    fun append(until: Int) {
        if (until <= cursor || visible.substring(cursor, until) != html.substring(cursor, until)) return
        var at = cursor
        boundary(at)
        while (at < until) {
            val end = if (html[at] == '&') html.indexOf(';', at + 1).takeIf { it in at + 2..minOf(until - 1, at + 32) } else null
            at = end?.plus(1) ?: at + Character.charCount(html.codePointAt(at))
            boundary(at)
        }
    }
    sourceHtmlTags(html, checkActive).forEach { tag -> append(tag.start); cursor = tag.end }
    append(html.length)
    require(boundaryCount > 0) { "原书正文没有可定位的文字" }
    val token = "\uE000${java.util.UUID.randomUUID()}\uE001"
    val probes = mutableMapOf<Int, Int>()
    fun probe(index: Int): Int = probes.getOrPut(index) {
        checkActive()
        val point = boundaries[index]
        val (text, at) = HtmlChapterParser.probe(html.substring(0, point) + token + html.substring(point), token, checkActive, image)
        require(text == canonical) { "原书章节文字映射失败" }
        at
    }
    return offsets.map { offset ->
        var low = 0
        var high = boundaryCount - 1
        while (low < high) { val middle = (low + high) ushr 1; if (probe(middle) < offset) low = middle + 1 else high = middle }
        require(probe(low) == offset) { "无法精确定位原书章节" }
        boundaries[low]
    }.toMutableList()
}

private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "css" -> "text/css"; "html", "htm", "xhtml" -> "text/html"; "xml", "opf", "ncx" -> "application/xml"
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"; "woff" -> "font/woff"; "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> "application/octet-stream"
}
