package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

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
