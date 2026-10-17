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
    if (sourcePath == null) return@withContext PreparedChapter(chapter, chapter.textPath?.let { File(it).readText() }.orEmpty())
    val lock = chapterContentLocks[(sourcePath.hashCode() and Int.MAX_VALUE) % chapterContentLocks.size]
    lock.withLock {
        val job = currentCoroutineContext()
        val textFile = File(requireNotNull(chapter.textPath))
        val text = textFile.readText()
        val textHash = chapterTextHash(textFile) { job.ensureActive() }
        val metadata = AtomicFile(File("$sourcePath.prepared.json"))
        val repository = LibraryRepository(context)
        val cached = runCatching {
            val json = JSONObject(metadata.readFully().toString(Charsets.UTF_8))
            if (json.optString("textSha256") == textHash) repository.readChapter(json) else null
        }.getOrNull()?.takeIf { it.id == chapter.id && it.textPath == chapter.textPath && it.title == chapter.title &&
            it.lazySourcePath == sourcePath && it.characterCount == text.length && it.inlineImages == chapter.inlineImages &&
            it.sourceStyles.all { style -> style.start >= 0 && style.end > style.start && style.end <= text.length } }
        if (cached != null) return@withLock PreparedChapter(cached, text)
        job.ensureActive()
        val manifest = JSONObject(AtomicFile(File(sourcePath)).readFully().toString(Charsets.UTF_8))
        val images = manifest.getJSONObject("images")
        val css = if (manifest.has("cssPath")) File(manifest.getString("cssPath")).readText() else manifest.optString("css")
        val styled = HtmlChapterParser.read(chapter.title, manifest.getString("html"), css,
            checkActive = { job.ensureActive() }) { attributes ->
            images.optString(attributes).takeIf { it.isNotEmpty() }
        }
        val styles = mapCanonicalStyles(styled.text, text, styled.sourceStyles)
        val prepared = chapter.copy(characterCount = text.length, sourceStyles = styles)
        job.ensureActive()
        writeChapterAtomic(metadata, repository.writeChapter(prepared).put("textSha256", textHash).toString())
        PreparedChapter(prepared, text)
    }
}

/** Used by imports after lightweight text/image extraction; no source styling is evaluated here. */
internal fun saveLazyChapter(directory: File, index: Int, parsed: ParsedTextChapter, html: String, css: String,
                             images: Map<String, String>): Chapter {
    val text = File(directory, "chapter-$index.txt")
    val manifest = File(directory, "chapter-$index.source.json")
    val cssBytes = css.toByteArray(Charsets.UTF_8)
    val cssHash = MessageDigest.getInstance("SHA-256").digest(cssBytes).joinToString("") { "%02x".format(it) }
    val cssFile = File(directory, "styles-$cssHash.css")
    if (!cssFile.isFile) writeChapterAtomic(AtomicFile(cssFile), css)
    writeChapterAtomic(AtomicFile(text), parsed.text)
    writeChapterAtomic(AtomicFile(manifest), JSONObject().put("version", 2).put("html", html).put("cssPath", cssFile.absolutePath)
        .put("images", JSONObject(images)).toString())
    return Chapter(title = parsed.title, textPath = text.absolutePath, inlineImages = parsed.inlineImages,
        characterCount = parsed.text.length, lazySourcePath = manifest.absolutePath)
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
    val boundaries = styles.flatMap { listOf(it.start, it.end) }.distinct().sorted()
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
    fun offset(value: Int): Int = mapped[boundaries.binarySearch(value)].coerceIn(0, canonical.length)
    return styles.mapNotNull { style ->
        val start = offset(style.start)
        val end = offset(style.end)
        if (end > start) style.copy(start = start, end = end) else null
    }
}
