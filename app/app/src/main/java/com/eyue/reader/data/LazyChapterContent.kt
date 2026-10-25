package com.eyue.reader.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class PreparedChapter(val chapter: Chapter, val text: String)

private val chapterContentLocks = Array(32) { Mutex() }

/** Plain text is immutable; deferred CSS projection can never move notes or saved reading offsets. */
suspend fun loadChapterContent(context: Context, book: Book, chapterIndex: Int): PreparedChapter = withContext(Dispatchers.IO) {
    val chapter = book.chapters[chapterIndex]
    val sourcePath = chapter.lazySourcePath
    if (sourcePath == null) {
        val job = currentCoroutineContext()
        val text = readChapterText(book, chapterIndex) { job.ensureActive() }
        val prepared = chapterTextFootnotes(book.format, chapter, text) { job.ensureActive() }
        return@withContext PreparedChapter(prepared, text)
    }
    val lock = chapterContentLocks[(sourcePath.hashCode() and Int.MAX_VALUE) % chapterContentLocks.size]
    lock.withLock {
        val job = currentCoroutineContext()
        val text = readChapterText(chapter) { job.ensureActive() }
        val textFile = File(requireNotNull(chapter.textPath))
        val textHash = chapterTextHash(textFile) { job.ensureActive() }
        val metadata = AtomicFile(File("$sourcePath.prepared.json"))
        val repository = LibraryRepository(context)
        val cached = runCatching {
            val json = JSONObject(metadata.readFully().toString(Charsets.UTF_8))
            if (json.optString("textSha256") == textHash && json.optInt("sourceFootnotesVersion") == 3 && json.optInt("sourceCssVersion") == 1)
                repository.readChapter(json) else null
        }.getOrNull()?.takeIf { it.id == chapter.id && it.textPath == chapter.textPath && it.title == chapter.title &&
            it.lazySourcePath == sourcePath && it.characterCount == text.length && it.inlineImages == chapter.inlineImages &&
            it.sourceStyles.all { style -> style.start >= 0 && style.end > style.start && style.end <= text.length } &&
            it.sourceCssStyles.all { style -> style.start >= 0 && style.end > style.start && style.end <= text.length } &&
            it.sourceFootnotes.all { note -> note.start >= 0 && note.end >= note.start && note.end <= text.length &&
                (note.end > note.start || note.imagePath != null) } }
        if (cached != null) return@withLock PreparedChapter(chapterTextFootnotes(book.format, cached, text) { job.ensureActive() }, text)
        job.ensureActive()
        val manifest = JSONObject(AtomicFile(File(sourcePath)).readFully().toString(Charsets.UTF_8))
        val images = manifest.getJSONObject("images")
        val css = if (manifest.has("cssPath")) File(manifest.getString("cssPath")).readText() else manifest.optString("css")
        val targets = manifest.optJSONObject("noteTargets")
        val noteTargets = targets?.keys()?.asSequence()?.associateWith { targets.getString(it) }.orEmpty()
        val styled = HtmlChapterParser.readWithNotes(chapter.title, manifest.getString("html"), css,
            checkActive = { job.ensureActive() }, noteTargets = noteTargets) { attributes ->
            images.optString(attributes).takeIf { it.isNotEmpty() }
        }
        val styles = mapCanonicalStyles(styled.text, text, styled.sourceStyles)
        val prepared = chapterTextFootnotes(book.format, chapter.copy(characterCount = text.length, sourceStyles = styles,
            sourceCssStyles = mapCanonicalStyles(styled.text, text, styled.sourceCssStyles),
            sourceFootnotes = mapCanonicalFootnotes(styled.text, text, styled.sourceFootnotes)), text) { job.ensureActive() }
        job.ensureActive()
        writeChapterAtomic(metadata, repository.writeChapter(prepared).put("textSha256", textHash)
            .put("sourceFootnotesVersion", 3).put("sourceCssVersion", 1).toString())
        PreparedChapter(prepared, text)
    }
}

/** Some converted/restored ebooks retain [n] prose but no usable link metadata. */
private fun chapterTextFootnotes(format: BookFormat, chapter: Chapter, text: String,
                                checkActive: () -> Unit): Chapter = when {
    format == BookFormat.TXT -> chapter.copy(sourceFootnotes = txtSourceFootnotes(text, checkActive = checkActive))
    format in setOf(BookFormat.MOBI, BookFormat.EPUB) && chapter.sourceFootnotes.isEmpty() -> {
        val notes = txtSourceFootnotes(text, allowAttachedDefinition = true, checkActive = checkActive)
        if (notes.isEmpty()) chapter else chapter.copy(sourceFootnotes = notes)
    }
    else -> chapter
}

/** Used by imports after lightweight text/image extraction; no source styling is evaluated here. */
internal fun saveLazyChapter(directory: File, index: Int, parsed: ParsedTextChapter, html: String, css: String,
                             images: Map<String, String>, noteTargets: Map<String, String> = emptyMap(), archivePath: String? = null): Chapter {
    val text = File(directory, "chapter-$index.txt")
    val manifest = File(directory, "chapter-$index.source.json")
    val cssBytes = css.toByteArray(Charsets.UTF_8)
    val cssHash = MessageDigest.getInstance("SHA-256").digest(cssBytes).joinToString("") { "%02x".format(it) }
    val cssFile = File(directory, "styles-$cssHash.css")
    if (!cssFile.isFile) writeImportChapter(cssFile, css)
    writeImportChapter(text, parsed.text)
    writeImportChapter(manifest, JSONObject().put("version", 3).put("html", html).put("cssPath", cssFile.absolutePath)
        .put("images", JSONObject(images)).put("noteTargets", JSONObject(noteTargets)).put("archivePath", archivePath).toString())
    return Chapter(title = parsed.title, textPath = text.absolutePath, inlineImages = parsed.inlineImages,
        characterCount = parsed.text.length, lazySourcePath = manifest.absolutePath, tocParentTitle = parsed.tocParentTitle,
        sourceFootnotes = parsed.sourceFootnotes, sourceCssStyles = parsed.sourceCssStyles)
}

/** New import files are private until the book's atomic library commit; do not fsync every chapter. */
private fun writeImportChapter(file: File, text: String) {
    if (file.exists()) writeChapterAtomic(AtomicFile(file), text)
    else file.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}

private fun chapterTextHash(file: File, checkActive: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Backup stores fully prepared text/styles/assets, so restored books need no source manifests. */
internal suspend fun materializeCloudBook(context: Context, book: Book): Book = book.copy(chapters =
    book.chapters.mapIndexed { index, chapter ->
        if (chapter.lazySourcePath == null) chapter else loadChapterContent(context, book, index).chapter.copy(lazySourcePath = null)
    })

private fun writeChapterAtomic(file: AtomicFile, text: String) {
    val stream = file.startWrite()
    try {
        stream.write(text.toByteArray(Charsets.UTF_8))
        file.finishWrite(stream)
    } catch (error: Throwable) {
        file.failWrite(stream)
        throw error
    }
}

/** HTML style markers may preserve whitespace that the plain import pass collapses. */
internal fun mapCanonicalStyles(styled: String, canonical: String, styles: List<SourceTextStyle>): List<SourceTextStyle> {
    if (styled == canonical || styles.isEmpty()) return styles
    val offset = canonicalOffsetMapper(styled, canonical, styles.flatMap { listOf(it.start, it.end) })
    return styles.mapNotNull { style ->
        val start = offset(style.start)
        val end = offset(style.end)
        if (end > start) style.copy(start = start, end = end) else null
    }
}

internal fun mapCanonicalFootnotes(styled: String, canonical: String, notes: List<SourceFootnote>): List<SourceFootnote> {
    if (styled == canonical || notes.isEmpty()) return notes
    val offset = canonicalOffsetMapper(styled, canonical, notes.flatMap { listOf(it.start, it.end) })
    return notes.mapNotNull { note ->
        val start = offset(note.start)
        val end = offset(note.end)
        if (end > start || note.imagePath != null) note.copy(start = start, end = end) else null
    }
}

private fun canonicalOffsetMapper(styled: String, canonical: String, offsets: List<Int>): (Int) -> Int {
    val boundaries = offsets.distinct().sorted()
    val mapped = IntArray(boundaries.size)
    var boundary = 0
    var source = 0
    var target = 0
    fun record() {
        while (boundary < boundaries.size && boundaries[boundary] <= source) mapped[boundary++] = target
    }
    while (source < styled.length || target < canonical.length) {
        record()
        when {
            source < styled.length && target < canonical.length && styled[source] == canonical[target] -> { source++; target++ }
            source < styled.length && styled[source].isWhitespace() -> source++
            target < canonical.length && canonical[target].isWhitespace() -> target++
            else -> throw IllegalArgumentException("章节文字缓存与源格式不一致，请重新导入")
        }
    }
    record()
    return { value -> mapped[boundaries.binarySearch(value)].coerceIn(0, canonical.length) }
}
