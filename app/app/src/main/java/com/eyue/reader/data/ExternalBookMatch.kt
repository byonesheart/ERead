package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import java.io.File
import java.util.UUID

/** External opens require equal names and bytes; size alone must never reuse a different edition. */
internal fun identicalExternalBook(context: Context, books: List<Book>, uri: Uri, name: String? = null): Book? {
    val info = documentSourceFileInfo(context, uri.toString()) ?: return null
    val candidates = books.filter { !it.cloudOnly && it.sourceFileName == (name ?: info.name) &&
        (info.bytes == null || it.sourceFileBytes == null || it.sourceFileBytes == info.bytes) }
    if (candidates.isEmpty()) return null
    fun digest(source: String): ByteArray = openDocumentStream(context, source).use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        hash.digest()
    }
    val incoming = digest(uri.toString())
    return candidates.firstOrNull { book ->
        val source = book.sourcePath?.takeIf { documentFile(it)?.isFile == true } ?: book.sourceUri ?: return@firstOrNull false
        runCatching { MessageDigest.isEqual(incoming, digest(source)) }.getOrDefault(false)
    }
}

/** Reuse the known shelf entry while keeping reading independent of an expiring external grant. */
internal fun retainExternalBookSource(context: Context, book: Book, incoming: Uri): Book {
    val root = File(context.filesDir, "books/${book.id}").apply { mkdirs() }.canonicalFile
    val existing = book.sourcePath?.let(::File)?.takeIf { it.isFile && it.canonicalPath.startsWith(root.path + File.separator) }
    val owned = existing ?: File(root, "external-${UUID.randomUUID()}.${book.format.name.lowercase()}").also { destination ->
        try { openDocumentStream(context, incoming.toString()).use { input -> destination.outputStream().use { input.copyTo(it) } } }
        catch (error: Throwable) { destination.delete(); throw error }
    }
    val aliases = setOfNotNull(book.sourcePath, book.sourceUri)
    fun path(value: String?) = if (value != null && value in aliases) owned.absolutePath else value
    return book.copy(sourcePath = owned.absolutePath, sourceUri = incoming.toString(), sourceLinked = false,
        coverPath = path(book.coverPath), chapters = book.chapters.map { chapter -> chapter.copy(textPath = path(chapter.textPath),
            pages = chapter.pages.map { page -> page.copy(path = requireNotNull(path(page.path)), textPath = path(page.textPath)) }) })
}
