package com.eyue.reader.data

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookFolderImportRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        root = File("build/folder-import-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun recursiveFolderOpensTwoBooksConcurrentlyButSerializesLibraryCallbacks() = runBlocking {
        val source = File(root, "source").apply { mkdirs() }
        File(source, "a.txt").writeText("第一章 开始\n第一本的正文。")
        File(source, "nested").mkdirs()
        File(source, "nested/b.TXT").writeText("第一章 继续\n第二本的正文。")
        File(source, "skip.png").writeText("Not a book")
        val opened = ConcurrentHashMap.newKeySet<String>()
        val ready = CountDownLatch(2)
        val provider = BookFolderTestProvider(source) { file ->
            if (opened.add(file.name)) ready.countDown()
            check(ready.await(10, TimeUnit.SECONDS)) { "Books were not imported in parallel" }
        }.register(context)
        val delivered = mutableListOf<Book>()
        var accepting = false
        val imported = DocumentImporter(context).importFolderBooks(provider.tree, LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE) {
            assertFalse(accepting)
            accepting = true
            delay(30)
            delivered += it
            accepting = false
        }
        assertEquals(2, imported.size)
        assertEquals(imported.map { it.id }.toSet(), delivered.map { it.id }.toSet())
        assertEquals(setOf("a", "b"), imported.map { it.title }.toSet())
        assertTrue(imported.all { it.format == BookFormat.TXT && it.chapters.isNotEmpty() })
    }

    @Test fun invalidSupportedFilesAreReportedWithoutLosingOtherImportedBooks() = runBlocking {
        val source = File(root, "source").apply { mkdirs() }
        File(source, "valid.txt").writeText("第一章\n保留这本书的正文。")
        File(source, "broken.mobi").writeText("invalid MOBI")
        File(source, "broken.epub").writeText("invalid EPUB")
        File(source, "broken.pdf").writeText("invalid PDF")
        File(source, "ignored.docx").writeText("ignored")
        val provider = BookFolderTestProvider(source).register(context)
        val importer = DocumentImporter(context)
        val books = importer.importFolderBooks(provider.tree, LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE)
        assertEquals("valid", books.single().title)
        assertEquals(3, importer.lastImportErrors.size)
        assertTrue(importer.lastImportErrors.none { it.contains("ignored.docx") })
    }
}

internal class BookFolderTestProvider(private val root: File, private val beforeOpen: (File) -> Unit = {}) : ContentProvider() {
    private val authority = "book-folder-${UUID.randomUUID()}"
    val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(authority, "root")
    fun register(context: Context): BookFolderTestProvider {
        attachInfo(context, ProviderInfo().apply { authority = this@BookFolderTestProvider.authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, this)
        return this
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    private fun file(uri: Uri): File {
        val id = DocumentsContract.getDocumentId(uri)
        return if (id == "root") root else File(root, id.removePrefix("root/"))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val current = file(uri)
        return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)).apply {
            val entries = if (uri.lastPathSegment == "children") current.listFiles().orEmpty().toList() else listOf(current)
            for (entry in entries) {
                val relative = entry.relativeTo(root).invariantSeparatorsPath
                val id = if (relative.isEmpty()) "root" else "root/$relative"
                addRow(arrayOf(id, entry.name, entry.length(), if (entry.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                    else "application/octet-stream", 0))
            }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val source = file(uri); beforeOpen(source)
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.parseMode(mode))
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
