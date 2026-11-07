package com.eyue.reader.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    private fun client(handle: (Request) -> Response) = OkHttpClient.Builder().followRedirects(false)
        .addInterceptor { handle(it.request()) }.build()

    private fun response(request: Request, code: Int, content: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test").body(content.toResponseBody())
}
