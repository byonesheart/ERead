package com.eyue.reader.ui

import android.content.Context
import android.os.Build
import android.system.Os
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

private const val BOOK_INDEX_MAGIC = 0x45524249
private const val BOOK_INDEX_VERSION = 1
private const val BOOK_INDEX_LIMIT = 4 * 1024 * 1024
private const val BOOK_INDEX_CHAPTER_LIMIT = 20_000
private const val BOOK_INDEX_PAGE_LIMIT = 200_000
private val bookIndexIo = Mutex()

/** ctime is filesystem-controlled: restoring mtime cannot conceal a changed private chapter cache. */
internal fun readerFileChangeToken(file: File): String? {
    if (Build.VERSION.SDK_INT < 27) return null
    return runCatching {
        val stat = Os.stat(file.absolutePath)
        if (stat.st_ino == 0L || stat.st_ctim.tv_sec <= 0L) null else
            "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}:${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
    }.getOrNull()
}

private fun bookIndexHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Legacy whole-book index: the production reader uses chapter caches and does not call this factory. */
internal suspend fun readerBookPageIndex(context: Context, book: Book, settings: ReaderSettings,
    density: Density, direction: LayoutDirection, width: Int, height: Int,
    fileIdentity: (File) -> String? = ::readerFileChangeToken): ReaderBookPageIndex = withContext(Dispatchers.IO) {
    val work = currentCoroutineContext()
    val dependencies = linkedMapOf<String, String?>()
    fun track(path: String?, allowMissing: Boolean = false) {
        if (path != null) {
            work.ensureActive()
            val file = File(path).absoluteFile
            if (file.path !in dependencies || !allowMissing && dependencies[file.path] == "missing") {
                dependencies[file.path] = if (allowMissing && !file.exists()) "missing" else fileIdentity(file)
            }
        }
    }
    book.chapters.forEach { chapter ->
        track(chapter.textPath); track(chapter.lazySourcePath)
        chapter.inlineImages.forEach { track(it.path, allowMissing = true) }
    }
    track(settings.fontPath, allowMissing = true)
    // Imported source CSS files share the lazy-source directory, so no chapter HTML/CSS needs opening.
    book.chapters.mapNotNull { it.lazySourcePath?.let(::File)?.absoluteFile?.parentFile }.distinct().forEach { directory ->
        directory.listFiles()?.filter { it.isFile && it.extension.equals("css", true) }?.sortedBy { it.name }?.forEach { track(it.path) }
    }
    val platform = readerLayoutPlatform(context, settings)
    val layout = readerLayoutCacheKey(book, settings, density, direction, width, height,
        List(book.chapters.size) { "" }, book.chapters.map { chapter -> chapter.inlineImages.map { "${it.offset}:${it.path}" } }, platform,
        checkActive = { work.ensureActive() })
    val identity = bookIndexHash(buildString {
        append(layout).append(':').append(book.contentVersion).append(':').append(book.sourceUri).append(':').append(book.sourcePath)
        append(':').append(book.sourceFileName).append(':').append(book.sourceFileBytes)
        book.chapters.forEach { append('\n').append(it.id).append(':').append(it.characterCount) }
        dependencies.forEach { (path, token) -> append('\n').append(path).append('=').append(token) }
    })
    ReaderBookPageIndex(context, book, settings, density, direction, width, height, platform,
        identity, dependencies, fileIdentity).also { it.restore() }
}

/** Rows are atomic checkpoints; the complete book is consolidated once, rather than rewritten per chapter. */
internal class ReaderBookPageIndex internal constructor(private val context: Context, private val book: Book,
    private val settings: ReaderSettings, private val density: Density, private val direction: LayoutDirection,
    private val width: Int, private val height: Int, private val platform: String, private val identity: String,
    private val dependencies: Map<String, String?>, private val fileIdentity: (File) -> String?) {
    private data class Row(val length: Int, val key: String, val preparedToken: String?, val pages: List<ReaderTextPage>)
    private val rows = linkedMapOf<Int, Row>()
    private val prefix = bookIndexHash(book.id) + "_"
    private val directory = File(context.cacheDir, "reader-book-page-index")
    private val snapshot = AtomicFile(File(directory, "$prefix$identity.bin"))
    private val parts = File(directory, "$prefix$identity.parts")
    private val usable = dependencies.values.all { it != null } && book.chapters.size in 1..BOOK_INDEX_CHAPTER_LIMIT &&
        book.chapters.all { it.textPath != null || it.lazySourcePath != null }
    private var invalid = false
    private var totalPages = 0
    val pages: Map<Int, List<ReaderTextPage>> get() = rows.mapValues { it.value.pages }
    val lengths: Map<Int, Int> get() = rows.mapValues { it.value.length }
    val complete: Boolean get() = rows.size == book.chapters.size
    fun hasChapter(index: Int): Boolean = index in rows

    private fun prepared(index: Int): File? = book.chapters[index].lazySourcePath?.let { File("$it.prepared.json").absoluteFile }
    private fun preparedToken(index: Int): String? = prepared(index)?.let { file ->
        if (file.exists()) fileIdentity(file) else "missing"
    } ?: "none"

    internal suspend fun restore() = bookIndexIo.withLock {
        if (!usable) return@withLock
        currentCoroutineContext().ensureActive()
        val all = read(snapshot, complete = true)
        if (all != null) {
            rows.putAll(all)
            totalPages = all.values.sumOf { it.pages.size }
            snapshot.baseFile.setLastModified(System.currentTimeMillis())
            return@withLock
        }
        var total = 0
        val checkpoints = parts.listFiles().orEmpty().mapNotNull { file ->
            file.name.removeSuffix(".bak").takeIf { it.endsWith(".bin") }?.removeSuffix(".bin")?.toIntOrNull()
        }.distinct().filter { it in book.chapters.indices }
        for (index in checkpoints) {
            currentCoroutineContext().ensureActive()
            val row = read(AtomicFile(File(parts, "$index.bin")), complete = false)?.get(index) ?: continue
            if (total + row.pages.size > BOOK_INDEX_PAGE_LIMIT) break
            total += row.pages.size
            rows[index] = row
        }
        totalPages = total
    }

    suspend fun record(index: Int, pages: List<ReaderTextPage>, text: String,
        chapter: Chapter = book.chapters[index], cacheKey: String? = null) = withContext(Dispatchers.IO) {
        if (!usable || invalid || index !in book.chapters.indices || chapter.id != book.chapters[index].id ||
            !readerValidPageRanges(pages, text.length, chapter.inlineImages.map { it.offset })) return@withContext
        val work = currentCoroutineContext()
        val key = cacheKey ?: readerLayoutCacheKey(book.copy(chapters = listOf(chapter)), settings, density, direction,
            width, height, listOf(text), listOf(chapter.inlineImages.map { "${it.offset}:${it.path}" }), platform,
            checkActive = { work.ensureActive() })
        bookIndexIo.withLock {
            work.ensureActive()
            // Each record checks only its own immutable inputs; metadata for the rest was captured at session creation.
            val sources = listOfNotNull(book.chapters[index].textPath, book.chapters[index].lazySourcePath)
            if (sources.any { path -> fileIdentity(File(path).absoluteFile) != dependencies[File(path).absolutePath] }) {
                invalid = true
                return@withLock
            }
            val token = preparedToken(index) ?: return@withLock
            val row = Row(text.length, key, token, pages.toList())
            if (rows[index] == row) return@withLock
            val previous = rows[index]
            val previousTotal = totalPages
            val total = totalPages - (previous?.pages?.size ?: 0) + pages.size
            if (total > BOOK_INDEX_PAGE_LIMIT) return@withLock
            totalPages = total
            rows[index] = row
            try {
                val persisted = if (complete) write(snapshot, rows)
                    else write(AtomicFile(File(parts, "$index.bin")), mapOf(index to row))
                if (persisted) {
                    if (complete) parts.deleteRecursively()
                    if (complete || rows.size == 1) prune()
                } else {
                    if (previous == null) rows.remove(index) else rows[index] = previous
                    totalPages = previousTotal
                }
            } catch (cancelled: CancellationException) {
                if (previous == null) rows.remove(index) else rows[index] = previous
                totalPages = previousTotal
                throw cancelled
            }
        }
    }

    private suspend fun read(file: AtomicFile, complete: Boolean): Map<Int, Row>? {
        return try {
            file.openRead().use { input ->
                if (input.channel.size() !in 32L..BOOK_INDEX_LIMIT.toLong()) return null
                val bytes = input.readBytes()
                val payload = bytes.copyOf(bytes.size - 32)
                if (!MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))) return null
                val data = DataInputStream(ByteArrayInputStream(payload))
                if (data.readInt() != BOOK_INDEX_MAGIC || data.readInt() != BOOK_INDEX_VERSION || data.readUTF() != identity) return null
                val count = data.readInt()
                if (count !in 1..book.chapters.size || complete && count != book.chapters.size || !complete && count != 1) return null
                val found = linkedMapOf<Int, Row>()
                var total = 0
                repeat(count) {
                    currentCoroutineContext().ensureActive()
                    val index = data.readInt()
                    if (index !in book.chapters.indices || index in found) return null
                    val length = data.readInt()
                    val key = data.readUTF()
                    val token = data.readUTF()
                    val size = data.readInt()
                    if (length < 0 || key.length != 64 || token != preparedToken(index) || size !in 1..BOOK_INDEX_PAGE_LIMIT - total ||
                        book.chapters[index].characterCount >= 0 && length != book.chapters[index].characterCount) return null
                    total += size
                    val pages = List(size) { ReaderTextPage(data.readInt(), data.readInt(), data.readInt()) }
                    if (!readerValidPageRanges(pages, length, book.chapters[index].inlineImages.map { it.offset })) return null
                    found[index] = Row(length, key, token, pages)
                }
                if (data.read() != -1) null else found
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private suspend fun write(file: AtomicFile, source: Map<Int, Row>): Boolean {
        var output: java.io.FileOutputStream? = null
        return try {
            currentCoroutineContext().ensureActive()
            val buffer = ByteArrayOutputStream()
            val data = DataOutputStream(buffer)
            data.writeInt(BOOK_INDEX_MAGIC); data.writeInt(BOOK_INDEX_VERSION); data.writeUTF(identity); data.writeInt(source.size)
            source.forEach { (index, row) ->
                currentCoroutineContext().ensureActive()
                data.writeInt(index); data.writeInt(row.length); data.writeUTF(row.key); data.writeUTF(requireNotNull(row.preparedToken)); data.writeInt(row.pages.size)
                row.pages.forEach { page -> data.writeInt(page.start); data.writeInt(page.end); data.writeInt(page.imageIndex) }
                if (buffer.size() > BOOK_INDEX_LIMIT - 32) return false
            }
            data.flush()
            val payload = buffer.toByteArray()
            file.baseFile.parentFile?.mkdirs()
            output = file.startWrite()
            output.write(payload); output.write(MessageDigest.getInstance("SHA-256").digest(payload))
            currentCoroutineContext().ensureActive()
            file.finishWrite(output)
            true
        } catch (error: Exception) {
            output?.let(file::failWrite)
            if (error is CancellationException) throw error
            false
        }
    }

    private fun prune() {
        val layouts = directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
            .groupBy { it.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".bin").removeSuffix(".parts") }
        layouts.entries.sortedByDescending { it.value.maxOf { item -> item.lastModified() } }.drop(3).forEach { (_, files) ->
            files.forEach { if (it.isDirectory) it.deleteRecursively() else AtomicFile(it).delete() }
        }
    }
}

internal suspend fun removeReaderBookPageIndexes(context: Context, bookId: String) = bookIndexIo.withLock {
    val prefix = bookIndexHash(bookId) + "_"
    File(context.cacheDir, "reader-book-page-index").listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach {
        if (it.isDirectory) it.deleteRecursively() else AtomicFile(it).delete()
    }
}
