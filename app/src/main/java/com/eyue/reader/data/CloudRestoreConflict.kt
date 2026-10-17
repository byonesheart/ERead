package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudBookInfo

enum class CloudRestoreChoice { LOCAL, CLOUD, BOTH }
enum class CloudBookMatch { SAME, DIFFERENT, UNKNOWN }
data class CloudRestoreConflict(val local: Book, val remote: Book, val locals: List<Book> = listOf(local))
data class CloudRestoreSelection(val keepLocalIds: Set<String>, val keepRemote: Boolean)

internal fun cloudRestoreConflicts(state: LibraryState, incoming: List<CloudBookInfo>,
    matches: Map<Pair<String, String>, CloudBookMatch> = emptyMap()): List<CloudRestoreConflict> =
    incoming.mapNotNull { info ->
        val candidates = sameTitleLocalBooks(state, info.book)
        if (candidates.any { matches[it.id to info.book.id] == CloudBookMatch.SAME }) null
        else candidates.firstOrNull { matches[it.id to info.book.id] != CloudBookMatch.DIFFERENT }
            ?.let { CloudRestoreConflict(it, info.book, candidates) }
    }.distinctBy { it.remote.id }

internal fun sameTitleLocalBooks(state: LibraryState, remote: Book): List<Book> =
    if (state.books.any { !it.cloudOnly && it.id == remote.id }) emptyList() else state.books.filter { local ->
    !local.cloudOnly && local.id != remote.id && local.mode == remote.mode &&
        local.title.trim().equals(remote.title.trim(), ignoreCase = true)
}

/** Apply the user decision only after any required cloud updates have completed. */
internal fun resolveCloudRestoreInformation(state: LibraryState, incoming: List<CloudBookInfo>,
    choices: Map<String, CloudRestoreChoice>, matches: Map<Pair<String, String>, CloudBookMatch> = emptyMap()): LibraryState {
    val selections = cloudRestoreConflicts(state, incoming, matches).associate { conflict ->
        val others = conflict.locals.map { it.id }.toSet() - conflict.local.id
        conflict.remote.id to when (choices[conflict.remote.id] ?: CloudRestoreChoice.BOTH) {
            CloudRestoreChoice.LOCAL -> CloudRestoreSelection(others + conflict.local.id, false)
            CloudRestoreChoice.CLOUD -> CloudRestoreSelection(others, true)
            CloudRestoreChoice.BOTH -> CloudRestoreSelection(others + conflict.local.id, true)
        }
    }
    return resolveCloudRestoreSelectionsInformation(state, incoming, selections, matches)
}

/** A local book selected in any conflict must remain selected in every overlapping conflict. */
internal fun normalizedCloudRestoreSelections(conflicts: List<CloudRestoreConflict>,
    selections: Map<String, CloudRestoreSelection>, protectedLocalIds: Set<String> = emptySet()): Map<String, CloudRestoreSelection> {
    val resolved = conflicts.associate { conflict ->
        val ids = conflict.locals.map { it.id }.toSet()
        val selection = selections[conflict.remote.id] ?: CloudRestoreSelection(ids, true)
        require(selection.keepLocalIds.all { it in ids } && (selection.keepRemote || selection.keepLocalIds.isNotEmpty())) {
            "每组同名书籍至少保留一本，请重新选择"
        }
        conflict.remote.id to selection
    }
    val protected = resolved.values.flatMap { it.keepLocalIds }.toSet() + protectedLocalIds
    return conflicts.associate { conflict -> conflict.remote.id to resolved.getValue(conflict.remote.id).let { selection ->
        selection.copy(keepLocalIds = selection.keepLocalIds + conflict.locals.filter { it.id in protected }.map { it.id })
    } }
}

internal fun cloudRestoreSelectionsForState(state: LibraryState, incoming: List<CloudBookInfo>,
    selections: Map<String, CloudRestoreSelection>, matches: Map<Pair<String, String>, CloudBookMatch>): Map<String, CloudRestoreSelection> {
    val automatic = state.books.filter { !it.cloudOnly && incoming.any { remote ->
        remote.book.id == it.id || matches[it.id to remote.book.id] == CloudBookMatch.SAME
    } }.map { it.id }.toSet()
    return normalizedCloudRestoreSelections(cloudRestoreConflicts(state, incoming, matches), selections, automatic)
}

internal fun resolveCloudRestoreSelectionsInformation(state: LibraryState, incoming: List<CloudBookInfo>,
    selections: Map<String, CloudRestoreSelection>, matches: Map<Pair<String, String>, CloudBookMatch> = emptyMap()): LibraryState {
    val conflicts = cloudRestoreConflicts(state, incoming, matches)
    val resolved = cloudRestoreSelectionsForState(state, incoming, selections, matches)
    val sameBooks = incoming.filter { info -> sameTitleLocalBooks(state, info.book).any {
        matches[it.id to info.book.id] == CloudBookMatch.SAME } }.map { it.book.id }.toSet()
    val discarded = conflicts.flatMap { conflict ->
        val selection = resolved.getValue(conflict.remote.id)
        conflict.locals.filterNot { it.id in selection.keepLocalIds }.map { it.id } +
            listOfNotNull(conflict.remote.id.takeIf { !selection.keepRemote })
    }.toSet() + state.books.filter { it.cloudOnly && it.id in sameBooks }.map { it.id }
    val retained = incoming.filterNot { it.book.id in sameBooks ||
        resolved[it.book.id]?.keepRemote == false }
    val base = state.copy(books = state.books.filterNot { it.id in discarded }, annotations = state.annotations.filterNot { it.bookId in discarded })
    return mergeCloudBookInformation(base, retained.map { it.book }, retained.flatMap { it.annotations },
        retained.mapNotNull { it.group }, retained.maxOfOrNull { it.readingSeconds } ?: 0)
}
