package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection
import com.eyue.reader.data.PublicationContentsEntry

internal data class ReaderContentsEntry(val key: String, val title: String, val chapter: Int,
    val startPage: Int, val endPage: Int, val depth: Int, val parentKey: String? = null,
    val groupChapters: Set<Int> = setOf(chapter))

private val numberedContentsTitle = Regex("^第[ \\t\\u3000]*[零〇一二三四五六七八九十百千万两0-9]{1,12}[ \\t\\u3000]*(小节|卷|章|幕|回|话|节)(?=$|[ \\t\\u3000:：、.．—-])[^\\r\\n。；;！？!?]{0,60}$")

private fun contentsTitleLevel(title: String): Int? = numberedContentsTitle.matchEntire(title.trim())?.let { match ->
    when (match.groupValues[1]) { "卷" -> 0; "章", "幕", "回", "话" -> 1; else -> 2 }
}

private fun Chapter.contentsParent(): String? = tocParentTitle?.trim()?.takeIf { it.isNotEmpty() && it != title.trim() }

private val seriesVolumeTitle = Regex("^(.{1,30}?)[ \\t\\u3000]*(?:[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]+|[IVXLCDM]+|[0-9]+)(?=$|[ \\t\\u3000·•:：、.．（(—-])[^\\r\\n。；;！？!?]{0,60}$")
private val seriesExtraTitle = Regex("^(.{1,30}?)[ \\t\\u3000]*(?:前传|外传|后传|番外)(?=$|[ \\t\\u3000·•:：、.．（(—-])[^\\r\\n。；;！？!?]{0,60}$")

/** Flat omnibus TOCs often number book titles, then restart chapter numbers inside each book. */
private fun seriesVolumeEnds(chapters: List<Chapter>): Map<Int, Int> {
    val candidates = chapters.indices.filter { chapters[it].contentsParent() == null && contentsTitleLevel(chapters[it].title) == null }
    val numbered = candidates.mapNotNull { index -> seriesVolumeTitle.matchEntire(chapters[index].title.trim())
        ?.groupValues?.get(1)?.trim()?.let { it to index } }.groupBy({ it.first }, { it.second })
    return buildMap {
        numbered.filterValues { it.size >= 2 }.forEach { (prefix, volumes) ->
            val extras = candidates.filter { seriesExtraTitle.matchEntire(chapters[it].title.trim())?.groupValues?.get(1)?.trim() == prefix }
            fun normalizedTitle(index: Int) = java.text.Normalizer.normalize(chapters[index].title, java.text.Normalizer.Form.NFKC)
                .filterNot(Char::isWhitespace).lowercase()
            if (volumes.map(::normalizedTitle).distinct().size < 2) return@forEach
            val roots = mutableListOf<Int>()
            (volumes + extras).sorted().forEach { index ->
                val previous = roots.lastOrNull()
                // Some packages repeat a volume title after its copyright/TOC,
                // using ASCII rather than Unicode Roman numerals on the title page.
                if (previous == null || normalizedTitle(previous) != normalizedTitle(index) ||
                    (previous + 1 until index).any { contentsTitleLevel(chapters[it].title) == 1 }) roots += index
            }
            roots.forEachIndexed { position, start ->
                val end = roots.getOrNull(position + 1) ?: chapters.size
                if ((start + 1 until end).any { contentsTitleLevel(chapters[it].title) == 1 } &&
                    (start + 1 until end).all { chapters[it].contentsParent() == null }) put(start, end)
            }
        }
    }
}

/** Directory grouping never changes physical chapter indices or cached chapter content. */
internal fun readerContentsEntries(chapters: List<Chapter>, hierarchical: Boolean = false,
    publicationContents: List<PublicationContentsEntry>? = null): List<ReaderContentsEntry> =
    if (!publicationContents.isNullOrEmpty() && chapters.all { it.pages.isEmpty() && it.subsections.isEmpty() })
        readerPublicationContents(chapters, publicationContents, hierarchical) else buildList {
    fun appendChapter(index: Int, depth: Int = 0, parentKey: String? = null, groupChapters: Set<Int> = setOf(index)) {
        val chapter = chapters[index]
        add(ReaderContentsEntry(chapter.id, chapter.title, index, 0,
            chapter.pages.size.takeIf { it > 0 } ?: Int.MAX_VALUE, depth, parentKey, groupChapters))
        fun append(sections: List<ComicSection>, parent: String, nestedDepth: Int) {
            sections.forEachIndexed { sectionIndex, section ->
                val key = "$parent/$sectionIndex"
                add(ReaderContentsEntry(key, section.title, index, section.startPage, section.endPage, nestedDepth, parent))
                append(section.children, key, nestedDepth + 1)
            }
        }
        append(chapter.subsections, chapter.id, depth + 1)
    }
    if (!hierarchical || chapters.any { it.pages.isNotEmpty() || it.subsections.isNotEmpty() }) {
        chapters.indices.forEach { appendChapter(it) }
        return@buildList
    }
    val volumeEnds = seriesVolumeEnds(chapters)
    var index = 0
    while (index < chapters.size) {
        val chapter = chapters[index]
        val volumeEnd = volumeEnds[index]
        if (volumeEnd != null) {
            appendChapter(index, groupChapters = (index until volumeEnd).toSet())
            (index + 1 until volumeEnd).forEach { appendChapter(it, 1, chapter.id) }
            index = volumeEnd
            continue
        }
        val nativeParent = chapter.contentsParent()
        if (nativeParent != null) {
            var end = index + 1
            while (end < chapters.size && chapters[end].contentsParent() == nativeParent) end++
            val parentKey = "${chapter.id}/parent"
            add(ReaderContentsEntry(parentKey, nativeParent, index, 0, Int.MAX_VALUE, 0,
                groupChapters = (index until end).toSet()))
            (index until end).forEach { appendChapter(it, 1, parentKey) }
            index = end
            continue
        }
        val nextParent = chapters.getOrNull(index + 1)?.contentsParent()
        if (nextParent != null && nextParent == chapter.title.trim()) {
            var end = index + 2
            while (end < chapters.size && chapters[end].contentsParent() == nextParent) end++
            appendChapter(index, groupChapters = (index until end).toSet())
            (index + 1 until end).forEach { appendChapter(it, 1, chapter.id) }
            index = end
            continue
        }
        val level = contentsTitleLevel(chapter.title)
        if (level != null && level < 2) {
            var end = index + 1
            while (end < chapters.size && chapters[end].contentsParent() == null &&
                contentsTitleLevel(chapters[end].title) == level + 1) end++
            // Prefer actual chapter/section groups when a volume would otherwise hide a third level.
            val nestedSections = level == 0 && chapters.getOrNull(end)?.let {
                it.contentsParent() == null && contentsTitleLevel(it.title) == 2
            } == true
            if (end > index + 1 && !nestedSections) {
                appendChapter(index, groupChapters = (index until end).toSet())
                (index + 1 until end).forEach { appendChapter(it, 1, chapter.id) }
                index = end
                continue
            }
        }
        appendChapter(index)
        index++
    }
}

/** Indexed once for either the full or collapsed directory, then queried only within one chapter. */
internal class ReaderContentsPositionIndex(entries: List<ReaderContentsEntry>) {
    private val entries = entries
    private val byChapter = buildMap<Int, MutableList<Int>> {
        entries.forEachIndexed { index, entry -> entry.groupChapters.forEach { chapter ->
            getOrPut(chapter) { mutableListOf() }.add(index)
        } }
    }

    fun current(chapter: Int, page: Int): Int = byChapter[chapter]?.lastOrNull { index ->
        val entry = entries[index]
        page >= entry.startPage && page < entry.endPage
    } ?: -1
}

internal fun readerContentsPositionIndex(entries: List<ReaderContentsEntry>): ReaderContentsPositionIndex =
    ReaderContentsPositionIndex(entries)

internal fun readerCurrentContentsEntry(entries: List<ReaderContentsEntry>, chapter: Int, page: Int): Int =
    readerContentsPositionIndex(entries).current(chapter, page)

internal fun readerChapterTitle(chapter: Chapter, page: Int): String = buildList {
    add(chapter.title)
    var sections = chapter.subsections
    while (true) {
        val section = sections.find { page >= it.startPage && page < it.endPage } ?: break
        add(section.title)
        sections = section.children
    }
}.joinToString(" / ")

internal class ReaderSearchLocationIndex internal constructor(
    private val chapters: List<Chapter>, private val labels: List<String>, private val chapterNumbers: IntArray
) {
    fun label(chapter: Int, page: Int): String {
        val content = chapters.getOrNull(chapter) ?: return "位置未知"
        if (content.subsections.isEmpty()) return labels[chapter]
        val title = mutableListOf(content.title)
        val ordinals = mutableListOf<String>()
        var sections = content.subsections
        while (true) {
            val index = sections.indexOfFirst { page >= it.startPage && page < it.endPage }
            if (index < 0) break
            val section = sections[index]
            ordinals += "第${index + 1}${if (ordinals.isEmpty()) "节" else "小节"}"
            title += section.title
            sections = section.children
        }
        val location = ordinals.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "未分节"
        return "第${chapterNumbers[chapter]}章 · $location · ${title.joinToString(" / ")}"
    }
}

/** Search hits share this one linear outline pass, rather than rebuilding a directory for each result. */
internal fun readerSearchLocationIndex(chapters: List<Chapter>, publicationContents: List<PublicationContentsEntry>? = null): ReaderSearchLocationIndex {
    val entries = readerContentsEntries(chapters, hierarchical = true, publicationContents)
    if (!publicationContents.isNullOrEmpty()) {
        val byKey = entries.associateBy { it.key }
        val positions = readerContentsPositionIndex(entries)
        val labels = chapters.indices.map { chapter ->
            val item = entries.getOrNull(positions.current(chapter, 0))
            generateSequence(item) { it.parentKey?.let(byKey::get) }.toList().asReversed().joinToString(" / ") { it.title }
                .ifEmpty { chapters[chapter].title }
        }
        return ReaderSearchLocationIndex(chapters, labels, IntArray(chapters.size) { it + 1 })
    }
    val parents = entries.filter { it.parentKey == null }.mapIndexed { index, entry -> entry.key to (index + 1 to entry.title) }.toMap()
    val sections = mutableMapOf<String, Int>()
    val chapterNumbers = IntArray(chapters.size)
    val labels = MutableList(chapters.size) { "位置未知" }
    entries.forEach { entry ->
        if (entry.key == chapters[entry.chapter].id) {
            val parent = entry.parentKey?.let { parents[it] }
            val number = parent?.first ?: parents.getValue(entry.key).first
            chapterNumbers[entry.chapter] = number
            labels[entry.chapter] = if (parent != null) {
                val parentKey = requireNotNull(entry.parentKey)
                val section = sections.getOrDefault(parentKey, 0) + 1
                sections[parentKey] = section
                "第${number}章 · 第${section}节 · ${parent.second} / ${entry.title}"
            } else "第${number}章 · 未分节 · ${entry.title}"
        }
    }
    return ReaderSearchLocationIndex(chapters, labels, chapterNumbers)
}

internal fun readerSearchLocationLabel(chapters: List<Chapter>, chapter: Int, page: Int): String =
    readerSearchLocationIndex(chapters).label(chapter, page)
