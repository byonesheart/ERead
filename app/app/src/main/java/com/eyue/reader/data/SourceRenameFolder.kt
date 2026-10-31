package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** Resolve through a user-granted folder, and verify bytes before switching away from a provider alias. */
internal fun sourceInRenameFolder(context: Context, book: Book, tree: Uri, name: String): String {
    require(DocumentsContract.isTreeUri(tree)) { "请选择原文件所在文件夹" }
    val parent = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
    val candidates = mutableListOf<Pair<Uri, Int?>>()
    context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) != name || mimeIndex >= 0 &&
                cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) continue
            candidates += DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex)) to
                flagsIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getInt)
        }
    } ?: throw IOException("无法读取所选文件夹，请重新授权")
    require(candidates.isNotEmpty()) { "所选文件夹内未找到《$name》，请选择原文件所在文件夹" }
    val original = book.sourcePath?.takeIf { documentFile(it)?.isFile == true } ?: EbookSourceWriteback.source(book)
    val matches = candidates.filter { (uri, _) -> EbookSourceWriteback.sameDocument(context, original, uri.toString()) }
    require(matches.size == 1) { if (matches.isEmpty()) "同名文件内容与原书不一致，未重命名" else "文件夹中存在多个相同文件，无法确定原文件，未重命名" }
    val (uri, flags) = matches.single()
    require(flags == null || flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) {
        "所选文件夹的文件服务仍不支持重命名，请在系统文件管理器中重命名后重新选择原文件"
    }
    return uri.toString()
}
