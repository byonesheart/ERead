package com.eyue.reader.data.cloud

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class WebDavTransportTest {
    @Test fun readOnlyMetadataUsesConditionalGetAndSupportsWeakTags() {
        val client = transport { request ->
            assertEquals("W/\"old\"", request.header("If-None-Match"))
            response(request, 304, "W/\"old\"")
        }
        assertEquals(CloudMetadataResponse(null, "W/\"old\"", unchanged = true),
            client.readMetadata("books/id/manifest.json", "W/\"old\""))
    }

    @Test fun bulkManifestTagsOnlyAcceptStrongValidatorsForSameOriginBookRecords() {
        fun row(href: String, tag: String, status: Int = 200) = """
            <d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>$tag</d:getetag></d:prop>
            <d:status>HTTP/1.1 $status Test</d:status></d:propstat></d:response>
        """.trimIndent()
        val root = "/dav/ERead/books/"
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" + listOf(
            row(root + "book/manifest.json", "\"version\""), row(root + "book/file.bin", "\"part\""),
            row(root + "weak/manifest.json", "W/\"weak\""), row(root + "forbidden/manifest.json", "\"none\"", 403),
            row("https://foreign.invalid${root}other/manifest.json", "\"foreign\"")
        ).joinToString("") + "</d:multistatus>"
        val client = transport { request ->
            assertEquals("PROPFIND", request.method)
            assertEquals("infinity", request.header("Depth"))
            response(request, 207).newBuilder().body(xml.toResponseBody()).build()
        }
        assertEquals(mapOf("book" to "\"version\""), client.manifestETags())
    }

    @Test fun importListsOnlyDirectSupportedChildrenAndDownloadsOriginalBytes() {
        val source = "网盘原始文件".toByteArray()
        val root = "/library/"
        val title = "书名 + 1.epub"
        fun item(href: String, properties: String = "", status: Int = 200) =
            """<d:response><d:href>$href</d:href><d:propstat><d:prop>$properties</d:prop><d:status>HTTP/1.1 $status Test</d:status></d:propstat></d:response>"""
        val xml = """<d:multistatus xmlns:d="DAV:">""" + listOf(
            item(root, "<d:resourcetype><d:collection/></d:resourcetype>"),
            item(root + "novels/", "<d:resourcetype><d:collection/></d:resourcetype>"),
            item(root + "%E4%B9%A6%E5%90%8D%20%2B%201.epub", "<d:getcontentlength>${source.size}</d:getcontentlength>"),
            item(root + "folder/nested.epub"), item(root + "cover.jpg"), item(root + "forbidden.pdf", status = 403),
            item(root + "unsafe%2Fname.epub"), item("https://foreign.invalid/library/foreign.epub")
        ).joinToString("") + "</d:multistatus>"
        val credentials = CloudCredentials("reader", "password", "https://example.invalid/library")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("example.invalid", request.url.host)
            val listing = request.method == "PROPFIND"
            if (listing) {
                assertEquals(root, request.url.encodedPath)
                assertEquals("1", request.header("Depth"))
            } else assertEquals(listOf("library", title), request.url.pathSegments)
            response(request, if (listing) 207 else 200).newBuilder()
                .body(if (listing) xml.toResponseBody() else source.toResponseBody()).build()
        }.build()
        val transport = WebDavCloudTransport(credentials, {}, client, {})
        assertEquals(listOf(WebDavImportEntry("novels", true, null), WebDavImportEntry(title, false, source.size.toLong())),
            transport.importEntries(emptyList()))
        val file = Files.createTempFile("webdav-book", ".epub").toFile()
        try {
            var transferred = 0L
            transport.downloadImport(listOf(title), file) { done, total ->
                transferred = done
                assertEquals(source.size.toLong(), total)
            }
            assertArrayEquals(source, file.readBytes())
            assertEquals(source.size.toLong(), transferred)
        } finally { file.delete() }
    }

    private fun response(request: Request, code: Int = 200, etag: String? = null, retryAfter: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body("{}".toResponseBody()).apply {
                etag?.let { header("ETag", it) }
                retryAfter?.let { header("Retry-After", it) }
            }.build()

    private fun transport(check: () -> Unit = {}, pause: (Long) -> Unit = {}, reply: (Request) -> Response): WebDavCloudTransport =
        WebDavCloudTransport(CloudCredentials("test@example.invalid", "not-a-real-password"), check,
            OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build(), pause)

    @Test fun quotedAndJianguoyunBareTagsRemainUnchangedInConditionalWrites() {
        for (tag in listOf("\"version-1\"", "0123456789abcdef", "\"\"")) {
            var calls = 0
            val client = transport { request ->
                calls++
                if (request.method == "GET") response(request, etag = " $tag\t") else {
                    assertEquals(tag, request.header("If-Match"))
                    assertNull(request.header("If-None-Match"))
                    response(request)
                }
            }
            val version = requireNotNull(client.versionedMetadata("catalog.json"))
            assertEquals(tag, version.etag)
            assertTrue(client.putVersionedMetadata("catalog.json", version.bytes, version.etag))
            assertEquals(2, calls)
        }
    }

    @Test fun weakInvalidOrMissingTagsCannotBeUsedToOverwriteAnExistingIndex() {
        for (tag in listOf(null, "", "*", "W/\"version\"", "w/\"version\"", "a,b", "a b", "a\r\nb", "\"version\"tail", "bare\"tag", "x".repeat(513))) {
            assertThrows(IllegalArgumentException::class.java) { cloudStrongETag(tag) }
        }
        val weak = transport { response(it, etag = "W/\"version\"") }
        assertThrows(IllegalArgumentException::class.java) { weak.versionedMetadata("catalog.json") }
    }

    @Test fun conditionalConflictIsReturnedWithoutRetryOrUnconditionalOverwrite() {
        var calls = 0
        val client = transport { request ->
            calls++
            assertEquals("*", request.header("If-None-Match"))
            assertNull(request.header("If-Match"))
            response(request, 412)
        }
        assertFalse(client.putVersionedMetadata("catalog.json", "{}".toByteArray(), null))
        assertEquals(1, calls)
    }

    @Test fun interruptedPutRetriesOnlyTheSameFileSliceAndBoundedNumberOfTimes() {
        val file = Files.createTempFile("cloud-upload", ".bin").toFile()
        try {
            val original = ByteArray(150_000) { (it % 251).toByte() }
            file.writeBytes(original)
            var calls = 0
            var paused = 0L
            val bodies = mutableListOf<ByteArray>()
            val client = transport(pause = { paused += it }) { request ->
                calls++
                assertEquals("PUT", request.method)
                assertEquals("/dav/ERead/books/id/part.bin", request.url.encodedPath)
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                bodies += buffer.readByteArray()
                if (calls < 3) throw IOException("synthetic disconnect")
                response(request, 201)
            }
            client.upload("books/id/part.bin", file, 10, 100_000) {}
            assertEquals(3, calls)
            assertEquals(3_000L, paused)
            bodies.forEach { assertArrayEquals(original.copyOfRange(10, 100_010), it) }
        } finally { file.delete() }
    }

    @Test fun transientHttpFailureHonorsBoundedRetryAfterAndThenSucceeds() {
        var calls = 0
        var paused = 0L
        val client = transport(pause = { paused += it }) { request ->
            calls++
            if (calls == 1) response(request, 503, retryAfter = "2") else response(request)
        }
        assertArrayEquals("{}".toByteArray(), client.metadata("catalog.json"))
        assertEquals(2, calls)
        assertEquals(2_000L, paused)
    }

    @Test fun authenticationFailureIsNotRetriedAndCarriesPermanentClassification() {
        var calls = 0
        val client = transport { calls++; response(it, 401) }
        val error = assertThrows(CloudHttpException::class.java) { client.metadata("catalog.json") }
        assertEquals(401, error.code)
        assertFalse(error.retryable)
        assertEquals(1, calls)
    }

    @Test fun repeatedServerFailureStopsAfterThreeAttemptsAndIsRetryableByTheWorker() {
        var calls = 0
        val client = transport { calls++; response(it, 503, retryAfter = "0") }
        val error = assertThrows(CloudHttpException::class.java) { client.metadata("catalog.json") }
        assertTrue(error.retryable)
        assertEquals(3, calls)
    }

    @Test fun cancellationDuringRetryDelayPreventsAnyFurtherNetworkCalls() {
        var calls = 0
        var cancelled = false
        val client = transport(check = { if (cancelled) throw CancellationException() }, pause = { cancelled = true }) {
            calls++
            throw IOException("synthetic disconnect")
        }
        assertThrows(CancellationException::class.java) { client.metadata("catalog.json") }
        assertEquals(1, calls)
    }

    @Test fun deleteUsesOnlyTheSelectedBookCollectionAndIsIdempotentAfterAResponseIsLost() {
        var calls = 0
        val client = transport { request ->
            calls++
            assertEquals("DELETE", request.method)
            assertEquals("/dav/ERead/books/selected-book/", request.url.encodedPath)
            assertEquals("infinity", request.header("Depth"))
            if (calls == 1) throw IOException("synthetic lost delete response")
            response(request, 404)
        }
        client.deleteCollection("books/selected-book/")
        assertEquals(2, calls)
        for (path in listOf("", "books/", "books/../", "books/id/file", "other/id/", "https://evil.test/")) {
            assertThrows(IllegalArgumentException::class.java) { client.deleteCollection(path) }
        }
        assertEquals(2, calls)
    }

    @Test fun partialCollectionDeletionIsNotReportedAsSuccess() {
        val client = transport { response(it, 207) }
        val error = assertThrows(CloudHttpException::class.java) { client.deleteCollection("books/selected-book/") }
        assertEquals(207, error.code)
        assertFalse(error.retryable)
    }

    @Test fun configuredServerReceivesRequestsWithinItsEReadCollection() {
        val credentials = CloudCredentials("account", "password", "http://example.invalid:8080/cloud")
        var calls = 0
        val transport = WebDavCloudTransport(credentials, {}, OkHttpClient.Builder().addInterceptor {
            val request = it.request()
            calls++
            assertEquals("http://example.invalid:8080/cloud/ERead/books/book/manifest.json", request.url.toString())
            assertEquals(okhttp3.Credentials.basic("account", "password", Charsets.UTF_8), request.header("Authorization"))
            response(request)
        }.build(), {})
        transport.metadata("books/book/manifest.json")
        assertEquals(1, calls)
    }
}
