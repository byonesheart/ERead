package com.eyue.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
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
    val atEnd: Boolean = false,
    val chapterIndex: Int = -1
)

/** Commands survive chapter loads; a stale chapter cannot consume a new chapter's anchor. */
internal class EbookReaderController {
    private var chapter = -1
    private var ready = false
    private var acceptsChapterChanges = false
    private var autoScrollSpeed = 0f
    private var dispatch: ((JSONObject) -> Unit)? = null
    private var pending: Pair<Int?, JSONObject>? = null

    fun navigate(offset: Int, imagePath: String? = null, lastPage: Boolean = false,
        ensureVisible: Boolean = false, expectedChapterIndex: Int? = null) {
        val command = JSONObject().put("type", "navigate").put("offset", offset).put("image", imagePath)
            .put("lastPage", lastPage).put("ensureVisible", ensureVisible).put("chapter", expectedChapterIndex)
        pending = expectedChapterIndex to command
        if (ready && (acceptsChapterChanges || expectedChapterIndex == null || expectedChapterIndex == chapter)) {
            pending = null
            dispatch?.invoke(command)
        }
    }

    fun checkpoint(offset: Int, imagePath: String? = null, expectedChapterIndex: Int? = null) {
        pending = expectedChapterIndex to JSONObject().put("type", "navigate").put("offset", offset).put("image", imagePath)
            .put("chapter", expectedChapterIndex)
    }

    fun move(direction: Int, expectedChapterIndex: Int? = null) {
        if (ready && (acceptsChapterChanges || expectedChapterIndex == null || expectedChapterIndex == chapter))
            dispatch?.invoke(JSONObject().put("type", "move").put("direction", direction))
    }

    fun autoScroll(speed: Float) {
        if (ready && speed != autoScrollSpeed) {
            autoScrollSpeed = speed
            dispatch?.invoke(JSONObject().put("type", "autoScroll").put("speed", speed.toDouble()))
        }
    }

    fun clearSelection() { dispatch?.invoke(JSONObject().put("type", "clearSelection")) }
    fun cancelTouch() { dispatch?.invoke(JSONObject().put("type", "cancelTouch")) }

    internal fun bind(chapterIndex: Int, continuous: Boolean = false, callback: (JSONObject) -> Unit) {
        autoScroll(0f)
        autoScrollSpeed = 0f
        chapter = chapterIndex; dispatch = callback; ready = false; acceptsChapterChanges = continuous
    }

    internal fun initialNavigation(chapterIndex: Int): JSONObject? =
        pending?.takeIf { it.first == null || it.first == chapterIndex }?.second?.takeIf { it.optString("type") == "navigate" }

    internal fun loaded(applied: JSONObject? = null) {
        ready = true
        if (applied != null && pending?.second === applied) pending = null
        if (!consumePending()) dispatch?.invoke(JSONObject().put("type", "report"))
    }

    internal fun consumePending(): Boolean {
        val command = pending?.takeIf { acceptsChapterChanges || it.first == null || it.first == chapter }?.second
            ?: return false
        pending = null
        dispatch?.invoke(command)
        return true
    }

    internal fun unbind() { autoScroll(0f); dispatch = null; ready = false; acceptsChapterChanges = false }
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
    onFootnote: (SourceFootnote, Rect) -> Unit,
    session: EbookReaderSession? = null,
    contentReady: Boolean = true,
    initialOffset: Int = 0,
    initialImagePath: String? = null,
    continuousChapters: List<Chapter> = emptyList(),
    readContinuousText: (suspend (Int) -> String)? = null,
    preload: Boolean = false,
    retentionCenter: Int = chapterIndex,
    onPageTap: ((Float, Float) -> Unit)? = null
) {
    if (settings.continuousEbookScroll && settings.readingMode == com.eyue.reader.data.ReadingMode.SCROLL &&
        continuousChapters.isNotEmpty() && readContinuousText != null) {
        EbookContinuousSurface(store, continuousChapters, readContinuousText, chapterIndex, initialOffset, initialImagePath,
            settings, annotations, controller, modifier, foreground, einkMode, selectionReset, selectionMenuEnabled,
            searchQuery, spokenRange, chineseConverter, onViewport, onTap, onManualStart, onManualEnd, onSelectionActive,
            onAction, onEdit, onRole, onAnnotationTap, onFootnote, session, onPageTap)
        return
    }
    val context = LocalContext.current
    val localSession = remember(context) { if (session == null) EbookReaderSession(context) else null }
    val browserSession = session ?: requireNotNull(localSession)
    val browserLease = remember { Any() }
    DisposableEffect(localSession) { onDispose { localSession?.close() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val paperColor = readerPalette(settings.background, settings.nightMode, MaterialTheme.colorScheme, einkMode).background.toArgb()
    val config = rememberEbookConfig(settings, foreground, dualPage, einkMode)
    val openFont = rememberEbookFontOpener(settings)
    var document by remember(store) { mutableStateOf<Pair<String, EbookSourceDocument>?>(null) }
    var loadingError by remember(store, chapterIndex, canonicalText) { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<EbookWebView?>(null) }
    var ready by remember(store, chapterIndex, canonicalText) { mutableStateOf(false) }
    var selected by remember(store, chapterIndex, canonicalText) { mutableStateOf<ReaderSelectedPassage?>(null) }
    val latestConfig by rememberUpdatedState(config)
    val latestConverter by rememberUpdatedState(chineseConverter)
    val latestSettings by rememberUpdatedState(settings)
    val latestViewport by rememberUpdatedState(onViewport)
    val latestTap by rememberUpdatedState(onTap)
    val latestPageTap by rememberUpdatedState(onPageTap)
    val latestManualStart by rememberUpdatedState(onManualStart)
    val latestManualEnd by rememberUpdatedState(onManualEnd)
    val latestChapterStep by rememberUpdatedState(onChapterStep)
    val latestSelectionActive by rememberUpdatedState(onSelectionActive)
    val latestAnnotationTap by rememberUpdatedState(onAnnotationTap)
    val latestFootnote by rememberUpdatedState(onFootnote)
    val latestChapter by rememberUpdatedState(chapter)
    val latestMenuEnabled by rememberUpdatedState(selectionMenuEnabled)
    val script = remember(context) { context.assets.open("reader/ebook-reader.js").bufferedReader().use { it.readText() } }
    val generation = remember(store, chapterIndex, canonicalText, contentReady) { UUID.randomUUID().toString() }
    val latestGeneration by rememberUpdatedState(generation)
    var loadingVisible by remember(generation) { mutableStateOf(false) }
    LaunchedEffect(generation, ready, loadingError) {
        loadingVisible = false
        if (!ready && loadingError == null) { delay(180); loadingVisible = true }
    }
    fun invoke(command: JSONObject) {
        if (command.optString("type") == "cancelTouch") webView?.let { view ->
            val now = android.os.SystemClock.uptimeMillis()
            val event = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        } else webView?.evaluateJavascript("window.ERead && ERead.command($command)", null)
    }
    LaunchedEffect(generation) {
        selected = null; ready = false; latestViewport(EbookViewportState())
        controller.bind(chapterIndex) { invoke(it) }
        if (!contentReady) return@LaunchedEffect
        try { document = generation to store.load(chapterIndex, canonicalText) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { loadingError = error.message ?: "无法读取原书结构" }
    }
    LaunchedEffect(loadingError) {
        loadingError?.let { latestViewport(EbookViewportState(error = it)) }
    }
    val documentPrepared = document?.first == generation
    LaunchedEffect(generation, documentPrepared, ready, loadingError, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!ready && loadingError == null) {
                delay(if (documentPrepared) 30_000 else 60_000)
                loadingError = if (documentPrepared) "原书页面启动超时，请重试；阅读记录未修改"
                    else "准备原书结构超时，请重试；阅读记录未修改"
            }
        }
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
    LaunchedEffect(config.toString(), ready) { if (ready) {
        delay(100)
        if (webView?.appliedConfig != config.toString()) {
            webView?.appliedConfig = config.toString()
            invoke(JSONObject().put("type", "configure").put("config", config))
        }
    } }
    LaunchedEffect(chineseConverter, ready) { if (ready) {
        invoke(JSONObject().put("type", "convert")); controller.consumePending()
    } }
    LaunchedEffect(decorations.toString(), ready) { if (ready) invoke(JSONObject().put("type", "decorate").put("decorations", decorations)) }
    LaunchedEffect(sourceNotes.toString(), ready) { if (ready) invoke(JSONObject().put("type", "notes").put("notes", sourceNotes)) }
    Box(modifier) {
        val prepared = document
        val source = prepared?.second
        if (source != null) {
            val browserKey = source.key
            fun loadDocument(browser: EbookWebView) {
                val reuse = browser.loadedKey == browserKey && browser.pageReady
                val navigation = controller.initialNavigation(chapterIndex) ?: JSONObject().put("type", "navigate")
                    .put("offset", initialOffset).put("image", initialImagePath)
                val image = navigation.optString("image").takeIf { it.isNotEmpty() }
                val offset = navigation.optInt("offset")
                val preserve = reuse && browser.appliedConfig == latestConfig.toString() && when {
                    navigation.optBoolean("lastPage") -> browser.viewport.atEnd
                    image != null -> image in browser.viewport.visibleImagePaths
                    else -> offset >= browser.viewport.offset && (offset < browser.viewport.endOffset ||
                        offset == browser.viewport.endOffset && (browser.viewport.atEnd || browser.viewport.endOffset == browser.viewport.offset))
                }
                browser.alpha = if (preserve) 1f else 0f
                browser.documentGeneration = generation
                fun fail(message: String) {
                    if (generation == latestGeneration && browser === webView) {
                        ready = false
                        browser.pageReady = false
                        loadingError = message
                    }
                }
                fun initialOptions() = JSONObject().put("canonical", canonicalText).put("config", latestConfig)
                    .put("noteTargets", JSONArray(source.noteTargets.keys.toList())).put("notes", latestSourceNotes)
                    .put("decorations", latestDecorations).put("images", JSONArray(chapter.inlineImages.map {
                        JSONObject().put("offset", it.offset).put("path", it.path)
                    })).put("initialNavigation", navigation).put("preserveViewport", preserve)
                browser.webViewClient = EbookReaderWebViewClient(source.baseUrl,
                    if (reuse) browser.envelope else ebookReaderHtml(source, script, UUID.randomUUID().toString(), initialOptions())
                        .also { browser.envelope = it }, openResource = store::openResource,
                    openFont = openFont, openBackground = {
                        latestSettings.backgroundImagePath?.takeIf { latestSettings.background == ReaderBackground.IMAGE }
                            ?.let { path -> File(path).inputStream() }
                    }, onFailure = ::fail, onDocumentLoaded = { view ->
                        if (generation == latestGeneration && browser === webView) {
                            val initial = initialOptions()
                            view.evaluateJavascript("(function(){if(!window.ERead)return false;ERead.init($initial);return true;})()") { started ->
                                if (started != "true") fail("原书阅读脚本未能启动，请重试")
                            }
                        }
                    })
                browser.convertText = { text ->
                    val converted = latestConverter?.convert(text)
                    JSONObject().put("text", converted?.text ?: text)
                        .put("forward", converted?.forward?.let { JSONArray(it.toList()) }).toString()
                }
                browser.onBridgeEvent = event@ { json ->
                    if (generation != latestGeneration || browser !== webView || loadingError != null) return@event
                    val event = runCatching { JSONObject(json) }.getOrNull() ?: return@event
                    fun anchor(): Rect {
                        val origin = IntArray(2).also(browser::getLocationInWindow)
                        val scale = browser.resources.displayMetrics.density
                        return Rect(origin[0] + event.optDouble("left").toFloat() * scale,
                            origin[1] + event.optDouble("top").toFloat() * scale,
                            origin[0] + event.optDouble("right").toFloat() * scale,
                            origin[1] + event.optDouble("bottom").toFloat() * scale)
                    }
                    when (event.optString("type")) {
                        "ready" -> { browser.loadedKey = browserKey; browser.pageReady = true; ready = true; controller.loaded(navigation) }
                        "error" -> fail(event.optString("message", "原书排版失败"))
                        "viewport" -> {
                            // A prefetched/reused WebView may still have queued reports
                            // from its previous owner. Wait for this attachment's ready.
                            if (!ready) return@event
                            val state = EbookViewportState(true, offset = event.optInt("offset"),
                            endOffset = event.optInt("endOffset"), pageIndex = event.optInt("page"), pageCount = event.optInt("pages", 1),
                            visibleImagePaths = event.optJSONArray("images")?.let { items -> List(items.length()) { items.getString(it) } }.orEmpty(),
                            scrolling = event.optBoolean("scrolling"), atEnd = event.optBoolean("atEnd"))
                            browser.viewport = state
                            latestViewport(state)
                            if (browser.alpha == 0f) browser.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if (generation == latestGeneration && browser === webView) browser.alpha = 1f
                                }
                            })
                        }
                        "tap" -> latestPageTap?.invoke(event.optDouble("x", .5).toFloat(), event.optDouble("y", .5).toFloat()) ?: latestTap()
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
                browser.appliedConfig = latestConfig.toString()
                if (reuse) browser.evaluateJavascript("ERead.command({type:'resume',options:${initialOptions()}})", null)
                else {
                    browser.pageReady = false
                    browser.loadUrl(source.baseUrl)
                }
            }
            key(browserKey) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { androidContext ->
                    browserSession.acquire(browserKey, chapterIndex, retentionCenter, browserLease).also { browser ->
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
                            offscreenPreRaster = preload
                        }
                        browser.isFocusable = !preload; browser.isFocusableInTouchMode = !preload
                        browser.importantForAccessibility = if (preload) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    }
                }, update = { browser ->
                    browser.paperColor = paperColor
                    if (prepared.first == generation && browser.documentGeneration != generation) loadDocument(browser)
                }, onRelease = { browser ->
                    browserSession.release(browser, browserLease)
                    if (webView === browser) webView = null
                })
            }
        }
        if (loadingVisible && !ready && loadingError == null) Column(Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(if (documentPrepared) "正在加载当前章节" else "正在准备原书结构", style = MaterialTheme.typography.bodySmall)
        }
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

/** Lay out only the two adjacent pages, behind the reader, using its real viewport. */
@Composable
internal fun EbookChapterPreloads(store: EbookSourceStore, chapters: List<Chapter>, text: List<String>, prepared: Set<Int>,
    center: Int, settings: ReaderSettings, annotations: List<Annotation>, foreground: Color, dualPage: Boolean,
    eink: Boolean, converter: ChineseTextConverter?, session: EbookReaderSession?) {
    if (session == null || settings.readingMode == com.eyue.reader.data.ReadingMode.SCROLL) return
    for (index in listOf(center + 1, center - 1).filter { it in prepared && it in chapters.indices }) key(index) {
        val controller = remember(store, index) { EbookReaderController() }
        EbookReaderSurface(store, index, chapters[index], text[index], settings, annotations, controller,
            Modifier.fillMaxSize().alpha(0f).zIndex(-1f).clearAndSetSemantics {}, dualPage, eink,
            foreground, selectionMenuEnabled = false, chineseConverter = converter, session = session,
            preload = true, retentionCenter = center,
            onViewport = {}, onTap = {}, onManualStart = {}, onManualEnd = {}, onChapterStep = {},
            onSelectionActive = {}, onAction = { _, _ -> }, onAnnotationTap = { _, _ -> }, onFootnote = { _, _ -> })
    }
}

@Composable
internal fun rememberEbookConfig(settings: ReaderSettings, foreground: Color, dualPage: Boolean = false, einkMode: Boolean = false): JSONObject {
    val context = LocalContext.current
    val density = LocalDensity.current
    val calibration = remember(settings.fontName, settings.fontPath, settings.bold) { readerFontCalibration(context, settings) }
    val fontScale = density.fontScale
    val palette = readerPalette(settings.background, settings.nightMode, MaterialTheme.colorScheme, einkMode)
    val paper = palette.background
    val fontUrl = remember(settings.fontName, settings.fontPath) { ebookReaderFontUrl(settings) }
    val config = remember(settings, foreground, dualPage, einkMode, fontScale, calibration, fontUrl, paper) {
        JSONObject().put("mode", settings.readingMode.name).put("turn", settings.pageTurnStyle.name)
            .put("authorCss", settings.useSourceCss).put("dual", dualPage).put("eink", einkMode)
            .put("vertical", settings.verticalText)
            .put("imageCrop", settings.pdfCrop.normalized().let { crop -> JSONObject().put("enabled", crop.enabled)
                .put("left", crop.left).put("right", crop.right).put("top", crop.top).put("bottom", crop.bottom) })
            .put("fontSize", settings.fontSize * calibration.sizeScale * fontScale)
            .put("lineHeight", settings.fontSize * settings.lineHeight * fontScale)
            .put("letterSpacing", (settings.letterSpacing + settings.fontSize * calibration.trackingEm) * fontScale)
            .put("paragraphGap", settings.fontSize * settings.paragraphSpacing * fontScale)
            .put("indent", settings.paragraphIndent)
            .put("bold", settings.bold).put("night", settings.nightMode)
            .put("color", "#%06x".format(foreground.toArgb() and 0xffffff))
            .put("overrideInk", palette.overridePublisherColors).put("paper", "#%06x".format(paper.toArgb() and 0xffffff))
            .put("paperImage", settings.backgroundImagePath?.takeIf { settings.background == ReaderBackground.IMAGE && !einkMode }
                ?.let { "https://eread.local/reader-background?v=${it.hashCode()}" })
            .put("left", settings.marginLeft).put("right", settings.marginRight)
            .put("top", settings.marginTop).put("bottom", settings.marginBottom)
            .put("font", if (settings.fontPath != null || readerBundledFontResource(settings) != null) "eread-selected"
                else if (settings.fontName == "等宽字体") "monospace" else "sans-serif")
            .put("fontUrl", fontUrl)
    }
    return config
}

internal fun ebookReaderFontUrl(settings: ReaderSettings): String {
    val version = settings.fontPath?.let { path -> File(path).let { "${path.hashCode()}-${it.length()}-${it.lastModified()}" } }
        ?: settings.fontName.hashCode().toString()
    return "https://eread.local/reader-font?v=$version"
}

/** A delayed request must read the font identified by its URL, even after another selection. */
@Composable
internal fun rememberEbookFontOpener(settings: ReaderSettings): (String) -> java.io.InputStream? {
    val context = LocalContext.current
    val selections = remember { linkedMapOf<String, ReaderSettings>() }
    val url = remember(settings.fontName, settings.fontPath) { ebookReaderFontUrl(settings) }
    SideEffect { synchronized(selections) {
        selections.remove(url); selections[url] = settings
        while (selections.size > 3) selections.remove(selections.keys.first())
    } }
    return remember(context) { { requested ->
        val selected = synchronized(selections) { selections[requested] }
        selected?.let { chosen -> runCatching {
            chosen.fontPath?.let { File(it).inputStream() }
                ?: readerBundledFontResource(chosen)?.let { context.resources.openRawResource(it) }
        }.getOrNull() }
    } }
}

/** Keep Android selection handles, but never display its competing copy/share menu. */
internal class EbookWebView(context: Context) : EbookPageTurnWebView(context) {
    var disposed = false
    var documentGeneration: String? = null
    var loadedKey: String? = null
    var pageReady = false
    var appliedConfig: String? = null
    var envelope: String = ""
    var viewport = EbookViewportState()
    var onBridgeEvent: ((String) -> Unit)? = null
    var convertText: ((String) -> String)? = null
    init {
        addJavascriptInterface(object {
            @JavascriptInterface fun convert(text: String): String = convertText?.invoke(text) ?: JSONObject().put("text", text).toString()
            @JavascriptInterface fun event(json: String) {
                val callback = onBridgeEvent
                post { if (callback === onBridgeEvent) callback?.invoke(json) }
            }
        }, "__ERead")
    }
    private fun selection(callback: ActionMode.Callback) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { callback.onCreateActionMode(mode, menu); menu.clear(); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { menu.clear(); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(selection(callback))
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(selection(callback), type)
}
