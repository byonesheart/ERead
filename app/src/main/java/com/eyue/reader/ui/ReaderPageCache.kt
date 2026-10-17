package com.eyue.reader.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AtomicFile
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.Book
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ReaderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

private const val CACHE_MAGIC = 0x45525043
private const val CACHE_FORMAT = 1
// Bump when page boundaries or inline-image placement rules change.
private const val PAGINATION_ALGORITHM_VERSION = 8
private const val MAX_CACHE_PAGES = 200_000
private const val MAX_CACHE_BYTES = 4L * 1024 * 1024
private const val MAX_BOOK_LAYOUTS = 3
private const val MAX_CHAPTER_LAYOUTS = 3
private val cacheIo = Mutex()

internal suspend fun readerCachedPages(
    context: Context, book: Book, settings: ReaderSettings, density: Density,
    direction: LayoutDirection, width: Int, height: Int, chapterTexts: List<String>,
    imageIdentities: List<List<String>> = book.chapters.map { chapter ->
        chapter.inlineImages.map { "${it.offset}:${it.path}" }
    },
    paginate: suspend () -> List<List<ReaderTextPage>>
): List<List<ReaderTextPage>> {
    val key = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        readerLayoutCacheKey(book, settings, density, direction, width, height, chapterTexts, imageIdentities,
            readerLayoutPlatform(context, settings),
            checkActive = { job.ensureActive() })
    }
    val offsets = imageIdentities.map { images -> images.map { it.substringBefore(':').toIntOrNull() ?: -1 } }
    val cached = withContext(Dispatchers.IO) {
        cacheIo.withLock { readCache(context, book.id, key, chapterTexts, offsets) }
    }
    if (cached != null) return cached
    val result = paginate()
    currentCoroutineContext().ensureActive()
    withContext(Dispatchers.IO) {
        if (validCachedPages(result, chapterTexts, offsets)) {
            cacheIo.withLock { writeCache(context, book.id, key, result) }
        }
    }
    currentCoroutineContext().ensureActive()
    return result
}

/** A chapter layout never reads or hashes any other chapter, including on a disk-cache hit. */
internal suspend fun readerCachedChapterPages(
    context: Context, book: Book, chapterIndex: Int, settings: ReaderSettings, density: Density,
    direction: LayoutDirection, width: Int, height: Int, chapterText: String,
    chapter: Chapter = book.chapters[chapterIndex], paginate: suspend () -> List<ReaderTextPage>
): List<ReaderTextPage> {
    val texts = listOf(chapterText)
    val images = listOf(chapter.inlineImages.map { "${it.offset}:${it.path}" })
    val offsets = listOf(chapter.inlineImages.map { it.offset })
    val key = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        readerLayoutCacheKey(book.copy(chapters = listOf(chapter)), settings, density, direction,
            width, height, texts, images, readerLayoutPlatform(context, settings),
            checkActive = { job.ensureActive() })
    }
    val prefix = "${cacheBookPrefix(book.id)}_${cacheBookPrefix(chapter.id)}_"
    val file = AtomicFile(File(context.cacheDir, "reader-chapter-page-cache/$prefix$key.bin"))
    val cached = withContext(Dispatchers.IO) {
        cacheIo.withLock { readCache(file, key, texts, offsets) }
    }
    if (cached != null) return cached.single()
    val result = paginate()
    currentCoroutineContext().ensureActive()
    withContext(Dispatchers.IO) {
        val pages = listOf(result)
        if (validCachedPages(pages, texts, offsets)) {
            cacheIo.withLock { writeCache(file, key, pages, prefix, MAX_CHAPTER_LAYOUTS) }
        }
    }
    currentCoroutineContext().ensureActive()
    return result
}

private fun readerLayoutPlatform(context: Context, settings: ReaderSettings): String {
    val configuration = context.resources.configuration
    val systemFont = if (settings.fontPath == null) {
        val family = when (settings.fontName) {
            "系统宋体" -> Typeface.SERIF
            "系统黑体" -> Typeface.SANS_SERIF
            "等宽字体" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        val paint = Paint().apply {
            typeface = Typeface.create(family, if (settings.bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = 20f
        }
        val metrics = paint.fontMetrics
        "${metrics.ascent.toBits()}:${metrics.descent.toBits()}:${metrics.leading.toBits()}:" +
            paint.measureText("山水故事阅读 ABCabc 0123456789").toBits()
    } else ""
    return "${Build.FINGERPRINT}:${Build.VERSION.SDK_INT}:${configuration.locales.toLanguageTags()}:" +
        (if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment else 0) + ":$systemFont"
}

/** Hash text in bounded UTF-16 chunks rather than allocating an entire encoded novel. */
internal fun readerLayoutCacheKey(
    book: Book, settings: ReaderSettings, density: Density, direction: LayoutDirection,
    width: Int, height: Int, chapterTexts: List<String>, imageIdentities: List<List<String>>,
    platform: String = "", checkActive: () -> Unit = {}
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(4096)
    fun integer(value: Int) { digest.update(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())) }
    fun text(value: String) {
        integer(value.length)
        var offset = 0
        while (offset < value.length) {
            checkActive()
            val count = minOf(buffer.size / 2, value.length - offset)
            repeat(count) { index ->
                val character = value[offset + index].code
                buffer[index * 2] = (character ushr 8).toByte()
                buffer[index * 2 + 1] = character.toByte()
            }
            digest.update(buffer, 0, count * 2)
            offset += count
        }
    }
    fun file(path: String?) {
        text(path.orEmpty())
        val source = path?.let(::File)
        text((source?.length() ?: -1).toString())
        text((source?.lastModified() ?: -1).toString())
    }
    integer(PAGINATION_ALGORITHM_VERSION)
    text(platform)
    integer(width); integer(height)
    integer(density.density.toBits()); integer(density.fontScale.toBits())
    text(direction.name)
    text(settings.fontName); file(settings.fontPath); text(settings.chineseConversion.name)
    integer(settings.fontSize.toBits()); integer(if (settings.bold) 1 else 0)
    integer(settings.lineHeight.toBits()); integer(settings.letterSpacing.toBits()); integer(settings.paragraphSpacing.toBits())
    integer(chapterTexts.size)
    chapterTexts.forEachIndexed { index, content ->
        file(book.chapters.getOrNull(index)?.textPath)
        text(content)
        val images = imageIdentities.getOrNull(index).orEmpty()
        integer(images.size)
        images.forEach { identity -> text(identity.substringBefore(':')); file(identity.substringAfter(':', "")) }
        val styles = book.chapters.getOrNull(index)?.sourceStyles.orEmpty()
        integer(styles.size)
        styles.forEach { source ->
            checkActive()
            integer(source.start); integer(source.end)
            text(source.fontScale?.toBits()?.toString().orEmpty())
            integer(source.bold?.let { if (it) 1 else 0 } ?: -1)
            integer(source.italic?.let { if (it) 1 else 0 } ?: -1)
            text(source.alignment?.name.orEmpty())
            text(source.firstLineIndentEm?.toBits()?.toString().orEmpty())
            text(source.restLineIndentEm?.toBits()?.toString().orEmpty())
            integer(if (source.paragraph) 1 else 0)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun cacheBookPrefix(bookId: String): String = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun cacheFile(context: Context, bookId: String, key: String): AtomicFile =
    AtomicFile(File(context.cacheDir, "reader-page-cache/${cacheBookPrefix(bookId)}_$key.bin"))

internal suspend fun removeReaderPageCaches(context: Context, bookId: String) = cacheIo.withLock {
    val prefix = cacheBookPrefix(bookId)
    AtomicFile(File(context.cacheDir, "reader-chapter-lengths/$prefix.json")).delete()
    listOf("reader-page-cache", "reader-chapter-page-cache").forEach { directory ->
        File(context.cacheDir, directory).listFiles().orEmpty().filter {
            it.name == "$prefix.bin" || it.name.startsWith("${prefix}_") && it.name.endsWith(".bin")
        }.forEach { AtomicFile(it).delete() }
    }
}

private fun readCache(context: Context, bookId: String, key: String, texts: List<String>, images: List<List<Int>>): List<List<ReaderTextPage>>? =
    readCache(cacheFile(context, bookId, key), key, texts, images)

private fun readCache(file: AtomicFile, key: String, texts: List<String>, images: List<List<Int>>): List<List<ReaderTextPage>>? =
    runCatching {
        file.openRead().use { raw ->
            if (raw.channel.size() > MAX_CACHE_BYTES) return@runCatching null
            val input = DataInputStream(BufferedInputStream(raw))
            if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_FORMAT || input.readUTF() != key) return@runCatching null
            val chapters = input.readInt()
            if (chapters != texts.size || chapters > MAX_CACHE_PAGES) return@runCatching null
            val pages = ArrayList<List<ReaderTextPage>>(chapters)
            var total = 0
            repeat(chapters) {
                val count = input.readInt()
                if (count <= 0 || count > MAX_CACHE_PAGES - total) return@runCatching null
                total += count
                pages += List(count) { ReaderTextPage(input.readInt(), input.readInt(), input.readInt()) }
            }
            if (input.read() != -1 || !validCachedPages(pages, texts, images)) null else {
                file.baseFile.setLastModified(System.currentTimeMillis())
                pages
            }
        }
    }.getOrNull()

private suspend fun writeCache(context: Context, bookId: String, key: String, pages: List<List<ReaderTextPage>>) {
    val file = cacheFile(context, bookId, key)
    writeCache(file, key, pages, "${cacheBookPrefix(bookId)}_", MAX_BOOK_LAYOUTS)
    AtomicFile(File(file.baseFile.parentFile, "${cacheBookPrefix(bookId)}.bin")).delete()
}

private suspend fun writeCache(file: AtomicFile, key: String, pages: List<List<ReaderTextPage>>,
                               prefix: String, maxLayouts: Int) {
    if (pages.size > MAX_CACHE_PAGES || pages.sumOf { it.size.toLong() } > MAX_CACHE_PAGES) return
    var output: java.io.FileOutputStream? = null
    try {
        currentCoroutineContext().ensureActive()
        file.baseFile.parentFile?.mkdirs()
        output = file.startWrite()
        val data = DataOutputStream(BufferedOutputStream(output))
        data.writeInt(CACHE_MAGIC); data.writeInt(CACHE_FORMAT); data.writeUTF(key); data.writeInt(pages.size)
        pages.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            data.writeInt(chapter.size)
            chapter.forEach { page -> data.writeInt(page.start); data.writeInt(page.end); data.writeInt(page.imageIndex) }
        }
        data.flush()
        currentCoroutineContext().ensureActive()
        file.finishWrite(output)
        file.baseFile.parentFile?.listFiles().orEmpty().filter {
            it != file.baseFile && it.name.startsWith(prefix) && it.name.endsWith(".bin")
        }.sortedByDescending { it.lastModified() }.drop(maxLayouts - 1).forEach { AtomicFile(it).delete() }
    } catch (error: Exception) {
        output?.let(file::failWrite)
        if (error is CancellationException) throw error
        // A cache failure must not prevent reading the newly computed pages.
    }
}

private fun validCachedPages(pages: List<List<ReaderTextPage>>, texts: List<String>, images: List<List<Int>>): Boolean {
    if (pages.size != texts.size || pages.size != images.size) return false
    return pages.indices.all { index ->
        val chapter = pages[index]
        val offsets = images[index]
        var cursor = 0
        var image = 0
        chapter.isNotEmpty() && chapter.all { page ->
            if (page.imageIndex >= 0) {
                val expected = offsets.getOrNull(image)
                val valid = page.imageIndex == image && expected != null && expected in 0..texts[index].length &&
                    page.start == expected && page.end == expected && page.start == cursor
                if (valid) image++
                valid
            } else {
                val valid = page.imageIndex == -1 && page.start == cursor && page.end in cursor..texts[index].length &&
                    (page.end > cursor || texts[index].isEmpty() && offsets.isEmpty() && chapter.size == 1)
                cursor = page.end
                valid
            }
        } && cursor == texts[index].length && image == offsets.size
    }
}
