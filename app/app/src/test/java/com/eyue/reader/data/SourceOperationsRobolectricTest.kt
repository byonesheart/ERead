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
    @Test fun epubReloadPreservesTheCompletePackageAndMovesRecordsWithoutCreatingAnotherBook() {
        val source = File(root, "novel.epub")
        fun write(text: String) {
            java.util.zip.ZipOutputStream(source.outputStream()).use { zip ->
                val entries = mapOf("META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                    "OPS/book.opf" to "<package><metadata><title>书</title></metadata><manifest><item id='body' href='body.xhtml'/></manifest><spine><itemref idref='body'/></spine></package>",
                    "OPS/body.xhtml" to "<html><head><link rel='stylesheet' href='book.css'/></head><body><h1>第一章</h1><p>$text</p></body></html>",
                    "OPS/book.css" to "@font-face{font-family:book;src:url(font.woff)} p{margin:1em}", "OPS/font.woff" to "original-font-bytes")
                entries.forEach { (name, content) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
            }
        }
        write("独立笔记")
        val provider = RenameFolderTestProvider(root, true).register(context)
        val original = kotlinx.coroutines.runBlocking { DocumentImporter(context).importFiles(listOf(provider.uri(source.name)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single() }
        val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, quote = "独立笔记", note = "我的记录", createdAt = 123L)
        write("外部新增文字，独立笔记")
        val store = LocalSourceReloads(context)
        val prepared = kotlinx.coroutines.runBlocking { store.prepare(original, DEFAULT_CHAPTER_RULE) }
        assertEquals(original.id, prepared.id)
        assertTrue(File(original.sourcePath!!).exists())
        assertArrayEquals(source.readBytes(), File(prepared.sourcePath!!).readBytes())
        val updated = store.recover(LibraryState(books = listOf(original), annotations = listOf(note)))
        assertEquals(1, updated.books.size)
        assertEquals(note.id, updated.annotations.single().id)
        assertEquals(note.note, updated.annotations.single().note)
        assertEquals(note.createdAt, updated.annotations.single().createdAt)
        assertTrue(readChapterText(updated.books.single().chapters.first()).contains("外部新增文字"))
        LibraryRepository(context).save(updated)
        store.finish(updated.books.single(), updated.annotations)
        assertTrue(source.isFile)
        assertTrue(File(prepared.sourcePath!!).isFile)
        assertFalse(File(original.sourcePath!!).exists())
    }
    @Test fun batchAndFolderAuthorizationMatchChangedSourcesWithoutComparingTheirContents() {
        val book = book().copy(sourceFileName = "original.epub", sourceUri = "content://expired/original.epub")
        File(root, "original.epub").writeText("different edited source contents")
        val second = File(root, "second.txt").apply { writeText("also edited") }
        val other = book.copy(id = "second", sourceFileName = second.name, sourceUri = "content://expired/second.txt")
        val provider = RenameFolderTestProvider(root, supported = true).register(context)
        val folder = book.copy(id = "comic-folder", format = BookFormat.IMAGES, sourceUri = provider.tree.toString(), sourceFileName = "folder", sourceFileBytes = null)
        assertEquals(provider.tree, localSourceGrantMatches(context, listOf(folder), listOf(provider.tree))[folder.id])
        val matched = localSourceGrantMatches(context, listOf(book, other), listOf(provider.tree))
        assertEquals(setOf(book.id, other.id), matched.keys)
        assertTrue(matched.values.all { DocumentsContract.isTreeUri(it) })
        com.eyue.reader.ui.retainBookAccess(context, provider.tree)
        assertTrue(localSourceAuthorized(context, book.copy(sourceUri = matched.getValue(book.id).toString())))
        val direct = localSourceGrantMatches(context, listOf(book, other), listOf(provider.uri("original.epub"), provider.uri("second.txt")))
        assertEquals(setOf(book.id, other.id), direct.keys)
        val ambiguous = localSourceGrantMatches(context, listOf(book, book.copy(id = "duplicate")), listOf(provider.tree))
        assertTrue(ambiguous.isEmpty())
    }
    @Test fun scanSelectionAndAutoReloadPersistIndependently() {
        val state = LibraryState(books = listOf(book()), scanLocalChanges = true, scannedBookIds = setOf("source-test"), autoReloadSources = true)
        LibraryRepository(context).save(state)
        val loaded = LibraryRepository(context).load()
        assertEquals(state.scannedBookIds, loaded.scannedBookIds)
        assertTrue(loaded.autoReloadSources)
    }
    @Test fun manualAutomaticAndOffScanModesRoundTripWithoutChangingBookSelection() {
        for (mode in SourceScanMode.entries) {
            val selected = LibraryState(books = listOf(book()), scanLocalChanges = mode != SourceScanMode.OFF,
                sourceScanAutomatic = mode == SourceScanMode.AUTO, scannedBookIds = setOf("source-test"), autoReloadSources = true)
            LibraryRepository(context).save(selected)
            val loaded = LibraryRepository(context).load()
            assertEquals(mode, loaded.sourceScanMode)
            assertEquals(selected.scannedBookIds, loaded.scannedBookIds)
            assertTrue(loaded.autoReloadSources)
        }
        val legacy = File(context.filesDir, "library.json")
        val json = org.json.JSONObject(legacy.readText()).apply { remove("sourceScanAutomatic"); put("scanLocalChanges", true) }
        legacy.writeText(json.toString())
        assertEquals(SourceScanMode.AUTO, LibraryRepository(context).load().sourceScanMode)
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
