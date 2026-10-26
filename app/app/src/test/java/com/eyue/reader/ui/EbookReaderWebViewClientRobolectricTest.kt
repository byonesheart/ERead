package com.eyue.reader.ui

import android.net.Uri
import android.os.Looper
import android.webkit.ReaderTestWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.eyue.reader.data.EbookSourceResource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EbookReaderWebViewClientRobolectricTest {
    private val documentUrl = "https://eread.local/book/archive/OPS/body.xhtml"
    private lateinit var browser: WebView
    private val failures = mutableListOf<String>()
    private var initializations = 0

    @Before fun setUp() { browser = WebView(RuntimeEnvironment.getApplication()) }

    @Test fun dataDocumentIsNotReplacedWithAForbiddenResponse() {
        val client = client()
        assertNull(client.shouldInterceptRequest(browser, request("data:text/html;charset=utf-8;base64,", main = true)))
        assertTrue(failures.isEmpty())
    }

    @Test fun theOwnedMainDocumentAndItsAnchorsAreAllowedToLoad() {
        val client = client()
        assertFalse(client.shouldOverrideUrlLoading(browser, request(documentUrl, main = true)))
        assertFalse(client.shouldOverrideUrlLoading(browser, request("$documentUrl#note", main = true)))
        assertTrue(client.shouldOverrideUrlLoading(browser, request(documentUrl)))
        listOf("https://example.com/", "https://eread.local/other/book.html", "content://other/private", "file:///data/private/key")
            .forEach { assertTrue(client.shouldOverrideUrlLoading(browser, request(it, main = true))) }
    }

    @Test fun inlineImageDataIsDecodedByWebViewInsteadOfBeingRejected() {
        assertNull(client().shouldInterceptRequest(browser, request("data:image/png;base64,AQ==")))
        assertTrue(failures.isEmpty())
    }

    @Test fun onlyTheOwnedBackgroundEndpointCanReadTheChosenImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        var opened = 0
        val client = EbookReaderWebViewClient(documentUrl, "parent", openResource = { null }, openFont = { null },
            onDocumentLoaded = {}, onFailure = failures::add, openBackground = { opened++; ByteArrayInputStream(png) })
        val response = requireNotNull(client.shouldInterceptRequest(browser, request("https://eread.local/reader-background?v=1")))
        assertEquals("image/png", response.mimeType)
        assertArrayEquals(png, response.data.use { it.readBytes() })
        assertEquals(403, client.shouldInterceptRequest(browser, request("https://other.local/reader-background"))!!.statusCode)
        assertEquals(403, client.shouldInterceptRequest(browser, request("file:///reader-background"))!!.statusCode)
        assertEquals(1, opened)
    }

    @Test fun onlyReaderOwnedChapterFramesCanNavigateAndReceiveTheirTrustedEnvelope() {
        val child = "$documentUrl?chapter=1&token=2"
        val html = "<html><body>相邻章节</body></html>"
        val client = EbookReaderWebViewClient(documentUrl, "parent", openResource = { null }, openFont = { null },
            onDocumentLoaded = {}, onFailure = failures::add, openChapterDocument = { url -> html.takeIf { url == child } })
        assertFalse(client.shouldOverrideUrlLoading(browser, request(child)))
        assertTrue(client.shouldOverrideUrlLoading(browser, request(child, main = true)))
        assertTrue(client.shouldOverrideUrlLoading(browser, request("$documentUrl?chapter=1&token=old")))
        assertTrue(client.shouldOverrideUrlLoading(browser, request("https://example.com/chapter.html")))
        val response = requireNotNull(client.shouldInterceptRequest(browser, request(child)))
        assertEquals("text/html", response.mimeType)
        assertEquals(html, response.data.bufferedReader().use { it.readText() })
    }

    @Test fun mainFrameHttpFailureEndsLoadingAndDoesNotInitializeTheErrorPage() {
        val client = client()
        client.onReceivedHttpError(browser, request(documentUrl, main = true),
            WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())))
        client.onPageFinished(browser, documentUrl)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("403"))
        assertEquals(0, initializations)
    }

    @Test fun mainFrameNetworkFailureEndsLoadingAndDoesNotInitializeTheErrorPage() {
        val client = client()
        client.onReceivedError(browser, request(documentUrl, main = true),
            ReaderTestWebResourceError(WebViewClient.ERROR_UNKNOWN, "net::ERR_HTTP_RESPONSE_CODE_FAILURE"))
        client.onPageFinished(browser, documentUrl)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("ERR_HTTP_RESPONSE_CODE_FAILURE"))
        assertEquals(0, initializations)
    }

    @Test fun resourceReadFailureIsReportedInsteadOfLeavingTheMainFrameSpinning() {
        val client = client(failResource = true)
        val response = requireNotNull(client.shouldInterceptRequest(browser,
            request("https://eread.local/book/archive/broken.xhtml", main = true)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(500, response.statusCode)
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("损坏的资源"))
    }

    @Test fun missingIllustrationDoesNotDiscardTheReadableDocument() {
        val client = client()
        client.onReceivedHttpError(browser, request("https://eread.local/book/archive/missing.png"),
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf())))
        client.onPageFinished(browser, documentUrl)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failures.isEmpty())
        assertEquals(1, initializations)
    }

    @Test fun mainDocumentUsesTheTrustedEnvelopeBeforeLookingInTheBookArchive() {
        var archiveReads = 0
        val html = "<!doctype html><html><body>正文🙂<script>trustedReader()</script></body></html>"
        val client = EbookReaderWebViewClient(documentUrl, html,
            openResource = { archiveReads++; error("Must not read the unwrapped archive document") }, openFont = { null },
            onDocumentLoaded = {}, onFailure = failures::add)
        val response = requireNotNull(client.shouldInterceptRequest(browser, request(documentUrl, main = true)))
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        assertEquals("UTF-8", response.encoding)
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertEquals(html, response.data.bufferedReader().use { it.readText() })
        assertEquals(0, archiveReads)
    }

    @Test fun localStylesImagesAndFontsKeepTheirExactBytes() {
        val bytes = byteArrayOf(0, 1, -1, 3)
        val url = "https://eread.local/book/archive/OPS/style.css"
        val client = EbookReaderWebViewClient(documentUrl, "正文", openResource = {
            assertEquals(url, it)
            EbookSourceResource("text/css", stream = ByteArrayInputStream(bytes))
        }, openFont = { ByteArrayInputStream(bytes) }, onDocumentLoaded = {}, onFailure = failures::add)
        val css = requireNotNull(client.shouldInterceptRequest(browser, request(url)))
        assertEquals("text/css", css.mimeType)
        assertArrayEquals(bytes, css.data.use { it.readBytes() })
        val font = requireNotNull(client.shouldInterceptRequest(browser, request("https://eread.local/reader-font?v=2")))
        assertEquals("font/ttf", font.mimeType)
        assertArrayEquals(bytes, font.data.use { it.readBytes() })
    }

    @Test fun unknownNetworkAndPrivateProviderRequestsStayBlocked() {
        val client = client()
        listOf("https://example.com/remote.png", "content://other/private", "file:///data/private/key").forEach { url ->
            val response = requireNotNull(client.shouldInterceptRequest(browser, request(url)))
            assertEquals(403, response.statusCode)
        }
        assertTrue(failures.isEmpty())
    }

    @Test fun unrelatedFinishedPagesDoNotInitializeTheReader() {
        val client = client()
        client.onPageFinished(browser, "chrome-error://chromewebdata/")
        client.onPageFinished(browser, "about:blank")
        assertEquals(0, initializations)
        client.onPageFinished(browser, "$documentUrl#note")
        assertEquals(1, initializations)
    }

    @Test fun duplicateMainFrameErrorsOnlyProduceOneTerminalFailure() {
        val client = client()
        repeat(2) { client.onReceivedHttpError(browser, request(documentUrl, main = true),
            WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures.size)
    }

    private fun client(failResource: Boolean = false) = EbookReaderWebViewClient(
        documentUrl, "<html><body>正文</body></html>",
        openResource = { if (failResource) error("损坏的资源") else null }, openFont = { null },
        onDocumentLoaded = { initializations++ }, onFailure = failures::add)

    private fun request(value: String, main: Boolean = false) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
