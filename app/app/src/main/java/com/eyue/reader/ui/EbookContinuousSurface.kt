package com.eyue.reader.ui

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.eyue.reader.data.*
import com.eyue.reader.data.Annotation
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One scrolling document; each of at most three chapters keeps its own HTML/CSS browsing context. */
@Composable
internal fun EbookContinuousSurface(
    store: EbookSourceStore, chapters: List<Chapter>, readText: suspend (Int) -> String,
    chapterIndex: Int, initialOffset: Int, initialImage: String?, settings: ReaderSettings,
    annotations: List<Annotation>, controller: EbookReaderController, modifier: Modifier, foreground: Color,
    eink: Boolean, selectionReset: Int, menuEnabled: Boolean, search: String, spoken: IntRange?,
    converter: ChineseTextConverter?, onViewport: (EbookViewportState) -> Unit, onTap: () -> Unit,
    onManualStart: () -> Unit, onManualEnd: () -> Unit, onSelectionActive: (Boolean) -> Unit,
    onAction: (ReaderSelectedPassage, AnnotationKind) -> Unit, onEdit: ((ReaderSelectedPassage) -> Unit)?,
    onRole: ((ReaderSelectedPassage) -> Unit)?, onAnnotation: (List<String>, Rect) -> Unit,
    onFootnote: (SourceFootnote, Rect) -> Unit, session: EbookReaderSession?, onPageTap: ((Float, Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val localSession = remember { if (session == null) EbookReaderSession(context) else null }
    val pool = session ?: requireNotNull(localSession)
    val config = rememberEbookConfig(settings, foreground, einkMode = eink)
    val openFont = rememberEbookFontOpener(settings)
    val latestConfig by rememberUpdatedState(config)
    val latestConverter by rememberUpdatedState(converter)
    val latestRead by rememberUpdatedState(readText)
    val latestAnnotations by rememberUpdatedState(annotations)
    val latestChapter by rememberUpdatedState(chapterIndex)
    val latestSearch by rememberUpdatedState(search)
    val latestSpoken by rememberUpdatedState(spoken)
    val latestViewport by rememberUpdatedState(onViewport)
    val latestTap by rememberUpdatedState(onTap)
    val latestPageTap by rememberUpdatedState(onPageTap)
    val latestManualStart by rememberUpdatedState(onManualStart)
    val latestManualEnd by rememberUpdatedState(onManualEnd)
    val latestSelection by rememberUpdatedState(onSelectionActive)
    val latestAnnotation by rememberUpdatedState(onAnnotation)
    val latestFootnote by rememberUpdatedState(onFootnote)
    val latestMenu by rememberUpdatedState(menuEnabled)
    val key = store.continuousKey
    val generation = remember(store, key) { UUID.randomUUID().toString() }
    val documents = remember(store, key) { mutableMapOf<Int, Pair<EbookSourceDocument, Chapter>>() }
    val html = remember(store, key) { ConcurrentHashMap<String, String>() }
    val jobs = remember(store, key) { mutableMapOf<Int, Pair<Int, Job>>() }
    val urls = remember(store, key) { mutableMapOf<Int, String>() }
    var browser by remember(key) { mutableStateOf<EbookWebView?>(null) }
    var ready by remember(store, key) { mutableStateOf(false) }
    var error by remember(store, key) { mutableStateOf<String?>(null) }
    var selected by remember(store, key) { mutableStateOf<ReaderSelectedPassage?>(null) }
    var loadingVisible by remember(key) { mutableStateOf(false) }
    val chapterScript = remember { context.assets.open("reader/ebook-reader.js").bufferedReader().use { it.readText() } }
    val windowScript = remember { context.assets.open("reader/ebook-continuous.js").bufferedReader().use { it.readText() } }
    fun invoke(command: JSONObject) { browser?.evaluateJavascript("window.ERead && ERead.command($command)", null) }
    fun decorations(index: Int) = JSONObject().put("annotations", JSONArray(latestAnnotations.filter { it.chapterIndex == index }.map {
        JSONObject().put("id", it.id).put("start", it.characterOffset).put("end", it.endOffset).put("kind", it.kind.name)
            .put("color", it.color).put("image", it.imagePath).put("underline", it.underlineStyle.name)
    })).put("search", latestSearch).put("spokenStart", latestSpoken?.takeIf { index == latestChapter }?.first ?: -1)
        .put("spokenEnd", latestSpoken?.takeIf { index == latestChapter }?.let { it.last + 1 } ?: -1)
    fun notes(chapter: Chapter) = JSONArray(chapter.sourceFootnotes.map {
        JSONObject().put("start", it.start).put("end", it.end).put("label", it.label).put("content", it.content)
    })
    fun request(view: EbookWebView, index: Int, token: Int) {
        if (index !in chapters.indices || jobs[index]?.let { it.first == token && it.second.isActive } == true) return
        jobs.remove(index)?.second?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val text = latestRead(index)
                val source = store.load(index, text)
                val chapter = withContext(Dispatchers.IO) {
                    val work = currentCoroutineContext()
                    readerEbookChapterWithFootnotes(DeferredEbookContent.ensure(chapters[index]) { work.ensureActive() }, text) { work.ensureActive() }
                }
                ensureActive()
                if (browser !== view || jobs[index]?.first != token) return@launch
                documents[index] = source to chapter
                val options = JSONObject().put("canonical", text).put("config", JSONObject(latestConfig.toString()).put("continuous", true))
                    .put("noteTargets", JSONArray(source.noteTargets.keys.toList())).put("notes", notes(chapter))
                    .put("decorations", decorations(index)).put("images", JSONArray(chapter.inlineImages.map {
                        JSONObject().put("offset", it.offset).put("path", it.path)
                    }))
                val url = source.baseUrl + "?eread-window=$generation&chapter=$index&token=$token"
                urls.put(index, url)?.let(html::remove)
                html[url] = withContext(Dispatchers.Default) { ebookReaderHtml(source, chapterScript, generation, options) }
                view.evaluateJavascript("EReadContinuous.provide($index,$token,${JSONObject.quote(url)})", null)
                invoke(JSONObject().put("type", "decorateChapter").put("chapter", index).put("decorations", decorations(index)))
                invoke(JSONObject().put("type", "notesChapter").put("chapter", index).put("notes", notes(chapter)))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                view.evaluateJavascript("EReadContinuous.event($index,{type:'error',message:${JSONObject.quote(failure.message ?: "章节读取失败")}})", null)
            }
        }
        jobs[index] = token to job; job.start()
    }
    fun attach(view: EbookWebView) {
        browser = view
        view.documentGeneration = generation
        val reuse = view.loadedKey == key && view.pageReady
        val initial = controller.initialNavigation(chapterIndex) ?: JSONObject().put("type", "navigate")
            .put("chapter", chapterIndex).put("offset", initialOffset).put("image", initialImage)
        val image = initial.optString("image").takeIf(String::isNotEmpty)
        val offset = initial.optInt("offset")
        val preserve = reuse && view.appliedConfig == latestConfig.toString() && view.viewport.chapterIndex == chapterIndex && when {
            initial.optBoolean("lastPage") -> view.viewport.atEnd
            image != null -> image in view.viewport.visibleImagePaths
            else -> offset >= view.viewport.offset && (offset < view.viewport.endOffset ||
                offset == view.viewport.endOffset && (view.viewport.atEnd || view.viewport.endOffset == view.viewport.offset))
        }
        val options = JSONObject().put("config", latestConfig).put("initialNavigation", initial)
            .put("preserveViewport", preserve)
            .put("chapters", JSONArray(chapters.map { JSONObject().put("title", it.title).put("length", it.characterCount.coerceAtLeast(0)) }))
        controller.bind(chapterIndex, continuous = true) { invoke(it) }
        view.alpha = if (preserve) 1f else 0f
        fun fail(message: String) {
            if (browser === view && view.documentGeneration == generation) {
                error = message; view.pageReady = false; latestViewport(EbookViewportState(error = message))
            }
        }
        val source = EbookSourceDocument(key, "<html><head></head><body></body></html>", store.continuousUrl, "")
        val envelope = if (reuse) view.envelope else ebookReaderHtml(source, windowScript, generation, options, allowReaderFrames = true)
            .also { view.envelope = it }
        view.webViewClient = EbookReaderWebViewClient(store.continuousUrl, envelope, store::openResource,
            openFont = openFont,
            onDocumentLoaded = {}, onFailure = ::fail, openChapterDocument = html::get)
        view.convertText = { text -> latestConverter?.convert(text).let { converted -> JSONObject().put("text", converted?.text ?: text)
            .put("forward", converted?.forward?.let { JSONArray(it.toList()) }).toString() } }
        view.onBridgeEvent = event@ { json ->
            if (browser !== view || view.documentGeneration != generation || error != null) return@event
            val event = runCatching { JSONObject(json) }.getOrNull() ?: return@event
            val index = event.optInt("chapter", latestChapter)
            if (index !in chapters.indices) return@event
            fun anchor(): Rect {
                val origin = IntArray(2).also(view::getLocationInWindow)
                val scale = view.resources.displayMetrics.density
                return Rect(origin[0] + event.optDouble("left").toFloat() * scale, origin[1] + event.optDouble("top").toFloat() * scale,
                    origin[0] + event.optDouble("right").toFloat() * scale, origin[1] + event.optDouble("bottom").toFloat() * scale)
            }
            when (event.optString("type")) {
                "chapterRequest" -> request(view, index, event.getInt("token"))
                "chapterDiscard" -> {
                    jobs.remove(index)?.second?.cancel(); documents.remove(index); urls.remove(index)?.let(html::remove)
                }
                "ready" -> { ready = true; view.loadedKey = key; view.pageReady = true; controller.loaded(initial) }
                "error" -> fail(event.optString("message", "原书排版失败"))
                "viewport" -> {
                    if (!ready) return@event
                    val state = EbookViewportState(ready = true, chapterIndex = index, offset = event.optInt("offset"), endOffset = event.optInt("endOffset"),
                        pageIndex = event.optInt("page"), pageCount = event.optInt("pages", 1), scrolling = event.optBoolean("scrolling"),
                        atEnd = event.optBoolean("atEnd"), visibleImagePaths = event.optJSONArray("images")?.let { rows -> List(rows.length()) { rows.getString(it) } }.orEmpty())
                    store.retainWindow(index); view.viewport = state; latestViewport(state)
                    if (view.alpha == 0f) view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { if (browser === view && view.documentGeneration == generation) view.alpha = 1f }
                    })
                }
                "tap" -> latestPageTap?.invoke(event.optDouble("x", .5).toFloat(), event.optDouble("y", .5).toFloat()) ?: latestTap()
                "manualStart" -> { selected = null; latestManualStart() }
                "manualEnd" -> latestManualEnd()
                "selection" -> documents[index]?.first?.canonicalText?.let { text ->
                    val start = event.optInt("start").coerceIn(0, text.length)
                    val end = event.optInt("end").coerceIn(start, text.length)
                    val image = event.optString("image").takeIf(String::isNotEmpty)
                    selected = if (latestMenu && (end > start || image != null)) ReaderSelectedPassage(index, event.optInt("page"), start, end,
                        text.substring(start, end), imagePath = image, anchor = anchor()) else null
                    latestSelection(selected != null)
                }
                "annotation" -> latestAnnotation(event.getJSONArray("ids").let { rows -> List(rows.length()) { rows.getString(it) } }, anchor())
                "footnote" -> documents[index]?.let { (source, chapter) ->
                    val start = event.optInt("offset").coerceIn(0, source.canonicalText.length)
                    val content = source.noteTargets[event.optString("target")]?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
                        ?: event.optString("content")
                    val note = chapter.sourceFootnotes.firstOrNull { start in it.start until it.end }
                        ?: content.takeIf(String::isNotBlank)?.let { SourceFootnote(start, start + event.optString("label").length, event.optString("label"), it) }
                    if (note != null) latestFootnote(note, anchor())
                }
            }
        }
        view.appliedConfig = latestConfig.toString()
        if (reuse) invoke(JSONObject().put("type", "resume").put("options", options)) else view.loadUrl(store.continuousUrl)
    }
    DisposableEffect(store, key) { onDispose {
        jobs.values.forEach { it.second.cancel() }; controller.unbind(); latestSelection(false); localSession?.close()
    } }
    LaunchedEffect(selectionReset, menuEnabled) { selected = null; controller.clearSelection(); latestSelection(false) }
    LaunchedEffect(key, ready, error) {
        loadingVisible = false
        if (!ready && error == null) { delay(180); loadingVisible = true }
    }
    LaunchedEffect(config.toString(), ready) { if (ready) {
        browser?.appliedConfig = config.toString(); invoke(JSONObject().put("type", "configure").put("config", config)); controller.consumePending()
    } }
    LaunchedEffect(converter, ready) { if (ready) {
        invoke(JSONObject().put("type", "convert")); controller.consumePending()
    } }
    LaunchedEffect(annotations, search, spoken, chapterIndex, ready) { if (ready) documents.keys.toList().forEach { index ->
        invoke(JSONObject().put("type", "decorateChapter").put("chapter", index).put("decorations", decorations(index)))
    } }
    LaunchedEffect(store, ready, lifecycleOwner) { lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (!ready) { delay(60_000); error = "准备连贯阅读超时，请重试"; latestViewport(EbookViewportState(error = error)) }
    } }
    Box(modifier) {
        key(key) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                pool.acquire(key, 0).also { view ->
                    view.setBackgroundColor(AndroidColor.TRANSPARENT); view.overScrollMode = WebView.OVER_SCROLL_NEVER
                    view.isVerticalScrollBarEnabled = false; view.isHorizontalScrollBarEnabled = false
                    view.settings.apply {
                        javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false; blockNetworkLoads = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        textZoom = 100; mediaPlaybackRequiresUserGesture = true; setSupportMultipleWindows(false)
                    }
                    attach(view)
                }
            }, onRelease = { view -> pool.release(view); if (browser === view) browser = null })
        }
        if (loadingVisible && !ready && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    val clipboard = LocalClipboardManager.current
    selected?.takeIf { menuEnabled }?.let { passage ->
        ReaderAnnotationPopup(passage.anchor ?: Rect.Zero, onDismiss = { selected = null }) {
            fun action(kind: AnnotationKind) { selected = null; controller.clearSelection(); onAction(passage, kind) }
            ReaderCompactActionMenu(listOf(
                ReaderCompactAction("note", "笔记") { action(AnnotationKind.NOTE) },
                ReaderCompactAction("highlight", "标记") { action(AnnotationKind.HIGHLIGHT) },
                ReaderCompactAction("underline", "划线") { action(AnnotationKind.UNDERLINE) }
            ) + listOfNotNull(onEdit?.takeIf { passage.imagePath == null }?.let { callback -> ReaderCompactAction("edit", "编辑") {
                selected = null; controller.clearSelection(); callback(passage)
            } }, onRole?.let { callback -> ReaderCompactAction("role", "角色") {
                selected = null; controller.clearSelection(); callback(passage)
            } }) + ReaderCompactAction("copy", "复制") {
                clipboard.setText(AnnotatedString(passage.quote)); selected = null; controller.clearSelection()
            })
        }
    }
}
