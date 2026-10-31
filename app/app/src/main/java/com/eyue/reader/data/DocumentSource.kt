package com.eyue.reader.data

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** All source operations are blocking: callers use Dispatchers.IO. Never delete a linked source. */
fun openDocumentStream(context: Context, pathOrUri: String): InputStream = sourceRead {
    documentFile(pathOrUri)?.inputStream()
        ?: context.contentResolver.openInputStream(Uri.parse(pathOrUri))
        ?: throw FileNotFoundException(pathOrUri)
}

/** PdfRenderer may take ownership of the descriptor. Pipes and asset slices use a short-lived copy. */
fun <T> withDocumentDescriptor(context: Context, pathOrUri: String, block: (ParcelFileDescriptor) -> T): T {
    documentFile(pathOrUri)?.let { file ->
        return sourceRead { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.use(block)
    }
    val asset = sourceRead { context.contentResolver.openAssetFileDescriptor(Uri.parse(pathOrUri), "r")
        ?: throw FileNotFoundException(pathOrUri) }
    asset.use {
        if (wholeSeekableDocument(asset)) return block(asset.parcelFileDescriptor)
    }
    return withTemporaryDocument(context, pathOrUri) { file ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(block)
    }
}

/** A caller-owned temporary file may be reused for non-seekable sources; serialize access and delete it on close. */
fun <T> withLocalDocument(context: Context, pathOrUri: String, temporaryFile: File? = null, block: (File) -> T): T {
    documentFile(pathOrUri)?.let { file ->
        requireDocumentAvailable(context, pathOrUri)
        return block(file)
    }
    if (temporaryFile?.isFile == true) return block(temporaryFile)
    val asset = sourceRead { context.contentResolver.openAssetFileDescriptor(Uri.parse(pathOrUri), "r")
        ?: throw FileNotFoundException(pathOrUri) }
    asset.use {
        if (wholeSeekableDocument(asset)) {
            val file = File("/proc/self/fd/${asset.parcelFileDescriptor.fd}")
            // Some providers/devices disallow reopening the descriptor; then use the stream fallback.
            val reopenable = try { RandomAccessFile(file, "r").use { true } } catch (_: IOException) { false }
            if (reopenable) return block(file)
        }
    }
    if (temporaryFile == null) return withTemporaryDocument(context, pathOrUri, block)
    temporaryFile.parentFile?.mkdirs()
    try {
        sourceRead { openDocumentStream(context, pathOrUri).use { input -> temporaryFile.outputStream().use { input.copyTo(it) } } }
    } catch (error: Throwable) {
        temporaryFile.delete()
        throw error
    }
    return block(temporaryFile)
}

private fun wholeSeekableDocument(asset: AssetFileDescriptor): Boolean {
    if (asset.startOffset != 0L) return false
    val length = asset.declaredLength
    val size = asset.parcelFileDescriptor.statSize
    if (length >= 0 && size >= 0 && length != size) return false
    return try {
        Os.lseek(asset.parcelFileDescriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (_: android.system.ErrnoException) { false }
}

private fun <T> withTemporaryDocument(context: Context, source: String, block: (File) -> T): T {
    val directory = File(context.cacheDir, "document-sources").apply { mkdirs() }
    val temporary = File.createTempFile("reading-", ".tmp", directory)
    try {
        sourceRead { openDocumentStream(context, source).use { input -> temporary.outputStream().use { input.copyTo(it) } } }
        return block(temporary)
    } finally {
        temporary.delete()
    }
}

/** Returns true only for a readable local file or an actually retained SAF read grant. */
fun retainDocumentReadPermission(context: Context, uri: Uri): Boolean {
    documentFile(uri.toString())?.let { return it.exists() && it.canRead() }
    if (uri.scheme != "content") return false
    val permissionUri = if (DocumentsContract.isTreeUri(uri)) {
        DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri))
    } else uri
    try {
        context.contentResolver.takePersistableUriPermission(permissionUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // ACTION_SEND and many ACTION_VIEW providers offer only a temporary grant.
    } catch (_: IllegalArgumentException) { }
    return context.contentResolver.persistedUriPermissions.any { grant ->
        grant.isReadPermission && (grant.uri == permissionUri || (DocumentsContract.isTreeUri(grant.uri) &&
            DocumentsContract.isTreeUri(uri) && grant.uri.authority == uri.authority &&
            DocumentsContract.getTreeDocumentId(grant.uri) == DocumentsContract.getTreeDocumentId(uri)))
    }
}

fun requireDocumentAvailable(context: Context, pathOrUri: String) {
    sourceRead {
        documentFile(pathOrUri)?.let { file ->
            if (!file.exists()) throw FileNotFoundException(pathOrUri)
            if (!file.canRead()) throw SecurityException(pathOrUri)
            if (!file.isDirectory) file.inputStream().use { }
            return@sourceRead
        }
        val uri = Uri.parse(pathOrUri)
        val document = if (DocumentsContract.isTreeUri(uri) && "document" !in uri.pathSegments)
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        var directory = DocumentsContract.isTreeUri(uri) && "document" !in uri.pathSegments
        val queried = context.contentResolver.query(document, null, null, null, null)
        queried?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException(pathOrUri)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else context.contentResolver.getType(document)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val flags = if (flagsIndex >= 0) cursor.getInt(flagsIndex) else 0
            directory = directory || mime in setOf(DocumentsContract.Document.MIME_TYPE_DIR, "inode/directory", "resource/folder") ||
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }
        if (directory && queried == null) throw FileNotFoundException(pathOrUri)
        if (!directory) openDocumentStream(context, pathOrUri).use { }
    }
}

fun requireBookSourceAvailable(context: Context, book: Book) {
    if (!book.sourceLinked) return
    (book.sourceUri ?: book.sourcePath)?.let { requireDocumentAvailable(context, it) }
    book.chapters.getOrNull(book.chapterIndex)?.pages?.getOrNull(book.pageIndex)?.let { page ->
        requireDocumentAvailable(context, page.path)
    }
}

/** A stable cache identity, without reading/copying the image bytes. */
fun documentSourceIdentity(context: Context, pathOrUri: String): String {
    documentFile(pathOrUri)?.let { return "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    var size = -1L
    var modified = -1L
    sourceRead {
        context.contentResolver.query(Uri.parse(pathOrUri), null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException(pathOrUri)
            fun number(column: String): Long = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong) ?: -1L
            size = number(OpenableColumns.SIZE)
            modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        }
    }
    return "$pathOrUri:$size:$modified"
}

internal data class DocumentSourceFileInfo(val name: String?, val bytes: Long?, val directory: Boolean, val flags: Int? = null)

/** Metadata only; never opens, copies or scans the book contents or a cache directory. */
internal fun documentSourceFileInfo(context: Context, pathOrUri: String): DocumentSourceFileInfo? = runCatching {
    documentFile(pathOrUri)?.let { file ->
        return@runCatching if (!file.exists()) null else
            DocumentSourceFileInfo(file.name, file.length().takeIf { file.isFile }, file.isDirectory)
    }
    val uri = Uri.parse(pathOrUri)
    val tree = DocumentsContract.isTreeUri(uri) && "document" !in uri.pathSegments
    val document = if (tree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    context.contentResolver.query(document, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }
            ?.let(cursor::getString)?.takeIf { it.isNotBlank() }
        val directory = tree || string(DocumentsContract.Document.COLUMN_MIME_TYPE) in
            setOf(DocumentsContract.Document.MIME_TYPE_DIR, "inode/directory", "resource/folder")
        val bytes = if (directory) null else cursor.getColumnIndex(OpenableColumns.SIZE)
            .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)?.takeIf { it >= 0 }
        val flags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getInt)
        DocumentSourceFileInfo(string(OpenableColumns.DISPLAY_NAME), bytes, directory, flags)
    }
}.getOrNull()

/** Old entries gain source identity lazily; private parser filenames must not become original names. */
internal fun hydrateBookSourceIdentity(context: Context, book: Book): Book {
    if (book.cloudOnly) return book
    val singleFile = book.format != BookFormat.IMAGES ||
        book.chapters.asSequence().flatMap { it.pages.asSequence() }.map { it.path }.distinct().take(2).count() == 1
    if (!singleFile) return book
    val original = book.sourceUri?.let { documentSourceFileInfo(context, it) }
    val privateSource = book.sourcePath?.let { documentSourceFileInfo(context, it) }
    val name = original?.name ?: book.sourceFileName ?: privateSource?.name?.takeUnless {
        book.sourcePath?.startsWith(File(context.filesDir, "books").absolutePath + File.separator) == true
    }
    if (original?.directory == true || privateSource?.directory == true)
        return book.copy(sourceFileName = name, sourceFileBytes = null)
    val currentBytes = if (book.sourceLinked) original?.bytes ?: privateSource?.bytes else privateSource?.bytes ?: original?.bytes
    return book.copy(sourceFileName = name, sourceFileBytes = currentBytes ?: book.sourceFileBytes)
}

/** Exact source filename and original file size are sufficient by the user's duplicate rule. */
internal fun cloudBookSourceMatch(local: Book, remote: Book): CloudBookMatch =
    if (!local.sourceFileName.isNullOrBlank() && local.sourceFileName == remote.sourceFileName &&
        local.sourceFileBytes != null && local.sourceFileBytes >= 0 && local.sourceFileBytes == remote.sourceFileBytes)
        CloudBookMatch.SAME else CloudBookMatch.UNKNOWN

internal fun documentFile(pathOrUri: String): File? {
    File(pathOrUri).takeIf { it.isAbsolute }?.let { return it }
    val uri = Uri.parse(pathOrUri)
    return when (uri.scheme) {
        null -> File(pathOrUri)
        "file" -> uri.path?.let(::File)
        else -> null
    }
}

internal inline fun <T> sourceRead(block: () -> T): T = try { block() }
catch (error: SecurityException) { throw IOException("源文件读取权限已失效，请重新选择", error) }
catch (error: FileNotFoundException) { throw IOException("源文件已移动或删除，或当前来源不可读取，请重新选择", error) }
