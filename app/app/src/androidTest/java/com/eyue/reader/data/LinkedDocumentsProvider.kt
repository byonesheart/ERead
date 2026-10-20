package com.eyue.reader.data

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Separate test-only provider offering both durable SAF documents and a streaming share. */
class LinkedDocumentsProvider : DocumentsProvider() {
    private data class Node(val id: String, val name: String, val mime: String, val file: File? = null,
                            val parent: String? = null, val pipe: Boolean = false)
    private val nodes = linkedMapOf<String, Node>()
    @Volatile private var heldRead: CountDownLatch? = null

    override fun onCreate(): Boolean {
        val directory = File(requireNotNull(context).cacheDir, "linked-provider-fixture").apply { mkdirs() }
        val text = File(directory, "linked.txt").apply { writeText("书名：引用小说\n作者：测试作者\n\n第一章 开始\n原文件正文。\n\n第二章 后续\n下一章节。") }
        val image = File(directory, "page.png")
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val pdf = File(directory, "chapter.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
            page.canvas.drawColor(Color.WHITE)
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val epub = File(directory, "linked.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
            entry("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>引用样式</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="css" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry("OPS/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="style.css"/></head><body><p class="title">章节标题</p><p><b>粗体正文</b>与正常正文。</p></body></html>""")
            entry("OPS/style.css", ".title { text-align: center; font-size: 1.5em; }")
        }
        nodes["root"] = Node("root", "引用漫画", DocumentsContract.Document.MIME_TYPE_DIR)
        nodes["image"] = Node("image", "1.png", "image/png", image, "root")
        nodes["pdf"] = Node("pdf", "第2话.pdf", "application/pdf", pdf, "root")
        nodes["txt"] = Node("txt", "linked.txt", "text/plain", text)
        nodes["epub"] = Node("epub", "linked.epub", "application/epub+zip", epub)
        nodes["pipe"] = Node("pipe", "shared.txt", "text/plain", text, pipe = true)
        nodes["held"] = Node("held", "held.txt", "text/plain", text, pipe = true)
        nodes["temporary-image"] = Node("temporary-image", "shared.png", "image/png", image)
        return true
    }

    override fun getDocumentType(documentId: String): String = nodes[documentId]?.mime ?: throw FileNotFoundException(documentId)
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            DocumentsContract.Root.COLUMN_ROOT_ID -> "linked-fixture"
            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root"
            DocumentsContract.Root.COLUMN_TITLE -> "引用测试"
            DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            else -> null
        } }.toTypedArray()) }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection, listOf(nodes[documentId] ?: throw FileNotFoundException(documentId)))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor(projection, nodes.values.filter { it.parent == parentDocumentId })
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == documentId || nodes[documentId]?.parent == parentDocumentId

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        val file = node.file ?: throw FileNotFoundException(documentId)
        if (documentId == "held") heldRead?.let {
            if (!it.await(30, TimeUnit.SECONDS)) throw FileNotFoundException("Held test source timed out")
        }
        if (!node.pipe) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output -> file.inputStream().use { it.copyTo(output) } } }
            catch (_: java.io.IOException) { } // Bounds/probe readers may close a stream before its end.
        }.start()
        return pipe[0]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (arg == "com.eyue.reader" && method in setOf("hold", "release")) {
            if (method == "hold") heldRead = CountDownLatch(1)
            else { heldRead?.countDown(); heldRead = null }
            return Bundle.EMPTY
        }
        if (arg == "com.eyue.reader" && method in setOf("grant", "revoke")) {
            val owner = requireNotNull(context)
            val id = extras?.getString("id") ?: "root"
            val uri = if (id == "root") DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)
                else DocumentsContract.buildDocumentUri(AUTHORITY, id)
            if (method == "revoke") owner.revokeUriPermission(arg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else owner.grantUriPermission(arg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (id == "root") Intent.FLAG_GRANT_PREFIX_URI_PERMISSION else 0) or
                (if (extras?.getBoolean("persistent", false) == true) Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else 0))
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }

    private fun cursor(projection: Array<out String>?, selected: List<Node>): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply { selected.forEach { node -> addRow(columns.map { when (it) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime
            DocumentsContract.Document.COLUMN_FLAGS -> if (node.file == null) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
            DocumentsContract.Document.COLUMN_SIZE -> node.file?.length() ?: 0L
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.file?.lastModified() ?: 0L
            else -> null
        } }.toTypedArray()) } }
    }

    companion object { const val AUTHORITY = "com.eyue.reader.test.linkedfixture" }
}
