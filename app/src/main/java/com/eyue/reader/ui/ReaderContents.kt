package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection

internal data class ReaderContentsEntry(val key: String, val title: String, val chapter: Int,
    val startPage: Int, val endPage: Int, val depth: Int)

internal fun readerContentsEntries(chapters: List<Chapter>): List<ReaderContentsEntry> = buildList {
    chapters.forEachIndexed { chapterIndex, chapter ->
        add(ReaderContentsEntry(chapter.id, chapter.title, chapterIndex, 0,
            chapter.pages.size.takeIf { it > 0 } ?: Int.MAX_VALUE, 0))
        fun append(sections: List<ComicSection>, path: String, depth: Int) {
            sections.forEachIndexed { index, section ->
                val key = "$path/$index"
                add(ReaderContentsEntry(key, section.title, chapterIndex, section.startPage, section.endPage, depth))
                append(section.children, key, depth + 1)
            }
        }
        append(chapter.subsections, chapter.id, 1)
    }
}

internal fun readerCurrentContentsEntry(entries: List<ReaderContentsEntry>, chapter: Int, page: Int): Int =
    entries.indexOfLast { it.chapter == chapter && page >= it.startPage && page < it.endPage }

internal fun readerChapterTitle(chapter: Chapter, page: Int): String = buildList {
    add(chapter.title)
    var sections = chapter.subsections
    while (true) {
        val section = sections.find { page >= it.startPage && page < it.endPage } ?: break
        add(section.title)
        sections = section.children
    }
}.joinToString(" / ")
