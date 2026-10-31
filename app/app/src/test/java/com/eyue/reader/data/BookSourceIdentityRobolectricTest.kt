package com.eyue.reader.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookSourceIdentityRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Test fun externalOpenRequiresEqualContentsEvenWhenNameAndSizeAreIdentical() {
        val stored = File(root, "cached.mobi").apply { writeText("abc") }
        val incoming = File(root, "book.mobi").apply { writeText("xyz") }
        val known = book().copy(sourcePath = stored.path, sourceFileName = incoming.name, sourceFileBytes = 3L)
        assertNull(identicalExternalBook(context, listOf(known), Uri.parse(incoming.path)))
        incoming.writeText("abc")
        assertEquals(known, identicalExternalBook(context, listOf(known), Uri.parse(incoming.path)))
        assertNull(identicalExternalBook(context, listOf(known.copy(sourceFileName = "other.mobi")), Uri.parse(incoming.path)))
    }
    private fun book() = Book(title = "元数据书名", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "第一章", textPath = "unavailable-chapter-cache")))

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "source-identity-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun originalFileMetadataIsAddedLazilyAndSizeRefreshesAfterWriteback() {
        val source = File(root, "原始文件名.mobi").apply { writeText("original") }
        val legacy = book().copy(sourcePath = source.absolutePath)
        val identified = hydrateBookSourceIdentity(context, legacy)
        assertEquals(source.name, identified.sourceFileName)
        assertEquals(source.length(), identified.sourceFileBytes)
        source.appendText(" edited")
        assertEquals(source.length(), hydrateBookSourceIdentity(context, identified).sourceFileBytes)
        assertNull(legacy.sourceFileName)
    }

    @Test fun privateParserFilenameIsNeverPresentedAsOriginalFilename() {
        val cached = File(context.filesDir, "books/id/source.mobi").apply { parentFile!!.mkdirs(); writeText("original") }
        val identified = hydrateBookSourceIdentity(context, book().copy(sourcePath = cached.absolutePath))
        assertNull(identified.sourceFileName)
        assertEquals(cached.length(), identified.sourceFileBytes)
        assertEquals(CloudBookMatch.UNKNOWN, cloudBookSourceMatch(identified,
            identified.copy(id = "remote", sourceFileName = "source.mobi")))
    }

    @Test fun restoredLegacyAssetFilenameIsUnknownEvenWhenPrivateFilesHaveEqualSizes() {
        val first = File(context.filesDir, "books/remote/cloud-first/assets/000000.mobi")
            .apply { parentFile!!.mkdirs(); writeText("first") }
        val second = File(context.filesDir, "books/other/cloud-second/assets/000000.mobi")
            .apply { parentFile!!.mkdirs(); writeText("other") }
        val local = hydrateBookSourceIdentity(context, book().copy(sourcePath = first.absolutePath))
        val remote = hydrateBookSourceIdentity(context, book().copy(sourcePath = second.absolutePath))
        assertNull(local.sourceFileName)
        assertNull(remote.sourceFileName)
        assertEquals(first.length(), local.sourceFileBytes)
        assertEquals(local.sourceFileBytes, remote.sourceFileBytes)
        assertEquals(CloudBookMatch.UNKNOWN, cloudBookSourceMatch(local, remote))
        assertEquals("原书.mobi", hydrateBookSourceIdentity(context,
            local.copy(sourceFileName = "原书.mobi")).sourceFileName)
    }

    @Test fun safSourceUsesDisplayNameAndSizeWithoutOpeningAnyFileOrCache() {
        val authority = "source-identity-${UUID.randomUUID()}"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/x-mobipocket-ebook"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("原始书.mobi", 12345L)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = error("Identity must not open book bytes")
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val identified = hydrateBookSourceIdentity(context, book().copy(sourceUri = "content://$authority/book/opaque-id", sourceLinked = true))
        assertEquals("原始书.mobi", identified.sourceFileName)
        assertEquals(12345L, identified.sourceFileBytes)
    }

    @Test fun comicDirectoriesAndMultipleImagesNeverUseCacheDirectorySize() {
        val folder = File(root, "漫画").apply { mkdirs() }
        val first = File(folder, "1.jpg").apply { writeText("image") }
        File(folder, "notes-cache.bin").writeBytes(ByteArray(100_000))
        val directoryBook = book().copy(format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            sourcePath = folder.absolutePath, chapters = listOf(Chapter(title = "一", pages = listOf(PageSource(first.absolutePath)))))
        assertEquals("漫画", hydrateBookSourceIdentity(context, directoryBook).sourceFileName)
        assertNull(hydrateBookSourceIdentity(context, directoryBook).sourceFileBytes)
        val multiple = directoryBook.copy(sourcePath = first.absolutePath, chapters = listOf(Chapter(title = "一",
            pages = listOf(PageSource(first.absolutePath), PageSource(File(folder, "2.jpg").absolutePath)))))
        assertNull(hydrateBookSourceIdentity(context, multiple).sourceFileBytes)
    }

    @Test fun sourceIdentitySerializationIsBackwardCompatibleAndDoesNotIncludeOtherStorage() {
        val repository = LibraryRepository(context)
        val known = book().copy(sourceFileName = "书.mobi", sourceFileBytes = 10_000_000L)
        assertEquals(known, repository.readBook(repository.writeBook(known)))
        val legacy = repository.writeBook(known).apply { remove("sourceFileName"); remove("sourceFileBytes") }
        assertNull(repository.readBook(legacy).sourceFileName)
        assertNull(repository.readBook(legacy).sourceFileBytes)
        assertNull(repository.readBook(repository.writeBook(known).put("sourceFileBytes", -1L)).sourceFileBytes)
    }

    @Test fun importRetainsActualFilenameAndOnlyTheOriginalFileSize() = runBlocking {
        val file = File(root, "文件名与书名不同.txt").apply { writeText("第一章 开始\n正文第一段。\n正文第二段。") }
        val authority = "source-import-${UUID.randomUUID()}"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/plain"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(arrayOf<Any>(file.name, file.length()))
                }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/book/opaque-id")
        val imported = DocumentImporter(context).importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertEquals(file.name, imported.sourceFileName)
        assertEquals(file.length(), imported.sourceFileBytes)
        File(context.filesDir, "books/${imported.id}/huge-cache.bin").writeBytes(ByteArray(100_000))
        assertEquals(file.length(), hydrateBookSourceIdentity(context, imported).sourceFileBytes)
    }
}
