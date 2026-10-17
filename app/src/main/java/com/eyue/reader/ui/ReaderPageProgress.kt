package com.eyue.reader.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.eyue.reader.data.Book
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.loadChapterContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class ReaderPageProgress(val label: String)

private data class ReaderPageCatalog(
    val chapters: Map<Int, List<ReaderTextPage>> = emptyMap(),
    val failed: Boolean = false
)

/** Scroll blocks stay unchanged; only page boundaries are retained for the footer. */
@Composable
internal fun rememberReaderPageProgress(book: Book, settings: ReaderSettings, spec: ReaderLayoutSpec,
                                       chapter: Int, offset: Int, imageIndex: Int? = null): ReaderPageProgress {
    val context = LocalContext.current
    val latestChapter by rememberUpdatedState(chapter)
    val catalog by key(book.id, book.contentVersion, book.chapters, spec, settings.paragraphSpacing) {
        produceState(ReaderPageCatalog()) {
            if (spec.textWidth <= 1 || spec.textHeight <= 1) return@produceState
            try {
                val pending = book.chapters.indices.toMutableSet()
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val target = latestChapter.takeIf { it in pending } ?: pending.first()
                    val pages = readerProgressChapterPages(context, book, settings, spec, target)
                    currentCoroutineContext().ensureActive()
                    value = value.copy(chapters = value.chapters + (target to pages))
                    pending.remove(target)
                    yield()
                    delay(1)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                value = value.copy(failed = true)
            }
        }
    }
    return readerPageProgress(catalog.chapters, book.chapters.size, chapter, offset, imageIndex, catalog.failed)
}

/** Uses exactly the paged reader's text, source styles, viewport and persistent chapter cache. */
internal suspend fun readerProgressChapterPages(context: Context, book: Book, settings: ReaderSettings,
                                               spec: ReaderLayoutSpec, chapter: Int): List<ReaderTextPage> {
    val prepared = loadChapterContent(context, book, chapter)
    return withContext(Dispatchers.Default) {
        val worker = currentCoroutineContext()
        val styles = ReaderSourceStyles(prepared.text, prepared.chapter.sourceStyles) { worker.ensureActive() }
        readerCachedChapterPages(context, book, chapter, settings, spec.density, spec.layoutDirection,
            spec.textWidth, spec.textHeight, prepared.text, prepared.chapter) {
            readerPaginate(context, listOf(prepared.text), spec.textStyle, spec.density, spec.layoutDirection,
                spec.textWidth, spec.textHeight, inlineImages = listOf(prepared.chapter.inlineImages),
                sourceStyles = listOf(styles), paragraphSpacing = settings.paragraphSpacing).single()
        }
    }
}

internal fun readerPageProgress(chapters: Map<Int, List<ReaderTextPage>>, chapterCount: Int, chapter: Int,
                               offset: Int, imageIndex: Int? = null, failed: Boolean = false): ReaderPageProgress {
    if (failed || chapterCount <= 0) return ReaderPageProgress("页数暂不可用")
    val pages = chapters[chapter]?.takeIf { it.isNotEmpty() } ?: return ReaderPageProgress("页数统计中")
    val imagePage = imageIndex?.let { image -> pages.indexOfFirst { it.imageIndex == image }.takeIf { it >= 0 } }
    val current = imagePage ?: pages.indexOfLast { it.imageIndex < 0 && it.start <= offset }.coerceAtLeast(0)
    if ((0 until chapterCount).any { chapters[it].isNullOrEmpty() }) {
        return ReaderPageProgress("本章 ${current + 1} / ${pages.size} 页 · 总页数统计中")
    }
    val previous = (0 until chapter).sumOf { chapters.getValue(it).size }
    val total = (0 until chapterCount).sumOf { chapters.getValue(it).size }
    return ReaderPageProgress("${previous + current + 1} / $total 页")
}
