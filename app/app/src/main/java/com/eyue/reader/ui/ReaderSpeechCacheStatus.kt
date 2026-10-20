package com.eyue.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.eyue.reader.data.AiSpeechCache
import com.eyue.reader.data.AiSpeechChapterCacheStatus
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookVoiceRole
import com.eyue.reader.data.LibraryMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReaderSpeechCacheStatus(val chapters: Set<Int> = emptySet(), val complete: Boolean = false,
    val otherChapters: Set<Int> = emptySet())

internal fun readerSpeechCacheStatus(book: Book, cached: AiSpeechChapterCacheStatus): ReaderSpeechCacheStatus {
    val readable = book.chapters.indices.filter { index -> index !in cached.emptyChapters && book.chapters[index].let { chapter ->
        chapter.characterCount != 0 && (chapter.textPath != null || chapter.pages.any { it.textPath != null })
    } }
    return ReaderSpeechCacheStatus(cached.currentChapters, readable.isNotEmpty() && cached.currentChapters.containsAll(readable), cached.otherChapters)
}

@Composable
internal fun rememberSpeechCacheStatus(book: Book, config: AiSpeechConfig?, revision: Int,
    commonRoles: List<BookVoiceRole>? = null): ReaderSpeechCacheStatus {
    val context = LocalContext.current
    val completed by produceState(ReaderSpeechCacheStatus(), book.id, book.contentVersion, book.chapters, book.voiceRoles, commonRoles, config, revision) {
        value = ReaderSpeechCacheStatus()
        if (book.mode == LibraryMode.NOVEL && !book.cloudOnly) {
            value = try { withContext(Dispatchers.IO) {
                val cached = AiSpeechCache(context).chapterCacheStatus(book, config, commonRoles)
                readerSpeechCacheStatus(book, cached)
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReaderSpeechCacheStatus() }
        }
    }
    return completed
}
