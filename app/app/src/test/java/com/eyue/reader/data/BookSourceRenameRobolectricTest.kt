package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookSourceRenameRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "rename-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun externalEpubRenamesOnlyOriginalPreservesExtensionAndCanRollBack() {
        val original = File(root, "old.epub").apply { writeText("archive bytes") }
        val private = File(root, "private.epub").apply { original.copyTo(this) }
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(),
            sourceUri = original.absolutePath, sourcePath = private.absolutePath)
        val transaction = BookSourceRename(context, book)
        val renamed = transaction.apply("新名字")
        assertFalse(original.exists())
        assertEquals("新名字.epub", renamed.sourceFileName)
        assertEquals(private.absolutePath, renamed.sourcePath)
        assertEquals("archive bytes", documentFile(renamed.sourceUri!!)!!.readText())
        assertEquals("archive bytes", private.readText())
        transaction.rollback()
        assertEquals("archive bytes", original.readText())
        assertFalse(File(root, "新名字.epub").exists())
    }

    @Test fun localPdfUpdatesEveryPageReferenceAndNeverOverwritesAnExistingFile() {
        val original = File(root, "old.pdf").apply { writeText("pdf") }
        val existing = File(root, "existing.pdf").apply { writeText("other") }
        val book = Book(title = "old", format = BookFormat.PDF, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "pages", pages = listOf(PageSource(original.path, 0), PageSource(original.path, 1)))),
            sourcePath = original.path)
        assertThrows(IllegalArgumentException::class.java) { BookSourceRename(context, book).apply("existing") }
        assertEquals("pdf", original.readText())
        assertEquals("other", existing.readText())
        val renamed = BookSourceRename(context, book).apply("new.pdf")
        assertEquals("new.pdf", renamed.sourceFileName)
        assertTrue(renamed.chapters.single().pages.all { it.path == renamed.sourcePath })
        assertEquals("pdf", File(renamed.sourcePath!!).readText())
    }

    @Test fun privateSourceDescriptorsFollowRenameAndRollBackWithTheFile() {
        val folder = File(context.filesDir, "books/book-id").apply { mkdirs() }
        val original = File(folder, "source.epub").apply { writeText("archive") }
        val descriptor = File(folder, "chapter-0.source.json.deferred.json")
        val bytes = JSONObject().put("source", original.path).put("path", "body.xhtml").toString().toByteArray()
        descriptor.writeBytes(bytes)
        val book = Book(id = "book-id", title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = emptyList(), sourcePath = original.path)
        val transaction = BookSourceRename(context, book)
        val renamed = transaction.apply("new")
        assertEquals(renamed.sourcePath, JSONObject(descriptor.readText()).getString("source"))
        transaction.rollback()
        assertArrayEquals(bytes, descriptor.readBytes())
        assertTrue(original.isFile)
    }

    @Test fun documentProviderReturningANewUriUpdatesTheSourceReference() {
        val authority = "rename-provider-${UUID.randomUUID()}"
        val providerInfo = ProviderInfo().apply { this.authority = authority; exported = true }
        var name = "old.epub"
        val oldUri = DocumentsContract.buildDocumentUri(authority, name)
        var currentUri = oldUri
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    if (uri == currentUri) addRow(arrayOf<Any>(name, 123L))
                }
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                require(method == "android:renameDocument")
                name = requireNotNull(extras?.getString(OpenableColumns.DISPLAY_NAME))
                currentUri = DocumentsContract.buildDocumentUri(authority, name)
                return Bundle().apply { putParcelable("uri", currentUri) }
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, providerInfo)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { this.providerInfo = providerInfo })
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(),
            sourceUri = oldUri.toString(), sourcePath = File(root, "private.epub").path)
        val transaction = BookSourceRename(context, book)
        val renamed = transaction.apply("新书名")
        assertEquals("新书名.epub", name)
        assertEquals(currentUri.toString(), renamed.sourceUri)
        assertNotEquals(oldUri.toString(), renamed.sourceUri)
        assertEquals(book.sourcePath, renamed.sourcePath)
        transaction.rollback()
        assertEquals(oldUri, currentUri)
    }
}
