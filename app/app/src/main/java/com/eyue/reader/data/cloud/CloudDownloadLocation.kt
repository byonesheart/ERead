package com.eyue.reader.data.cloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.eyue.reader.data.*
import java.io.File

/** Public source files live in Downloads; rendering, notes and caches remain private. */
internal class CloudDownloadLocation(private val context: Context) {
    private val preferences = context.getSharedPreferences("cloud_download_location", 0)
    val tree: Uri? get() = preferences.getString("tree", null)?.let(Uri::parse)
    fun owns(source: String): Boolean = source in preferences.getStringSet("owned", emptySet()).orEmpty()
    fun select(uri: Uri?) { preferences.edit().apply { if (uri == null) remove("tree") else putString("tree", uri.toString()) }.apply() }
    @Suppress("DEPRECATION")
    fun label(): String = tree?.let { "所选文件夹：" + DocumentsContract.getTreeDocumentId(it) }
        ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "E阅").absolutePath

    fun publish(book: Book, check: () -> Unit = {}): Book {
        val destination = tree
        val created = mutableListOf<String>()
        fun copy(source: String, name: String, mime: String): String {
            check()
            val safe = name.replace('\\', '_').replace(Regex("[\\/:*?\"<>|\u0000-\u001f]"), "_").take(180).ifBlank { "book" }
            val uri: Uri
            var legacy: File? = null
            if (destination != null) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
                uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, mime, safe)) { "无法在所选下载文件夹创建文件" }
            } else if (Build.VERSION.SDK_INT >= 29) {
                uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/E阅/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "无法在系统下载目录创建文件" }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "E阅")
                require(directory.mkdirs() || directory.isDirectory) { "无法写入系统下载目录，请授予存储权限或另选下载文件夹" }
                var file = File(directory, safe)
                var number = 1
                while (file.exists()) file = File(directory, "${File(safe).nameWithoutExtension} (${number++}).${File(safe).extension}")
                legacy = file; uri = Uri.fromFile(file)
            }
            created += uri.toString()
            val output = legacy?.outputStream() ?: context.contentResolver.openOutputStream(uri, "w")
                ?: error("无法写入下载文件")
            val bytes = output.use { stream -> openDocumentStream(context, source).use { input -> CloudArchive.copy(input, stream, CLOUD_ARCHIVE_LIMIT, check) } }
            val savedBytes = legacy?.length() ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            require(savedBytes == null || savedBytes < 0 || savedBytes == bytes) { "下载文件写入不完整，未保存" }
            if (destination == null && Build.VERSION.SDK_INT >= 29) {
                check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) { "下载文件未能公开保存" }
            }
            return legacy?.absolutePath ?: uri.toString()
        }
        try {
            if (book.format == BookFormat.IMAGES) {
                val paths = linkedMapOf<String, String>()
                val exported = book.copy(chapters = book.chapters.map { chapter -> chapter.copy(pages = chapter.pages.map { page ->
                    val path = paths.getOrPut(page.path) {
                        val extension = page.path.substringAfterLast('.', "jpg")
                        copy(page.path, "${book.title}-${paths.size + 1}.$extension", if (extension == "pdf") "application/pdf" else "image/$extension")
                    }
                    page.copy(path = path)
                }) })
                preferences.edit().putStringSet("owned", preferences.getStringSet("owned", emptySet()).orEmpty() + created).apply()
                return exported
            }
            val source = book.sourcePath ?: return book
            val mime = when (book.format) {
                BookFormat.EPUB -> "application/epub+zip"
                BookFormat.MOBI -> "application/x-mobipocket-ebook"
                BookFormat.TXT -> "text/plain"
                BookFormat.PDF -> "application/pdf"
                BookFormat.IMAGES -> "application/octet-stream"
            }
            val name = book.sourceFileName ?: "${book.title}.${book.format.name.lowercase()}"
            val uri = copy(source, name, mime)
            preferences.edit().putStringSet("owned", preferences.getStringSet("owned", emptySet()).orEmpty() + created).apply()
            return book.copy(sourceUri = uri, sourceLinked = false, sourceFileName = documentSourceFileInfo(context, uri)?.name ?: name)
        } catch (error: Throwable) {
            created.forEach { path -> runCatching {
                val uri = Uri.parse(path)
                if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
                else if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                else context.contentResolver.delete(uri, null, null)
            } }
            throw error
        }
    }
}
