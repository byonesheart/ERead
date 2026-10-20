package com.eyue.reader.data.cloud

import android.content.Context
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.openDocumentStream
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** Independent of filenames, chapter splitting, font/style, bookmarks and ZIP packaging. */
internal fun cloudBookContentIdentity(context: Context, book: Book, check: () -> Unit = {}): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        if (book.mode == LibraryMode.NOVEL) {
            val paths = if (book.format == BookFormat.PDF) book.chapters.flatMap { it.pages.map { page -> page.textPath } }
                else book.chapters.map { it.textPath }
            if (paths.isEmpty() || paths.any { it == null }) return null
            val chars = CharArray(8192)
            val normalized = ByteArray(chars.size * 2)
            var count = 0L
            for (path in paths) openDocumentStream(context, requireNotNull(path)).reader(Charsets.UTF_8).use { input ->
                while (true) {
                    check()
                    val size = input.read(chars)
                    if (size < 0) break
                    var bytes = 0
                    for (index in 0 until size) if (!chars[index].isWhitespace()) {
                        normalized[bytes++] = (chars[index].code shr 8).toByte()
                        normalized[bytes++] = chars[index].code.toByte()
                    }
                    digest.update(normalized, 0, bytes)
                    count += bytes / 2
                }
            }
            if (count == 0L) null else "text-v1:${CloudArchive.hex(digest.digest())}"
        } else {
            val pages = book.chapters.flatMap { it.pages }
            if (pages.isEmpty()) return null
            val hashes = HashMap<String, String>()
            for (page in pages) {
                check()
                val hash = hashes.getOrPut(page.path) { cloudSourceHash(context, page.path, check) }
                digest.update("$hash:${page.pdfPage}\n".toByteArray(Charsets.UTF_8))
            }
            "pages-v1:${CloudArchive.hex(digest.digest())}"
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }
}

internal fun cloudSourceHash(context: Context, path: String, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    openDocumentStream(context, path).use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            check()
            val count = input.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
    }
    return CloudArchive.hex(digest.digest())
}
