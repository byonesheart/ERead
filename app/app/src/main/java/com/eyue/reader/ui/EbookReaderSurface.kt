package com.eyue.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ChineseTextConverter
import com.eyue.reader.data.EbookSourceDocument
import com.eyue.reader.data.EbookSourceStore
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.SourceFootnote
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

internal data class EbookViewportState(
    val ready: Boolean = false,
    val error: String? = null,
    val offset: Int = 0,
    val endOffset: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val visibleImagePaths: List<String> = emptyList(),
    val scrolling: Boolean = false,
    val atEnd: Boolean = false
)

/** Commands survive chapter loads; a stale chapter cannot consume a new chapter's anchor. */
internal class EbookReaderController {
    private var chapter = -1
    private var ready = false
    private var dispatch: ((JSONObject) -> Unit)? = null
    private var pending: Pair<Int?, JSONObject>? = null

    fun navigate(offset: Int, imagePath: String? = null, lastPage: Boolean = false,
        ensureVisible: Boolean = false, expectedChapterIndex: Int? = null) {
        val command = JSONObject().put("type", "navigate").put("offset", offset).put("image", imagePath)
            .put("lastPage", lastPage).put("ensureVisible", ensureVisible)
        pending = expectedChapterIndex to command
        if (ready && (expectedChapterIndex == null || expectedChapterIndex == chapter)) dispatch?.invoke(command)
    }

    fun checkpoint(offset: Int, imagePath: String? = null, expectedChapterIndex: Int? = null) {
        pending = expectedChapterIndex to JSONObject().put("type", "navigate").put("offset", offset).put("image", imagePath)
    }

    fun move(direction: Int, expectedChapterIndex: Int? = null) {
        if (ready && (expectedChapterIndex == null || expectedChapterIndex == chapter))
            dispatch?.invoke(JSONObject().put("type", "move").put("direction", direction))
    }

    fun clearSelection() { dispatch?.invoke(JSONObject().put("type", "clearSelection")) }

    internal fun bind(chapterIndex: Int, callback: (JSONObject) -> Unit) {
        chapter = chapterIndex; dispatch = callback; ready = false
    }

    internal fun loaded() {
        ready = true
        val command = pending?.takeIf { it.first == null || it.first == chapter }?.second
        if (command != null) pending = null
        dispatch?.invoke(command ?: JSONObject().put("type", "report"))
    }

    internal fun unbind() { dispatch = null; ready = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EbookReaderSurface(
    store: EbookSourceStore,
    chapterIndex: Int,
    chapter: Chapter,
    canonicalText: String,
    settings: ReaderSettings,
    annotations: List<Annotation>,
    controller: EbookReaderController,
    modifier: Modifier = Modifier,
    dualPage: Boolean = false,
    einkMode: Boolean = false,
    foreground: Color,
    selectionReset: Int = 0,
    selectionMenuEnabled: Boolean = true,
    searchQuery: String = "",
    spokenRange: IntRange? = null,
    chineseConverter: ChineseTextConverter? = null,
    onViewport: (EbookViewportState) -> Unit,
    onTap: () -> Unit,
    onManualStart: () -> Unit,
    onManualEnd: () -> Unit,
    onChapterStep: (Int) -> Unit,
    onSelectionActive: (Boolean) -> Unit,
    onAction: (ReaderSelectedPassage, AnnotationKind) -> Unit,
    onEdit: ((ReaderSelectedPassage) -> Unit)? = null,
    onRole: ((ReaderSelectedPassage) -> Unit)? = null,
    onAnnotationTap: (List<String>, Rect) -> Unit,
    onFootnote: (SourceFootnote, Rect) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val paperColor = (if (settings.nightMode) MaterialTheme.colorScheme.background else when (settings.background) {
        ReaderBackground.WHITE -> Color.White
        ReaderBackground.GREEN -> Color(0xFFE5EBD9)
        else -> MaterialTheme.colorScheme.background
    }).toArgb()
    val calibration = remember(settings.fontName, settings.fontPath, settings.bold) { readerFontCalibration(context, settings) }
    val fontScale = density.fontScale
    val fontVersion = remember(settings.fontName, settings.fontPath) { UUID.randomUUID().toString() }
    val config = remember(settings, foreground, dualPage, einkMode, fontScale, calibration, fontVersion) {
        JSONObject().put("mode", settings.readingMode.name).put("turn", settings.pageTurnStyle.name)
            .put("authorCss", settings.useSourceCss).put("dual", dualPage).put("eink", einkMode)
            .put("fontSize", settings.fontSize * calibration.sizeScale * fontScale)
            .put("lineHeight", settings.fontSize * settings.lineHeight * fontScale)
            .put("letterSpacing", (settings.letterSpacing + settings.fontSize * calibration.trackingEm) * fontScale)
            .put("paragraphGap", settings.fontSize * settings.paragraphSpacing * fontScale)
            .put("bold", settings.bold).put("night", settings.nightMode)
            .put("color", "#%06x".format(foreground.toArgb() and 0xffffff))
            .put("left", settings.marginLeft).put("right", settings.marginRight)
            .put("top", settings.marginTop).put("bottom", settings.marginBottom)
            .put("font", if (settings.fontPath != null || readerBundledFontResource(settings) != null) "eread-selected"
                else if (settings.fontName == "等宽字体") "monospace" else "sans-serif")
            .put("fontUrl", "https://eread.local/reader-font?v=$fontVersion")
    }
    var document by remember(store, chapterIndex, canonicalText, chineseConverter) { mutableStateOf<EbookSourceDocument?>(null) }
    var loadingError by remember(store, chapterIndex, canonicalText, chineseConverter) { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<EbookWebView?>(null) }
    var ready by remember(store, chapterIndex, canonicalText, chineseConverter) { mutableStateOf(false) }
    var selected by remember(store, chapterIndex, canonicalText) { mutableStateOf<ReaderSelectedPassage?>(null) }
    val latestConfig by rememberUpdatedState(config)
    val latestConverter by rememberUpdatedState(chineseConverter)
    val latestSettings by rememberUpdatedState(settings)
    val latestViewport by rememberUpdatedState(onViewport)
    val latestTap by rememberUpdatedState(onTap)
    val latestManualStart by rememberUpdatedState(onManualStart)
    val latestManualEnd by rememberUpdatedState(onManualEnd)
    val latestChapterStep by rememberUpdatedState(onChapterStep)
    val latestSelectionActive by rememberUpdatedState(onSelectionActive)
    val latestAnnotationTap by rememberUpdatedState(onAnnotationTap)
    val latestFootnote by rememberUpdatedState(onFootnote)
    val latestChapter by rememberUpdatedState(chapter)
    val latestMenuEnabled by rememberUpdatedState(selectionMenuEnabled)
    val script = remember(context) { context.assets.open("reader/ebook-reader.js").bufferedReader().use { it.readText() } }
    val generation = remember(store, chapterIndex, canonicalText, chineseConverter) { UUID.randomUUID().toString() }
    val latestGeneration by rememberUpdatedState(generation)
    fun invoke(command: JSONObject) { webView?.evaluateJavascript("window.ERead && ERead.command($command)", null) }
    LaunchedEffect(generation) {
        selected = null; ready = false; latestViewport(EbookViewportState())
        controller.bind(chapterIndex) { invoke(it) }
        try { document = store.load(chapterIndex, canonicalText) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { loadingError = error.message ?: "无法读取原书结构" }
    }
    LaunchedEffect(loadingError) {
        loadingError?.let { latestViewport(EbookViewportState(error = it)) }
    }
    LaunchedEffect(selectionReset, selectionMenuEnabled) {
        selected = null; controller.clearSelection(); latestSelectionActive(false)
    }
    DisposableEffect(controller) { onDispose { controller.unbind(); latestSelectionActive(false) } }
    val decorations = remember(annotations, chapterIndex, searchQuery, spokenRange) {
        JSONObject().put("annotations", JSONArray(annotations.filter { it.chapterIndex == chapterIndex }.map {
            JSONObject().put("id", it.id).put("start", it.characterOffset).put("end", it.endOffset)
                .put("kind", it.kind.name).put("color", it.color).put("image", it.imagePath)
                .put("underline", it.underlineStyle.name)
        })).put("search", searchQuery).put("spokenStart", spokenRange?.first ?: -1).put("spokenEnd", spokenRange?.let { it.last + 1 } ?: -1)
    }
    val latestDecorations by rememberUpdatedState(decorations)
    val sourceNotes = remember(chapter.sourceFootnotes) { JSONArray(chapter.sourceFootnotes.map {
        JSONObject().put("start", it.start).put("end", it.end).put("label", it.label).put("content", it.content)
    }) }
    val latestSourceNotes by rememberUpdatedState(sourceNotes)
    LaunchedEffect(config.toString(), ready) { if (ready) invoke(JSONObject().put("type", "configure").put("config", config)) }
    LaunchedEffect(decorations.toString(), ready) { if (ready) invoke(JSONObject().put("type", "decorate").put("decorations", decorations)) }
    LaunchedEffect(sourceNotes.toString(), ready) { if (ready) invoke(JSONObject().put("type", "notes").put("notes", sourceNotes)) }
    Box(modifier) {
        val source = document
        if (source != null) {
            key(source.key, generation) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { androidContext ->
                    EbookWebView(androidContext).also { browser ->
                        webView = browser
                        browser.setBackgroundColor(AndroidColor.TRANSPARENT)
                        browser.overScrollMode = WebView.OVER_SCROLL_NEVER
                        browser.isVerticalScrollBarEnabled = false; browser.isHorizontalScrollBarEnabled = false
                        browser.settings.apply {
                            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
                            domStorageEnabled = false; databaseEnabled = false; blockNetworkLoads = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            builtInZoomControls = false; displayZoomControls = false
                            textZoom = 100; mediaPlaybackRequiresUserGesture = true
                            setSupportMultipleWindows(false)
                        }
                        browser.webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                if (generation == latestGeneration) {
                                    ready = false
                                    latestViewport(EbookViewportState(error = "原书排版组件已停止，请重试"))
                                }
                                return true
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                if (request.url.scheme == "https" && request.url.authority == "eread.local" && request.url.path == "/reader-font") {
                                    val chosen = latestSettings
                                    val input = runCatching { chosen.fontPath?.let { File(it).inputStream() }
                                        ?: readerBundledFontResource(chosen)?.let { context.resources.openRawResource(it) } }.getOrNull()
                                    if (input != null) return WebResourceResponse("font/ttf", null, input)
                                }
                                val resource = runCatching { store.openResource(request.url.toString()) }.getOrNull()
                                return if (resource != null) WebResourceResponse(resource.mimeType, resource.encoding, resource.stream)
                                else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (generation != latestGeneration) return
                                val initial = JSONObject().put("canonical", canonicalText).put("config", latestConfig)
                                    .put("noteTargets", JSONArray(source.noteTargets.keys.toList()))
                                    .put("notes", latestSourceNotes)
                                    .put("decorations", latestDecorations).put("images", JSONArray(chapter.inlineImages.map {
                                        JSONObject().put("offset", it.offset).put("path", it.path)
                                    }))
                                view.evaluateJavascript("window.ERead && ERead.init($initial)", null)
                            }
                        }
                        browser.addJavascriptInterface(object {
                            @JavascriptInterface fun convert(text: String): String {
                                val converted = latestConverter?.convert(text)
                                return JSONObject().put("text", converted?.text ?: text)
                                    .put("forward", converted?.forward?.let { JSONArray(it.toList()) }).toString()
                            }
                            @JavascriptInterface fun event(json: String) {
                                browser.post {
                                    if (generation != latestGeneration || browser !== webView) return@post
                                    val event = runCatching { JSONObject(json) }.getOrNull() ?: return@post
                                    fun anchor(): Rect {
                                        val origin = IntArray(2).also(browser::getLocationInWindow)
                                        val scale = browser.resources.displayMetrics.density
                                        return Rect(origin[0] + event.optDouble("left").toFloat() * scale,
                                            origin[1] + event.optDouble("top").toFloat() * scale,
                                            origin[0] + event.optDouble("right").toFloat() * scale,
                                            origin[1] + event.optDouble("bottom").toFloat() * scale)
                                    }
                                    when (event.optString("type")) {
                                        "ready" -> { ready = true; controller.loaded() }
                                        "error" -> { ready = false; latestViewport(EbookViewportState(error = event.optString("message", "原书排版失败"))) }
                                        "viewport" -> latestViewport(EbookViewportState(true, offset = event.optInt("offset"),
                                            endOffset = event.optInt("endOffset"), pageIndex = event.optInt("page"), pageCount = event.optInt("pages", 1),
                                            visibleImagePaths = event.optJSONArray("images")?.let { items -> List(items.length()) { items.getString(it) } }.orEmpty(),
                                            scrolling = event.optBoolean("scrolling"), atEnd = event.optBoolean("atEnd")))
                                        "tap" -> latestTap()
                                        "turn" -> browser.turnPage(event.optInt("page"), event.optInt("direction"),
                                            runCatching { PageTurnStyle.valueOf(event.optString("style")) }.getOrDefault(PageTurnStyle.SLIDE))
                                        "manualStart" -> { selected = null; latestManualStart() }
                                        "manualEnd" -> latestManualEnd()
                                        "chapterStep" -> latestChapterStep(event.optInt("direction"))
                                        "selection" -> {
                                            val start = event.optInt("start").coerceIn(0, canonicalText.length)
                                            val end = event.optInt("end").coerceIn(start, canonicalText.length)
                                            val image = event.optString("image").takeIf { it.isNotEmpty() }
                                            selected = if (latestMenuEnabled && (end > start || image != null)) ReaderSelectedPassage(chapterIndex,
                                                event.optInt("page"), start, end, canonicalText.substring(start, end), imagePath = image, anchor = anchor()) else null
                                            latestSelectionActive(selected != null)
                                        }
                                        "annotation" -> latestAnnotationTap(event.getJSONArray("ids").let { ids -> List(ids.length()) { ids.getString(it) } }, anchor())
                                        "footnote" -> {
                                            val target = event.optString("target")
                                            val content = source.noteTargets[target]?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
                                                ?: event.optString("content")
                                            val start = event.optInt("offset").coerceIn(0, canonicalText.length)
                                            val note = latestChapter.sourceFootnotes.firstOrNull { start in it.start until it.end }
                                                ?: content.takeIf { it.isNotBlank() }?.let { SourceFootnote(start, start + event.optString("label").length,
                                                    event.optString("label"), it) }
                                            if (note != null) latestFootnote(note, anchor())
                                        }
                                    }
                                }
                            }
                        }, "__ERead")
                        browser.loadDataWithBaseURL(source.baseUrl, ebookReaderHtml(source, script, UUID.randomUUID().toString()), "text/html", "UTF-8", null)
                    }
                }, update = { browser -> browser.paperColor = paperColor }, onRelease = { browser ->
                    browser.finishTurn(); browser.stopLoading(); browser.removeJavascriptInterface("__ERead"); browser.destroy()
                    if (webView === browser) webView = null
                })
            }
        }
        if (!ready && loadingError == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    selected?.takeIf { selectionMenuEnabled }?.let { passage ->
        ReaderAnnotationPopup(passage.anchor ?: Rect.Zero, onDismiss = { selected = null }) {
            fun action(kind: AnnotationKind) { selected = null; controller.clearSelection(); onAction(passage, kind) }
            ReaderCompactActionMenu(listOf(
                ReaderCompactAction("note", "笔记") { action(AnnotationKind.NOTE) },
                ReaderCompactAction("highlight", "标记") { action(AnnotationKind.HIGHLIGHT) },
                ReaderCompactAction("underline", "划线") { action(AnnotationKind.UNDERLINE) }
            ) + listOfNotNull(onRole?.let { callback -> ReaderCompactAction("role", "角色") {
                selected = null; controller.clearSelection(); callback(passage)
            } }, onEdit?.takeIf { passage.imagePath == null }?.let { callback -> ReaderCompactAction("edit", "编辑") {
                selected = null; controller.clearSelection(); callback(passage)
            } }) + ReaderCompactAction("copy", "复制") {
                clipboard.setText(AnnotatedString(passage.quote)); selected = null; controller.clearSelection()
            })
        }
    }
}

/** Keep Android selection handles, but never display its competing copy/share menu. */
private class EbookWebView(context: Context) : EbookPageTurnWebView(context) {
    private fun selection(callback: ActionMode.Callback) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { callback.onCreateActionMode(mode, menu); menu.clear(); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { menu.clear(); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(selection(callback))
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(selection(callback), type)
}
