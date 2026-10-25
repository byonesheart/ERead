package com.eyue.reader.data

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Test-APK-only SAF provider; the app APK never contains these fixture files or permissions. */
class ComicFixtureDocumentsProvider : DocumentsProvider() {
    private data class Node(val id: String, val parent: String?, val name: String, val mime: String, val file: File? = null)
    private val nodes = linkedMapOf<String, Node>()

    override fun onCreate(): Boolean {
        val directory = File(requireNotNull(context).cacheDir, "comic-provider-fixture").apply { mkdirs() }
        fun folder(id: String, parent: String?, name: String) {
            nodes[id] = Node(id, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        fun image(id: String, parent: String, name: String, color: Int) {
            val file = File(directory, "$id.png")
            val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            nodes[id] = Node(id, parent, name, "image/png", file)
        }
        folder("root", null, "自然排序漫画")
        image("root10", "root", "page-10.png", Color.BLUE)
        image("root2", "root", "page-2.png", Color.GREEN)
        image("root1", "root", "page-1.png", Color.RED)
        folder("c10", "root", "第10话")
        folder("c2", "root", "第2话")
        image("c2-10", "c2", "10.png", Color.BLUE)
        image("c2-2", "c2", "2.png", Color.GREEN)
        folder("nested", "c2", "bonus")
        image("nested-1", "nested", "1.png", Color.RED)
        image("c10-1", "c10", "1.png", Color.MAGENTA)
        nodes["flagroot"] = Node("flagroot", null, "flags漫画", "")
        image("flag1", "flagroot", "1.png", Color.GREEN)
        nodes["inoderoot"] = Node("inoderoot", null, "inode漫画", "inode/directory")
        image("inode1", "inoderoot", "1.png", Color.BLUE)
        nodes["resroot"] = Node("resroot", null, "resource漫画", "resource/folder")
        image("res1", "resroot", "1.png", Color.GREEN)
        val opaque = File(directory, "opaque.txt").apply { writeText("书名：无扩展名文件\n作者：元数据作者\n\n第一章\n正文。") }
        nodes["opaque"] = Node("opaque", null, "opaque-file", "text/plain", opaque)
        val pdf = File(directory, "chapter3.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Chapter 3 page ${index + 1}", 30f, 60f, Paint().apply { textSize = 18f })
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        nodes["pdf3"] = Node("pdf3", "root", "第3话.pdf", "application/pdf", pdf)
        nodes["binarypdf"] = Node("binarypdf", null, "opaque-file", "application/octet-stream", pdf)
        folder("hroot", null, "层级漫画")
        folder("h-empty", "hroot", "空目录")
        folder("hwrap", "hroot", "单独打包目录")
        folder("h2", "hwrap", "第2话")
        folder("h10", "hwrap", "第10话")
        image("h2-direct", "h2", "1.png", Color.RED)
        nodes["h2-pdf"] = Node("h2-pdf", "h2", "第2节.pdf", "application/pdf", pdf)
        folder("h2-s10", "h2", "第10节")
        image("h2-s10-direct", "h2-s10", "1.png", Color.GREEN)
        folder("h2-p2", "h2-s10", "第2段")
        folder("h2-p10", "h2-s10", "第10段")
        image("h2-p2-page", "h2-p2", "1.png", Color.BLUE)
        image("h2-p10-page", "h2-p10", "1.png", Color.MAGENTA)
        image("h10-page", "h10", "1.png", Color.BLUE)
        return true
    }

    // Deliberately leave getType empty: consumers must also inspect queryDocument's MIME column.
    override fun getDocumentType(documentId: String): String = ""

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> "fixture"
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root"
                DocumentsContract.Root.COLUMN_TITLE -> "漫画测试"
                DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                else -> null
            } }.toTypedArray<Any?>())
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection, listOf(nodes[documentId] ?: throw FileNotFoundException(documentId)), documentId == "opaque")

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor(projection, nodes.values.filter { it.parent == parentDocumentId })

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var node = nodes[documentId]
        while (node != null) {
            if (node.id == parentDocumentId) return true
            node = node.parent?.let(nodes::get)
        }
        return false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(nodes[documentId]?.file ?: throw FileNotFoundException(documentId), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "grant_fixture" && arg == "com.eyue.reader") {
            val owner = requireNotNull(context)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            listOf("root", "hroot").forEach { owner.grantUriPermission(arg, DocumentsContract.buildTreeDocumentUri(AUTHORITY, it), flags) }
            nodes.keys.forEach { owner.grantUriPermission(arg, DocumentsContract.buildDocumentUri(AUTHORITY, it), flags) }
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }

    private fun cursor(projection: Array<out String>?, values: List<Node>, opaque: Boolean = false): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
        return MatrixCursor(columns).apply {
            values.forEach { node ->
                addRow(columns.map { when (it) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (opaque) "opaque-file" else node.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (opaque) "" else node.mime
                    DocumentsContract.Document.COLUMN_FLAGS -> if (node.file == null && node.mime !in setOf("inode/directory", "resource/folder")) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                    DocumentsContract.Document.COLUMN_SIZE -> node.file?.length() ?: 0L
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
    }

    companion object { const val AUTHORITY = "com.eyue.reader.test.comicfixture" }
}
