package com.eyue.reader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class LibraryCleanupKind { HISTORY, AI_AUDIO, ANNOTATIONS, ROLES }

data class LibraryCleanupUsage(val historyBytes: Long = 0, val aiAudioBytes: Long = 0,
    val annotationBytes: Long = 0, val roleBytes: Long = 0) {
    fun bytes(kind: LibraryCleanupKind): Long = when (kind) {
        LibraryCleanupKind.HISTORY -> historyBytes
        LibraryCleanupKind.AI_AUDIO -> aiAudioBytes
        LibraryCleanupKind.ANNOTATIONS -> annotationBytes
        LibraryCleanupKind.ROLES -> roleBytes
    }
}

internal fun cleanLibraryRecords(state: LibraryState, kind: LibraryCleanupKind, bookIds: Set<String> = state.books.map { it.id }.toSet()): LibraryState =
    when (kind) {
        LibraryCleanupKind.HISTORY -> state.copy(books = state.books.map { if (it.id in bookIds) it.copy(lastReadAt = 0) else it })
        LibraryCleanupKind.ANNOTATIONS -> state.copy(annotations = state.annotations.filterNot {
            it.bookId in bookIds && it.kind != AnnotationKind.BOOKMARK
        })
        LibraryCleanupKind.ROLES -> state.copy(books = state.books.map { if (it.id in bookIds) it.copy(voiceRoles = emptyList()) else it },
            commonVoiceRoles = emptyList())
        LibraryCleanupKind.AI_AUDIO -> state
    }

/** Metadata sizes describe the relevant UTF-8 records, rather than promising filesystem block savings. */
internal suspend fun measureLibraryCleanup(context: Context, state: LibraryState): LibraryCleanupUsage = withContext(Dispatchers.IO) {
    val repository = LibraryRepository(context)
    val histories = state.books.filter { it.lastReadAt != 0L }.map { JSONObject().put("bookId", it.id).put("lastReadAt", it.lastReadAt) }
    val notes = state.annotations.filter { it.kind != AnnotationKind.BOOKMARK }.map(repository::writeAnnotation)
    val roles = state.books.flatMap { book -> book.voiceRoles.map { it.toJson().put("bookId", book.id) } } +
        state.commonVoiceRoles.map { it.toJson().put("scope", "common") }
    fun size(items: List<JSONObject>) = if (items.isEmpty()) 0L else JSONArray(items).toString().toByteArray(Charsets.UTF_8).size.toLong()
    LibraryCleanupUsage(size(histories), state.books.sumOf { AiSpeechCache(context).bytes(it.id) }, size(notes), size(roles))
}

internal suspend fun localBookStorageBytes(context: Context, book: Book): Long = withContext(Dispatchers.IO) {
    val books = File(context.filesDir, "books").canonicalFile
    val directory = File(books, book.id).canonicalFile
    require(directory.parentFile == books) { "图书目录越界" }
    val owned = if (directory.isDirectory) directory.walkTopDown().filter { it.isFile && it.canonicalFile.toPath().startsWith(directory.toPath()) }.sumOf { it.length() } else 0L
    val prefix = MessageDigest.getInstance("SHA-256").digest(book.id.toByteArray()).joinToString("") { "%02x".format(it) }
    val pages = listOf("reader-page-cache", "reader-chapter-page-cache", "reader-chapter-lengths").sumOf { name ->
        File(context.cacheDir, name).listFiles().orEmpty().filter { it.isFile && (it.name == "$prefix.bin" || it.name == "$prefix.json" || it.name.startsWith("${prefix}_")) }.sumOf { it.length() }
    }
    owned + pages + AiSpeechCache(context).bytes(book.id)
}
