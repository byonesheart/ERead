package com.eyue.reader.ui

import com.eyue.reader.data.Chapter
import com.eyue.reader.data.PublicationContentsEntry

internal fun readerPublicationContents(chapters: List<Chapter>, contents: List<PublicationContentsEntry>, hierarchical: Boolean): List<ReaderContentsEntry> {
    val nodes = contents.filter { it.chapter in chapters.indices }
    val boundaries = (nodes.map { it.chapter } + chapters.size).distinct().sorted()
    val ends = boundaries.zipWithNext().toMap()
    val owned = nodes.associate { item -> item.id to (item.chapter until (ends[item.chapter] ?: chapters.size)).toMutableSet() }
    nodes.asReversed().forEach { item -> item.parentId?.let { parent -> owned[parent]?.addAll(owned.getValue(item.id)) } }
    // Leading cover/front matter still belongs to the first visible directory group.
    nodes.firstOrNull()?.let { owned[it.id]?.addAll(0 until it.chapter) }
    val depths = mutableMapOf<String, Int>()
    return nodes.map { item ->
        val parent = item.parentId?.takeIf { it in depths }
        val depth = parent?.let { depths.getValue(it) + 1 } ?: 0
        depths[item.id] = depth
        ReaderContentsEntry("publication/${item.id}", item.title, item.chapter, 0, Int.MAX_VALUE,
            if (hierarchical) depth else 0, parent?.takeIf { hierarchical }?.let { "publication/$it" }, owned.getValue(item.id))
    }
}
