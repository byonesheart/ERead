package com.eyue.reader.data

import org.json.JSONObject
import java.security.MessageDigest

/** Keeps the existing fingerprint format while serializing chapter/style metadata only on structural changes. */
internal class CloudBookFingerprint(repository: LibraryRepository, state: LibraryState, book: Book) {
    private data class Token(val key: String, val start: Int, val end: Int)
    private val prefix: ByteArray?
    private val prefixDigest: MessageDigest
    private val keys: List<String>
    private val segments: List<ByteArray>
    private val tail = cloudBackupFingerprintTail(repository, state, book).map { it.toByteArray() }

    init {
        val json = repository.writeBook(book.copy(cloudBackupAt = 0, cloudEnabled = true, cloudOnly = false, lastReadAt = 0)).toString()
        val tokens = listOf("chapterIndex", "pageIndex", "characterOffset", "progress", "positionImagePath").mapNotNull { key ->
            val marker = "\"$key\":"
            val start = json.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return@mapNotNull null
            var end = start
            if (json[start] == '"') {
                end++
                while (end < json.length) {
                    if (json[end] == '\\') end += 2
                    else if (json[end++] == '"') break
                }
            } else while (end < json.length && json[end] != ',' && json[end] != '}') end++
            Token(key, start, end)
        }.sortedBy { it.start }
        val prefixBytes = json.substring(0, tokens.first().start).toByteArray()
        prefixDigest = MessageDigest.getInstance("SHA-256").apply { update(prefixBytes) }
        // Android's digest can clone its state, so the large serialized chapter prefix need not stay in memory.
        prefix = try { prefixDigest.clone(); null } catch (_: CloneNotSupportedException) { prefixBytes }
        keys = tokens.map { it.key }
        segments = tokens.mapIndexed { index, token ->
            json.substring(token.end, tokens.getOrNull(index + 1)?.start ?: json.length).toByteArray()
        }
    }

    fun fingerprint(book: Book): String {
        val digest = if (prefix == null) prefixDigest.clone() as MessageDigest
            else MessageDigest.getInstance("SHA-256").apply { update(prefix) }
        keys.forEachIndexed { index, key ->
            val value = when (key) {
                "chapterIndex" -> book.chapterIndex.toString()
                "pageIndex" -> book.pageIndex.toString()
                "characterOffset" -> book.characterOffset.toString()
                "progress" -> JSONObject.numberToString(book.progress.toDouble())
                else -> JSONObject.quote(book.positionImagePath)
            }
            digest.update(value.toByteArray())
            digest.update(segments[index])
        }
        digest.update(0)
        tail.forEach { digest.update(it); digest.update(0) }
        return "records-v2:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class CloudDirtyBookIndex(private val repository: LibraryRepository,
    private val prepare: (LibraryRepository, LibraryState, Book) -> CloudBookFingerprint = ::CloudBookFingerprint) {
    private data class Inputs(val book: Book, val annotations: List<Annotation>, val settings: ReaderSettings, val group: ShelfGroup?)
    private data class Position(val chapter: Int, val page: Int, val offset: Int, val progress: Float, val image: String?)
    private data class Entry(val inputs: Inputs, val template: CloudBookFingerprint, val position: Position, val digest: String)
    private val entries = mutableMapOf<String, Entry>()
    private var annotationSource: List<Annotation>? = null
    private var annotations: Map<String, List<Annotation>> = emptyMap()

    fun dirtyBookIds(state: LibraryState, account: String, history: CloudBackupHistory,
                     refreshFiles: Boolean = false, check: () -> Unit = {}): Set<String> {
        if (annotationSource !== state.annotations) {
            annotationSource = state.annotations
            annotations = state.annotations.groupBy { it.bookId }.mapValues { (_, notes) -> notes.sortedBy { it.id } }
        }
        val books = state.books.filter { it.cloudEnabled && !it.cloudOnly }
        entries.keys.retainAll(books.map { it.id }.toSet())
        return books.filter { book ->
            check()
            val inputs = Inputs(book.withoutReadingPosition().copy(cloudBackupAt = 0, cloudEnabled = true, cloudOnly = false,
                positionImagePath = book.positionImagePath?.let { "" }), annotations[book.id].orEmpty(),
                if (book.mode == LibraryMode.COMIC) state.comicSettings else state.settings,
                state.groups.find { it.id == book.groupId })
            val previous = entries[book.id]
            val template = previous?.takeIf { !refreshFiles && it.inputs == inputs }?.template
                ?: prepare(repository, state, book)
            // These small fields are the only varying input during reading; lastReadAt never changes a backup.
            val position = Position(book.chapterIndex, book.pageIndex, book.characterOffset, book.progress, book.positionImagePath)
            val digest = if (previous != null && template === previous.template && position == previous.position) previous.digest
                else template.fingerprint(book)
            entries[book.id] = Entry(inputs, template, position, digest)
            digest != history.uploadedDigest(account, book.id)
        }.map { it.id }.toSet()
    }
}
