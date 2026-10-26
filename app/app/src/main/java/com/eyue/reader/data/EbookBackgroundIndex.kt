package com.eyue.reader.data

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Cancellation between/inside chapters lets page interaction reclaim the reader's resources. */
internal suspend fun indexEbookChapters(book: Book, onIndexed: (Int, Int) -> Unit) {
    for ((index, chapter) in book.chapters.withIndex()) {
        currentCoroutineContext().ensureActive()
        val length = withContext(Dispatchers.IO) {
            val work = currentCoroutineContext()
            val priority = Process.getThreadPriority(Process.myTid())
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val prepared = DeferredEbookContent.ensure(chapter) { work.ensureActive() }
                prepared.characterCount.takeIf { it >= 0 } ?: countChapterText(prepared) { work.ensureActive() }
            } finally { Process.setThreadPriority(priority) }
        }
        currentCoroutineContext().ensureActive()
        onIndexed(index, length)
        delay(100)
    }
}
