package com.eyue.reader.ui

import android.content.Context
import android.util.AtomicFile
import com.eyue.reader.data.Book
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.PreparedChapter
import com.eyue.reader.data.ChapterTextReadException
import com.eyue.reader.data.countChapterText
import com.eyue.reader.data.loadChapterContent
import com.eyue.reader.data.readChapterPageText
import com.eyue.reader.data.readChapterText
import com.eyue.reader.data.readerSourceStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class ReaderLoadedChapter(val chapter: Chapter, val text: String,
                                      val styles: ReaderSourceStyles, val blocks: List<ReaderTextPage>)

/** Styles and scroll blocks belong to requested chapters; unseen chapters stay on disk. */
internal class ReaderChapterStore(private val context: Context, private val book: Book,
    private val useSourceCss: Boolean = false,
    private val read: suspend (Int) -> PreparedChapter = { loadChapterContent(context, book, it) }) {
    private val locks = book.chapters.map { Mutex() }
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, ReaderLoadedChapter>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ReaderLoadedChapter>?): Boolean = size > 6
    })
    private var window: IntRange? = null

    /** The reader and this store retain the same current/adjacent chapter window. */
    fun retainWindow(center: Int) = synchronized(cache) {
        window = (center - 1).coerceAtLeast(0)..(center + 1).coerceAtMost(book.chapters.lastIndex)
        cache.keys.retainAll(requireNotNull(window).toSet())
    }

    /** Search and speech share the prepared window, but never prepare or retain distant styles. */
    suspend fun readText(index: Int): String = locks[index].withLock {
        currentCoroutineContext().ensureActive()
        cache[index]?.text ?: withContext(Dispatchers.IO) {
            val work = currentCoroutineContext()
            readChapterText(book, index) { work.ensureActive() }
        }
    }

    /** An absent OCR source is valid; an existing OCR reference that cannot be read is an error. */
    suspend fun readPageText(chapterIndex: Int, pageIndex: Int): String = withContext(Dispatchers.IO) {
        val work = currentCoroutineContext()
        readChapterPageText(book.chapters[chapterIndex], pageIndex) { work.ensureActive() }
    }

    suspend fun load(index: Int): ReaderLoadedChapter = locks[index].withLock {
        currentCoroutineContext().ensureActive()
        cache[index]?.let { return@withLock it }
        val prepared = try { read(index) }
        catch (error: CancellationException) { throw error }
        catch (error: ChapterTextReadException) { throw error }
        catch (error: Exception) { throw ChapterTextReadException(book.chapters[index].title, error) }
        val result = withContext(Dispatchers.Default) {
            val work = currentCoroutineContext()
            ReaderLoadedChapter(prepared.chapter, prepared.text,
                ReaderSourceStyles(prepared.text, prepared.chapter.readerSourceStyles(useSourceCss)) { work.ensureActive() },
                readerScrollBlocks(prepared.text, prepared.chapter.inlineImages) { work.ensureActive() })
        }
        currentCoroutineContext().ensureActive()
        synchronized(cache) { if (window?.contains(index) != false) cache[index] = result }
        result
    }
}

/** Old books get an inexpensive, persistent length catalog without retaining all chapter strings. */
internal suspend fun readerChapterLengths(context: Context, book: Book, allowScan: Boolean = true): List<Int> = withContext(Dispatchers.IO) {
    if (book.chapters.all { it.characterCount >= 0 }) return@withContext book.chapters.map { it.characterCount }
    if (!allowScan) return@withContext com.eyue.reader.data.DeferredEbookContent.lengthHints(book.chapters)
    val work = currentCoroutineContext()
    val key = MessageDigest.getInstance("SHA-256").digest(book.id.toByteArray()).joinToString("") { "%02x".format(it) }
    val catalog = AtomicFile(File(context.cacheDir, "reader-chapter-lengths/$key.json"))
    val identities = book.chapters.map { chapter -> chapter.textPath?.let { path ->
        val file = File(path)
        "${chapter.id}:$path:${file.length()}:${file.lastModified()}"
    }.orEmpty() }
    val cached = runCatching { JSONObject(catalog.readFully().toString(Charsets.UTF_8)) }.getOrNull()
    val rows = cached?.optJSONArray("chapters")
    val lengths = book.chapters.mapIndexed { index, chapter ->
        currentCoroutineContext().ensureActive()
        val row = rows?.optJSONObject(index)
        when {
            chapter.characterCount >= 0 -> chapter.characterCount
            row?.optString("identity") == identities[index] && row.optInt("length", -1) >= 0 -> row.getInt("length")
            else -> countChapterText(chapter) { work.ensureActive() }
        }
    }
    currentCoroutineContext().ensureActive()
    runCatching {
        catalog.baseFile.parentFile?.mkdirs()
        val output = catalog.startWrite()
        try {
            val json = JSONObject().put("chapters", JSONArray(lengths.mapIndexed { index, length ->
                JSONObject().put("identity", identities[index]).put("length", length)
            }))
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            catalog.finishWrite(output)
        } catch (error: Throwable) { catalog.failWrite(output); throw error }
    }
    lengths
}
