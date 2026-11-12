package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudRestoredBook
import java.util.UUID

internal fun Chapter.cloudShelfChapter(): Chapter = copy(
    textPath = null, lazySourcePath = null, sourceStyles = emptyList(), sourceCssStyles = emptyList(),
    sourceFootnotes = emptyList(), inlineImages = emptyList(),
    pages = pages.map { it.copy(path = "", textPath = null) }
)

internal fun cloudBookGroups(groups: List<ShelfGroup>, groupId: String?): List<ShelfGroup> {
    val group = groups.find { it.id == groupId } ?: return emptyList()
    return listOfNotNull(group, groups.find { it.id == group.parentId && it.mode == group.mode && it.parentId == null })
}

private fun cloudGroup(groups: List<ShelfGroup>, incoming: ShelfGroup?, mode: LibraryMode,
    incomingGroups: List<ShelfGroup>): Pair<List<ShelfGroup>, String?> {
    if (incoming == null || incoming.mode != mode) return groups to null
    var available = groups
    fun ensure(group: ShelfGroup, parent: String?): String {
        val sameLevel = available.filter { it.mode == mode && it.parentId == parent }
        val existing = sameLevel.find { it.id == group.id } ?: sameLevel.find { it.name == group.name }
        if (existing != null) return existing.id
        val local = group.copy(id = if (available.any { it.id == group.id }) UUID.randomUUID().toString() else group.id, parentId = parent)
        available = available + local
        return local.id
    }
    val parent = incomingGroups.find { it.id == incoming.parentId && it.mode == mode && it.parentId == null }?.let { ensure(it, null) }
    val group = ensure(incoming, parent)
    return available to group
}

/** Information restore never replaces a downloaded local book or downloads its reading content. */
internal fun mergeCloudBookInformation(state: LibraryState, books: List<Book>, annotations: List<Annotation>,
                                      incomingGroups: List<ShelfGroup>, readingSeconds: Long): LibraryState {
    val existing = state.books.associateBy { it.id }
    var groups = state.groups
    val restored = books.distinctBy { it.id }.filter { existing[it.id]?.cloudOnly != false }.map { book ->
        val (updatedGroups, groupId) = cloudGroup(groups, incomingGroups.find { it.id == book.groupId && it.mode == book.mode }, book.mode, incomingGroups)
        groups = updatedGroups
        book.copy(cloudOnly = true, cloudEnabled = true, sourcePath = null, sourceUri = null, sourceLinked = false,
            groupId = groupId, chapters = book.chapters.map(Chapter::cloudShelfChapter))
    }
    val byId = restored.associateBy { it.id }
    val retainedNotes = state.annotations.filterNot { it.bookId in byId }
    val existingNoteIds = retainedNotes.map { it.id }.toSet()
    return state.copy(books = state.books.map { byId[it.id] ?: it } + restored.filter { it.id !in existing },
        annotations = retainedNotes + annotations.filter { it.bookId in byId && it.id !in existingNoteIds }.distinctBy { it.id },
        groups = groups, readingSeconds = maxOf(state.readingSeconds, readingSeconds.coerceAtLeast(0)))
}

/** A manually downloaded book restores its own records and profile without changing the other mode. */
internal fun mergeCloudDownloadedBook(state: LibraryState, restored: CloudRestoredBook): LibraryState {
    val (groups, groupId) = cloudGroup(state.groups, restored.group, restored.book.mode, listOfNotNull(restored.parentGroup))
    val book = restored.book.copy(groupId = groupId, cloudOnly = false, cloudEnabled = true, cloudBackupAt = restored.backupAt)
    val current = if (book.mode == LibraryMode.COMIC) state.comicSettings else state.settings
    val settings = restored.settings.copy(nightMode = state.settings.nightMode,
        fonts = (current.fonts + restored.settings.fonts).associateBy { it.name }.values.toList())
    val retainedNotes = state.annotations.filterNot { it.bookId == book.id }
    val retainedNoteIds = retainedNotes.map { it.id }.toSet()
    return state.copy(books = if (state.books.any { it.id == book.id }) state.books.map { if (it.id == book.id) book else it }
            else state.books + book,
        annotations = retainedNotes + restored.annotations.filter { it.bookId == book.id && it.id !in retainedNoteIds }.distinctBy { it.id },
        groups = groups, settings = if (book.mode == LibraryMode.NOVEL) settings else state.settings,
        comicSettings = if (book.mode == LibraryMode.COMIC) settings else state.comicSettings,
        readingSeconds = maxOf(state.readingSeconds, restored.readingSeconds.coerceAtLeast(0)))
}
