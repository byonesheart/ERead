package com.eyue.reader.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Immutable chapter references are committed first; readable text/assets are committed on demand. */
internal object DeferredEbookContent {
    private val locks = Array(32) { Any() }
    private val imageLocks = Array(32) { Any() }
    private val lengthLocks = Array(32) { Any() }

    fun epub(source: File, parsed: ParsedEpub, directory: File): List<Chapter> = ZipFile(source).use { zip -> parsed.sections.mapIndexed { index, section ->
        create(directory, index, section.title, section.tocParentTitle, JSONObject().put("format", "EPUB")
            .put("source", source.absolutePath).put("path", section.path).put("anchor", section.sourceAnchor)
            .put("estimate", ((zip.getEntry(section.path)?.size ?: 0L) / 3 / parsed.sections.count { it.path == section.path }).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()))
    }.also { saveLengthHints(directory, it) } }

    fun mobi(source: File, parsed: ParsedMobi, directory: File): List<Chapter> {
        val resources = File(directory, "mobi-import-resources.json")
        stage(resources, JSONObject().put("source", source.absolutePath).put("images", JSONArray(parsed.images.map {
            JSONObject().put("index", it.recIndex).put("offset", it.offset).put("length", it.length).put("extension", it.extension)
        })).toString())
        val css = File(directory, "mobi-import.css")
        stage(css, parsed.css)
        return parsed.sections.mapIndexed { index, section ->
            val raw = File(directory, "chapter-$index.original.html")
            stage(raw, section.html)
            create(directory, index, section.title, section.tocParentTitle, JSONObject().put("format", "MOBI")
                .put("htmlPath", raw.absolutePath).put("cssPath", css.absolutePath)
                .put("resources", resources.absolutePath).put("noteTargets", JSONObject(section.sourceNoteTargets)).put("estimate", (section.html.length / 2).coerceAtLeast(1)))
        }.also { saveLengthHints(directory, it) }
    }

    private fun create(directory: File, index: Int, title: String, parent: String?, descriptor: JSONObject): Chapter {
        val manifest = File(directory, "chapter-$index.source.json")
        stage(File(manifest.path + ".deferred.json"), descriptor.put("index", index).toString())
        stage(manifest, JSONObject().put("version", 3).put("deferred", true).toString())
        return Chapter(title = title, textPath = File(directory, "chapter-$index.txt").absolutePath,
            lazySourcePath = manifest.absolutePath, tocParentTitle = parent)
    }

    fun pending(chapter: Chapter): Boolean = descriptor(chapter)?.isFile == true && cached(chapter) == null

    fun lengthHints(chapters: List<Chapter>): List<Int> {
        val folder = chapters.firstOrNull()?.lazySourcePath?.let { File(it).parentFile }
        val saved = folder?.let { runCatching { JSONObject(AtomicFile(File(it, "ebook-lengths.json")).readFully().toString(Charsets.UTF_8)) }.getOrNull() }
        val ids = saved?.optJSONArray("ids")
        val rows = saved?.optJSONArray("lengths")
        return chapters.mapIndexed { index, chapter -> chapter.characterCount.takeIf { it >= 0 }
            ?: if (ids?.optString(index) == chapter.id) rows?.optInt(index, 1024)?.coerceAtLeast(0) ?: 1024 else 1024 }
    }

    private fun saveLengthHints(directory: File, chapters: List<Chapter>) = stage(File(directory, "ebook-lengths.json"),
        JSONObject().put("ids", JSONArray(chapters.map { it.id })).put("lengths", JSONArray(chapters.map { chapter ->
            JSONObject(requireNotNull(descriptor(chapter)).readText()).optInt("estimate", 1024)
        })).toString())

    fun cached(chapter: Chapter): Chapter? {
        val path = chapter.lazySourcePath ?: return null
        val text = chapter.textPath?.let(::File) ?: return null
        val file = File(path + ".indexed.json")
        if (!file.isFile) return null
        val row = runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (row.optString("id") != chapter.id || row.optLong("bytes", -1) != text.length() ||
            row.optLong("modified", -1) != text.lastModified() || !text.isFile) return null
        return chapter.copy(characterCount = row.getInt("length"),
            inlineImages = row.getJSONArray("images").let { images -> (0 until images.length()).map { index ->
                images.getJSONObject(index).let { InlineImage(it.getInt("offset"), it.getString("path")) }
            } }, sourceFootnotes = row.getJSONArray("notes").let { notes -> (0 until notes.length()).map { index ->
                notes.getJSONObject(index).let { SourceFootnote(it.getInt("start"), it.getInt("end"), it.getString("label"), it.getString("content"),
                    it.optString("imagePath").takeIf(String::isNotBlank)) }
            } })
    }

    fun ensure(chapter: Chapter, check: () -> Unit = {}): Chapter {
        val descriptor = descriptor(chapter)?.takeIf { it.isFile } ?: return chapter
        check()
        synchronized(locks[(descriptor.path.hashCode() and Int.MAX_VALUE) % locks.size]) {
            check()
            cached(chapter)?.let { return it }
            val row = JSONObject(AtomicFile(descriptor).readFully().toString(Charsets.UTF_8))
            val directory = descriptor.parentFile!!
            val imagePaths = mutableMapOf<String, String>()
            val targets: Map<String, String>
            val html: String
            val css: String
            val archivePath: String?
            val parsed: ParsedTextChapter
            if (row.getString("format") == "EPUB") {
                val source = File(row.getString("source"))
                val path = row.getString("path")
                val sections = EpubArchive.read(source, onlyPaths = setOf(path), mergeLegacyToc = true, onProgress = { check() }).sections
                val anchor = row.optString("anchor").takeIf(String::isNotEmpty)
                val section = if (sections.size == 1) sections.single() else sections.singleOrNull { it.sourceAnchor == anchor }
                    ?: throw IllegalArgumentException("EPUB 目录位置无法定位：${chapter.title}")
                targets = section.sourceNoteTargets
                css = section.css
                archivePath = path
                ZipFile(source).use { zip ->
                    val entries = zip.entries().asSequence().associateBy { EpubArchive.safePath(it.name) }
                    val raw = zip.getInputStream(requireNotNull(entries[path])).use { decodeEpubMarkup(it.readBytes()) }
                    html = ebookHtmlFragment(raw, section.sourceStart, section.sourceEnd)
                    parsed = HtmlChapterParser.readPlainWithNotes(chapter.title, section.html, check, targets) { attributes ->
                        val reference = imageReference(attributes)
                        val image = reference?.let { runCatching { EpubArchive.resolve(path, it) }.getOrNull() }
                        val entry = image?.let(entries::get)
                        if (image == null || entry == null) null else imagePaths.getOrPut(attributes) {
                            val digest = MessageDigest.getInstance("SHA-256").digest(image.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                            val destination = File(directory, "epub-inline-$digest.${image.substringAfterLast('.', "img")}")
                            copyImage(destination, check) { output -> zip.getInputStream(entry).use { it.copyTo(output) } }
                            destination.absolutePath
                        }
                    }
                }
            } else {
                check()
                html = File(row.getString("htmlPath")).readText()
                css = File(row.getString("cssPath")).readText()
                archivePath = null
                targets = row.getJSONObject("noteTargets").let { notes -> notes.keys().asSequence().associateWith { notes.getString(it) } }
                val resources = JSONObject(File(row.getString("resources")).readText())
                val source = File(resources.getString("source"))
                val images = resources.getJSONArray("images").let { rows -> (0 until rows.length()).associate { at ->
                    val image = rows.getJSONObject(at)
                    image.getInt("index") to MobiImageResource(image.getInt("index"), image.getLong("offset"), image.getLong("length"), image.getString("extension"))
                } }
                parsed = HtmlChapterParser.readPlainWithNotes(chapter.title, html, check, targets) { attributes ->
                    val index = sourceHtmlAttribute(attributes, "recindex")?.toIntOrNull() ?: imageReference(attributes)?.let {
                        Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull(32)
                    }
                    val image = index?.let(images::get)
                    if (image == null) null else imagePaths.getOrPut(attributes) {
                        val destination = File(directory, "mobi-inline-${image.recIndex}.${image.extension}")
                        require(image.offset >= 0 && image.length >= 0 && image.offset + image.length <= source.length()) { "MOBI 图片记录越界" }
                        copyImage(destination, check) { output -> java.io.RandomAccessFile(source, "r").use { input ->
                            input.seek(image.offset)
                            var remaining = image.length
                            val buffer = ByteArray(32768)
                            while (remaining > 0) {
                                check()
                                val size = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, size); output.write(buffer, 0, size); remaining -= size
                            }
                        } }
                        destination.absolutePath
                    }
                }
            }
            check()
            // Once a text cache exists it may contain a saved user edit. Never replace it with the original.
            val textFile = File(requireNotNull(chapter.textPath))
            val existing = if (textFile.isFile) readExistingChapterText(chapter, check) else null
            val content = if (existing == null || existing == parsed.text) parsed else parsed.copy(text = existing,
                inlineImages = chapter.inlineImages, sourceFootnotes = chapter.sourceFootnotes)
            val prepared = saveLazyChapter(directory, row.getInt("index"), content, html, css, imagePaths, targets, archivePath)
                .copy(id = chapter.id, title = chapter.title, tocParentTitle = chapter.tocParentTitle)
            check()
            write(File(requireNotNull(chapter.lazySourcePath) + ".indexed.json"), JSONObject().put("id", chapter.id)
                .put("length", prepared.characterCount).put("bytes", textFile.length()).put("modified", textFile.lastModified())
                .put("images", JSONArray(prepared.inlineImages.map { JSONObject().put("offset", it.offset).put("path", it.path) }))
                .put("notes", JSONArray(prepared.sourceFootnotes.map { JSONObject().put("start", it.start).put("end", it.end)
                    .put("label", it.label).put("content", it.content).put("imagePath", it.imagePath) })).toString())
            val lengths = File(directory, "ebook-lengths.json")
            synchronized(lengthLocks[(lengths.path.hashCode() and Int.MAX_VALUE) % lengthLocks.size]) {
                val catalog = JSONObject(AtomicFile(lengths).readFully().toString(Charsets.UTF_8))
                val at = row.getInt("index")
                if (catalog.getJSONArray("ids").optString(at) == chapter.id) {
                    catalog.getJSONArray("lengths").put(at, prepared.characterCount)
                    write(lengths, catalog.toString())
                }
            }
            return prepared
        }
    }

    private fun descriptor(chapter: Chapter): File? = chapter.lazySourcePath?.let { File(it + ".deferred.json") }
    private fun imageReference(attributes: String): String? = sourceHtmlAttribute(attributes, "src")
        ?: sourceHtmlAttribute(attributes, "xlink:href") ?: sourceHtmlAttribute(attributes, "href")

    private fun copyImage(file: File, check: () -> Unit, copy: (java.io.OutputStream) -> Unit) {
        synchronized(imageLocks[(file.path.hashCode() and Int.MAX_VALUE) % imageLocks.size]) {
            if (file.isFile) return
            check()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { copy(output); check(); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
    }

    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private fun stage(file: File, text: String) = file.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}
