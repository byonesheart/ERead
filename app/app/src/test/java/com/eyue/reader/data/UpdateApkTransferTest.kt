package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException

class UpdateApkTransferTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://github.com/byonesheart/ERead/releases/download/v1/ERead.apk"

    @Test fun resumesAtTheSavedByteAfterGitHubRedirect() = runBlocking {
        val partial = folder.newFile().apply { writeText("abc") }
        val etag = folder.newFile().apply { writeText("\"same\"") }
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            if (request.url.host == "github.com") response(request, 302, "")
                .header("Location", "https://release-assets.githubusercontent.com/file?signature=example").build()
            else response(request, 206, "def").header("Content-Range", "bytes 3-5/6").header("ETag", "\"same\"").build()
        }
        val progress = mutableListOf<Pair<Long, Long>>()
        UpdateApkTransfer(client, partial, etag).download(url) { bytes, total -> progress += bytes to total }
        assertEquals(listOf("bytes=3-", "bytes=3-"), requests.map { it.header("Range") })
        assertEquals(listOf("\"same\"", "\"same\""), requests.map { it.header("If-Range") })
        assertEquals("abcdef", partial.readText())
        assertEquals(6L to 6L, progress.last())
    }

    @Test fun restartsWhenServerReturnsTheWholePackage() = runBlocking {
        val partial = folder.newFile().apply { writeText("old") }
        val etag = folder.newFile().apply { writeText("\"old\"") }
        val client = client { response(it, 200, "new package").header("ETag", "\"new\"").build() }
        UpdateApkTransfer(client, partial, etag).download(url) { _, _ -> }
        assertEquals("new package", partial.readText())
        assertEquals("\"new\"", etag.readText())
    }

    @Test fun rejectsAnIncorrectResumeOffsetBeforeWriting() = runBlocking {
        val partial = folder.newFile().apply { writeText("abc") }
        val etag = folder.newFile().apply { writeText("\"same\"") }
        val client = client { response(it, 206, "cdef").header("Content-Range", "bytes 2-5/6").build() }
        val error = runCatching { UpdateApkTransfer(client, partial, etag).download(url) { _, _ -> } }.exceptionOrNull()
        assertEquals(UpdateDownloadException::class.java, error?.javaClass)
        assertEquals("abc", partial.readText())
        assertFalse(etag.exists())
    }

    @Test fun aMidBodyTimeoutIsRetryableAndTheNextAttemptContinuesFromDisk() = runBlocking {
        val partial = folder.newFile()
        val etag = folder.newFile()
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            if (request.header("Range") == null) {
                val body = object : ResponseBody() {
                    private val input = object : Source {
                        private var sent = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (sent) throw SocketTimeoutException("Connection stalled")
                            sent = true
                            sink.writeUtf8("abc")
                            return 3
                        }
                        override fun timeout() = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()
                    override fun contentType() = null
                    override fun contentLength() = 6L
                    override fun source(): BufferedSource = input
                }
                response(request, 200, "").body(body).header("ETag", "\"same\"").build()
            } else response(request, 206, "def").header("Content-Range", "bytes 3-5/6").header("ETag", "\"same\"").build()
        }
        val progress = mutableListOf<Pair<Long, Long>>()
        val transfer = UpdateApkTransfer(client, partial, etag)
        val failure = runCatching { transfer.download(url) { bytes, total -> progress += bytes to total } }
            .exceptionOrNull() as UpdateDownloadException
        assertTrue(failure.retryable)
        assertEquals("abc", partial.readText())
        assertEquals(listOf(0L to 6L, 3L to 6L), progress)

        transfer.download(url) { bytes, total -> progress += bytes to total }
        assertEquals("bytes=3-", requests.last().header("Range"))
        assertEquals("\"same\"", requests.last().header("If-Range"))
        assertEquals("abcdef", partial.readText())
        assertEquals(6L to 6L, progress.last())
    }

    @Test fun temporaryHttpFailuresCanRetryAndMissingReleaseAssetsDoNot() = runBlocking {
        val partial = folder.newFile()
        val etag = folder.newFile()
        for (code in listOf(429, 503, 404)) {
            val transfer = UpdateApkTransfer(client { response(it, code, "").build() }, partial, etag)
            val error = runCatching { transfer.download(url) { _, _ -> } }.exceptionOrNull() as UpdateDownloadException
            assertEquals(code != 404, error.retryable)
            assertEquals(0L, partial.length())
        }
    }

    @Test fun boundedRetryFollowsGitHubAndKeepsReadingShortRangesFromTheFileServer() = runBlocking {
        val partial = folder.newFile()
        val etag = folder.newFile()
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            val range = request.header("Range")
            if (request.url.host == "github.com") response(request, 302, "")
                .header("Location", "https://release-assets.githubusercontent.com/package").build()
            else when (range) {
                "bytes=0-3" -> response(request, 206, "abc").header("Content-Range", "bytes 0-2/7")
                    .header("ETag", "\"same\"").build()
                "bytes=3-6" -> response(request, 206, "defg").header("Content-Range", "bytes 3-6/7")
                    .header("ETag", "\"same\"").build()
                else -> error("Unexpected range $range")
            }
        }
        val progress = mutableListOf<Pair<Long, Long>>()
        val hosts = mutableListOf<String>()
        UpdateApkTransfer(client, partial, etag, rangeSize = 4).download(url, onConnecting = { hosts += it }) { bytes, total ->
            progress += bytes to total
        }
        assertEquals(listOf("GitHub", "GitHub 文件服务器", "GitHub 文件服务器"), hosts)
        assertEquals(listOf("bytes=0-3", "bytes=0-3", "bytes=3-6"), requests.map { it.header("Range") })
        assertEquals("\"same\"", requests.last().header("If-Range"))
        assertEquals("abcdefg", partial.readText())
        assertEquals(listOf(0L, 3L, 3L, 7L), progress.map { it.first })
        assertTrue(progress.all { it.second == 7L })
    }

    @Test fun systemProxyExclusionsMatchHostsWithoutAccidentallyExcludingGithubAssets() {
        assertTrue(updateProxyExcludes("Localhost", "localhost"))
        assertTrue(updateProxyExcludes("git.internal.example", "*.internal.example"))
        assertFalse(updateProxyExcludes("release-assets.githubusercontent.com", "github.com"))
        assertFalse(updateProxyExcludes("github.com.example", "github.com"))
    }

    private fun client(handle: (Request) -> Response) = OkHttpClient.Builder().followRedirects(false)
        .addInterceptor { handle(it.request()) }.build()

    private fun response(request: Request, code: Int, content: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test").body(content.toResponseBody())
}
