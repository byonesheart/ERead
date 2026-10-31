package com.eyue.reader.data

import android.content.Context
import java.security.MessageDigest

/** Only hashes readable original sources; never changes book text or prompts for access. */
internal class LocalSourceChanges(private val context: Context) {
    private val preferences = context.getSharedPreferences("local_source_snapshots", 0)
    private fun hash(source: String, check: () -> Unit): String = openDocumentStream(context, source).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun changed(book: Book, check: () -> Unit = {}): Boolean {
        if (book.cloudOnly || book.isDemo) return false
        val sources = if (book.format == BookFormat.IMAGES) book.chapters.flatMap { it.pages }.map { it.path }.distinct()
            else listOfNotNull(book.sourceUri ?: book.sourcePath)
        if (sources.isEmpty()) return false
        val source = sources.singleOrNull()
        val key = "${book.id}:${book.contentVersion}:${sources.joinToString("|")}"
        val current = if (sources.size == 1) hash(sources.single(), check) else {
            val digest = MessageDigest.getInstance("SHA-256")
            sources.forEach { digest.update(hash(it, check).toByteArray()) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val saved = preferences.getString(book.id, null)
        val previous = if (preferences.getString(book.id + ":key", null) == key) saved else null
        val expected = previous ?: book.sourcePath?.takeIf { source != null }?.let { path ->
            documentFile(path)?.takeIf { it.isFile && it.canonicalFile != source?.let(::documentFile)?.canonicalFile }
                ?.let { hash(path, check) }
        } ?: current
        if (previous == null) preferences.edit().putString(book.id, expected).putString(book.id + ":key", key).apply()
        return current != expected
    }
}
