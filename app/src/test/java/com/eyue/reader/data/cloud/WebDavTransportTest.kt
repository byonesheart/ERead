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
