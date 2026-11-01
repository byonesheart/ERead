package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.eyue.reader.data.cloud.CloudDownloadLocation

internal fun localSourceAuthorized(context: Context, book: Book): Boolean {
    val sources = runCatching { LocalSourceChanges(context).sourcePaths(book) }.getOrElse { return false }
    return sources.isNotEmpty() && sources.all { source ->
        documentFile(source)?.let { return@all it.exists() && it.canRead() }
        val uri = Uri.parse(source)
        val persistent = context.contentResolver.persistedUriPermissions.any { grant ->
            grant.isReadPermission && (grant.uri == uri || DocumentsContract.isTreeUri(uri) && DocumentsContract.isTreeUri(grant.uri) &&
                uri.authority == grant.uri.authority && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(grant.uri))
        }
        (persistent || CloudDownloadLocation(context).owns(source)) && runCatching { requireDocumentAvailable(context, source) }.isSuccess
    }
}

internal fun localSourceFolderFiles(context: Context, tree: Uri, check: () -> Unit = {}): Map<Uri, String> {
    val files = linkedMapOf<Uri, String>()
    val visited = mutableSetOf<String>()
    fun collect(uri: Uri, parent: String) {
        if (!visited.add("$uri:$parent")) return
        check()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                check()
                val id = rows.getString(0)
                if (rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) collect(uri, id)
                else files[DocumentsContract.buildDocumentUriUsingTree(uri, id)] = rows.getString(1)
            }
        } ?: error("无法读取所选文件夹")
    }
    collect(tree, DocumentsContract.getTreeDocumentId(tree))
    return files
}

/** Folder grants bind paths first; filename fallback is allowed only for an unambiguous selection. */
internal fun localSourceGrantMatches(context: Context, books: List<Book>, uris: List<Uri>, check: () -> Unit = {}): Map<String, Uri> {
    val files = linkedMapOf<Uri, String>()
    uris.forEach { uri ->
        if (DocumentsContract.isTreeUri(uri) && "document" !in uri.pathSegments) files.putAll(localSourceFolderFiles(context, uri, check))
        else documentSourceFileInfo(context, uri.toString())?.name?.let { files[uri] = it }
    }
    fun identity(uri: Uri): String = if (DocumentsContract.isDocumentUri(context, uri))
        "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" else uri.toString()
    return books.mapNotNull { book ->
        val source = book.sourceUri ?: book.sourcePath
        val rootGrant = uris.firstOrNull { tree ->
            if (source == null || !DocumentsContract.isTreeUri(tree) || "document" in tree.pathSegments) false
            else {
                val original = Uri.parse(source)
                val originalId = when {
                    DocumentsContract.isTreeUri(original) && "document" !in original.pathSegments -> DocumentsContract.getTreeDocumentId(original)
                    DocumentsContract.isDocumentUri(context, original) -> DocumentsContract.getDocumentId(original)
                    else -> null
                }
                original.authority == tree.authority && originalId == DocumentsContract.getTreeDocumentId(tree)
            }
        }
        if (rootGrant != null) return@mapNotNull book.id to rootGrant
        val exact = files.keys.firstOrNull { source != null && identity(it) == identity(Uri.parse(source)) }
        val name = book.sourceFileName ?: source?.let(::documentFile)?.name
        val named = files.filterValues { it == name }.keys
        val selected = exact ?: named.singleOrNull()?.takeIf {
            books.count { other -> (other.sourceFileName ?: other.sourcePath?.let(::documentFile)?.name) == name } == 1
        }
        selected?.let { book.id to it }
    }.toMap()
}
