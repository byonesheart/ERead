package com.eyue.reader

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalFileIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun viewKeepsGrantedDocumentUriAndDeduplicatesClipData() {
        val uri = Uri.parse("content://file.manager/document/primary%3ABooks%2FStory.epub")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("book", uri)
        }
        val request = externalFilesFromIntent(intent)!!
        assertEquals(listOf(uri), request.uris)
        assertEquals("application/epub+zip", request.mimeType)
    }

    @Test fun shareReadsStreamAndClipDataWithoutImportingTextExtras() {
        val first = Uri.parse("content://file.manager/books/first.mobi")
        val second = Uri.parse("content://file.manager/books/second.pdf")
        val intent = Intent(Intent.ACTION_SEND).setType("application/x-mobipocket-ebook").apply {
            putExtra(Intent.EXTRA_STREAM, first)
            putExtra(Intent.EXTRA_TEXT, "https://example.com/unrelated.txt")
            clipData = ClipData.newRawUri("books", first).apply { addItem(ClipData.Item(second)) }
        }
        assertEquals(listOf(first, second), externalFilesFromIntent(intent)!!.uris)
    }

    @Test fun multipleSharePreservesOrderAndAcceptsFileUris() {
        val first = Uri.parse("content://file.manager/books/001.jpg")
        val second = Uri.parse("file:///storage/emulated/0/Books/002.png")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        }
        assertEquals(listOf(first, second), externalFilesFromIntent(intent)!!.uris)
    }

    @Test fun ignoresLauncherTextOnlyAndRemoteLinks() {
        assertNull(externalFilesFromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(externalFilesFromIntent(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "just text")))
        assertNull(externalFilesFromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/book.pdf"))))
        assertNull(externalFilesFromIntent(Intent(Intent.ACTION_EDIT, Uri.parse("content://file.manager/book.epub"))))
        assertNull(externalFilesFromIntent(null))
    }

    @Test fun directoryMimeAndTreeUriRemainAvailableToImporter() {
        val tree = Uri.parse("content://file.manager/tree/primary%3AComics")
        val request = externalFilesFromIntent(Intent(Intent.ACTION_VIEW).setDataAndType(tree, "vnd.android.document/directory"))!!
        assertEquals(listOf(tree), request.uris)
        assertEquals("vnd.android.document/directory", request.mimeType)
    }

    @Test fun manifestResolvesTypedContentAndFileDocumentsIncludingOpaqueIds() {
        val types = listOf("application/epub+zip", "text/plain", "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook", "application/pdf", "image/jpeg", "image/png",
            "vnd.android.document/directory", "inode/directory", "resource/folder")
        for (type in types) for (uri in listOf("content://file.manager/document/123", "file:///storage/emulated/0/Books/123")) {
            assertEquals("$type $uri", MainActivity::class.java.name, resolveView(uri, type)?.activityInfo?.name)
        }
    }

    @Test fun manifestFallbackResolvesSupportedSuffixesAndDottedNamesOnly() {
        val names = listOf("story.epub", "story.TXT", "story.mobi", "vol.2.story.pdf", "001.jpg",
            "002.JPEG", "003.png", "004.webp", "005.gif", "006.bmp", "007.heic", "008.heif", "009.avif")
        for (name in names) for (prefix in listOf("content://file.manager/books/", "file:///storage/emulated/0/Books/")) {
            assertNotNull("$prefix$name with octet-stream", resolveView(prefix + name, "application/octet-stream"))
            assertNotNull("$prefix$name without MIME", resolveView(prefix + name, null))
        }
        for (name in listOf("archive.zip", "book.epub.zip", "video.mp4", "opaque-document-id")) {
            assertNull(resolveView("content://file.manager/books/$name", "application/octet-stream"))
            assertNull(resolveView("file:///storage/emulated/0/Books/$name", null))
        }
        assertNull(resolveView("https://example.com/book.epub", "application/epub+zip"))
        assertNull(resolveView("content://file.manager/books/book.pdf", "application/zip"))
    }

    @Test fun manifestShareTargetsSupportedMimeTypesWithoutCatchAllBinaryFilter() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("application/epub+zip", "text/plain", "application/x-mobipocket-ebook", "application/pdf", "image/png")) {
                val resolved = context.packageManager.resolveActivity(Intent(action).setType(type).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
                assertEquals("$action $type", MainActivity::class.java.name, resolved?.activityInfo?.name)
            }
            for (type in listOf("application/zip", "application/octet-stream", "video/mp4")) {
                assertNull(context.packageManager.resolveActivity(Intent(action).setType(type).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY))
            }
        }
    }

    private fun resolveView(uri: String, mime: String?) = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY)
}
