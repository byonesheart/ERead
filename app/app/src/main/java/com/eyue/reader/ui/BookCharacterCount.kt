package com.eyue.reader.ui

import com.eyue.reader.data.Book
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.readChapterText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader

internal fun Book.hasSpeechText(): Boolean = mode == LibraryMode.NOVEL && !cloudOnly && chapters.any { chapter ->
    chapter.textPath != null && chapter.characterCount != 0 || chapter.pages.any { it.textPath != null }
}

/** Read one buffer at a time: even a large book never needs to be held in memory for its count. */
internal suspend fun bookCharacterCount(book: Book): Long = withContext(Dispatchers.IO) {
    if (book.mode == LibraryMode.COMIC) return@withContext 0L
    var total = 0L
    for ((index, chapter) in book.chapters.withIndex()) {
        if (chapter.textPath != null) {
            val work = currentCoroutineContext()
            val text = readChapterText(book, index) { work.ensureActive() }
            total += countTextCharacters(text.reader()) { work.ensureActive() }
            continue
        }
        val paths = chapter.textPath?.let { listOf(it) } ?: chapter.pages.mapNotNull { it.textPath }
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            File(path).bufferedReader().use { input ->
                total += countTextCharacters(input) { currentCoroutineContext().ensureActive() }
            }
        }
    }
    total
}

internal inline fun countTextCharacters(input: Reader, checkActive: () -> Unit = {}): Long {
    val buffer = CharArray(8192)
    var total = 0L
    var highSurrogate: Char? = null
    while (true) {
        checkActive()
        val size = input.read(buffer)
        if (size < 0) break
        for (index in 0 until size) {
            val character = buffer[index]
            val previous = highSurrogate
            if (previous != null) {
                total++
                highSurrogate = null
                if (character.isLowSurrogate()) continue
            }
            if (character.isHighSurrogate()) highSurrogate = character
            else if (character != '\uFFFC' && !Character.isWhitespace(character) && !Character.isSpaceChar(character)) total++
        }
    }
    return total + if (highSurrogate != null) 1 else 0
}
