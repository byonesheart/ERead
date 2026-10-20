package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudRestoredBook
import java.util.UUID

private fun cloudGroup(groups: List<ShelfGroup>, incoming: ShelfGroup?, mode: LibraryMode): Pair<List<ShelfGroup>, String?> {
    if (incoming == null || incoming.mode != mode) return groups to null
    val existing = groups.find { it.id == incoming.id && it.mode == mode }
        ?: groups.find { it.name == incoming.name && it.mode == mode }
    if (existing != null) return groups to existing.id
    val group = if (groups.any { it.id == incoming.id }) incoming.copy(id = UUID.randomUUID().toString()) else incoming
    return (groups + group) to group.id
}

/** Information restore never replaces a downloaded local book or downloads its reading content. */
internal fun mergeCloudBookInformation(state: LibraryState, books: List<Book>, annotations: List<Annotation>,
                                      incomingGroups: List<ShelfGroup>, readingSeconds: Long): LibraryState {
    val existing = state.books.associateBy { it.id }
    var groups = state.groups
    val restored = books.distinctBy { it.id }.filter { existing[it.id]?.cloudOnly != false }.map { book ->
        val (updatedGroups, groupId) = cloudGroup(groups, incomingGroups.find { it.id == book.groupId && it.mode == book.mode }, book.mode)
        groups = updatedGroups
        book.copy(cloudOnly = true, cloudEnabled = true, sourcePath = null, sourceUri = null, sourceLinked = false,
            groupId = groupId, chapters = book.chapters.map { chapter ->
                chapter.copy(textPath = null, lazySourcePath = null, sourceStyles = emptyList(), inlineImages = emptyList(),
                    pages = chapter.pages.map { it.copy(path = "", textPath = null) })
            })
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
    val (groups, groupId) = cloudGroup(state.groups, restored.group, restored.book.mode)
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
