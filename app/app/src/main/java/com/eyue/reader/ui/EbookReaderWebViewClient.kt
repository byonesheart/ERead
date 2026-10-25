package com.eyue.reader.ui

import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.eyue.reader.data.EbookSourceResource
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Serves the reader-owned document and its resources without falling through to the network. */
internal class EbookReaderWebViewClient(
    private val documentUrl: String,
    documentHtml: String,
    private val openResource: (String) -> EbookSourceResource?,
    private val openFont: () -> InputStream?,
    private val onDocumentLoaded: (WebView) -> Unit,
    private val onFailure: (String) -> Unit,
    private val openChapterDocument: (String) -> String? = { null }
) : WebViewClient() {
    private val document = documentHtml.toByteArray(Charsets.UTF_8)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var failed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !(request.method == "GET" && if (request.isForMainFrame) isDocument(request.url.toString())
            else openChapterDocument(request.url.toString()) != null)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // WebView also intercepts data: loads, including its loadDataWithBaseURL envelope.
        // Inline images must be decoded by WebView, not replaced with our 403 response.
        if (request.url.scheme == "data") return null
        if (request.method != "GET") return denied()
        if (!request.isForMainFrame) openChapterDocument(request.url.toString())?.let { html ->
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        if (request.isForMainFrame && isDocument(request.url.toString())) {
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(document))
        }
        return try {
            if (request.url.scheme == "https" && request.url.authority == "eread.local" && request.url.path == "/reader-font") {
                openFont()?.let { return WebResourceResponse("font/ttf", null, it) }
            }
            val resource = openResource(request.url.toString())
            if (resource != null) WebResourceResponse(resource.mimeType, resource.encoding, resource.stream) else denied()
        } catch (error: Exception) {
            if (request.isForMainFrame) fail("原书页面读取失败：${error.localizedMessage.orEmpty().take(160)}")
            WebResourceResponse("text/plain", "UTF-8", 500, "Read failed", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!failed && url != null && isDocument(url)) onDocumentLoaded(view)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) fail("原书页面加载失败：${error.description}（${error.errorCode}）")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) fail("原书页面加载失败：HTTP ${errorResponse.statusCode}")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        fail("原书排版组件已停止，请重试")
        return true
    }

    private fun isDocument(url: String) = url.substringBefore('#') == documentUrl
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private fun fail(message: String) {
        if (failed) return
        failed = true
        mainHandler.post { onFailure(message) }
    }
}
