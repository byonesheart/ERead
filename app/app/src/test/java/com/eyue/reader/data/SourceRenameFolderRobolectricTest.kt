package com.eyue.reader.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
class SourceRenameFolderRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "rename-folder-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun unsupportedProviderWithoutCapabilityFlagsRequestsFolderAccess() {
        val source = File(root, "old.epub").apply { writeText("complete publication") }
        val provider = RenameFolderTestProvider(root, false, flagsProvided = false).register(context)
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(),
            sourceUri = provider.uri(source.name).toString())
        assertThrows(SourceRenameFolderRequiredException::class.java) { BookSourceRename(context, book).apply("new") }
        assertEquals(1, provider.renameCalls)
        assertEquals("complete publication", source.readText())
    }

    @Test fun folderGrantRenamesTheRealSourceKeepsPrivateCopyAndSupportsRollback() {
        val actual = File(root, "actual").apply { mkdirs() }
        val source = File(actual, "old.epub").apply { writeText("complete publication") }
        val cache = File(context.filesDir, "private.epub").apply { source.copyTo(this) }
        val alias = RenameFolderTestProvider(actual, false).register(context)
        val folder = RenameFolderTestProvider(actual, true).register(context)
        val oldUri = alias.uri(source.name).toString()
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, sourceUri = oldUri,
            sourcePath = cache.path, chapters = listOf(Chapter(title = "cover", pages = listOf(PageSource(oldUri)))))
        assertThrows(SourceRenameFolderRequiredException::class.java) { BookSourceRename(context, book).apply("new") }
        assertEquals(0, alias.renameCalls)
        val selected = sourceInRenameFolder(context, book, folder.tree, source.name)
        val transaction = BookSourceRename(context, book.copy(sourceUri = selected), setOf(oldUri))
        val renamed = transaction.apply("new")
        assertFalse(source.exists())
        assertEquals("complete publication", File(actual, "new.epub").readText())
        assertEquals("complete publication", cache.readText())
        assertEquals(cache.path, renamed.sourcePath)
        assertEquals(renamed.sourceUri, renamed.chapters.single().pages.single().path)
        transaction.rollback()
        assertEquals("complete publication", source.readText())
        assertFalse(File(actual, "new.epub").exists())
    }

    @Test fun wrongFolderOrDifferentSameNameFileNeverRenamesEitherFile() {
        val source = File(root, "old.epub").apply { writeText("original") }
        val other = File(root, "other").apply { mkdirs() }
        val wrong = File(other, "old.epub").apply { writeText("modified") }
        val provider = RenameFolderTestProvider(other, true).register(context)
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(), sourcePath = source.path)
        val error = assertThrows(IllegalArgumentException::class.java) { sourceInRenameFolder(context, book, provider.tree, source.name) }
        assertTrue(error.message!!.contains("内容与原书不一致"))
        assertThrows(IllegalArgumentException::class.java) { sourceInRenameFolder(context, book, provider.tree, "missing.epub") }
        assertEquals(0, provider.renameCalls)
        assertEquals("original", source.readText())
        assertEquals("modified", wrong.readText())
    }

    @Test fun aFolderProviderWithoutRenameCapabilityStopsWithAnExplanation() {
        val source = File(root, "old.epub").apply { writeText("original") }
        val provider = RenameFolderTestProvider(root, false).register(context)
        val book = Book(title = "old", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(), sourcePath = source.path)
        val error = assertThrows(IllegalArgumentException::class.java) { sourceInRenameFolder(context, book, provider.tree, source.name) }
        assertTrue(error.message!!.contains("仍不支持重命名"))
        assertEquals(0, provider.renameCalls)
    }
}

internal class RenameFolderTestProvider(private val root: File, private val supported: Boolean,
    private val flagsProvided: Boolean = true) : ContentProvider() {
    private val authority = "rename-folder-provider-${UUID.randomUUID()}"
    var renameCalls = 0
        private set
    val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(authority, "folder")
    fun uri(name: String): Uri = DocumentsContract.buildDocumentUri(authority, "folder/$name")
    fun register(context: Context): RenameFolderTestProvider {
        val info = ProviderInfo().apply { this.authority = this@RenameFolderTestProvider.authority; exported = true }
        attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, this)
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE),
            ResolveInfo().apply { providerInfo = info })
        return this
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    private fun file(uri: Uri): File = File(root, DocumentsContract.getDocumentId(uri).substringAfter("folder/"))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = listOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE) +
            if (flagsProvided) listOf(DocumentsContract.Document.COLUMN_FLAGS) else emptyList()
        return MatrixCursor(columns.toTypedArray()).apply {
            val files = if (uri.lastPathSegment == "children") root.listFiles().orEmpty().toList() else listOf(file(uri))
            files.filter { it.isFile }.forEach { file ->
                val row = mutableListOf<Any>("folder/${file.name}", file.name, file.length(), "application/octet-stream")
                if (flagsProvided) row += DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    if (supported) DocumentsContract.Document.FLAG_SUPPORTS_RENAME else 0
                addRow(row)
            }
        }
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "android:renameDocument")
        renameCalls++
        if (!supported) throw UnsupportedOperationException("Rename not supported")
        val old = requireNotNull(extras?.getParcelable<Uri>("uri"))
        val name = requireNotNull(requireNotNull(extras).getString(OpenableColumns.DISPLAY_NAME))
        check(file(old).renameTo(File(root, name)))
        val next = if (DocumentsContract.isTreeUri(old)) DocumentsContract.buildDocumentUriUsingTree(old, "folder/$name") else uri(name)
        return Bundle().apply { putParcelable("uri", next) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
