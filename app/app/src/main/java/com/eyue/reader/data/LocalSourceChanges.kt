package com.eyue.reader.data

import android.content.Context
import java.security.MessageDigest

/** Background inspection never modifies reading caches or requests permission. */
internal class LocalSourceChanges(private val context: Context) {
    private val preferences = context.getSharedPreferences("local_source_snapshots", 0)
    private fun sources(book: Book): List<String> {
        if (book.format != BookFormat.IMAGES) return listOfNotNull(book.sourceUri ?: book.sourcePath)
        val pages = book.chapters.flatMap { it.pages }.map { it.path }.distinct()
        return if (pages.size == 1 && book.sourceFileName != null && book.sourceUri != null) listOf(book.sourceUri) else pages
    }

    private fun hash(source: String, check: () -> Unit): String = openDocumentStream(context, source).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }
    private fun fingerprint(sources: List<String>, check: () -> Unit): String = if (sources.size == 1) hash(sources.single(), check)
        else hex(MessageDigest.getInstance("SHA-256").apply {
            sources.forEach { update(hash(it, check).toByteArray()) }
        }.digest())
    private fun identity(sources: List<String>) = sources.joinToString("|") { documentSourceIdentity(context, it).split(':').takeLast(2).joinToString(":") }

    /** Reuse the digest from the import copy/TXT decode; metadata covers linked PDF/image imports. */
    fun imported(book: Book, digest: String? = null) {
        val sources = sources(book)
        if (sources.isEmpty()) return
        // A completed copy remains a valid import even if its temporary source has disappeared.
        val metadata = if (digest == null) runCatching { identity(sources) }.getOrNull() else null
        preferences.edit().remove(book.id).remove(book.id + ":text").remove(book.id + ":key")
            .remove(book.id + ":identity").apply {
                if (metadata != null) putString(book.id + ":identity", metadata)
                if (digest != null) putString(book.id, digest)
            }.apply()
    }
    /** Only a successful source save or download may reset the accepted content baseline. */
    fun accepted(book: Book, check: () -> Unit = {}) {
        imported(book, fingerprint(sources(book), check))
    }
    fun changed(book: Book, check: () -> Unit = {}): Boolean {
        if (book.cloudOnly || book.isDemo) return false
        val sources = sources(book)
        if (sources.isEmpty()) return false
        val current = fingerprint(sources, check)
        if (!preferences.contains(book.id + ":key"))
            preferences.getString(book.id, null)?.let { return current != it }
        val source = sources.singleOrNull()
        val privateCopy = book.sourcePath?.takeIf { source != null }?.let(::documentFile)?.takeIf {
            it.isFile && it.canonicalFile != source?.let(::documentFile)?.canonicalFile
        }
        if (privateCopy != null) {
            val expected = hash(privateCopy.path, check)
            preferences.edit().putString(book.id, expected).remove(book.id + ":key").apply()
            return current != expected
        }
        val previousIdentity = preferences.getString(book.id + ":identity", null)
        if (previousIdentity != null && previousIdentity != identity(sources)) return true
        if (source != null && book.sourceFileBytes != null &&
            documentSourceFileInfo(context, source)?.bytes?.let { it != book.sourceFileBytes } == true) return true
        // Legacy linked TXT books have chapter caches but no raw-file snapshot. Ignore only
        // whitespace introduced by chapter splitting; do not mistake a first scan for an import.
        if (book.format == BookFormat.TXT && source != null && book.chapters.all { chapter ->
            chapter.textPath?.let(::documentFile)?.canonicalFile != documentFile(source)?.canonicalFile
        }) {
            fun textHash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.filterNot(Char::isWhitespace).toByteArray()))
            val expected = preferences.getString(book.id + ":text", null) ?: textHash(book.chapters.joinToString("") {
                check(); readChapterText(it)
            }).also { preferences.edit().putString(book.id + ":text", it).apply() }
            val actual = openDocumentStream(context, source).use { textHash(TextParser.decode(it.readBytes())) }
            if (actual != expected) return true
        }
        preferences.edit().putString(book.id, current).remove(book.id + ":key").apply()
        return false
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
