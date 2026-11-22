package com.eyue.reader.data.cloud

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class S3TransportTest {
    @Test fun cosErrorsShowOperationCodeAndRequestIdWithoutReturningCredentials() {
        val client = transport { request -> response(request, 403, """<Error>
            <Code>SignatureDoesNotMatch</Code><Message>Signature mismatch test-secret test-access</Message>
            <RequestId>cos-request-123</RequestId><StringToSign>PRIVATE DEBUG CONTENT</StringToSign>
            </Error>""".toByteArray()) }
        val error = assertThrows(CloudHttpException::class.java) { client.ensureCollection("") }
        assertTrue(error.message!!.contains("HTTP 403"))
        assertTrue(error.message!!.contains("列举存储桶"))
        assertTrue(error.message!!.contains("SignatureDoesNotMatch"))
        assertTrue(error.message!!.contains("cos-request-123"))
        assertFalse(error.message!!.contains("test-secret"))
        assertFalse(error.message!!.contains("test-access"))
        assertFalse(error.message!!.contains("PRIVATE DEBUG CONTENT"))
    }

    @Test fun malformedAndExternalEntityErrorsStillReportHttpStatus() {
        for (body in listOf("<html>Denied</html>", "<!DOCTYPE root SYSTEM 'file:///private'><Error/>", "not xml")) {
            val client = transport { request -> response(request, 403, body.toByteArray()) }
            val error = assertThrows(CloudHttpException::class.java) { client.metadata("catalog.json") }
            assertTrue(error.message!!.contains("HTTP 403"))
            assertTrue(error.message!!.contains("读取备份"))
            assertFalse(error.message!!.contains("file:///private"))
        }
    }

    @Test fun cosSignatureMessageIsNotMisdiagnosedAsMissingPermissions() {
        val client = transport { request -> response(request, 403,
            "<Error><Code>AccessDenied</Code><Message>SignatureDoesNotMatch</Message></Error>".toByteArray()) }
        val error = assertThrows(CloudHttpException::class.java) { client.metadata("catalog.json") }
        assertTrue(error.message!!.contains("签名兼容"))
        assertFalse(error.message!!.contains("拒绝策略"))
    }

    @Test fun failedTestWritesDoNotDeleteAndCleanupCannotHideTheOriginalFailure() {
        val requests = mutableListOf<String>()
        val rejected = transport { request ->
            requests += request.method
            response(request, 403, "<Error><Code>AccessDenied</Code></Error>".toByteArray())
        }
        assertThrows(CloudHttpException::class.java) { rejected.testWriteAccess() }
        assertEquals(listOf("PUT"), requests)
        var writes = 0
        val cleanupFails = transport { request ->
            if (request.method == "PUT" && ++writes == 1) response(request, 200)
            else response(request, 403, "<Error><Code>${if(request.method == "DELETE") "CleanupDenied" else "OriginalDenied"}</Code></Error>".toByteArray())
        }
        val error = assertThrows(CloudHttpException::class.java) { cleanupFails.testWriteAccess() }
        assertTrue(error.message!!.contains("OriginalDenied"))
        assertEquals(1, error.suppressed.size)
        assertTrue(error.suppressed.single().message!!.contains("CleanupDenied"))
    }

    private val credentials = CloudCredentials("test-access", "test-secret", "https://storage.example.invalid/",
        S3Location("reader-backups", "us-east-1", "书架 + 1"))

    private fun transport(reply: (Request) -> Response) = S3CloudTransport(credentials, {},
        OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build())

    private fun response(request: Request, code: Int, bytes: ByteArray = byteArrayOf(), etag: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(bytes.toResponseBody()).apply { etag?.let { header("ETag", it) } }.build()

    @Test fun signedPathsAndQueriesUseS3EncodingAndConditionsAreSigned() {
        val request = signS3Request(credentials, "GET",
            "https://storage.example.invalid:9443/reader-backups/书架 + 1/file*?prefix=a/b%2Bc&continuation-token=z/%2B=&list-type=2".toHttpUrl(),
            null, "empty-hash", mapOf("If-None-Match" to "\"version\""), Instant.parse("2026-11-22T00:00:00Z"))
        assertEquals("/reader-backups/%E4%B9%A6%E6%9E%B6%20%2B%201/file%2A", request.url.encodedPath)
        assertEquals("continuation-token=z%2F%2B%3D&list-type=2&prefix=a%2Fb%2Bc", request.url.encodedQuery)
        assertEquals("storage.example.invalid:9443", request.header("Host"))
        assertTrue(request.header("Authorization")!!.contains("20261122/us-east-1/s3/aws4_request"))
        assertTrue(request.header("Authorization")!!.contains("if-none-match"))
        // Independently generated with botocore's S3SigV4Auth.
        assertTrue(request.header("Authorization")!!.endsWith("Signature=35e197cf6dbcb7d9be07aada9a33b6087399c95fba12a70682eeec7076ea3070"))
        assertFalse(request.toString().contains("test-secret"))
    }

    @Test fun credentialPayloadPreservesS3AndSeparatesBackendBucketAndPrefix() {
        val restored = CloudCredentialPayload.decode(CloudCredentialPayload.encode(credentials))
        assertEquals(credentials.s3, restored.s3)
        assertEquals(credentials.identity, restored.identity)
        assertEquals(credentials.password, restored.password)
        assertNotEquals(credentials.identity, CloudCredentials(credentials.account, credentials.password, credentials.serverUrl).identity)
        assertNotEquals(credentials.identity, CloudCredentials(credentials.account, credentials.password,
            credentials.serverUrl, S3Location("other-bucket", "us-east-1")).identity)
    }

    @Test fun listPaginatesAndOnlyDeletesWithinTheSelectedBook() {
        val requests = mutableListOf<Request>()
        var listings = 0
        val prefix = "书架 + 1/books/book/"
        val client = transport { request ->
            requests += request
            if (request.method == "DELETE") response(request, 204) else {
                listings++
                assertEquals(prefix, request.url.queryParameter("prefix"))
                val second = listings == 2
                if (second) assertEquals("token/+", request.url.queryParameter("continuation-token"))
                response(request, 200, ("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Contents><Key>${prefix}${if (second) "second.bin" else "first.bin"}</Key></Contents>
                    <IsTruncated>${!second}</IsTruncated>
                    ${if (second) "" else "<NextContinuationToken>token/+</NextContinuationToken>"}
                </ListBucketResult>""").toByteArray())
            }
        }
        client.deleteCollection("books/book/")
        assertEquals(2, listings)
        assertEquals(2, requests.count { it.method == "DELETE" })
        assertThrows(IllegalArgumentException::class.java) { client.deleteCollection("") }
        assertThrows(IllegalArgumentException::class.java) { client.deleteCollection("books/") }
    }

    @Test fun listRejectsARepeatedContinuationAndOutOfScopeDeletion() {
        val repeating = transport { request -> response(request, 200,
            "<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>repeat</NextContinuationToken></ListBucketResult>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { repeating.collections("books/") }
        val foreign = transport { request -> response(request, 200,
            "<ListBucketResult><Contents><Key>outside/book.bin</Key></Contents></ListBucketResult>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { foreign.deleteCollection("books/book/") }
        val entity = transport { request -> response(request, 200,
            "<!DOCTYPE root [<!ENTITY external SYSTEM 'file:///private'>]><ListBucketResult/>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { entity.collections("books/") }
    }

    @Test fun conditionalReadsAndWritesHandleMissingUnchangedAndConflicts() {
        val reads = mutableListOf<Request>()
        var code = 404
        val client = transport { request -> reads += request; response(request, code, "record".toByteArray(), "\"tag\"") }
        assertNull(client.versionedMetadata("catalog.json"))
        code = 200
        assertEquals("\"tag\"", client.versionedMetadata("catalog.json")!!.etag)
        code = 304
        assertTrue(client.readMetadata("catalog.json", "\"tag\"").unchanged)
        assertEquals("\"tag\"", reads.last().header("If-None-Match"))
        for (conflict in listOf(409, 412)) {
            code = conflict
            assertFalse(client.putVersionedMetadata("catalog.json", byteArrayOf(1), "\"tag\""))
            assertEquals("\"tag\"", reads.last().header("If-Match"))
        }
        code = 200
        assertTrue(client.putVersionedMetadata("catalog.json", byteArrayOf(1), null))
        assertEquals("*", reads.last().header("If-None-Match"))
    }

    @Test fun fileUploadsOnlyTheSelectedPartAndDownloadsAreBounded() {
        val file = Files.createTempFile("s3-part", ".bin").toFile()
        val output = Files.createTempFile("s3-download", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(0, 1, 2, 3, 4))
            var done = 0L
            val client = transport { request ->
                if (request.method == "PUT") {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    assertArrayEquals(byteArrayOf(1, 2, 3), buffer.readByteArray())
                    assertEquals(3, request.body!!.contentLength())
                }
                response(request, 200, byteArrayOf(7, 8))
            }
            client.upload("books/book/part.bin", file, 1, 3) { done = it }
            assertEquals(3L, done)
            assertEquals(2L, client.download("books/book/part.bin", output, false, 2) {})
            assertArrayEquals(byteArrayOf(7, 8), output.readBytes())
            assertThrows(IllegalArgumentException::class.java) { client.download("books/book/part.bin", output, false, 1) {} }
        } finally { file.delete(); output.delete() }
    }
}
