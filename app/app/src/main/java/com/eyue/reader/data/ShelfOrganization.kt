package com.eyue.reader.data

import android.content.Context

internal const val UNGROUPED_FILTER = "@ungrouped"

internal fun normalizeShelfTags(tags: Iterable<String>): List<String> =
    tags.map { it.trim().take(60) }.filter { it.isNotEmpty() && it.none(Char::isISOControl) }
        .distinct().take(64)

internal fun List<ShelfGroup>.reorderShelfGroup(mode: LibraryMode, id: String, targetId: String): List<ShelfGroup> {
    val source = find { it.id == id && it.mode == mode } ?: return this
    val siblings = filter { it.mode == mode && it.parentId == source.parentId }.toMutableList()
    val destination = siblings.indexOfFirst { it.id == targetId }
    if (destination < 0 || id == targetId) return this
    siblings.remove(source)
    siblings.add(destination, source)
    val ordered = siblings.iterator()
    return map { if (it.mode == mode && it.parentId == source.parentId) ordered.next() else it }
}

internal fun hiddenShelfTags(groups: List<ShelfGroup>, hidden: (String?) -> Set<String>): Map<String?, Set<String>> {
    val global = hidden(null)
    val scoped = groups.associate { it.id to hidden(it.id) }
    return buildMap {
        put(null, global + hidden(UNGROUPED_FILTER))
        groups.forEach { group -> put(group.id, global + scoped[group.parentId].orEmpty() + scoped[group.id].orEmpty()) }
    }
}

data class ShelfBrowseFilter(
    val groupId: String? = null,
    val subgroupIds: Set<String> = emptySet(),
    val tag: String? = null,
    val tagMode: Boolean = false
) {
    internal fun available(groups: List<ShelfGroup>, tags: Set<String>): ShelfBrowseFilter {
        val group = groupId?.takeIf { id -> id == UNGROUPED_FILTER || groups.any { it.id == id && it.parentId == null } }
        val children = if (group == null) emptySet() else subgroupIds.filterTo(mutableSetOf()) { id ->
            groups.any { it.id == id && it.parentId == group }
        }
        return copy(groupId = group, subgroupIds = children, tag = tag?.takeIf { it in tags })
    }

    internal fun includesGroup(book: Book, childIds: Set<String>): Boolean {
        if (tagMode || groupId == null) return true
        if (groupId == UNGROUPED_FILTER) return book.groupId == null
        if (subgroupIds.isNotEmpty()) return book.groupId in subgroupIds
        return book.groupId == groupId || book.groupId in childIds
    }
}

internal class ShelfBrowsePreferences(context: Context, private val mode: LibraryMode) {
    private val prefs = context.getSharedPreferences("shelf-browsing", Context.MODE_PRIVATE)
    private val key = mode.name

    var gridLayout: Boolean
        get() = prefs.getBoolean("$key-grid", true)
        set(value) { prefs.edit().putBoolean("$key-grid", value).apply() }

    fun read() = ShelfBrowseFilter(prefs.getString("$key-group", null),
        prefs.getStringSet("$key-subgroups", emptySet()).orEmpty().toSet(),
        prefs.getString("$key-tag", null), prefs.getBoolean("$key-tag-mode", false))

    fun save(filter: ShelfBrowseFilter) {
        prefs.edit().putString("$key-group", filter.groupId).putStringSet("$key-subgroups", filter.subgroupIds)
            .putString("$key-tag", filter.tag).putBoolean("$key-tag-mode", filter.tagMode).apply()
    }

    fun hidden(groupId: String?): Set<String> =
        prefs.getStringSet(hiddenKey(groupId), emptySet()).orEmpty().toSet()

    fun saveHidden(groupId: String?, tags: Set<String>) {
        prefs.edit().putStringSet(hiddenKey(groupId), tags).apply()
    }

    private fun hiddenKey(groupId: String?) = when (groupId) {
        null -> "hidden-global"
        UNGROUPED_FILTER -> "$key-hidden-ungrouped"
        else -> "hidden-$groupId"
    }
}
