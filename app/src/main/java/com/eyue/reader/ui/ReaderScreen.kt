package com.eyue.reader.ui

import android.app.Activity
import android.content.*
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.UnderlineStyle
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.ChineseTextConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ReaderTextPage(val start: Int, val end: Int, val imageIndex: Int = -1)
internal data class ReaderDocument(val text: List<String>, val pageText: List<List<String>>, val errors: List<String>,
    val sourceStyles: List<ReaderSourceStyles?> = emptyList(), val scrollBlocks: List<List<ReaderTextPage>> = emptyList(),
    val scrollIndex: ReaderScrollIndex? = null, val chapters: List<Chapter> = emptyList(),
    val readyChapters: Set<Int> = emptySet(), val readText: (suspend (Int) -> String)? = null)
internal data class ReaderSearchResult(val chapter: Int, val page: Int, val offset: Int, val snippet: String)
internal data class ReaderSearchDraft(val query: String, val replacement: String)
private enum class ReaderPanel { INTERFACE, SETTINGS, FONTS, SPACING, CONTENTS, SEARCH, ANNOTATIONS }
private data class ReaderReturnPosition(val chapter: Int, val page: Int, val offset: Int, val imagePath: String?,
    val imageIndex: Int?, val title: String, val progress: Float, val forward: Boolean)
private data class ReaderTextLayout(val result: TextLayoutResult, val offsets: OffsetMapping)

@OptIn(ExperimentalFoundationApi::class)
private val ReaderScrollBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ReaderScreen(
    book: Book,
    settings: ReaderSettings,
    annotations: List<Annotation>,
    onClose: () -> Unit,
    onProgress: (chapterIndex: Int, pageIndex: Int, characterOffset: Int, progress: Float, imagePath: String?) -> Unit,
    onSettings: (ReaderSettings) -> Unit,
    onAddAnnotation: (Annotation) -> Unit,
    onDeleteAnnotation: (String) -> Unit,
    onImportFont: () -> Unit,
    volumeEvents: Flow<Int>,
    onEditTxt: (chapterIndex: Int, start: Int, end: Int, replacement: String) -> Unit = { _, _, _, _ -> },
    onChapterRules: () -> Unit = {},
    onImportBackground: () -> Unit = {},
    onSpeechSettings: () -> Unit = {},
    onGenerateSpeech: ((Set<Int>) -> Unit)? = null,
    speechGenerationBusy: Boolean = false,
    speechCacheConfig: AiSpeechConfig? = null,
    speechCacheRevision: Int = 0,
    onResetDefaults: () -> Unit = {},
    onReplaceMatch: ((ReaderSearchResult, String, String) -> Unit)? = null,
    onReplaceAll: ((String, String) -> Unit)? = null,
    textEditBusy: Boolean = false,
    resumeSearch: ReaderSearchDraft? = null,
    onSearchClosed: () -> Unit = {},
    speechGeneration: com.eyue.reader.data.AiSpeechBatchProgress? = null,
    onRoleSelection: ((String) -> Unit)? = null,
    onCancelSpeech: (() -> Unit)? = null,
    commonVoiceRoles: List<com.eyue.reader.data.BookVoiceRole>? = null,
    einkMode: Boolean = false,
    tabletDualPage: Boolean = false
) {
    val context = LocalContext.current
    val chineseDisplay by produceState<Pair<ChineseConversion, ChineseTextConverter?>?>(null, settings.chineseConversion) {
        value = settings.chineseConversion to ChineseTextConverter.load(context, settings.chineseConversion)
    }
    val chineseConverter = chineseDisplay?.takeIf { it.first == settings.chineseConversion }?.second
    val conversionReady = settings.chineseConversion == ChineseConversion.ORIGINAL || chineseConverter != null
    val importedBook = book
    var document by remember(book.id) { mutableStateOf<ReaderDocument?>(null) }
    val book = importedBook.copy(chapters = document?.chapters?.takeIf { it.size == importedBook.chapters.size } ?: importedBook.chapters)
    val chapterStore = remember(importedBook.id, importedBook.chapters) { ReaderChapterStore(context, importedBook) }
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    val density = LocalDensity.current
    val dualPage = readerUseDualPage(tabletDualPage, settings.readingMode, LocalWindowInfo.current.containerSize, density)
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val pointerConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var readerForeground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snapshotFlow { snackbar.currentSnackbarData }.filterNotNull().collectLatest { message ->
            delay(1000)
            message.dismiss()
        }
    }
    val speechFactory = LocalReaderSpeechFactory.current
    val speech = remember(book.id, speechFactory) { speechFactory(context) }
    val mediaAudio = remember(book.id) { ReaderAudioSession(context, book.title) }
    val speaking by speech.playing.collectAsState()
    val speechError by speech.error.collectAsState()
    var speechResumePending by remember(book.id) { mutableStateOf(false) }
    var manualDrag by remember(book.id) { mutableStateOf(false) }
    var manualNavigationVersion by remember(book.id) { mutableIntStateOf(0) }
    var speechNavigationVersion by remember(book.id) { mutableIntStateOf(0) }
    var speechMoveCount by remember(book.id) { mutableIntStateOf(0) }
    var pendingSpeechLocation by remember(book.id) { mutableStateOf<Pair<ReaderSpeechSegment, Int>?>(null) }
    var speechOwnsPosition by remember(book.id) { mutableStateOf(false) }
    var speechSessionActive by remember(book.id) { mutableStateOf(false) }
    var restartSpeechForEngine by remember(book.id) { mutableStateOf(false) }
    var spokenLocation by remember(book.id) { mutableStateOf<Pair<ReaderSpeechSegment, Int>?>(null) }
    var followPagePrompt by remember(book.id) { mutableStateOf(false) }
    val latestNightMode by rememberUpdatedState(settings.nightMode)
    val latestTtsSpeed by rememberUpdatedState(settings.ttsSpeed)
    val latestTtsFollowPage by rememberUpdatedState(settings.ttsFollowPage)
    val raster = book.format == BookFormat.IMAGES || book.format == BookFormat.PDF ||
        book.mode == LibraryMode.COMIC && book.format in setOf(BookFormat.EPUB, BookFormat.MOBI)
    val directScroll = !raster && settings.readingMode == ReadingMode.SCROLL
    val editable = !raster && !book.cloudOnly && book.mode == LibraryMode.NOVEL &&
        book.format in setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI)
    var chapterIndex by remember(book.id) { mutableIntStateOf(book.chapterIndex.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))) }
    var pageIndex by remember(book.id) { mutableIntStateOf(book.pageIndex.coerceAtLeast(0)) }
    var characterOffset by remember(book.id) { mutableIntStateOf(book.characterOffset.coerceAtLeast(0)) }
    var positionImagePath by remember(book.id) { mutableStateOf(book.positionImagePath) }
    var initialPositionPending by remember(book.id) { mutableStateOf(true) }
    var restoreRequest by remember(book.id) { mutableIntStateOf(0) }
    var restoring by remember(book.id) { mutableStateOf(true) }
    var chromeVisible by remember(book.id) { mutableStateOf(false) }
    var progressDraft by remember(book.id) { mutableStateOf<Float?>(null) }
    var returnPosition by remember(book.id) { mutableStateOf<ReaderReturnPosition?>(null) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    var speechBarHeight by remember { mutableIntStateOf(0) }
    var panel by remember(book.id) { mutableStateOf<ReaderPanel?>(if (resumeSearch != null) ReaderPanel.SEARCH else null) }
    var selectedPassage by remember(book.id) { mutableStateOf<ReaderSelectedPassage?>(null) }
    var txtSelection by remember(book.id) { mutableStateOf<ReaderSelectedPassage?>(null) }
    var tappedNoteIds by remember(book.id) { mutableStateOf<List<String>>(emptyList()) }
    var activeTextSelections by remember(book.id) { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    var annotationAnchor by remember(book.id) { mutableStateOf(Rect.Zero) }
    var pointerAnchor by remember(book.id) { mutableStateOf<Rect?>(null) }
    var bodyCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var noteTapHandled by remember(book.id) { mutableStateOf(false) }
    var selectionReset by remember(book.id) { mutableIntStateOf(0) }
    var editorKind by remember { mutableStateOf(AnnotationKind.NOTE) }
    var noteDraft by remember { mutableStateOf("") }
    var markColor by remember { mutableStateOf(ReaderMarkColors.first().second) }
    var underlineStyle by remember { mutableStateOf(UnderlineStyle.STRAIGHT) }
    var searchQuery by remember(book.id) { mutableStateOf(resumeSearch?.query.orEmpty()) }
    var highlightQuery by remember(book.id) { mutableStateOf("") }
    var annotationQuery by remember(book.id) { mutableStateOf("") }
    var annotationKind by remember(book.id) { mutableStateOf<AnnotationKind?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pageViewport = readerPageViewport(viewport, dualPage, density)
    var textPages by remember(book.id) { mutableStateOf(List(book.chapters.size) { emptyList<ReaderTextPage>() }) }
    var paginatedChapters by remember(book.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var layoutGeneration by remember(book.id) { mutableIntStateOf(0) }
    var lastPagePending by remember(book.id) { mutableStateOf(false) }
    var layoutImageHint by remember(book.id) { mutableStateOf<Int?>(null) }
    var speechPreparation by remember(book.id) { mutableStateOf<Job?>(null) }
    var speechTexts by remember(book.id) { mutableStateOf<List<String>>(emptyList()) }
    var speechSpeedDraft by remember(settings.ttsSpeed) { mutableFloatStateOf(settings.ttsSpeed) }
    var reflowing by remember(book.id) { mutableStateOf(false) }
    var paginationProgress by remember(book.id) { mutableFloatStateOf(0f) }
    var paginationError by remember(book.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val textLayouts = remember(book.id, settings.fontSize, settings.fontPath, settings.fontName,
        settings.bold, settings.lineHeight, settings.letterSpacing, settings.paragraphSpacing, chineseConverter, pageViewport, settings.readingMode,
        density, layoutDirection) { linkedMapOf<Pair<Int, Int>, ReaderTextLayout>() }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val layoutSpec = remember(settings.fontPath, settings.fontName, settings.fontSize, settings.bold,
        settings.lineHeight, settings.letterSpacing, density, layoutDirection, pageViewport) {
        readerLayoutSpec(settings, density, layoutDirection, pageViewport)
    }
    val colors = MaterialTheme.colorScheme
    val background = if (einkMode) { if (settings.nightMode) Color.Black else Color.White }
        else if (settings.nightMode) colors.background else when (settings.background) {
        ReaderBackground.WHITE -> Color.White
        ReaderBackground.GREEN -> Color(0xFFE5EBD9)
        else -> colors.background
    }
    val backgroundPath = settings.backgroundImagePath.takeIf { settings.background == ReaderBackground.IMAGE && !einkMode }
    val backgroundImage by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, backgroundPath) {
        value = withContext(Dispatchers.IO) { backgroundPath?.let { path ->
            runCatching { BitmapLoader.decodeBounded(File(path), 1440, 2880, 2_000_000)?.asImageBitmap() }.getOrNull()
        } }
    }
    val foreground = if (einkMode) { if (settings.nightMode) Color.White else Color.Black } else colors.onBackground
    val muted = foreground.copy(alpha = if (einkMode) 1f else 0.56f)
    val textStyle = layoutSpec.textStyle.copy(color = foreground)
    val textWidth = layoutSpec.textWidth
    val textHeight = layoutSpec.textHeight
    val chapter = book.chapters.getOrNull(chapterIndex)
    val chapterTitle = chapter?.let { readerChapterTitle(it, pageIndex) }.orEmpty()
    val currentText = document?.text?.getOrNull(chapterIndex).orEmpty()
    val currentPages = textPages.getOrNull(chapterIndex).orEmpty()
    val pageCounts = if (raster) book.chapters.map { it.pages.size } else textPages.map { if (directScroll) it.size.coerceAtLeast(1) else it.size }
    val allPagesReady = raster || directScroll || paginatedChapters.size == book.chapters.size
    val chapterStarts = remember(pageCounts) { pageCounts.runningFold(0, Int::plus).dropLast(1) }
    val chapterPageCount = pageCounts.getOrNull(chapterIndex) ?: 0
    val totalPages = pageCounts.sum()
    val visiblePageEnd = readerVisiblePageEnd(pageIndex, chapterPageCount, dualPage)
    val globalPage = pageCounts.take(chapterIndex).sum() + visiblePageEnd
    val scrollPageProgress = if (directScroll) rememberReaderPageProgress(importedBook, settings, layoutSpec,
        chapterIndex, characterOffset, currentPages.getOrNull(pageIndex)?.imageIndex?.takeIf { it >= 0 }) else null
    val atScrollEnd by remember(directScroll, totalPages, listState) { derivedStateOf {
        directScroll && document?.readyChapters?.contains(book.chapters.lastIndex) == true && totalPages > 0 && listState.layoutInfo.totalItemsCount == totalPages &&
            listState.layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward
    } }
    val progress = if (atScrollEnd) 1f else if (directScroll || !allPagesReady) document?.scrollIndex?.progress(chapterIndex, characterOffset,
        currentPages.getOrNull(pageIndex)?.imageIndex?.takeIf { it >= 0 }) ?: book.progress
        else if (totalPages > 0) (globalPage.toFloat() / totalPages).coerceIn(0f, 1f) else book.progress
    val currentPageRange = currentPages.getOrNull(pageIndex)
    val currentBookmarks = annotations.filter { item ->
        item.bookId == book.id && item.chapterIndex == chapterIndex && item.kind == AnnotationKind.BOOKMARK &&
            if (raster) item.pageIndex == pageIndex else currentPageRange?.let {
                if (it.imageIndex >= 0) book.chapters[chapterIndex].inlineImages.getOrNull(it.imageIndex)?.path?.let { path ->
                    item.imagePath == path && item.characterOffset == it.start &&
                        (book.chapters[chapterIndex].inlineImages.count { image -> image.offset == it.start && image.path == path } <= 1 || item.pageIndex == pageIndex)
                } == true
                else item.imagePath == null && item.characterOffset >= it.start && (item.characterOffset < it.end ||
                    (pageIndex == currentPages.lastIndex && item.characterOffset == it.end))
            } == true
    }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val halfScreen = with(density) { windowHeight.toDp() / 2f }

    fun publishChapter(index: Int, loaded: ReaderLoadedChapter) {
        val current = document ?: return
        document = current.copy(text = current.text.toMutableList().apply { this[index] = loaded.text },
            sourceStyles = current.sourceStyles.toMutableList().apply { this[index] = loaded.styles },
            scrollBlocks = current.scrollBlocks.toMutableList().apply { this[index] = loaded.blocks },
            chapters = current.chapters.toMutableList().apply { this[index] = loaded.chapter },
            readyChapters = current.readyChapters + index)
    }

    fun pauseSpeech() { speechPreparation?.cancel(); speechPreparation = null; speechResumePending = false; pendingSpeechLocation = null; speechOwnsPosition = false; if (!speech.pause()) speech.stop(); mediaAudio.pause() }
    fun stopSpeech() { pauseSpeech(); speech.stop(); mediaAudio.stop(); speechTexts = emptyList(); speechSessionActive = false; spokenLocation = null; followPagePrompt = false }
    fun dismissAnnotationBubble() {
        if (selectedPassage != null || tappedNoteIds.isNotEmpty() || activeTextSelections.isNotEmpty()) {
            selectedPassage = null; tappedNoteIds = emptyList(); activeTextSelections = emptySet(); selectionReset++
        }
    }
    fun textSelectionChanged(sourceChapter: Int, sourcePage: Int, active: Boolean) {
        val key = sourceChapter to sourcePage
        activeTextSelections = if (active) activeTextSelections + key else activeTextSelections - key
    }
    fun pauseSpeechForNavigation() {
        dismissAnnotationBubble()
        manualNavigationVersion++
        pendingSpeechLocation = null
        speechOwnsPosition = false
        if (speechSessionActive && !latestTtsFollowPage) { followPagePrompt = true; return }
        if (speech.playing.value || speechPreparation?.isActive == true || mediaAudio.pending != null) {
            speechResumePending = true
            speechPreparation?.cancel(); speechPreparation = null
            if (!speech.pause()) speech.stop()
            mediaAudio.pause()
        }
    }

    fun locatePage(chapterNumber: Int, offset: Int, imagePath: String? = null, imageIndexHint: Int? = null, pageHint: Int = 0): Int {
        val pages = textPages.getOrNull(chapterNumber).orEmpty()
        if (imagePath != null) {
            val matches = pages.indices.filter { index -> pages[index].imageIndex >= 0 &&
                book.chapters.getOrNull(chapterNumber)?.inlineImages?.getOrNull(pages[index].imageIndex)?.path == imagePath }
            val atOffset = matches.filter { pages[it].start == offset }
            atOffset.firstOrNull { pages[it].imageIndex == imageIndexHint }?.let { return it }
            atOffset.firstOrNull { it == pageHint }?.let { return it }
            (atOffset.firstOrNull() ?: matches.firstOrNull())?.let { return it }
        }
        return pages.indexOfLast { it.imageIndex < 0 && it.start <= offset }.coerceAtLeast(0)
    }

    fun jump(chapterNumber: Int, pageNumber: Int = 0, offset: Int = 0, imagePath: String? = null, imageIndexHint: Int? = null,
             fromSpeech: Boolean = false) {
        if (!fromSpeech) pauseSpeechForNavigation()
        lastPagePending = false
        initialPositionPending = false
        restoring = true
        chapterIndex = chapterNumber.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        if (!raster && (document?.readyChapters?.contains(chapterIndex) != true || !directScroll && chapterIndex !in paginatedChapters))
            reflowing = true
        characterOffset = offset.coerceAtLeast(0)
        positionImagePath = imagePath
        pageIndex = if (raster) pageNumber.coerceIn(0, (pageCounts.getOrNull(chapterIndex)?.minus(1) ?: 0).coerceAtLeast(0))
            else locatePage(chapterIndex, characterOffset, imagePath, imageIndexHint, pageNumber)
        restoreRequest++
    }
    fun jumpPage(chapterNumber: Int, pageNumber: Int) {
        val page = textPages.getOrNull(chapterNumber)?.getOrNull(pageNumber)
        val imagePath = page?.imageIndex?.takeIf { it >= 0 }?.let { book.chapters[chapterNumber].inlineImages[it].path }
        jump(chapterNumber, pageNumber, page?.start ?: 0, imagePath, page?.imageIndex)
    }
    fun jumpChapter(direction: Int) {
        val target = chapterIndex + direction
        if (target in book.chapters.indices && !reflowing && !restoring) jumpPage(target, 0)
    }
    fun rememberLayout(sourceChapter: Int, sourcePage: Int, layout: TextLayoutResult) {
        val source = document?.text?.getOrNull(sourceChapter).orEmpty()
        val page = textPages.getOrNull(sourceChapter)?.getOrNull(sourcePage) ?: return
        val offsets = readerParagraphText(AnnotatedString(readerPageText(source, page)), textStyle, settings.paragraphSpacing, chineseConverter, source, page.start).offsetMapping
        textLayouts[sourceChapter to sourcePage] = ReaderTextLayout(layout, offsets)
        if (textLayouts.size > 16) textLayouts.remove(textLayouts.keys.first())
    }
    fun syncScrollPosition(index: Int, scrollOffset: Int) {
        if (totalPages == 0) return
        // Loading preceding chapters changes item counts before the list has remeasured.
        // Its visible key still identifies the chapter/block actually on screen.
        val visibleKey = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String ?: return
        val parts = visibleKey.split(':', limit = 2)
        val visibleChapter = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in book.chapters.indices } ?: return
        val visiblePage = parts.getOrNull(1)?.toIntOrNull() ?: return
        val previousPage = pageIndex
        val previousChapter = chapterIndex
        chapterIndex = visibleChapter
        pageIndex = visiblePage.coerceIn(0, (pageCounts[chapterIndex] - 1).coerceAtLeast(0))
        characterOffset = if (raster) { if (previousPage == pageIndex) characterOffset else 0 } else {
            val page = textPages.getOrNull(chapterIndex)?.getOrNull(pageIndex)
            positionImagePath = page?.imageIndex?.takeIf { it >= 0 }?.let { book.chapters[chapterIndex].inlineImages[it].path }
            val text = document?.text?.getOrNull(chapterIndex).orEmpty()
            val measured = textLayouts[chapterIndex to pageIndex] ?: page?.takeIf { it.imageIndex < 0 }?.let {
                val rendered = readerParagraphText(document?.sourceStyles?.getOrNull(chapterIndex)?.apply(text, it.start, it.end, textStyle)
                    ?: AnnotatedString(readerPageText(text, it)), textStyle, settings.paragraphSpacing, chineseConverter, text, it.start)
                ReaderTextLayout(measurer.measure(rendered.text, style = textStyle, constraints = Constraints(maxWidth = textWidth)), rendered.offsetMapping)
            }
            ((page?.start ?: 0) + (measured?.let { it.offsets.transformedToOriginal(it.result.getOffsetForPosition(Offset(0f, scrollOffset.toFloat()))) } ?: 0)).coerceAtMost(page?.end ?: 0)
        }
        if (raster) { positionImagePath = null; if (previousChapter != chapterIndex) characterOffset = 0 }
    }
    val latestSyncScrollPosition by rememberUpdatedState { syncScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    fun seekGlobal(page: Int) {
        if (totalPages == 0) return
        var remainder = page.coerceIn(0, totalPages - 1)
        for (index in pageCounts.indices) {
            if (remainder < pageCounts[index]) {
                jumpPage(index, remainder)
                return
            }
            remainder -= pageCounts[index]
        }
    }
    fun seekProgress(fraction: Float) {
        val previousImage = currentPageRange?.imageIndex?.takeIf { it >= 0 }
        fun rememberReturn(forward: Boolean) {
            returnPosition = ReaderReturnPosition(chapterIndex, pageIndex, characterOffset, positionImagePath,
                previousImage, chapterTitle, progress, forward)
        }
        if (!directScroll && allPagesReady) {
            if (totalPages == 0) return
            val target = (fraction * (totalPages - 1)).roundToInt()
            if (target == globalPage - 1) return
            rememberReturn(target < globalPage - 1)
            seekGlobal(target)
        } else {
            val target = document?.scrollIndex?.location(fraction) ?: return
            if (target.chapter == chapterIndex && target.offset == characterOffset && target.imageIndex == previousImage) return
            val forward = when {
                target.chapter != chapterIndex -> target.chapter < chapterIndex
                target.offset != characterOffset -> target.offset < characterOffset
                else -> (target.imageIndex ?: Int.MAX_VALUE) < (previousImage ?: Int.MAX_VALUE)
            }
            rememberReturn(forward)
            val imagePath = target.imageIndex?.let { book.chapters[target.chapter].inlineImages[it].path }
            jump(target.chapter, offset = target.offset, imagePath = imagePath, imageIndexHint = target.imageIndex)
        }
    }
    fun move(direction: Int) {
        if (book.chapters.isEmpty() || document == null || reflowing || restoring) return
        pauseSpeechForNavigation()
        chromeVisible = false
        if (settings.readingMode == ReadingMode.SCROLL &&
            (direction > 0 && listState.canScrollForward || direction < 0 && listState.canScrollBackward)) {
            speechMoveCount++
            scope.launch {
                try {
                    if (einkMode) listState.scrollBy(viewport.height * 0.82f * direction)
                    else listState.animateScrollBy(viewport.height * 0.82f * direction)
                }
                finally { speechMoveCount-- }
            }
            return
        }
        val adjacent = readerAdjacentPage(chapterIndex, pageIndex, pageCounts, direction, dualPage)
        when {
            adjacent != null && adjacent.chapter == chapterIndex -> jumpPage(adjacent.chapter, adjacent.page)
            adjacent != null && direction > 0 -> jumpPage(adjacent.chapter, adjacent.page)
            adjacent != null -> {
                val previous = chapterIndex - 1
                val needsLastPage = !raster && !directScroll && previous !in paginatedChapters
                jumpPage(previous, adjacent.page)
                lastPagePending = needsLastPage
            }
            else -> scope.launch { snackbar.showSnackbar(if (direction > 0) "已到全书末尾" else "已到全书开头") }
        }
    }
    fun openPanel(value: ReaderPanel) {
        dismissAnnotationBubble()
        if (value == ReaderPanel.CONTENTS) scope.launch { listState.stopScroll() }
        panel = value; chromeVisible = false
    }
    fun tapPage() {
        if (panel == ReaderPanel.INTERFACE || panel == ReaderPanel.SETTINGS) { panel = null; chromeVisible = false }
        else chromeVisible = !chromeVisible
    }
    fun toggleBookmark() {
        if (currentBookmarks.isNotEmpty()) {
            currentBookmarks.forEach { onDeleteAnnotation(it.id) }
            scope.launch { snackbar.showSnackbar("已移除本页书签") }
        } else {
            val text = if (raster) document?.pageText?.getOrNull(chapterIndex)?.getOrNull(pageIndex).orEmpty() else currentText
            val offset = characterOffset.coerceIn(0, text.length)
            onAddAnnotation(Annotation(bookId = book.id, chapterIndex = chapterIndex, pageIndex = pageIndex,
                characterOffset = characterOffset, quote = if (positionImagePath != null) "正文插图" else text.substring(offset, minOf(text.length, offset + 180)),
                kind = AnnotationKind.BOOKMARK, imagePath = positionImagePath))
            scope.launch { snackbar.showSnackbar("已添加书签") }
        }
    }
    fun openSelection(selection: ReaderSelectedPassage, kind: AnnotationKind) {
        tappedNoteIds = emptyList()
        selectedPassage = selection.copy(anchor = selection.anchor ?: pointerAnchor)
        editorKind = kind
        noteDraft = ""
        markColor = ReaderMarkColors.first().second
        underlineStyle = UnderlineStyle.STRAIGHT
        chromeVisible = false
    }
    fun openTxtEditor(selection: ReaderSelectedPassage) {
        pauseSpeech()
        selectedPassage = null
        tappedNoteIds = emptyList()
        selectionReset++
        txtSelection = selection
        panel = null
        chromeVisible = false
    }
    fun showNotes(notes: List<Annotation>, anchor: Rect?): Boolean {
        if (notes.isEmpty()) return false
        selectionReset++
        selectedPassage = null
        annotationAnchor = anchor ?: pointerAnchor ?: Rect(view.width / 2f, view.height / 2f, view.width / 2f + 1, view.height / 2f + 1)
        tappedNoteIds = notes.map { it.id }
        chromeVisible = false
        return true
    }
    fun tapText(sourceChapter: Int, offset: Int, anchor: Rect) {
        val sourceText = document?.text?.getOrNull(sourceChapter).orEmpty()
        if (showNotes(readerTextAnnotations(sourceText, annotations, book.id, sourceChapter, offset), anchor)) noteTapHandled = true
    }
    fun tapImage(sourceChapter: Int, sourcePage: Int, sourceOffset: Int = 0, imagePath: String? = null) {
        val notes = annotations.filter { item -> item.bookId == book.id && item.chapterIndex == sourceChapter &&
            item.kind != AnnotationKind.BOOKMARK && (item.kind != AnnotationKind.NOTE || item.note.isNotBlank()) &&
            if (imagePath == null) item.imagePath == null && item.pageIndex == sourcePage
            else item.imagePath == imagePath && item.characterOffset == sourceOffset }
        if (showNotes(notes, pointerAnchor)) noteTapHandled = true else tapPage()
    }
    fun imageSelection(sourceChapter: Int, sourcePage: Int) {
        val text = document?.pageText?.getOrNull(sourceChapter)?.getOrNull(sourcePage).orEmpty()
        openSelection(ReaderSelectedPassage(sourceChapter, sourcePage, 0, text.length, text.take(180), raster = true), AnnotationKind.NOTE)
    }
    fun inlineImageSelection(sourceChapter: Int, sourcePage: Int, page: ReaderTextPage) {
        val image = book.chapters[sourceChapter].inlineImages[page.imageIndex]
        openSelection(ReaderSelectedPassage(sourceChapter, sourcePage, page.start, page.end,
            "正文插图", raster = true, imagePath = image.path), AnnotationKind.NOTE)
    }
    fun followSpeech(segment: ReaderSpeechSegment, offset: Int) {
        spokenLocation = segment to offset
        if (!settings.ttsFollowPage) return
        if (speechResumePending || manualDrag || document == null) return
        if (selectedPassage != null || tappedNoteIds.isNotEmpty() || activeTextSelections.isNotEmpty()) {
            pendingSpeechLocation = segment to offset
            return
        }
        if (!readerForeground || reflowing || settings.readingMode == ReadingMode.SCROLL && restoring) {
            pendingSpeechLocation = segment to offset
            return
        }
        pendingSpeechLocation = null
        val targetChapter = segment.chapter.coerceIn(0, book.chapters.lastIndex)
        if (!raster && (targetChapter !in document!!.readyChapters || !directScroll && targetChapter !in paginatedChapters)) {
            pendingSpeechLocation = segment to offset
            chapterIndex = targetChapter
            characterOffset = offset
            restoring = true
            return
        }
        val targetText = if (raster) document?.pageText?.getOrNull(targetChapter)?.getOrNull(segment.page).orEmpty()
            else document?.text?.getOrNull(targetChapter).orEmpty()
        val targetOffset = offset.coerceIn(0, targetText.length)
        val targetPage = if (raster) segment.page else locatePage(targetChapter, targetOffset)
        characterOffset = targetOffset
        positionImagePath = null
        if (settings.readingMode != ReadingMode.SCROLL) {
            chapterIndex = targetChapter
            pageIndex = targetPage
            return
        }
        val globalIndex = (chapterStarts.getOrNull(targetChapter) ?: 0) + targetPage
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == globalIndex }
        val layout = textLayouts[targetChapter to targetPage]
        val lineVisible = if (raster) visible != null else if (visible != null && layout != null) {
            val start = textPages.getOrNull(targetChapter)?.getOrNull(targetPage)?.start ?: 0
            val local = layout.offsets.originalToTransformed((targetOffset - start).coerceIn(0,
                layout.offsets.transformedToOriginal(layout.result.layoutInput.text.length)))
            val line = layout.result.getLineForOffset(local)
            visible.offset + layout.result.getLineTop(line) >= listState.layoutInfo.viewportStartOffset &&
                visible.offset + layout.result.getLineBottom(line) <= listState.layoutInfo.viewportEndOffset -
                    speechBarHeight - if (chromeVisible) toolbarHeight else with(density) { 36.dp.roundToPx() }
        } else false
        chapterIndex = targetChapter
        pageIndex = targetPage
        if (!lineVisible) jump(targetChapter, targetPage, targetOffset, fromSpeech = true)
    }
    val latestFollowSpeech by rememberUpdatedState<(ReaderSpeechSegment, Int) -> Unit>({ segment, offset -> followSpeech(segment, offset) })
    fun spokenRange(sourceChapter: Int): IntRange? = if (speaking && spokenLocation?.first?.chapter == sourceChapter)
        readerSpeechParagraph(document?.text?.getOrNull(sourceChapter)?.takeIf { it.isNotEmpty() }
            ?: speechTexts.getOrNull(sourceChapter).orEmpty(), spokenLocation?.second ?: 0) else null
    fun pdfSpeechOffset(sourceChapter: Int, sourcePage: Int): Int? = spokenLocation?.takeIf {
        speaking && it.first.chapter == sourceChapter && it.first.page == sourcePage
    }?.second
    fun startSpeechAt(startChapter: Int, startPage: Int, startOffset: Int) {
        speechPreparation?.cancel()
        speechResumePending = false
        speechNavigationVersion = manualNavigationVersion
        speech.stop()
        pendingSpeechLocation = null
        followPagePrompt = false
        speechSessionActive = true
        val pageSnapshot = textPages
        val modeSnapshot = settings.readingMode
        speechPreparation = scope.launch {
        val texts = if (raster) emptyList() else withContext(Dispatchers.IO) {
            importedBook.chapters.map { chapter ->
                currentCoroutineContext().ensureActive()
                chapter.textPath?.let { path -> runCatching { File(path).readText() }.getOrDefault("") }.orEmpty()
            }
        }
        speechTexts = texts
        val source = mutableListOf<ReaderSpeechSegment>()
        document?.let { loaded ->
            for (chapterNumber in startChapter until book.chapters.size) {
                if (raster) loaded.pageText.getOrNull(chapterNumber).orEmpty().forEachIndexed { pageNumber, text ->
                    if (chapterNumber > startChapter || pageNumber >= startPage) {
                        val offset = if (chapterNumber == startChapter && pageNumber == startPage) startOffset.coerceIn(0, text.length) else 0
                        source += ReaderSpeechSegment(chapterNumber, pageNumber, offset, text.substring(offset),
                            roleContext = text.takeIf { speech.usesChapterSegments })
                    }
                } else if (!speech.usesChapterSegments && modeSnapshot != ReadingMode.SCROLL && pageSnapshot.getOrNull(chapterNumber)?.isNotEmpty() == true) {
                    val text = texts.getOrNull(chapterNumber).orEmpty()
                    pageSnapshot.getOrNull(chapterNumber).orEmpty().forEachIndexed { pageNumber, page ->
                        if (page.imageIndex < 0 && (chapterNumber > startChapter || pageNumber >= startPage)) {
                            val offset = if (chapterNumber == startChapter && pageNumber == startPage)
                                startOffset.coerceIn(page.start, page.end) else page.start
                            if (offset < page.end) source += ReaderSpeechSegment(chapterNumber, pageNumber, offset,
                                text.substring(offset, page.end))
                        }
                    }
                } else {
                    val text = texts.getOrNull(chapterNumber).orEmpty()
                    val offset = if (chapterNumber == startChapter) startOffset.coerceIn(0, text.length) else 0
                    source += ReaderSpeechSegment(chapterNumber, 0, offset, text.substring(offset),
                        roleContext = text.takeIf { speech.usesChapterSegments })
                }
            }
        }
        mediaAudio.play {
            speech.start(source, latestTtsSpeed) { segment, offset ->
                if (speech.playing.value) latestFollowSpeech(segment, offset)
            }
            speechOwnsPosition = speech.playing.value && latestTtsFollowPage
            mediaAudio.update(speech.playing.value, true, latestTtsSpeed)
        }
        speechPreparation = null
        }
    }
    fun startSpeech() {
        if (settings.readingMode == ReadingMode.SCROLL && !restoring && !reflowing) latestSyncScrollPosition()
        startSpeechAt(chapterIndex, pageIndex, characterOffset)
    }
    fun canResumeSpokenNode(): Boolean {
        val (segment, offset) = spokenLocation ?: return false
        if (raster) return readerPageIsVisible(segment.chapter, segment.page, chapterIndex, pageIndex, dualPage)
        val spokenPage = locatePage(segment.chapter, offset)
        if (settings.readingMode != ReadingMode.SCROLL)
            return readerPageIsVisible(segment.chapter, spokenPage, chapterIndex, pageIndex, dualPage)
        val globalIndex = (chapterStarts.getOrNull(segment.chapter) ?: return false) + spokenPage
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == globalIndex } ?: return false
        val layout = textLayouts[segment.chapter to spokenPage] ?: return false
        val start = textPages.getOrNull(segment.chapter)?.getOrNull(spokenPage)?.start ?: return false
        val local = layout.offsets.originalToTransformed((offset - start).coerceIn(0,
            layout.offsets.transformedToOriginal(layout.result.layoutInput.text.length)))
        val line = layout.result.getLineForOffset(local)
        return visible.offset + layout.result.getLineTop(line) >= listState.layoutInfo.viewportStartOffset &&
            visible.offset + layout.result.getLineBottom(line) <= listState.layoutInfo.viewportEndOffset -
                speechBarHeight - if (chromeVisible) toolbarHeight else with(density) { 36.dp.roundToPx() }
    }
    fun resumeSpeech() {
        // A user-paused queue stays paused during navigation, but sync resumes from the new page.
        if (latestTtsFollowPage && speechNavigationVersion != manualNavigationVersion) {
            if (settings.readingMode == ReadingMode.SCROLL && !restoring && !reflowing) latestSyncScrollPosition()
            if (!canResumeSpokenNode()) { startSpeech(); return }
            val (segment, offset) = requireNotNull(spokenLocation)
            val text = if (raster) document?.pageText?.getOrNull(segment.chapter)?.getOrNull(segment.page).orEmpty()
                else document?.text?.getOrNull(segment.chapter)?.takeIf { it.isNotEmpty() }
                    ?: speechTexts.getOrNull(segment.chapter).orEmpty()
            val sentenceStart = readerSpeechSentenceStart(text, offset)
            startSpeechAt(segment.chapter, if (raster) segment.page else locatePage(segment.chapter, sentenceStart), sentenceStart)
            return
        }
        mediaAudio.play {
            if (speech.resume()) { speechOwnsPosition = latestTtsFollowPage; mediaAudio.update(true, true, latestTtsSpeed) }
            else {
                val location = spokenLocation
                if (location == null) startSpeech() else startSpeechAt(location.first.chapter,
                    if (raster) location.first.page else locatePage(location.first.chapter, location.second), location.second)
            }
        }
    }
    fun stepSpeech(direction: Int) {
        val location = spokenLocation
        var targetChapter = location?.first?.chapter ?: chapterIndex
        var targetPage = location?.first?.page ?: pageIndex
        val text = if (raster) document?.pageText?.getOrNull(targetChapter)?.getOrNull(targetPage).orEmpty()
            else speechTexts.getOrNull(targetChapter) ?: document?.text?.getOrNull(targetChapter).orEmpty()
        val point = location?.second ?: characterOffset
        var targetOffset = readerSpeechStep(text, point, direction)
        if (direction > 0 && targetOffset >= text.length) {
            if (raster && targetPage + 1 < book.chapters[targetChapter].pages.size) targetPage++
            else if (targetChapter < book.chapters.lastIndex) { targetChapter++; targetPage = 0 }
            else { pauseSpeech(); return }
            targetOffset = 0
        } else if (direction < 0 && targetOffset == 0 && point <= 1 && (targetChapter > 0 || raster && targetPage > 0)) {
            if (raster && targetPage > 0) targetPage-- else { targetChapter--; targetPage = if (raster) book.chapters[targetChapter].pages.lastIndex else 0 }
            val previous = if (raster) document?.pageText?.getOrNull(targetChapter)?.getOrNull(targetPage).orEmpty()
                else speechTexts.getOrNull(targetChapter) ?: document?.text?.getOrNull(targetChapter).orEmpty()
            targetOffset = readerSpeechParagraph(previous, previous.lastIndex)?.first ?: 0
        }
        startSpeechAt(targetChapter, if (raster) targetPage else locatePage(targetChapter, targetOffset), targetOffset)
    }
    val latestStartSpeech by rememberUpdatedState<() -> Unit>({ resumeSpeech() })
    fun toggleSpeech() {
        if (speechSessionActive) { if (speech.playing.value) pauseSpeech() else resumeSpeech() } else startSpeech()
    }

    SideEffect {
        mediaAudio.onPause = ::pauseSpeech
        mediaAudio.onPlay = ::resumeSpeech
        mediaAudio.onStop = ::stopSpeech
        mediaAudio.onError = { message -> scope.launch { snackbar.showSnackbar(message) } }
        mediaAudio.update(speaking, speechSessionActive, settings.ttsSpeed)
    }
    DisposableEffect(speech, mediaAudio) { onDispose {
        restartSpeechForEngine = speechSessionActive &&
            (restartSpeechForEngine || speech.playing.value || speechPreparation?.isActive == true || mediaAudio.pending != null)
        speechPreparation?.cancel(); speechPreparation = null; speechOwnsPosition = false
        mediaAudio.pause(); speech.release()
    } }
    DisposableEffect(mediaAudio) { onDispose { mediaAudio.stop() } }
    LaunchedEffect(speech) {
        if (restartSpeechForEngine) {
            restartSpeechForEngine = false
            if (speechSessionActive) {
                val location = spokenLocation
                if (location == null) startSpeech() else startSpeechAt(location.first.chapter,
                    if (raster) location.first.page else locatePage(location.first.chapter, location.second), location.second)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            readerForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(chromeVisible) { if (!chromeVisible) { returnPosition = null; speechBarHeight = 0 } }
    LaunchedEffect(speech, settings.ttsSpeed) { speech.setSpeed(settings.ttsSpeed) }
    LaunchedEffect(settings.ttsFollowPage) {
        if (!settings.ttsFollowPage) speechResumePending = false
        speechOwnsPosition = settings.ttsFollowPage && speaking
        followPagePrompt = false
        if (settings.ttsFollowPage) {
            if (speechNavigationVersion != manualNavigationVersion) {
                speechOwnsPosition = false
                if (speaking) pauseSpeechForNavigation()
            } else spokenLocation?.let { latestFollowSpeech(it.first, it.second) }
        }
    }
    LaunchedEffect(readerForeground) {
        if (readerForeground && speaking) spokenLocation?.let { latestFollowSpeech(it.first, it.second) }
    }
    LaunchedEffect(book.id, pendingSpeechLocation, restoring, reflowing, speechResumePending, manualDrag, speaking, readerForeground,
        selectedPassage, tappedNoteIds, activeTextSelections) {
        if (readerForeground && !restoring && !reflowing && !speechResumePending && !manualDrag &&
            selectedPassage == null && tappedNoteIds.isEmpty() && activeTextSelections.isEmpty()) {
            val pending = pendingSpeechLocation
            pendingSpeechLocation = null
            if (pending != null) latestFollowSpeech(pending.first, pending.second)
        }
    }
    LaunchedEffect(book.id, listState, speech) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { manualDrag = true; pauseSpeechForNavigation() }
                is DragInteraction.Stop, is DragInteraction.Cancel -> manualDrag = false
            }
        }
    }
    LaunchedEffect(book.id, speechResumePending, manualNavigationVersion, manualDrag, scrolling, speechMoveCount,
        restoring, reflowing, progressDraft, readerForeground, settings.ttsFollowPage, followPagePrompt) {
        if (readerForeground && (speechResumePending || followPagePrompt) && !manualDrag && !scrolling &&
            speechMoveCount == 0 && !restoring && !reflowing && progressDraft == null) {
            delay(1_000)
            followPagePrompt = false
            if (speechResumePending && !manualDrag && !listState.isScrollInProgress && speechMoveCount == 0 && !restoring && !reflowing && progressDraft == null) {
                if (settings.readingMode == ReadingMode.SCROLL) latestSyncScrollPosition()
                speechResumePending = false
                latestStartSpeech()
            }
        }
    }
    LaunchedEffect(scrolling) { if (scrolling) dismissAnnotationBubble() }
    LaunchedEffect(chapterIndex, pageIndex) { dismissAnnotationBubble() }
    BackHandler(enabled = selectedPassage != null || tappedNoteIds.isNotEmpty()) { dismissAnnotationBubble() }
    BackHandler(enabled = (panel == null || panel == ReaderPanel.INTERFACE || panel == ReaderPanel.SETTINGS) && selectedPassage == null && txtSelection == null &&
        tappedNoteIds.isEmpty() && speechError == null) {
        if (panel == ReaderPanel.INTERFACE || panel == ReaderPanel.SETTINGS) panel = null
        else if (chromeVisible) chromeVisible = false else { stopSpeech(); onClose() }
    }
    DisposableEffect(view, settings.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(activity, settings.nightMode) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.isAppearanceLightStatusBars = !settings.nightMode
        controller?.isAppearanceLightNavigationBars = !settings.nightMode
        onDispose {
            controller?.isAppearanceLightStatusBars = !latestNightMode
            controller?.isAppearanceLightNavigationBars = !latestNightMode
        }
    }
    DisposableEffect(activity, settings.brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { owner -> owner.attributes = owner.attributes.apply {
            screenBrightness = if (settings.brightness < 0f) -1f else settings.brightness.coerceIn(0.01f, 1f)
        } }
        onDispose { if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
    LaunchedEffect(importedBook.id, importedBook.chapters) {
        document = null
        if (!raster) {
            val lengths = readerChapterLengths(context, importedBook)
            document = ReaderDocument(List(book.chapters.size) { "" }, emptyList(), emptyList(),
                List(book.chapters.size) { null }, List(book.chapters.size) { emptyList() },
                ReaderScrollIndex(lengths, importedBook.chapters.map { it.inlineImages }, emptyList()), importedBook.chapters,
                readText = { index -> withContext(Dispatchers.IO) { importedBook.chapters[index].textPath?.let { path ->
                    runCatching { File(path).readText() }.getOrDefault("") }.orEmpty() } })
            return@LaunchedEffect
        }
        document = withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            fun read(path: String?): String = path?.let { runCatching { File(it).readText(Charsets.UTF_8) }.getOrElse {
                errors += "文件读取失败：${File(path).name}"; ""
            } }.orEmpty()
            ReaderDocument(book.chapters.map { "" }, book.chapters.map { item -> item.pages.map { read(it.textPath) } }, errors,
                chapters = importedBook.chapters, readyChapters = book.chapters.indices.toSet())
        }
        document?.errors?.firstOrNull()?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(book.id, textWidth, textHeight, settings.fontSize, settings.lineHeight,
        settings.letterSpacing, settings.paragraphSpacing, settings.fontPath, settings.fontName, settings.bold, density, layoutDirection,
        settings.readingMode, settings.chineseConversion, chineseConverter) {
        if (raster) return@LaunchedEffect
        layoutImageHint = textPages.getOrNull(chapterIndex)?.getOrNull(pageIndex)?.imageIndex?.takeIf { it >= 0 }
        textPages = if (directScroll) document?.scrollBlocks ?: List(book.chapters.size) { emptyList() }
            else List(book.chapters.size) { emptyList() }
        paginatedChapters = emptySet()
        restoring = true
        layoutGeneration++
    }
    LaunchedEffect(book.id, document?.scrollIndex, chapterIndex, layoutGeneration, viewport, conversionReady) {
        if (document == null || !conversionReady) return@LaunchedEffect
        if (raster || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        val target = chapterIndex
        val ready = if (directScroll) target in document!!.readyChapters && textPages[target].isNotEmpty()
            else target in paginatedChapters && target in document!!.readyChapters
        reflowing = !ready
        if (!ready) restoring = true
        paginationProgress = 0f
        paginationError = null
        try {
            val workerDensity = Density(density.density, density.fontScale)
            suspend fun prepare(index: Int, visible: Boolean): List<ReaderTextPage> {
                val loaded = chapterStore.load(index)
                currentCoroutineContext().ensureActive()
                if (visible) publishChapter(index, loaded)
                val pages = if (directScroll) loaded.blocks else readerCachedChapterPages(context, importedBook, index,
                    settings, workerDensity, layoutDirection, textWidth, textHeight, loaded.text, loaded.chapter) {
                    readerPaginate(context, listOf(loaded.text), textStyle, workerDensity, layoutDirection, textWidth, textHeight,
                        inlineImages = listOf(loaded.chapter.inlineImages), sourceStyles = listOf(loaded.styles), paragraphSpacing = settings.paragraphSpacing,
                        chineseConverter = chineseConverter) { fraction ->
                        if (index == target) withContext(Dispatchers.Main) { paginationProgress = fraction }
                    }.single()
                }
                currentCoroutineContext().ensureActive()
                textPages = textPages.toMutableList().apply { this[index] = pages }
                if (!directScroll) paginatedChapters = paginatedChapters + index
                return pages
            }
            val previousImageIndex = layoutImageHint ?: textPages.getOrNull(target)?.getOrNull(pageIndex)?.imageIndex
            val pages = prepare(target, true)
            if (!ready || restoring || initialPositionPending) {
                val loaded = document!!
                characterOffset = characterOffset.coerceIn(0, loaded.text[target].length)
                if (characterOffset in 1 until loaded.text[target].length && Character.isLowSurrogate(loaded.text[target][characterOffset]) &&
                    Character.isHighSurrogate(loaded.text[target][characterOffset - 1])) characterOffset--
                val first = pages.firstOrNull()
                if (initialPositionPending && positionImagePath == null && book.progress == 0f && pageIndex == 0 && characterOffset == 0 && first != null && first.imageIndex >= 0)
                    positionImagePath = loaded.chapters[target].inlineImages[first.imageIndex].path
                if (lastPagePending) {
                    pageIndex = readerSpreadStart(pages.lastIndex, dualPage)
                    characterOffset = pages[pageIndex].start
                    positionImagePath = pages[pageIndex].imageIndex.takeIf { it >= 0 }?.let { loaded.chapters[target].inlineImages[it].path }
                    lastPagePending = false
                } else pageIndex = locatePage(target, characterOffset, positionImagePath, previousImageIndex, pageIndex)
                initialPositionPending = false
                layoutImageHint = null
                restoreRequest++
            }
            reflowing = false
            withFrameNanos { }
            val nearby = listOf(target + 1, target + 2, target - 1).filter { it in book.chapters.indices }
            for (index in nearby) {
                try { prepare(index, true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                yield()
            }
            if (!directScroll) for (index in book.chapters.indices) {
                if (index in paginatedChapters) continue
                try { prepare(index, false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The requested chapter reports its own read error when opened. */ }
                delay(10)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            paginationError = "正文排版失败：${error.localizedMessage.orEmpty().take(120)}"
            reflowing = false
        }
    }
    LaunchedEffect(book.id, document != null, restoreRequest, settings.readingMode, raster, reflowing) {
        if (document == null || chapterPageCount == 0 || (!raster && reflowing)) return@LaunchedEffect
        if (settings.readingMode != ReadingMode.SCROLL) { restoring = false; return@LaunchedEffect }
        val restoreToken = restoreRequest
        restoring = true
        try {
            val destination = pageIndex.coerceIn(0, chapterPageCount - 1)
            val scrollOffset = if (raster) 0 else currentPages.getOrNull(destination)?.takeIf { it.imageIndex < 0 }?.let { page ->
                val visible = readerPageText(currentText, page)
                val local = (characterOffset - page.start).coerceIn(0, visible.length)
                val rendered = readerParagraphText(document?.sourceStyles?.getOrNull(chapterIndex)?.apply(currentText, page.start, page.end, textStyle)
                    ?: AnnotatedString(visible), textStyle, settings.paragraphSpacing, chineseConverter, currentText, page.start)
                val measured = measurer.measure(rendered.text, style = textStyle, constraints = Constraints(maxWidth = textWidth))
                measured.getLineTop(measured.getLineForOffset(rendered.offsetMapping.originalToTransformed(local))).toInt().coerceAtLeast(0)
            } ?: 0
            listState.scrollToItem((chapterStarts.getOrNull(chapterIndex) ?: 0) + destination, scrollOffset)
        } finally {
            if (restoreToken == restoreRequest && !reflowing) restoring = false
        }
    }
    LaunchedEffect(book.id, document != null, listState, settings.readingMode) {
        if (document == null || settings.readingMode != ReadingMode.SCROLL) return@LaunchedEffect
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            Triple(index, listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key)
        }.distinctUntilChanged().collect {
            if (!reflowing && !restoring && totalPages > 0 && !speechOwnsPosition) {
                latestSyncScrollPosition()
            }
        }
    }
    LaunchedEffect(book.id, chapterIndex, pageIndex, characterOffset, positionImagePath, totalPages, progress, reflowing, restoring) {
        if (totalPages > 0 && !reflowing && !restoring) onProgress(chapterIndex, pageIndex, characterOffset, progress, positionImagePath)
    }
    LaunchedEffect(book.id, document, volumeEvents, settings.volumePaging, settings.readingMode, chapterIndex, pageIndex, textPages, speech) {
        volumeEvents.collect { if (settings.volumePaging) move(it) }
    }

    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        backgroundImage?.let { bitmap ->
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(if (settings.nightMode) Color.Black.copy(alpha = .72f) else Color.White.copy(alpha = .72f)))
        }
        // Permanent gutters keep typography and page counts unchanged when controls appear.
        val tapObserver = Modifier.pointerInput(book.id, raster) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                bodyCoordinates?.takeIf { it.isAttached }?.localToWindow(down.position)?.let { point ->
                    pointerAnchor = Rect(point.x - 2, point.y - 2, point.x + 2, point.y + 2)
                }
                noteTapHandled = false
                var moved = false
                var released = false
                var upTime = down.uptimeMillis
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > pointerConfiguration.touchSlop) moved = true
                    upTime = change.uptimeMillis
                    released = !change.pressed
                } while (!released)
                if (!raster && released && !moved && !noteTapHandled && upTime - down.uptimeMillis < pointerConfiguration.longPressTimeoutMillis) tapPage()
            }
        }
        val imageTapObserver = Modifier.pointerInput(book.id) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Images wait for a possible double tap. Their own tap callback opens the
                // note or controls after that delay, so the body must not toggle early.
                noteTapHandled = true
            }
        }
        Box(Modifier.fillMaxSize().padding(top = ReaderBodyTopPadding, bottom = ReaderBodyBottomPadding).onSizeChanged { viewport = it }
            .onGloballyPositioned { bodyCoordinates = it }.testTag("reader_body").then(tapObserver)) {
            when {
                book.chapters.isEmpty() -> Text("这本书没有可阅读的章节", Modifier.align(Alignment.Center), color = muted)
                paginationError != null || document == null || (!raster && (!conversionReady || chapterIndex !in document!!.readyChapters ||
                    !directScroll && chapterIndex !in paginatedChapters || textPages.isEmpty() || reflowing)) -> {
                    Column(Modifier.align(Alignment.Center).padding(24.dp).testTag("reader_loading"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (paginationError == null && !einkMode) {
                            if (document == null) CircularProgressIndicator(color = colors.primary)
                            else CircularProgressIndicator(progress = { paginationProgress }, color = colors.primary)
                        }
                        Text(paginationError ?: if (document == null) "正在读取正文" else "正在排版 ${(paginationProgress * 100).toInt()}%",
                            color = muted, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { stopSpeech(); onClose() }) { Text("退出阅读") }
                    }
                }
                chapterPageCount == 0 -> Text("这一章没有可阅读的内容", Modifier.align(Alignment.Center), color = muted)
                settings.readingMode == ReadingMode.SCROLL -> {
                    // Read-only text fields request their whole block/cursor on focus. Reading taps
                    // must keep the viewport; navigation still uses explicit scrollToItem/scrollBy.
                    CompositionLocalProvider(LocalBringIntoViewSpec provides
                        if (raster) LocalBringIntoViewSpec.current else ReaderScrollBringIntoViewSpec) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("reader_scroll"),
                        flingBehavior = if (einkMode) ReaderEinkFlingBehavior else ScrollableDefaults.flingBehavior(),
                        contentPadding = if (raster) PaddingValues(0.dp) else PaddingValues(horizontal = ReaderTextHorizontalPadding, vertical = ReaderTextVerticalPadding)) {
                        book.chapters.forEachIndexed { sourceChapter, sourceChapterData ->
                        if (raster) itemsIndexed(sourceChapterData.pages, key = { index, _ -> "$sourceChapter:$index" }) { index, source ->
                            val color = annotations.lastOrNull { it.bookId == book.id && it.chapterIndex == sourceChapter && it.pageIndex == index && it.kind != AnnotationKind.BOOKMARK && it.color != null }?.color?.let(::Color)
                            ReaderImagePage(source, viewport.width, false, foreground,
                                onTap = { tapImage(sourceChapter, index) }, onLongPress = { imageSelection(sourceChapter, index) }, markColor = color,
                                modifier = Modifier.testTag("reader_image_${sourceChapter}_$index"),
                                speechOffset = pdfSpeechOffset(sourceChapter, index), speechText = document?.pageText?.getOrNull(sourceChapter)?.getOrNull(index).orEmpty(),
                                einkMode = einkMode)
                        } else if (sourceChapter !in document!!.readyChapters) item(key = "$sourceChapter:0") {
                            LaunchedEffect(sourceChapter) {
                                runCatching { chapterStore.load(sourceChapter) }.onSuccess { loaded ->
                                    publishChapter(sourceChapter, loaded)
                                    textPages = textPages.toMutableList().apply { this[sourceChapter] = loaded.blocks }
                                }.onFailure { error -> if (error is CancellationException) throw error }
                            }
                            Box(Modifier.fillMaxWidth().height(with(density) { viewport.height.toDp() }), contentAlignment = Alignment.Center) {
                                Text("正在读取 ${sourceChapterData.title}", color = muted, fontSize = 13.sp)
                            }
                        } else itemsIndexed(textPages.getOrNull(sourceChapter).orEmpty(), key = { index, _ -> "$sourceChapter:$index" }) { index, page ->
                            if (page.imageIndex >= 0) {
                                val image = sourceChapterData.inlineImages[page.imageIndex]
                                val color = annotations.lastOrNull { it.bookId == book.id && it.chapterIndex == sourceChapter && it.imagePath == image.path && it.characterOffset == page.start && it.kind != AnnotationKind.BOOKMARK }?.color?.let(::Color)
                                ReaderImagePage(PageSource(image.path), textWidth, false, foreground,
                                    onTap = { tapImage(sourceChapter, index, page.start, image.path) }, onLongPress = { inlineImageSelection(sourceChapter, index, page) }, markColor = color,
                                    modifier = Modifier.then(imageTapObserver).testTag("reader_inline_image_${sourceChapter}_${page.imageIndex}"), description = "正文插图", einkMode = einkMode)
                            } else ReaderSelectableText(readerAnnotatedPage(document?.text?.getOrNull(sourceChapter).orEmpty(), page, book.id, sourceChapter, annotations, highlightQuery,
                                colors.primaryContainer, settings.nightMode, document?.sourceStyles?.getOrNull(sourceChapter), textStyle, spokenRange(sourceChapter)), textStyle, sourceChapter, index, page.start, selectionReset,
                                onAction = { selection, kind -> openSelection(selection, kind) }, modifier = Modifier.fillMaxWidth().padding(bottom =
                                    if (readerParagraphGapAfter(document?.text?.getOrNull(sourceChapter).orEmpty(), page.end))
                                        with(density) { (textStyle.fontSize.value * settings.paragraphSpacing).sp.toDp() } else 0.dp),
                                onTextLayout = { rememberLayout(sourceChapter, index, it) },
                                onTextTap = { offset, anchor -> tapText(sourceChapter, offset, anchor) }, onEdit = if (editable) { selection -> openTxtEditor(selection) } else null,
                                onRole = onRoleSelection?.let { callback -> { passage -> selectionReset++; callback(passage.quote) } },
                                selectionMenuEnabled = selectedPassage == null && txtSelection == null && tappedNoteIds.isEmpty() && panel == null,
                                paragraphSpacing = settings.paragraphSpacing, chineseConverter = chineseConverter,
                                sourceContext = document?.text?.getOrNull(sourceChapter),
                                onSelectionActive = { textSelectionChanged(sourceChapter, index, it) })
                        }
                        }
                    }
                    }
                }
                else -> {
                    var drag by remember(chapterIndex, pageIndex) { mutableStateOf(0f) }
                    Box(Modifier.fillMaxSize().pointerInput(book.id, book.chapters, chapterIndex, pageIndex, textPages, pageCounts, settings.readingMode, speech) {
                        if (settings.readingMode == ReadingMode.VERTICAL_PAGED) detectVerticalDragGestures(
                            onDragStart = { drag = 0f; manualDrag = true; pauseSpeechForNavigation() },
                            onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                            onDragEnd = { if (abs(drag) > size.height * .1f) move(if (drag < 0) 1 else -1); manualDrag = false },
                            onDragCancel = { manualDrag = false })
                        else detectHorizontalDragGestures(onDragStart = { drag = 0f; manualDrag = true; pauseSpeechForNavigation() },
                            onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                            onDragEnd = { if (abs(drag) > size.width * 0.14f) move(if (drag < 0) 1 else -1); manualDrag = false },
                            onDragCancel = { manualDrag = false })
                    }) {
                        ReaderPageTurnFrame(ReaderPageLocation(chapterIndex, readerSpreadStart(pageIndex, dualPage)),
                            dualPage, pageCounts, settings.pageTurnStyle, settings.readingMode == ReadingMode.VERTICAL_PAGED,
                            einkMode, background, backgroundContent = {
                                backgroundImage?.let { bitmap ->
                                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(Modifier.fillMaxSize().background(if (settings.nightMode) Color.Black.copy(alpha = .72f) else Color.White.copy(alpha = .72f)))
                                }
                            }) { sourceChapter, sourcePage, interactive ->
                        val sourceText = document?.text?.getOrNull(sourceChapter).orEmpty()
                        if (raster) book.chapters.getOrNull(sourceChapter)?.pages?.getOrNull(sourcePage)?.let { source ->
                            val color = annotations.lastOrNull { it.bookId == book.id && it.chapterIndex == sourceChapter && it.pageIndex == sourcePage && it.kind != AnnotationKind.BOOKMARK && it.color != null }?.color?.let(::Color)
                            ReaderImagePage(source, pageViewport.width, true, foreground,
                                onTap = { if (interactive) tapImage(sourceChapter, sourcePage) },
                                onLongPress = { if (interactive) imageSelection(sourceChapter, sourcePage) }, markColor = color,
                                modifier = Modifier.testTag("reader_image_${sourceChapter}_$sourcePage"),
                                speechOffset = pdfSpeechOffset(sourceChapter, sourcePage), speechText = document?.pageText?.getOrNull(sourceChapter)?.getOrNull(sourcePage).orEmpty(),
                                einkMode = einkMode)
                        } else textPages.getOrNull(sourceChapter)?.getOrNull(sourcePage)?.let { page ->
                            if (page.imageIndex >= 0) {
                                val image = book.chapters[sourceChapter].inlineImages[page.imageIndex]
                                val color = annotations.lastOrNull { it.bookId == book.id && it.chapterIndex == sourceChapter && it.imagePath == image.path && it.characterOffset == page.start && it.kind != AnnotationKind.BOOKMARK }?.color?.let(::Color)
                                ReaderImagePage(PageSource(image.path), textWidth, true, foreground,
                                    onTap = { if (interactive) tapImage(sourceChapter, sourcePage, page.start, image.path) },
                                    onLongPress = { if (interactive) inlineImageSelection(sourceChapter, sourcePage, page) }, markColor = color,
                                    modifier = Modifier.padding(horizontal = ReaderTextHorizontalPadding, vertical = ReaderTextVerticalPadding).then(imageTapObserver)
                                        .testTag("reader_inline_image_${sourceChapter}_${page.imageIndex}"), description = "正文插图", einkMode = einkMode)
                            } else ReaderSelectableText(readerAnnotatedPage(sourceText, page, book.id, sourceChapter, annotations, highlightQuery,
                                colors.primaryContainer, settings.nightMode, document?.sourceStyles?.getOrNull(sourceChapter), textStyle, spokenRange(sourceChapter)), textStyle, sourceChapter, sourcePage, page.start, selectionReset,
                                onAction = { selection, kind -> if (interactive) openSelection(selection, kind) }, modifier = Modifier.fillMaxSize().padding(horizontal = ReaderTextHorizontalPadding, vertical = ReaderTextVerticalPadding),
                                onTextLayout = { rememberLayout(sourceChapter, sourcePage, it) },
                                onTextTap = { offset, anchor -> if (interactive) tapText(sourceChapter, offset, anchor) },
                                onEdit = if (editable && interactive) { selection -> openTxtEditor(selection) } else null,
                                onRole = onRoleSelection?.let { callback -> { passage -> selectionReset++; callback(passage.quote) } },
                                selectionMenuEnabled = interactive && selectedPassage == null && txtSelection == null && tappedNoteIds.isEmpty() && panel == null,
                                paragraphSpacing = settings.paragraphSpacing, chineseConverter = chineseConverter, sourceContext = sourceText,
                                onSelectionActive = { textSelectionChanged(sourceChapter, sourcePage, it) })
                        }
                        }
                    }
                }
            }
        }
        if (!chromeVisible) ReaderStatus(settings, chapterTitle, muted, currentBookmarks.isNotEmpty())
        if (!chromeVisible) Text("${(progress * 100).toInt()}%  ·  " + if (directScroll) scrollPageProgress!!.label else if (!allPagesReady)
            "本章 $visiblePageEnd / $chapterPageCount 页 · 总页数统计中" else
            "${if (totalPages > 0) globalPage.coerceAtMost(totalPages) else 0} / $totalPages 页",
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 8.dp).testTag("reader_page_progress"), fontSize = 11.sp, color = muted)
        if (chromeVisible) {
            Surface(color = colors.surface.copy(alpha = 0.97f), contentColor = colors.onSurface,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Row(Modifier.height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { stopSpeech(); onClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架") }
                    Text(book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = ::toggleBookmark) { Icon(Icons.Outlined.BookmarkBorder, "快速添加书签", tint = if (currentBookmarks.isNotEmpty()) colors.primary else colors.onSurface) }
                    if (editable) IconButton(onClick = {
                        openTxtEditor(ReaderSelectedPassage(chapterIndex, pageIndex, 0, currentText.length, currentText))
                    }, enabled = document != null && !reflowing && !restoring) { Icon(Icons.Outlined.Edit, "编辑当前章节") }
                    IconButton(onClick = { onSettings(settings.copy(nightMode = !settings.nightMode)) }) {
                        Icon(if (settings.nightMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "切换夜间模式")
                    }
                    IconButton(onClick = { openPanel(ReaderPanel.SEARCH) }) { Icon(Icons.Outlined.Search, "全文搜索与替换") }
                }
            }
            Surface(color = colors.surface.copy(alpha = 0.97f), contentColor = colors.onSurface,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .onSizeChanged { toolbarHeight = it.height }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(chapterTitle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = colors.onSurfaceVariant)
                        val previewPage = if (totalPages == 0) 0 else
                            (progressDraft?.let { (it * (totalPages - 1)).roundToInt() + 1 } ?: globalPage).coerceIn(1, totalPages)
                        val previewPercent = if (directScroll || !allPagesReady) ((progressDraft ?: progress) * 100).toInt() else
                            if (totalPages == 0) 0 else (previewPage.toFloat() * 100 / totalPages).toInt()
                        Text("$previewPercent% · " + if (directScroll) "第 ${chapterIndex + 1} / ${book.chapters.size} 章" else if (!allPagesReady)
                            "本章 $visiblePageEnd / $chapterPageCount 页" else
                            "$previewPage / $totalPages 页", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { jumpChapter(-1) }, enabled = chapterIndex > 0 && !reflowing && !restoring) {
                        Icon(Icons.Outlined.SkipPrevious, "上一章", Modifier.size(22.dp))
                    }
                    Slider(progressDraft ?: if (directScroll || !allPagesReady) progress else if (totalPages <= 1) 0f else (globalPage - 1).toFloat() / (totalPages - 1),
                        onValueChange = { pauseSpeechForNavigation(); progressDraft = it }, onValueChangeFinished = {
                            progressDraft?.let(::seekProgress)
                            progressDraft = null
                        }, enabled = (directScroll || totalPages > 1) && !reflowing && !restoring,
                        modifier = Modifier.weight(1f).height(36.dp).testTag("reader_progress").semantics { contentDescription = "全书阅读进度" })
                    IconButton(onClick = { jumpChapter(1) }, enabled = chapterIndex < book.chapters.lastIndex && !reflowing && !restoring) {
                        Icon(Icons.Outlined.SkipNext, "下一章", Modifier.size(22.dp))
                    }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ReaderToolbarAction("目录", Icons.AutoMirrored.Outlined.MenuBook) { openPanel(ReaderPanel.CONTENTS) }
                        ReaderToolbarAction("朗读", Icons.Outlined.Headphones, tint = if (speechSessionActive) Color(0xFFE35858) else colors.onSurface) {
                            if (speechSessionActive) stopSpeech() else startSpeech()
                        }
                        ReaderToolbarAction("书签", Icons.Outlined.BookmarkBorder) { annotationKind = null; openPanel(ReaderPanel.ANNOTATIONS) }
                        ReaderToolbarAction("界面设置", Icons.Outlined.Palette, "界面", tint = colors.onSurface) { openPanel(ReaderPanel.INTERFACE) }
                        ReaderToolbarAction("阅读设置", Icons.Outlined.Settings, "设置", tint = colors.onSurface) { openPanel(ReaderPanel.SETTINGS) }
                    }
                }
            }
        }
        if (chromeVisible && speechSessionActive && selectedPassage == null && txtSelection == null) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                shadowElevation = 6.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                    .padding(bottom = if (panel == ReaderPanel.INTERFACE || panel == ReaderPanel.SETTINGS || panel == ReaderPanel.FONTS) halfScreen + 12.dp
                        else if (chromeVisible) with(density) { toolbarHeight.toDp() } + 12.dp else 36.dp)
                    .onSizeChanged { speechBarHeight = it.height }
                    .testTag("reader_speech_bar")) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Headphones, "朗读开启", tint = Color(0xFFE35858), modifier = Modifier.size(18.dp))
                        IconButton(onClick = { stepSpeech(-1) }) { Icon(Icons.Outlined.FastRewind, "回退一段") }
                        IconButton(onClick = { toggleSpeech() }) { Icon(if (speaking) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (speaking) "暂停朗读" else "继续朗读") }
                        IconButton(onClick = { stepSpeech(1) }) { Icon(Icons.Outlined.FastForward, "快进一段") }
                        FilterChip(settings.ttsFollowPage, { onSettings(settings.copy(ttsFollowPage = !settings.ttsFollowPage)) }, { Text("同步", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedLabelColor = Color(0xFFE35858), selectedContainerColor = Color(0xFFE35858).copy(alpha = .15f)))
                        IconButton(onClick = ::stopSpeech) { Icon(Icons.Outlined.Close, "关闭朗读") }
                        IconButton(onClick = onSpeechSettings, modifier = Modifier.size(40.dp).testTag("reader_speech_settings")) {
                            Icon(Icons.Outlined.Settings, "朗读设置", tint = colors.onSurface)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("语速", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                        Slider(speechSpeedDraft, { speechSpeedDraft = it }, Modifier.weight(1f).semantics { contentDescription = "悬浮栏朗读语速" },
                            valueRange = .5f..2f, steps = 14, onValueChangeFinished = {
                                if (speechSpeedDraft != settings.ttsSpeed) onSettings(settings.copy(ttsSpeed = speechSpeedDraft)) })
                        Text(String.format(Locale.getDefault(), "%.1f×", speechSpeedDraft), fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        }
        if (speechSessionActive && followPagePrompt && !settings.ttsFollowPage && selectedPassage == null && txtSelection == null) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh,
                contentColor = colors.onSurface, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
                    .padding(bottom = if (chromeVisible) with(density) { (toolbarHeight + speechBarHeight).toDp() } + 24.dp else 36.dp)
                    .testTag("reader_speech_prompt")) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("从当前页面朗读？", Modifier.weight(1f), fontSize = 11.sp)
                    TextButton(onClick = { startSpeech() }) { Text("确认") }
                    TextButton(onClick = {
                        spokenLocation?.let { (segment, offset) ->
                            jump(segment.chapter, if (raster) segment.page else locatePage(segment.chapter, offset), offset, fromSpeech = true)
                        }
                        followPagePrompt = false
                    }) { Text("返回原位置") }
                }
            }
        }
        returnPosition?.takeIf { chromeVisible && !speechSessionActive && panel == null && selectedPassage == null }?.let { previous ->
            FilledTonalButton(onClick = {
                jump(previous.chapter, previous.page, previous.offset, previous.imagePath, previous.imageIndex)
                returnPosition = null
            }, enabled = !reflowing && !restoring,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = with(density) { toolbarHeight.toDp() } + 12.dp)
                    .widthIn(max = 360.dp).testTag("reader_jump_return"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(if (previous.forward) Icons.AutoMirrored.Outlined.ArrowForward else Icons.AutoMirrored.Outlined.ArrowBack,
                    if (previous.forward) "返回较后位置" else "返回较前位置", Modifier.size(18.dp))
                Text("返回 ${previous.title} · ${(previous.progress * 100).toInt()}%", modifier = Modifier.padding(start = 6.dp),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (currentBookmarks.isNotEmpty()) {
            val ribbonColor = colors.primary
            Canvas(Modifier.align(Alignment.TopEnd).padding(end = if (chromeVisible) 6.dp else 24.dp, top = if (chromeVisible) 56.dp else 0.dp)
                .width(18.dp).height(28.dp).testTag("reader_bookmark_marker").semantics { contentDescription = "当前页书签" }) {
                val path = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height);
                    lineTo(size.width / 2, size.height - 8.dp.toPx()); lineTo(0f, size.height); close() }
                drawPath(path, ribbonColor)
            }
        }
        if (panel == ReaderPanel.INTERFACE || panel == ReaderPanel.SETTINGS || panel == ReaderPanel.FONTS) {
            Surface(color = colors.surface, shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = halfScreen).testTag("reader_settings_sheet")) {
                if (panel != ReaderPanel.SETTINGS) ReaderCompactSettings(settings, raster, onSettings,
                    onImportFont, onImportBackground, onFonts = { panel = ReaderPanel.FONTS })
                else ReaderPreferencesPanel(settings, onSettings, onResetDefaults)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (speechError != null) AlertDialog(onDismissRequest = { speech.clearError() }, title = { Text("朗读失败") },
        text = { ReaderDialogAppearance(settings); Text(speechError.orEmpty()) }, confirmButton = { TextButton(onClick = { speech.clearError() }) { Text("知道了") } })
    selectedPassage?.let { selected ->
        fun clearSelection() { selectedPassage = null; selectionReset++ }
        ReaderSelectionEditor(settings, selected, editorKind, { editorKind = it }, noteDraft, { noteDraft = it }, markColor, { markColor = it },
            onSave = {
                onAddAnnotation(Annotation(bookId = book.id, chapterIndex = selected.chapter, pageIndex = selected.page,
                    characterOffset = selected.start, endOffset = selected.end, quote = selected.quote,
                    note = if (editorKind == AnnotationKind.NOTE) noteDraft.trim() else "", kind = editorKind, color = markColor,
                    imagePath = selected.imagePath, underlineStyle = underlineStyle))
                clearSelection()
                scope.launch { snackbar.showSnackbar("已保存${when (editorKind) { AnnotationKind.NOTE -> "笔记"; AnnotationKind.UNDERLINE -> "划线"; else -> "彩色标记" }}") }
            }, onDismiss = ::clearSelection, underlineStyle = underlineStyle, onUnderlineStyle = { underlineStyle = it })
    }
    val tappedNotes = tappedNoteIds.mapNotNull { id -> annotations.firstOrNull { it.id == id } }
    LaunchedEffect(tappedNoteIds, tappedNotes) {
        if (tappedNoteIds.isNotEmpty() && tappedNotes.isEmpty()) tappedNoteIds = emptyList()
    }
    if (tappedNotes.isNotEmpty()) ReaderAnnotationPreview(settings, tappedNotes, annotationAnchor, onDeleteAnnotation) { tappedNoteIds = emptyList() }
    txtSelection?.let { selected ->
        ReaderTxtEditor(settings, selected, onSave = { replacement ->
            txtSelection = null
            selectionReset++
            onEditTxt(selected.chapter, selected.start, selected.end, replacement)
        }, onDismiss = { txtSelection = null; selectionReset++ }, overwriteSource = book.format in setOf(BookFormat.EPUB, BookFormat.MOBI))
    }
    when (panel) {
        ReaderPanel.FONTS -> ReaderFontSettings(settings, onSettings, { panel = ReaderPanel.INTERFACE; onImportFont() }, { panel = ReaderPanel.INTERFACE })
        ReaderPanel.SPACING -> ReaderSpacingSettings(settings, onSettings, { panel = null })
        ReaderPanel.CONTENTS -> {
            val speechStatus = rememberSpeechCacheStatus(importedBook, speechCacheConfig, speechCacheRevision, commonVoiceRoles)
            ReaderContentsPanel(book, chapterIndex, pageIndex, settings, { panel = null },
            onChapterRules = if (book.mode == LibraryMode.NOVEL) onChapterRules else null,
            onGenerateSpeech = onGenerateSpeech,
            speechGenerationBusy = speechGenerationBusy,
            cachedSpeechChapters = speechStatus.chapters, speechBookComplete = speechStatus.complete,
            otherCachedSpeechChapters = speechStatus.otherChapters,
            speechGeneration = speechGeneration, onCancelSpeech = onCancelSpeech) { targetChapter, targetPage ->
            jumpPage(targetChapter, targetPage); panel = null
            }
        }
        ReaderPanel.SEARCH, ReaderPanel.ANNOTATIONS -> {
            ModalBottomSheet(onDismissRequest = { panel = null; onSearchClosed() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surface) {
                ReaderDialogAppearance(settings)
                when (panel) {
                    ReaderPanel.SEARCH -> ReaderSearchPanel(book, document, raster, searchQuery, { searchQuery = it },
                        onReplaceMatch = onReplaceMatch, onReplaceAll = onReplaceAll, replacing = textEditBusy,
                        initialReplacement = resumeSearch?.replacement.orEmpty()) { result ->
                        jump(result.chapter, result.page, result.offset); highlightQuery = searchQuery.trim(); panel = null; onSearchClosed()
                    }
                    ReaderPanel.ANNOTATIONS -> ReaderAnnotationsPanel(book, raster, annotations, annotationKind, { annotationKind = it },
                        annotationQuery, { annotationQuery = it }, onJump = { item -> jump(item.chapterIndex, item.pageIndex, item.characterOffset, item.imagePath); panel = null }, onDelete = onDeleteAnnotation)
                    else -> Unit
                }
            }
        }
        ReaderPanel.INTERFACE, ReaderPanel.SETTINGS, null -> Unit
    }
}

@Composable
private fun ReaderToolbarAction(description: String, icon: ImageVector, label: String = description,
    tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(Modifier.width(48.dp).clickable(onClick = onClick).padding(vertical = 8.dp).semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint); Text(label, fontSize = 10.sp, color = tint)
    }
}

@Composable
private fun ReaderStatus(settings: ReaderSettings, chapterTitle: String, color: Color, hasBookmark: Boolean) {
    val context = LocalContext.current
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    var battery by remember { mutableIntStateOf(-1) }
    LaunchedEffect(settings.showTime) {
        while (settings.showTime) { time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(30_000) }
    }
    DisposableEffect(context, settings.showBattery) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
            }
        }
        if (settings.showBattery) context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { if (settings.showBattery) context.unregisterReceiver(receiver) }
    }
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 24.dp, end = if (hasBookmark) 48.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (settings.showChapter) chapterTitle else "", Modifier.weight(1f), color = color, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (settings.showTime) Text(time, color = color, fontSize = 10.sp, modifier = Modifier.padding(start = 10.dp))
        if (settings.showBattery && battery >= 0) Text("$battery%", color = color, fontSize = 10.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

internal fun readerPageText(text: String, page: ReaderTextPage): String = text.substring(page.start, page.end).removeSuffix("\n")

private fun readerAnnotatedPage(text: String, page: ReaderTextPage, bookId: String, chapter: Int, annotations: List<Annotation>,
    query: String, searchColor: Color, night: Boolean, sourceStyles: ReaderSourceStyles?, baseStyle: TextStyle,
    spoken: IntRange? = null): AnnotatedString {
    val visible = readerPageText(text, page)
    val result = AnnotatedString.Builder(sourceStyles?.apply(text, page.start, page.end, baseStyle) ?: AnnotatedString(visible))
    readerSearchHighlight(visible, query, searchColor).spanStyles.forEach { result.addStyle(it.item, it.start, it.end) }
    annotations.filter { it.bookId == bookId && it.chapterIndex == chapter && it.imagePath == null && it.kind != AnnotationKind.BOOKMARK }.forEach { item ->
        val range = readerAnnotationRange(text, item) ?: return@forEach
        val start = (range.first - page.start).coerceIn(0, visible.length)
        val end = (range.last + 1 - page.start).coerceIn(0, visible.length)
        if (end > start) {
            if (item.kind == AnnotationKind.UNDERLINE) result.addReaderUnderline(start, end, item.underlineStyle, Color.Unspecified)
            else result.addStyle(SpanStyle(background = Color(item.color ?: ReaderMarkColors.first().second).copy(alpha = if (night) 0.26f else 0.4f)), start, end)
        }
    }
    spoken?.let {
        val start = (it.first - page.start).coerceIn(0, visible.length)
        val end = (it.last + 1 - page.start).coerceIn(0, visible.length)
        if (end > start) result.addStyle(SpanStyle(background = Color(0xFFFFA66B).copy(alpha = if (night) .3f else .35f)), start, end)
    }
    return result.toAnnotatedString()
}
