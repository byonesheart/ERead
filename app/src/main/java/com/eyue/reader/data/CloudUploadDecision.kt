package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudBookInfo

internal class CloudUploadConflictException(message: String) : IllegalStateException(message)

internal fun cloudUploadIdentityKeys(account: String, book: Book): List<String> = buildList {
    add("$account\ntitle\n${book.mode}\n${book.title.trim().lowercase(java.util.Locale.ROOT)}")
    val name = book.sourceFileName?.takeIf { it.isNotBlank() }
    val bytes = book.sourceFileBytes?.takeIf { it >= 0 }
    if (name != null && bytes != null) add("$account\nsource\n${name.length}:$name\n$bytes")
}

/** Keep local IDs stable: selections, notes and AI caches still refer to the local book. */
internal fun cloudUploadTargetId(book: Book, incoming: List<CloudBookInfo>,
    matches: Map<Pair<String, String>, CloudBookMatch>, linkedId: String? = null): String =
    incoming.firstOrNull { it.book.id == linkedId }?.book?.id
        ?: incoming.firstOrNull { it.book.id == book.id }?.book?.id
        ?: incoming.firstOrNull { cloudRestoreBookMatch(book, it.book, matches) == CloudBookMatch.SAME }?.book?.id
        ?: book.id

internal fun cloudUploadSnapshot(state: LibraryState, book: Book, cloudId: String): Pair<LibraryState, Book> {
    if (book.id == cloudId) return state to book
    val portable = book.copy(id = cloudId)
    return state.copy(books = state.books.filterNot { it.id == book.id || it.id == cloudId } + portable,
        annotations = state.annotations.filterNot { it.bookId == cloudId }.map {
            if (it.bookId == book.id) it.copy(bookId = cloudId) else it
        }) to portable
}

internal fun cloudUploadConflicts(state: LibraryState, bookIds: Set<String>, incoming: List<CloudBookInfo>,
    matches: Map<Pair<String, String>, CloudBookMatch>): List<CloudRestoreConflict> =
    state.books.filter { it.id in bookIds && !it.cloudOnly }.flatMap { local ->
        cloudRestoreConflicts(state.copy(books = listOf(local)), incoming, matches)
    }.distinctBy { it.remote.id }.map { conflict ->
        conflict.copy(locals = sameTitleLocalBooks(state, conflict.remote, matches)
            .filter { cloudRestoreBookMatch(it, conflict.remote, matches) == CloudBookMatch.UNKNOWN }.ifEmpty { conflict.locals })
    }

/** Apply exactly the confirmed groups; books imported during network work were never candidates for removal. */
internal fun applyCloudUploadSelections(state: LibraryState, incoming: List<CloudBookInfo>,
    conflicts: List<CloudRestoreConflict>, selections: Map<String, CloudRestoreSelection>,
    matches: Map<Pair<String, String>, CloudBookMatch> = emptyMap()): LibraryState {
    val retained = selections.values.flatMap { it.keepLocalIds }.toSet()
    val discarded = conflicts.flatMap { it.locals }.map { it.id }.toSet() - retained
    val discardedCloudOnly = conflicts.filterNot { selections.getValue(it.remote.id).keepRemote }.map { it.remote.id }.toSet()
    val removed = state.books.filter { it.id in discarded || it.cloudOnly && it.id in discardedCloudOnly }.map { it.id }.toSet()
    val base = state.copy(books = state.books.filterNot { it.id in removed }, annotations = state.annotations.filterNot { it.bookId in removed })
    val cloud = incoming.filter { info -> selections[info.book.id]?.keepRemote == true && base.books.none { local ->
        !local.cloudOnly && cloudRestoreBookMatch(local, info.book, matches) == CloudBookMatch.SAME
    } }
    return mergeCloudBookInformation(base, cloud.map { it.book }, cloud.flatMap { it.annotations }, cloud.mapNotNull { it.group },
        cloud.maxOfOrNull { it.readingSeconds } ?: 0)
}
