package com.eyue.reader.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.eyue.reader.data.Book
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.loadChapterContent
import com.eyue.reader.data.ChineseTextConverter
import com.eyue.reader.data.readerSourceStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

internal data class ReaderPageProgress(val label: String)

private data class ReaderPageCatalog(
    val chapters: Map<Int, List<ReaderTextPage>> = emptyMap(),
    val failed: Boolean = false
)

/** Scroll blocks stay unchanged; only page boundaries are retained for the footer. */
@Composable
internal fun rememberReaderPageProgress(book: Book, settings: ReaderSettings, spec: ReaderLayoutSpec,
                                       chapter: Int, offset: Int, imageIndex: Int? = null, delayMillis: Long = 0): ReaderPageProgress {
    val context = LocalContext.current
    val catalog by key(book.id, book.contentVersion, book.chapters, chapter, spec, settings.paragraphSpacing, settings.chineseConversion, settings.useSourceCss,
        settings.marginLeft, settings.marginRight, settings.marginTop, settings.marginBottom) {
        produceState(ReaderPageCatalog()) {
            if (spec.textWidth <= 1 || spec.textHeight <= 1) return@produceState
            try {
                if (delayMillis > 0) delay(delayMillis)
                val pages = readerProgressChapterPages(context, book, settings, spec, chapter)
                currentCoroutineContext().ensureActive()
                value = ReaderPageCatalog(mapOf(chapter to pages))
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
                                               spec: ReaderLayoutSpec, chapter: Int,
                                               bookPageIndex: ReaderBookPageIndex? = null): List<ReaderTextPage> {
    val prepared = loadChapterContent(context, book, chapter)
    val chineseConverter = ChineseTextConverter.load(context, settings.chineseConversion)
    return withContext(Dispatchers.Default) {
        val worker = currentCoroutineContext()
        val styles = ReaderSourceStyles(prepared.text, prepared.chapter.readerSourceStyles(settings.useSourceCss)) { worker.ensureActive() }
        readerCachedChapterPages(context, book, chapter, settings, spec.density, spec.layoutDirection,
            spec.textWidth, spec.textHeight, prepared.text, prepared.chapter, bookPageIndex) {
            readerPaginate(context, listOf(prepared.text), spec.textStyle, spec.density, spec.layoutDirection,
                spec.textWidth, spec.textHeight, inlineImages = listOf(prepared.chapter.inlineImages),
                sourceStyles = listOf(styles), paragraphSpacing = settings.paragraphSpacing, chineseConverter = chineseConverter).single()
        }
    }
}

internal fun readerPageProgress(chapters: Map<Int, List<ReaderTextPage>>, chapterCount: Int, chapter: Int,
                               offset: Int, imageIndex: Int? = null, failed: Boolean = false): ReaderPageProgress {
    if (failed || chapterCount <= 0) return ReaderPageProgress("页数暂不可用")
    val pages = chapters[chapter]?.takeIf { it.isNotEmpty() } ?: return ReaderPageProgress("本章页数计算中")
    val imagePage = imageIndex?.let { image -> pages.indexOfFirst { it.imageIndex == image }.takeIf { it >= 0 } }
    val current = imagePage ?: pages.indexOfLast { it.imageIndex < 0 && it.start <= offset }.coerceAtLeast(0)
    return ReaderPageProgress("本章 ${current + 1} / ${pages.size} 页")
}
