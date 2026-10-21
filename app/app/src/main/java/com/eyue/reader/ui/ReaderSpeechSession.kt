package com.eyue.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eyue.reader.data.Book
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns preparation, cancellation and audio focus for one open book. */
internal class ReaderSpeechSession(private val scope: CoroutineScope, private val audio: ReaderAudioSession) {
    var active by mutableStateOf(false)
        private set
    var preparation by mutableStateOf<Job?>(null)
        private set
    var readError by mutableStateOf<String?>(null)
        private set
    var restartForEngine by mutableStateOf(false)
    private var generation = 0

    fun cancelPreparation() { generation++; preparation?.cancel(); preparation = null }

    fun pause(engine: ReaderSpeechController) {
        cancelPreparation()
        if (!engine.pause()) engine.stop()
        audio.pause()
    }

    fun stop(engine: ReaderSpeechController) {
        pause(engine)
        engine.stop()
        audio.stop()
        active = false
    }

    fun clearError(engine: ReaderSpeechController) { readError = null; engine.clearError() }

    fun detach(engine: ReaderSpeechController) {
        restartForEngine = active && (restartForEngine || engine.playing.value || preparation?.isActive == true || audio.pending != null)
        cancelPreparation()
        audio.pause()
        engine.release()
    }

    fun start(engine: ReaderSpeechController, speed: () -> Float, onStarted: () -> Unit,
              onLocation: (ReaderSpeechSegment, Int) -> Unit, source: suspend () -> ReaderSpeechSource?) {
        cancelPreparation()
        // Keep the paused node until preparation confirms there is somewhere to move to.
        if (engine.playing.value && !engine.pause()) engine.stop()
        audio.pause()
        active = true
        readError = null
        val token = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val ready = source() ?: return@launch
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                audio.play {
                    if (token == generation) {
                        engine.stop()
                        engine.start(ready, speed(), onLocation)
                        onStarted()
                        audio.update(engine.playing.value, true, speed())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (token == generation) {
                    readError = error.message ?: "无法读取朗读正文"
                    engine.stop()
                    audio.stop()
                    active = false
                }
            } finally { if (token == generation) preparation = null }
        }
        preparation = job
        job.start()
    }
}

/** Preparing or resuming speech reads only the selected chapter; the provider owns subsequent IO. */
internal suspend fun readerSpeechSource(book: Book, chapters: ReaderChapterStore, raster: Boolean,
    rasterTexts: List<List<String>>, pages: List<List<ReaderTextPage>>, mode: ReadingMode,
    chapterSegments: Boolean, startChapter: Int, startPage: Int, startOffset: Int): ReaderSpeechSource {
    suspend fun segments(chapter: Int): List<ReaderSpeechSegment> {
        currentCoroutineContext().ensureActive()
        if (raster) return rasterTexts.getOrNull(chapter).orEmpty().mapIndexedNotNull { page, text ->
            if (chapter == startChapter && page < startPage) return@mapIndexedNotNull null
            val offset = if (chapter == startChapter && page == startPage) startOffset.coerceIn(0, text.length) else 0
            ReaderSpeechSegment(chapter, page, offset, text.substring(offset), roleContext = text)
        }
        val text = chapters.readText(chapter)
        val chapterPages = pages.getOrNull(chapter).orEmpty()
        if (!chapterSegments && mode != ReadingMode.SCROLL && chapterPages.isNotEmpty()) {
            return chapterPages.mapIndexedNotNull { pageIndex, page ->
                if (page.imageIndex >= 0 || chapter == startChapter && pageIndex < startPage) return@mapIndexedNotNull null
                val end = page.end.coerceAtMost(text.length)
                val offset = if (chapter == startChapter && pageIndex == startPage)
                    startOffset.coerceIn(page.start.coerceAtMost(end), end) else page.start.coerceAtMost(end)
                if (offset >= end) null else ReaderSpeechSegment(chapter, pageIndex, offset,
                    text.substring(offset, end), roleContext = text)
            }
        }
        val offset = if (chapter == startChapter) startOffset.coerceIn(0, text.length) else 0
        return listOf(ReaderSpeechSegment(chapter, 0, offset, text.substring(offset), roleContext = text))
    }
    return ReaderSpeechSource(startChapter, book.chapters.size, segments(startChapter), ::segments)
}
