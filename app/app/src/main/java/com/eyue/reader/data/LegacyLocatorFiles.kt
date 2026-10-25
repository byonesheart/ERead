package com.eyue.reader.data

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only size accounting and explicit cleanup for obsolete locator sidecars. */
internal class LegacyLocatorFiles(private val directory: File) {
    suspend fun removeAnnotation(bookId: String, annotationId: String) = withContext(Dispatchers.IO) {
        synchronized(fileLock) { file(bookId, "annotation-${safeId(annotationId)}").delete() }
    }

    suspend fun removeBook(bookId: String): Boolean = withContext(Dispatchers.IO) { removeBookNow(bookId) }

    /** Also used by the repository's existing synchronous, I/O-thread book deletion. */
    fun removeBookNow(bookId: String): Boolean =
        synchronized(fileLock) {
            val target = File(directory, safeId(bookId)).canonicalFile
            require(target.toPath().startsWith(directory.canonicalFile.toPath()) && target != directory.canonicalFile)
            target.deleteRecursively()
        }

    suspend fun annotationBytes(bookId: String, annotationId: String): Long = withContext(Dispatchers.IO) {
        synchronized(fileLock) { file(bookId, "annotation-${safeId(annotationId)}").baseFile.length() }
    }

    suspend fun bytes(bookId: String): Long = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val target = File(directory, safeId(bookId)).canonicalFile
            require(target.toPath().startsWith(directory.canonicalFile.toPath()))
            if (target.isDirectory) target.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }
    }

    private fun file(bookId: String, name: String) = AtomicFile(File(directory, "${safeId(bookId)}/$name.json"))

    private companion object {
        val fileLock = Any()
        fun safeId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
