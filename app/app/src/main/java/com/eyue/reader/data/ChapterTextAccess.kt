package com.eyue.reader.data

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** A failed cache read is different from a successfully read, empty chapter. */
internal class ChapterTextReadException(val chapterTitle: String, cause: Exception? = null) :
    IOException("无法读取「$chapterTitle」的正文缓存或排版资料，请重新导入该书。", cause)

internal fun readChapterText(chapter: Chapter, check: () -> Unit = {}): String =
    readChapterTextFile(chapter.textPath, chapter.title, check)

internal fun readChapterText(book: Book, index: Int, check: () -> Unit = {}): String {
    val chapter = book.chapters[index]
    if (chapter.textPath == null && (book.mode == LibraryMode.COMIC || book.format in setOf(BookFormat.PDF, BookFormat.IMAGES))) {
        check()
        return ""
    }
    return readChapterText(chapter, check)
}

internal fun readChapterPageText(chapter: Chapter, pageIndex: Int, check: () -> Unit = {}): String {
    val path = chapter.pages[pageIndex].textPath ?: return ""
    return readChapterTextFile(path, "${chapter.title} · 第 ${pageIndex + 1} 页", check)
}

internal fun countChapterText(chapter: Chapter, check: () -> Unit = {}): Int {
    var count = 0
    visitChapterText(chapter.textPath, chapter.title, check) { _, size -> count += size }
    return count
}

private fun readChapterTextFile(path: String?, title: String, check: () -> Unit): String {
    val text = StringBuilder()
    visitChapterText(path, title, check) { buffer, size -> text.append(buffer, 0, size) }
    return text.toString()
}

private fun visitChapterText(path: String?, title: String, check: () -> Unit, consume: (CharArray, Int) -> Unit) {
    check()
    if (path == null) throw ChapterTextReadException(title)
    try {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(File(path).inputStream(), decoder).use { input ->
            val buffer = CharArray(8192)
            while (true) {
                check()
                val size = input.read(buffer)
                if (size < 0) break
                consume(buffer, size)
            }
        }
        check()
    } catch (error: IOException) {
        throw ChapterTextReadException(title, error)
    } catch (error: SecurityException) {
        throw ChapterTextReadException(title, error)
    }
}
