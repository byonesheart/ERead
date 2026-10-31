package com.eyue.reader.data

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.eyue.reader.data.cloud.CloudDownloadLocation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SourceOperationsRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        root = File("build/source-operations-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }
        }
        context.getSharedPreferences("cloud_download_location", 0).edit().clear().commit()
        context.getSharedPreferences("local_source_snapshots", 0).edit().clear().commit()
    }
    @After fun cleanup() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        root.deleteRecursively()
    }
    private fun book(): Book {
        val source = File(root, "original.epub").apply { writeText("same content") }
        val cached = File(context.filesDir, "cached.epub").apply { writeText(source.readText()) }
        return Book(id = "source-test", title = "样书", sourceFileName = "样书.epub", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            sourcePath = cached.path, sourceUri = source.path, chapters = listOf(Chapter(title = "第一章")))
    }
    @Test fun sourceChangesSurviveRestartAndOwnSavedChangesResetTheBaseline() {
        val book = book()
        assertFalse(LocalSourceChanges(context).changed(book))
        File(root, "original.epub").writeText("new! content")
        assertTrue(LocalSourceChanges(context).changed(book))
        assertTrue(LocalSourceChanges(context).changed(book))
        File(book.sourcePath!!).writeText("new! content")
        val saved = book.copy(contentVersion = book.contentVersion + 1)
        assertTrue(LocalSourceChanges(context).changed(saved))
        LocalSourceChanges(context).accepted(saved)
        assertFalse(LocalSourceChanges(context).changed(saved))
    }
    @Test fun firstScanDetectsExistingDifferencesAgainstTheImportedCopyAndReferenceFilesKeepTheirBaseline() {
        val book = book()
        File(root, "original.epub").writeText("externally changed")
        assertTrue(LocalSourceChanges(context).changed(book))
        val linked = book.copy(id = "linked", sourcePath = File(root, "original.epub").path)
        assertFalse(LocalSourceChanges(context).changed(linked))
        File(root, "original.epub").appendText(" again")
        assertTrue(LocalSourceChanges(context).changed(linked))
    }
    @Test fun legacyLinkedTxtDetectsSameSizeChangesBeforeItsFirstScan() {
        val original = File(root, "old.txt").apply { writeText("第一章\n原始正文。") }
        val cached = File(context.filesDir, "chapter.txt").apply { writeText(original.readText()) }
        val book = book().copy(id = "legacy-txt", format = BookFormat.TXT, sourceUri = original.path,
            sourcePath = original.path, sourceLinked = true, sourceFileBytes = original.length(),
            chapters = listOf(Chapter(title = "第一章", textPath = cached.path)))
        original.writeText("第一章\n变更正文。")
        // Beta4 may already have accepted a late first scan; migrate using the cached TXT.
        context.getSharedPreferences("local_source_snapshots", 0).edit()
            .putString(book.id, java.security.MessageDigest.getInstance("SHA-256").digest(original.readBytes())
                .joinToString("") { "%02x".format(it) }).putString(book.id + ":key", "old-version-key").commit()
        assertTrue(LocalSourceChanges(context).changed(book))
        assertTrue(LocalSourceChanges(context).changed(book.copy(contentVersion = book.contentVersion + 1)))
        assertEquals("第一章\n原始正文。", cached.readText())
    }
    @Test fun keepingACopiedImportDigestDoesNotNeedTheExpiredOriginalGrant() {
        val book = book().copy(sourceUri = "content://expired.provider/missing.epub")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(File(book.sourcePath!!).readBytes())
            .joinToString("") { "%02x".format(it) }
        LocalSourceChanges(context).imported(book, digest)
        assertEquals(digest, context.getSharedPreferences("local_source_snapshots", 0).getString(book.id, null))
    }
    @Test fun singleImageBooksInspectTheOriginalRatherThanOnlyTheirPrivateCopy() {
        val book = book().copy(format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "图片", pages = listOf(PageSource(book().sourcePath!!)))))
        LocalSourceChanges(context).accepted(book)
        File(root, "original.epub").writeText("changed external image bytes")
        assertTrue(LocalSourceChanges(context).changed(book))
    }
    @Test fun importedDigestSurvivesAChangedVersionAndReauthorizedUri() {
        val book = book()
        LocalSourceChanges(context).accepted(book)
        val changed = File(root, "changed.epub").apply { writeText("externally changed") }
        assertTrue(LocalSourceChanges(context).changed(book.copy(sourceUri = changed.path, contentVersion = 500)))
    }
    @Test fun linkedImportBaselineDetectsAnEditBeforeScanningIsEnabled() {
        val source = File(root, "linked.pdf").apply { writeText("initial") }
        val book = book().copy(id = "linked-pdf", format = BookFormat.PDF, sourceUri = source.path, sourcePath = source.path)
        LocalSourceChanges(context).imported(book)
        source.writeText("longer edited content")
        assertTrue(LocalSourceChanges(context).changed(book))
    }
    @Test fun externalOpenKeepsOwnedBytesAfterTheTemporarySourceIsRemoved() {
        val book = book().copy(sourcePath = File(root, "original.epub").path, sourceLinked = true)
        val retained = retainExternalBookSource(context, book, Uri.parse(book.sourceUri))
        assertFalse(retained.sourceLinked)
        assertEquals(book.id, retained.id)
        File(root, "original.epub").delete()
        assertEquals("same content", File(retained.sourcePath!!).readText())
        requireBookSourceAvailable(context, retained)
    }
    @Test fun startupScanIsOptInAndPersists() {
        val repository = LibraryRepository(context)
        assertFalse(repository.load().scanLocalChanges)
        repository.save(LibraryState(scanLocalChanges = true))
        assertTrue(repository.load().scanLocalChanges)
    }
    @Test fun indexedComicFilesAreScannedWithoutTryingToOpenTheFolderAsAFile() {
        val image = File(root, "page.jpg").apply { writeText("image-bytes") }
        val comic = book().copy(format = BookFormat.IMAGES, mode = LibraryMode.COMIC, sourcePath = null, sourceUri = null,
            chapters = listOf(Chapter(title = "漫画", pages = listOf(PageSource(image.path)))))
        assertFalse(LocalSourceChanges(context).changed(comic))
        image.writeText("changed-image")
        assertTrue(LocalSourceChanges(context).changed(comic))
    }
    private fun provider(authority: String): DownloadProvider = DownloadProvider(File(root, authority).apply { mkdirs() }).also {
        it.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(authority, it)
    }
    @Test fun systemDownloadsPublishesOnlyTheOriginalAndPreservesPrivateRenderingAssets() {
        val provider = provider("media")
        val book = book()
        val location = CloudDownloadLocation(context)
        val exported = location.publish(book)
        assertEquals(book.sourcePath, exported.sourcePath)
        assertTrue(exported.sourceUri!!.startsWith("content://media/external_primary/downloads/"))
        assertEquals("Download/E阅/", provider.inserted!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, provider.pending)
        assertTrue(location.owns(exported.sourceUri!!))
        assertEquals("same content", openDocumentStream(context, exported.sourceUri!!).use { it.readBytes().toString(Charsets.UTF_8) })
        val renamed = BookSourceRename(context, exported).apply("新书名")
        assertEquals("新书名.epub", provider.name)
        assertEquals(exported.sourceUri, renamed.sourceUri)
        assertEquals("same content", File(book.sourcePath!!).readText())
    }
    @Test fun selectedFolderIsPersistentAndCancellationNeverLeavesAPartialPublicFile() {
        val provider = provider("test.documents")
        val tree = DocumentsContract.buildTreeDocumentUri("test.documents", "folder")
        CloudDownloadLocation(context).select(tree)
        assertEquals(tree, CloudDownloadLocation(context).tree)
        val book = book()
        val exported = CloudDownloadLocation(context).publish(book)
        assertTrue(exported.sourceUri!!.startsWith("content://test.documents/"))
        assertEquals("same content", openDocumentStream(context, exported.sourceUri!!).use { it.readBytes().toString(Charsets.UTF_8) })
        CloudDownloadLocation(context).select(null)
        val media = provider("media")
        var checks = 0
        assertTrue(runCatching { CloudDownloadLocation(context).publish(book) {
            if (++checks > 1) throw java.io.IOException("cancel")
        } }.isFailure)
        assertEquals(1, media.deleted)
        assertEquals("same content", File(book.sourcePath!!).readText())
    }
}

private class DownloadProvider(private val directory: File) : ContentProvider() {
    var inserted: ContentValues? = null
    var pending = -1
    var name = ""
    var deleted = 0
    private var index = 0
    private val files = mutableMapOf<Uri, File>()
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/epub+zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        inserted = ContentValues(values); name = values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
        return Uri.withAppendedPath(uri, (++index).toString()).also { files[it] = File(directory, index.toString()) }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.let { pending = it }
        values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.let { name = it }
        return 1
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(name, files[uri]?.length() ?: 0L)) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(requireNotNull(files[uri]), ParcelFileDescriptor.parseMode(mode))
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted++; files.remove(uri)?.delete(); return 1
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "android:createDocument")
        @Suppress("DEPRECATION") val parent = requireNotNull(extras).getParcelable<Uri>("uri")!!
        name = extras.getString(OpenableColumns.DISPLAY_NAME)!!
        val uri = DocumentsContract.buildDocumentUriUsingTree(parent, "file-${++index}")
        files[uri] = File(directory, index.toString())
        return Bundle().apply { putParcelable("uri", uri) }
    }
}
