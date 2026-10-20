package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentSourceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var context: Context
    private val granted = mutableSetOf<String>()

    @Before fun setUp() {
        root = File(target.cacheDir, "linked-source-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(root, "private").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        target.contentResolver.persistedUriPermissions.filter { it.uri.authority == LinkedDocumentsProvider.AUTHORITY }.forEach {
            target.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        granted.forEach { providerCall("revoke", it) }
        root.deleteRecursively()
    }

    @Test fun localTxtIsReferencedAndRebuildDoesNotWriteBesideOriginal() = runBlocking {
        val external = File(root, "external").apply { mkdirs() }
        val original = File(external, "novel.txt").apply { writeText("第一章 开始\n第一段。\n第二章 后续\n第二段。") }
        val importer = DocumentImporter(context)
        val book = importer.importFiles(listOf(Uri.fromFile(original)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertTrue(book.sourceLinked)
        assertEquals(Uri.fromFile(original).toString(), book.sourceUri)
        assertEquals(original.canonicalPath, book.sourcePath)
        assertFalse(File(context.filesDir, "books/${book.id}/source.txt").exists())
        val rebuilt = importer.rebuildChapters(book, "^第二章.*$")
        assertTrue(rebuilt.chapters.all { File(requireNotNull(it.textPath)).canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator) })
        assertEquals(listOf("novel.txt"), external.listFiles()!!.map { it.name })
        assertTrue(original.isFile)
    }

    @Test fun durableSafTxtRetainsUriWithoutAnOriginalCopy() = runBlocking {
        val uri = grant("txt", persistent = true)
        val book = DocumentImporter(context).importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertTrue(book.sourceLinked)
        assertEquals(uri.toString(), book.sourceUri)
        assertNull(book.sourcePath)
        assertTrue(target.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        assertFalse(File(context.filesDir, "books/${book.id}/source.txt").exists())
        requireBookSourceAvailable(context, book)
        val rebuilt = DocumentImporter(context).rebuildChapters(book, DEFAULT_CHAPTER_RULE)
        assertEquals(book.title, rebuilt.title)
    }

    @Test fun durableSafFolderKeepsImageAndPdfUris() = runBlocking {
        val tree = grant("root", persistent = true)
        val book = DocumentImporter(context).importFolder(tree, DEFAULT_CHAPTER_RULE)
        assertTrue(book.sourceLinked)
        assertEquals(tree.toString(), book.sourceUri)
        assertNull(book.sourcePath)
        val pages = book.chapters.flatMap { it.pages }
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.path.startsWith("content://") })
        assertTrue(pages.any { it.pdfPage == 0 })
        val originals = File(context.filesDir, "books/${book.id}").walkTopDown().filter { it.isFile && it.extension in setOf("png", "pdf") }.toList()
        assertTrue(originals.isEmpty())
        requireBookSourceAvailable(context, book)
        assertTrue(documentSourceIdentity(context, pages.first().path).startsWith(pages.first().path))
    }

    @Test fun streamingTemporaryNovelKeepsOnlyParsedContent() = runBlocking {
        val uri = grant("pipe", persistent = false)
        val importer = DocumentImporter(context)
        val book = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertFalse(book.sourceLinked)
        assertNull(book.sourcePath)
        assertEquals(uri.toString(), book.sourceUri)
        assertTrue(importer.lastImportWarnings.any { it.contains("临时") })
        assertFalse(File(context.filesDir, "books/${book.id}/source.txt").exists())
        assertTrue(File(context.cacheDir, "document-sources").listFiles().orEmpty().isEmpty())
        assertTrue(book.chapters.any { File(requireNotNull(it.textPath)).readText().contains("正文") })
    }

    @Test fun localAdaptationDeletesStreamingCopyEvenWhenParserFails() {
        val uri = grant("pipe", persistent = false)
        var temporary: File? = null
        try {
            withLocalDocument(context, uri.toString()) {
                temporary = it
                assertTrue(it.isFile)
                throw IOException("parser failure")
            }
            fail("Expected parser failure")
        } catch (error: IOException) { assertEquals("parser failure", error.message) }
        assertNotNull(temporary)
        assertFalse(temporary!!.exists())
        assertTrue(File(context.cacheDir, "document-sources").listFiles().orEmpty().isEmpty())
    }

    @Test fun temporaryImageFallbackSurvivesRevocationAndIsReported() = runBlocking {
        val uri = grant("temporary-image", persistent = false)
        val importer = DocumentImporter(context)
        val book = importer.importFiles(listOf(uri), LibraryMode.COMIC, DEFAULT_CHAPTER_RULE).single()
        assertFalse(book.sourceLinked)
        assertTrue(importer.lastImportWarnings.any { it.contains("副本") })
        val page = book.chapters.single().pages.single()
        assertTrue(File(page.path).isFile)
        providerCall("revoke", "temporary-image")
        openDocumentStream(context, page.path).use { assertTrue(it.read() >= 0) }
    }

    @Test fun removedLinkedFileHasAnExplicitSourceError() = runBlocking {
        val original = File(root, "gone.txt").apply { writeText("第一章\n正文。") }
        val book = DocumentImporter(context).importFiles(listOf(Uri.fromFile(original)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertTrue(original.delete())
        try { requireBookSourceAvailable(context, book); fail("Expected missing source") }
        catch (error: IOException) { assertTrue(error.message.orEmpty().contains("移动或删除")) }
    }

    @Test fun versionTwoLinkedEpubAddsStylesAndReusesUnchangedText() = runBlocking {
        val uri = grant("epub", persistent = true)
        val importer = DocumentImporter(context)
        val imported = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        val old = imported.copy(contentVersion = 2, chapters = imported.chapters.map { it.copy(sourceStyles = emptyList()) })
        val updated = importer.upgradeInlineBooks(LibraryState(books = listOf(old))).books.single()
        assertEquals(6, updated.contentVersion)
        assertEquals(old.chapters.map { it.textPath }, updated.chapters.map { it.textPath })
        assertTrue(updated.chapters.flatMap { it.sourceStyles }.any { it.bold == true })
        assertTrue(updated.chapters.flatMap { it.sourceStyles }.any { it.alignment == SourceTextAlignment.CENTER })
        assertEquals(uri.toString(), updated.sourceUri)
        assertTrue(updated.sourceLinked)
        requireBookSourceAvailable(context, updated)
        assertNull(updated.sourcePath)
    }

    @Test fun revokedDurableUriReportsPermissionLoss() = runBlocking {
        val uri = grant("txt", persistent = true)
        val book = DocumentImporter(context).importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        assertTrue(book.sourceLinked)
        providerCall("revoke", "txt")
        try { requireBookSourceAvailable(context, book); fail("Expected revoked source") }
        catch (error: IOException) { assertTrue(error.message.orEmpty().contains("权限")) }
    }

    @Test fun versionTwoPrivateOriginalIsPreservedDuringStyleUpgrade() = runBlocking {
        val uri = grant("epub", persistent = true)
        val importer = DocumentImporter(context)
        val imported = importer.importFiles(listOf(uri), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        val legacySource = File(context.filesDir, "books/${imported.id}/source.epub")
        openDocumentStream(context, uri.toString()).use { input -> legacySource.outputStream().use { input.copyTo(it) } }
        val originalBytes = legacySource.readBytes()
        val old = imported.copy(contentVersion = 2, sourcePath = legacySource.absolutePath, sourceUri = null, sourceLinked = false,
            chapters = imported.chapters.map { it.copy(sourceStyles = emptyList()) })
        val updated = importer.upgradeInlineBooks(LibraryState(books = listOf(old))).books.single()
        assertEquals(6, updated.contentVersion)
        assertFalse(updated.sourceLinked)
        assertEquals(legacySource.absolutePath, updated.sourcePath)
        assertEquals(old.chapters.map { it.textPath }, updated.chapters.map { it.textPath })
        assertArrayEquals(originalBytes, legacySource.readBytes())
        assertTrue(updated.chapters.flatMap { it.sourceStyles }.isNotEmpty())
    }

    private fun grant(id: String, persistent: Boolean): Uri {
        granted += id
        providerCall("grant", id, persistent)
        return if (id == "root") DocumentsContract.buildTreeDocumentUri(LinkedDocumentsProvider.AUTHORITY, id)
            else DocumentsContract.buildDocumentUri(LinkedDocumentsProvider.AUTHORITY, id)
    }

    private fun providerCall(method: String, id: String, persistent: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().context.contentResolver.call(Uri.parse("content://${LinkedDocumentsProvider.AUTHORITY}"),
            method, target.packageName, Bundle().apply { putString("id", id); putBoolean("persistent", persistent) })
    }
}
