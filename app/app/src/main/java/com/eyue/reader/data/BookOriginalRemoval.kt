package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/** External single files are opt-in. Imported private copies are owned by normal cache removal. */
internal fun bookOriginalSource(context: Context, book: Book): String? {
    if (book.cloudOnly || book.isDemo) return null
    val source = book.sourceUri ?: book.sourcePath ?: return null
    documentFile(source)?.let { file ->
        if (listOf(context.filesDir, context.cacheDir).any { file.canonicalFile.toPath().startsWith(it.canonicalFile.toPath()) } || file.isDirectory) return null
    }
    if (documentSourceFileInfo(context, source)?.directory == true) return null
    return source
}

internal fun deleteBookOriginal(context: Context, book: Book) {
    val source = bookOriginalSource(context, book) ?: return
    try {
        documentFile(source)?.let { file ->
            if (!file.exists()) return
            require(file.isFile) { "仅支持删除原文件，不删除文件夹" }
            check(file.delete()) { "原文件删除失败，请检查文件权限" }
            return
        }
        val info = documentSourceFileInfo(context, source) ?: throw IOException("无法读取原文件，请重新授予删除权限")
        require(!info.directory) { "不删除原文件夹" }
        val uri = Uri.parse(source)
        val deleted = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
            else context.contentResolver.delete(uri, null, null) > 0
        check(deleted) { "原文件删除失败，书架记录已保留" }
    } catch (failure: SecurityException) {
        throw IOException("没有原文件删除权限，书架记录已保留，请重新授权", failure)
    }
}
