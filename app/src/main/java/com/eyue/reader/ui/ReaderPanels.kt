package com.eyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.AiSpeechBatchProgress
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.UnderlineStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

internal val ReaderMarkColors = listOf(
    "黄色" to 0xFFFFD76CL, "绿色" to 0xFFA6DCA4L,
    "蓝色" to 0xFFA9C8F0L, "粉色" to 0xFFFFB5BAL
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ReaderCompactSettings(
    settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit, onImportBackground: () -> Unit, onMore: () -> Unit,
    onFonts: () -> Unit = onImportFont
) {
    var fontSizeDraft by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize) }
    var lineHeightDraft by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }
    var paragraphSpacingDraft by remember(settings.paragraphSpacing) { mutableFloatStateOf(settings.paragraphSpacing) }
    var letterSpacingDraft by remember(settings.letterSpacing) { mutableFloatStateOf(settings.letterSpacing) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("背景", Modifier.width(48.dp), fontSize = 13.sp)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ReaderBackground.PAPER to "纸白", ReaderBackground.WHITE to "纯白", ReaderBackground.GREEN to "护眼").forEach { (value, name) ->
                FilterChip(!settings.nightMode && settings.background == value, { onSettings(settings.copy(background = value, nightMode = false)) }, { Text(name, fontSize = 12.sp) },
                    modifier = Modifier.height(34.dp))
            }
            if (settings.backgroundImagePath != null) FilterChip(settings.background == ReaderBackground.IMAGE,
                { onSettings(settings.copy(background = ReaderBackground.IMAGE)) }, { Text("图片", fontSize = 12.sp) }, Modifier.height(34.dp))
            TextButton(onClick = onImportBackground, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("导入图片", fontSize = 12.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("亮度", Modifier.width(48.dp), fontSize = 13.sp)
            Slider(if (settings.brightness < 0f) 0.5f else settings.brightness, { onSettings(settings.copy(brightness = it)) },
                Modifier.weight(1f).semantics { contentDescription = "阅读亮度" }, valueRange = 0.01f..1f)
            TextButton(onClick = { onSettings(settings.copy(brightness = -1f)) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (settings.brightness < 0f) "随系统" else "系统", fontSize = 11.sp)
            }
        }
        if (!raster) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", Modifier.width(48.dp), fontSize = 13.sp)
                Slider(fontSizeDraft, { fontSizeDraft = it },
                    Modifier.weight(1f).semantics { contentDescription = "阅读字号" }, valueRange = 12f..36f, steps = 23,
                    onValueChangeFinished = { if (fontSizeDraft != settings.fontSize) onSettings(settings.copy(fontSize = fontSizeDraft)) })
                Text("${fontSizeDraft.toInt()}", Modifier.width(28.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("行距", Modifier.width(48.dp), fontSize = 13.sp)
                Slider(lineHeightDraft, { lineHeightDraft = it }, Modifier.weight(1f).semantics { contentDescription = "阅读行间距" },
                    valueRange = 1.2f..2.4f, onValueChangeFinished = {
                        if (lineHeightDraft != settings.lineHeight) onSettings(settings.copy(lineHeight = lineHeightDraft)) })
                Text(String.format(Locale.getDefault(), "%.2f", lineHeightDraft), Modifier.width(34.dp), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("段距", Modifier.width(48.dp), fontSize = 13.sp)
                Slider(paragraphSpacingDraft, { paragraphSpacingDraft = it }, Modifier.weight(1f).semantics { contentDescription = "阅读段间距" },
                    valueRange = 0f..2f, onValueChangeFinished = {
                        if (paragraphSpacingDraft != settings.paragraphSpacing) onSettings(settings.copy(paragraphSpacing = paragraphSpacingDraft)) })
                Text(String.format(Locale.getDefault(), "%.1f", paragraphSpacingDraft), Modifier.width(34.dp), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字距", Modifier.width(48.dp), fontSize = 13.sp)
                Slider(letterSpacingDraft, { letterSpacingDraft = it }, Modifier.weight(1f).semantics { contentDescription = "阅读字间距" },
                    valueRange = -0.2f..3f, onValueChangeFinished = {
                        if (letterSpacingDraft != settings.letterSpacing) onSettings(settings.copy(letterSpacing = letterSpacingDraft)) })
                Text(String.format(Locale.getDefault(), "%.1f", letterSpacingDraft), Modifier.width(34.dp), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字体", Modifier.width(48.dp), fontSize = 13.sp)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("跟随系统" to "系统", "系统宋体" to "宋体", "系统黑体" to "黑体").forEach { (name, label) ->
                        FilterChip(settings.fontPath == null && settings.fontName == name,
                            { onSettings(settings.copy(fontName = name, fontPath = null)) }, { Text(label, fontSize = 12.sp) }, Modifier.height(34.dp))
                    }
                    FilterChip(settings.bold, { onSettings(settings.copy(bold = !settings.bold)) }, { Text("加粗", fontSize = 12.sp) }, Modifier.height(34.dp))
                    TextButton(onClick = onFonts, contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.testTag("reader_more_fonts")) { Text("更多", fontSize = 12.sp) }
                }
            }
        } else {
            Text("保持原页排版，双指可缩放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模式", Modifier.width(48.dp), fontSize = 13.sp)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Triple(ReadingMode.SCROLL, "滚动", Icons.Outlined.UnfoldMore),
                    Triple(ReadingMode.PAGED, "翻页", Icons.Outlined.SwapHoriz),
                    Triple(ReadingMode.VERTICAL_PAGED, "上下", Icons.Outlined.SwapVert)).forEach { (mode, label, icon) ->
                    FilterChip(settings.readingMode == mode, { onSettings(settings.copy(readingMode = mode)) },
                        { Text(label, fontSize = 12.sp) }, Modifier.height(34.dp).testTag("reader_mode_${mode.name.lowercase()}"),
                        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) })
                }
            }
        }
        if (settings.readingMode != ReadingMode.SCROLL) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("翻页", Modifier.width(48.dp), fontSize = 13.sp)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(PageTurnStyle.COVER to "覆盖", PageTurnStyle.SLIDE to "滑动", PageTurnStyle.CURL to "仿真").forEach { (style, label) ->
                    FilterChip(settings.pageTurnStyle == style, { onSettings(settings.copy(pageTurnStyle = style)) },
                        { Text(label, fontSize = 12.sp) }, Modifier.height(34.dp).testTag("reader_turn_${style.name.lowercase()}"))
                }
            }
        }
        TextButton(onClick = onMore, modifier = Modifier.align(Alignment.End)) { Text("更多设置") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ReaderMoreSettings(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit,
    onFonts: () -> Unit, onSpacing: () -> Unit, onClose: () -> Unit,
    onResetDefaults: () -> Unit = {}) {
    var confirmReset by remember { mutableStateOf(false) }
    ReaderDialogFrame("更多阅读设置", settings, onClose) {
        ReaderToggle("时间", settings.showTime) { onSettings(settings.copy(showTime = it)) }
        ReaderToggle("电量", settings.showBattery) { onSettings(settings.copy(showBattery = it)) }
        ReaderToggle("章节名称", settings.showChapter) { onSettings(settings.copy(showChapter = it)) }
        ReaderToggle("音量键翻页", settings.volumePaging) { onSettings(settings.copy(volumePaging = it)) }
        ReaderToggle("阅读时保持亮屏", settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
        HorizontalDivider()
        ReaderSettingLabel("系统朗读语速", String.format(Locale.getDefault(), "%.1f×", settings.ttsSpeed))
        ReaderToggle("朗读与阅读页面同步", settings.ttsFollowPage) { onSettings(settings.copy(ttsFollowPage = it)) }
        Slider(settings.ttsSpeed, { onSettings(settings.copy(ttsSpeed = it)) }, valueRange = 0.5f..2f, steps = 14)
        Text("使用系统文字转语音引擎，从当前阅读位置开始。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onFonts) { Text("字体与导入") }
            TextButton(onClick = onSpacing) { Text("字距与行距") }
        }
        HorizontalDivider()
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.testTag("reader_reset_defaults")) { Text("恢复本模式默认设置") }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("恢复本模式默认设置？") },
        text = { Text("恢复当前小说或漫画模式的阅读设置，另一模式的设置保持不变。") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onResetDefaults() },
            modifier = Modifier.testTag("reader_reset_defaults_confirm")) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

@Composable
internal fun ReaderFontSettings(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    ReaderDialogFrame("字体格式", settings, onClose) {
        listOf("等宽字体").forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { onSettings(settings.copy(fontName = name, fontPath = null)) }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                if (settings.fontPath == null && settings.fontName == name) Text("已选", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
        settings.fonts.forEach { font ->
            Row(Modifier.fillMaxWidth().clickable { onSettings(settings.copy(fontName = font.name, fontPath = font.path)) }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(font.name, Modifier.weight(1f))
                if (settings.fontPath == font.path) Text("已选", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onImport) { Text("导入 TTF / OTF 字体") }
    }
}

@Composable
internal fun ReaderSpacingSettings(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit, onClose: () -> Unit) {
    var lineHeightDraft by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }
    var paragraphSpacingDraft by remember(settings.paragraphSpacing) { mutableFloatStateOf(settings.paragraphSpacing) }
    var letterSpacingDraft by remember(settings.letterSpacing) { mutableFloatStateOf(settings.letterSpacing) }
    ReaderDialogFrame("间距", settings, onClose) {
        ReaderSettingLabel("行间距", String.format(Locale.getDefault(), "%.2f", lineHeightDraft))
        Slider(lineHeightDraft, { lineHeightDraft = it }, Modifier.semantics { contentDescription = "阅读行间距" }, valueRange = 1.2f..2.4f,
            onValueChangeFinished = { if (lineHeightDraft != settings.lineHeight) onSettings(settings.copy(lineHeight = lineHeightDraft)) })
        ReaderSettingLabel("段间距", String.format(Locale.getDefault(), "%.1f", paragraphSpacingDraft))
        Slider(paragraphSpacingDraft, { paragraphSpacingDraft = it }, Modifier.semantics { contentDescription = "阅读段间距" }, valueRange = 0f..2f,
            onValueChangeFinished = { if (paragraphSpacingDraft != settings.paragraphSpacing) onSettings(settings.copy(paragraphSpacing = paragraphSpacingDraft)) })
        ReaderSettingLabel("字间距", String.format(Locale.getDefault(), "%.1f", letterSpacingDraft))
        Slider(letterSpacingDraft, { letterSpacingDraft = it }, Modifier.semantics { contentDescription = "阅读字间距" }, valueRange = -0.2f..3f,
            onValueChangeFinished = { if (letterSpacingDraft != settings.letterSpacing) onSettings(settings.copy(letterSpacing = letterSpacingDraft)) })
    }
}

@Composable
private fun ReaderDialogFrame(title: String, settings: ReaderSettings, onClose: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
        text = {
            ReaderDialogAppearance(settings)
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        })
}

@Composable
internal fun ReaderSelectionEditor(
    settings: ReaderSettings, selection: ReaderSelectedPassage, kind: AnnotationKind, onKind: (AnnotationKind) -> Unit,
    note: String, onNote: (String) -> Unit, color: Long, onColor: (Long) -> Unit,
    onSave: () -> Unit, onDismiss: () -> Unit,
    underlineStyle: UnderlineStyle = UnderlineStyle.STRAIGHT, onUnderlineStyle: (UnderlineStyle) -> Unit = {}
) {
    val size = LocalWindowInfo.current.containerSize
    val anchor = selection.anchor ?: Rect(size.width / 2f, size.height / 2f, size.width / 2f + 1, size.height / 2f + 1)
    ReaderAnnotationPopup(anchor, onDismiss, focusable = kind == AnnotationKind.NOTE) {
        if (selection.quote.isNotBlank()) Text(selection.quote, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReaderCompactActionMenu(listOf(
            ReaderCompactAction("note", "笔记", kind == AnnotationKind.NOTE) { onKind(AnnotationKind.NOTE) },
            ReaderCompactAction("highlight", "标记", kind == AnnotationKind.HIGHLIGHT) { onKind(AnnotationKind.HIGHLIGHT) }
        ) + listOfNotNull(if (!selection.raster) ReaderCompactAction("underline", "划线", kind == AnnotationKind.UNDERLINE) {
            onKind(AnnotationKind.UNDERLINE)
        } else null), moreTag = "reader_annotation_kind_more")
        if (kind == AnnotationKind.UNDERLINE) ReaderUnderlineStylePicker(underlineStyle, onUnderlineStyle)
        if (kind != AnnotationKind.UNDERLINE) ReaderColorPicker(color, onColor)
        if (kind == AnnotationKind.NOTE) OutlinedTextField(note, onNote, Modifier.fillMaxWidth().testTag("reader_note_input"),
            label = { Text("写下想法") }, minLines = 2, maxLines = 4)
        ReaderCompactActionMenu(listOf(
            ReaderCompactAction("save", "保存", enabled = kind in listOf(AnnotationKind.HIGHLIGHT, AnnotationKind.UNDERLINE) || note.isNotBlank(), onClick = onSave),
            ReaderCompactAction("cancel", "取消", onClick = onDismiss)
        ), moreTag = "reader_annotation_actions_more")
    }
}

// Dialogs have their own Android window; brightness should remain the same while using reader controls.
@Composable
internal fun ReaderDialogAppearance(settings: ReaderSettings) {
    val view = LocalView.current
    val window = remember(view) {
        (view as? DialogWindowProvider)?.window ?: generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    }
    SideEffect {
        window?.let { owner ->
            WindowCompat.getInsetsController(owner, owner.decorView).apply {
                isAppearanceLightStatusBars = !settings.nightMode
                isAppearanceLightNavigationBars = !settings.nightMode
            }
        }
    }
    DisposableEffect(window, settings.brightness) {
        val previous = window?.attributes?.screenBrightness
        window?.let { owner -> owner.attributes = owner.attributes.apply {
            screenBrightness = if (settings.brightness < 0f) -1f else settings.brightness.coerceIn(0.01f, 1f)
        } }
        onDispose { if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

@Composable
internal fun ReaderColorPicker(selected: Long, onColor: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ReaderMarkColors.forEach { (name, value) ->
            Box(Modifier.size(34.dp).background(Color(value), CircleShape)
                .border(if (selected == value) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .clickable { onColor(value) }.semantics { contentDescription = "$name 标记颜色" })
        }
    }
}

private data class ReaderSearchHit(val result: ReaderSearchResult, val matchedText: String)

@Composable
internal fun ReaderSearchPanel(book: Book, document: ReaderDocument?, raster: Boolean, query: String,
    onQuery: (String) -> Unit,
    onReplaceMatch: ((ReaderSearchResult, String, String) -> Unit)? = null,
    onReplaceAll: ((String, String) -> Unit)? = null,
    replacing: Boolean = false,
    initialReplacement: String = "",
    onResult: (ReaderSearchResult) -> Unit) {
    var results by remember(book.id) { mutableStateOf<List<ReaderSearchHit>>(emptyList()) }
    var searching by remember(book.id) { mutableStateOf(false) }
    var searchFailure by remember(book.id) { mutableStateOf<String?>(null) }
    var replacement by remember(book.id) { mutableStateOf(initialReplacement) }
    var editing by remember(book.id, book.contentVersion) { mutableStateOf<ReaderSearchHit?>(null) }
    var editText by remember(editing) { mutableStateOf(editing?.matchedText.orEmpty()) }
    var confirmingAll by remember(book.id, book.contentVersion) { mutableStateOf(false) }
    val editable = book.mode == LibraryMode.NOVEL && !raster && !book.cloudOnly &&
        book.format in setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI)
    val canReplace = editable && (onReplaceMatch != null || onReplaceAll != null)
    LaunchedEffect(book.id, book.contentVersion, book.chapters, query, document?.readText, document?.text, document?.pageText) {
        results = emptyList()
        searchFailure = null
        if (query.isBlank() || document == null) { searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(220)
            val needle = query.trim()
            results = withContext(Dispatchers.IO) {
                val matches = mutableListOf<ReaderSearchHit>()
                val work = currentCoroutineContext()
                fun search(text: String, chapter: Int, page: Int) {
                    var cursor = 0
                    while (cursor < text.length && matches.size < 100) {
                        work.ensureActive()
                        val found = text.indexOf(needle, cursor, ignoreCase = true)
                        if (found < 0) break
                        val result = ReaderSearchResult(chapter, page, found,
                            text.substring((found - 35).coerceAtLeast(0), (found + needle.length + 65).coerceAtMost(text.length)).replace('\n', ' '))
                        matches += ReaderSearchHit(result, text.substring(found, found + needle.length))
                        cursor = found + needle.length
                    }
                }
                if (raster) {
                    document.pageText.forEachIndexed { chapter, pages ->
                        pages.forEachIndexed { page, text -> search(text, chapter, page) }
                    }
                } else {
                    for (index in book.chapters.indices) {
                        work.ensureActive()
                        if (matches.size == 100) break
                        search(document.readText?.invoke(index) ?: document.text.getOrNull(index).orEmpty(), index, 0)
                    }
                }
                matches
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { searchFailure = error.message ?: "无法读取书籍正文" }
          finally { searching = false }
    }
    ReaderPanelTitle("全文搜索与替换", if (raster) "搜索已导入的文字层" else "搜索整本书，点击结果返回原文")
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("reader_search_query"), singleLine = true,
        enabled = !replacing, placeholder = { Text("输入关键词") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, enabled = !replacing) { Icon(Icons.Outlined.Close, "清空") } })
    if (canReplace) {
        OutlinedTextField(replacement, { replacement = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("reader_search_replacement"), enabled = !replacing, singleLine = true,
            label = { Text("替换为") }, placeholder = { Text("留空可删除匹配文字") })
        if (onReplaceAll != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirmingAll = true },
                enabled = !replacing && !searching && query.isNotBlank() && results.isNotEmpty(),
                modifier = Modifier.testTag("reader_search_replace_all")) { Text("全部替换") }
        }
    }
    val hasText = document?.let { if (raster) it.pageText.any { pages -> pages.any(String::isNotBlank) }
        else it.readText != null || it.text.any(String::isNotBlank) } ?: false
    Text(when {
        replacing -> "正在保存替换…"
        document == null -> "正在读取文字…"
        searchFailure != null -> "搜索失败：$searchFailure"
        !hasText -> "没有可搜索的文字。漫画按原页阅读，导入时不提取文字；图片和扫描 PDF 需先识别文字。"
        query.isBlank() -> "输入关键词开始搜索"
        searching -> "搜索中…"
        results.size == 100 -> if (canReplace && onReplaceAll != null) "显示前 100 个结果；全部替换会处理整本书的所有匹配"
            else "显示前 100 个结果，请缩小关键词范围"
        else -> "找到 ${results.size} 处结果"
    }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(24.dp, 12.dp))
    val matchColor = MaterialTheme.colorScheme.primaryContainer
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(results, key = { "${it.result.chapter}:${it.result.page}:${it.result.offset}" }) { hit ->
            val result = hit.result
            val tag = "${result.chapter}_${result.page}_${result.offset}"
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("reader_search_result_$tag").clickable(enabled = !replacing) { onResult(result) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${book.chapters.getOrNull(result.chapter)?.title.orEmpty()}${if (raster) " · 第 ${result.page + 1} 页" else ""}",
                        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text(readerSearchHighlight(result.snippet, query.trim(), matchColor), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (editable && onReplaceMatch != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = hit }, enabled = !replacing && !searching,
                            modifier = Modifier.testTag("reader_search_edit_$tag")) { Text("编辑") }
                        TextButton(onClick = { onReplaceMatch(result, hit.matchedText, replacement) }, enabled = !replacing && !searching,
                            modifier = Modifier.testTag("reader_search_replace_$tag")) { Text("替换") }
                    }
                }
            }
        }
    }
    editing?.let { hit ->
        AlertDialog(onDismissRequest = { if (!replacing) editing = null }, title = { Text("编辑此处文字") },
            text = { OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth().testTag("reader_search_edit_text"),
                enabled = !replacing, minLines = 2, maxLines = 6, label = { Text("修改后文字（留空删除）") }) },
            confirmButton = { TextButton(onClick = {
                onReplaceMatch?.invoke(hit.result, hit.matchedText, editText)
                editing = null
            }, enabled = !replacing, modifier = Modifier.testTag("reader_search_edit_confirm")) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !replacing) { Text("取消") } })
    }
    if (confirmingAll && canReplace && onReplaceAll != null) AlertDialog(onDismissRequest = { confirmingAll = false },
        title = { Text("替换整本书中的所有匹配？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("查找：${query.trim()}")
            Text(if (replacement.isEmpty()) "替换为：删除匹配文字" else "替换为：$replacement")
            Text("忽略大小写，按普通文字匹配。处理整本书所有匹配，包括未显示的结果。")
        } },
        confirmButton = { TextButton(onClick = { confirmingAll = false; onReplaceAll(query.trim(), replacement) },
            enabled = !replacing, modifier = Modifier.testTag("reader_search_replace_all_confirm")) { Text("全部替换") } },
        dismissButton = { TextButton(onClick = { confirmingAll = false }, enabled = !replacing) { Text("取消") } })
}

@Composable
internal fun ReaderAnnotationsPanel(
    book: Book, raster: Boolean, annotations: List<Annotation>, kind: AnnotationKind?, onKind: (AnnotationKind?) -> Unit,
    query: String, onQuery: (String) -> Unit, onJump: (Annotation) -> Unit, onDelete: (String) -> Unit
) {
    ReaderPanelTitle("书签与笔记", "彩色摘录随阅读位置保存")
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true,
        placeholder = { Text("搜索摘录、笔记或章节") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
    AnnotationFilterRow(kind, onKind, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), tagPrefix = "reader_annotation_filter")
    val matches = annotations.filter { item -> item.bookId == book.id && (kind == null || kind == item.kind) &&
        (query.isBlank() || "${item.quote} ${item.note} ${book.chapters.getOrNull(item.chapterIndex)?.title}".contains(query.trim(), ignoreCase = true))
    }.sortedByDescending { it.createdAt }
    LazyColumn(Modifier.fillMaxWidth().height(360.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (matches.isEmpty()) item { Text("暂无匹配记录。长按正文可添加笔记或彩色标记。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(matches, key = { it.id }) { item ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onJump(item) }) {
                Row(Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.color?.let { Box(Modifier.size(12.dp).background(Color(it), CircleShape)) }
                            Text((book.chapters.getOrNull(item.chapterIndex)?.title ?: "第 ${item.chapterIndex + 1} 章") +
                                if (item.imagePath != null) " · 正文插图" else if (raster) " · 第 ${item.pageIndex + 1} 页" else "", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
                        if (item.quote.isNotBlank()) Text(item.quote, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        if (item.note.isNotBlank()) Text(item.note, fontSize = 14.sp)
                        if (item.quote.isBlank() && item.note.isBlank()) Text(if (item.kind == AnnotationKind.BOOKMARK) "页面书签" else "页面彩色标记", fontSize = 14.sp)
                    }
                    IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除记录", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ReaderContentsPanel(book: Book, chapter: Int, page: Int, settings: ReaderSettings,
    onClose: () -> Unit, onChapterRules: (() -> Unit)? = null,
    onGenerateSpeech: ((Set<Int>) -> Unit)? = null, speechGenerationBusy: Boolean = false,
    cachedSpeechChapters: Set<Int> = emptySet(), speechGeneration: AiSpeechBatchProgress? = null,
    speechBookComplete: Boolean = false, onCancelSpeech: (() -> Unit)? = null,
    otherCachedSpeechChapters: Set<Int> = emptySet(), onJump: (Int, Int) -> Unit) {
    val entries = remember(book.chapters) { readerContentsEntries(book.chapters) }
    val current = remember(entries, chapter, page) { readerCurrentContentsEntry(entries, chapter, page) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val sections = entries.size - book.chapters.size
    val colors = MaterialTheme.colorScheme
    var selectingSpeech by remember(book.id) { mutableStateOf(false) }
    var speechSelection by remember(book.id) { mutableStateOf<Set<Int>>(emptySet()) }
    val canGenerateSpeech = book.mode == LibraryMode.NOVEL && !book.cloudOnly && onGenerateSpeech != null
    val generation = speechGeneration?.takeIf { it.bookId == book.id && it.running }
    fun toggleSpeechChapter(index: Int) {
        speechSelection = if (index in speechSelection) speechSelection - index else speechSelection + index
    }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ReaderDialogAppearance(settings)
        Surface(Modifier.fillMaxSize().testTag("reader_contents_fullscreen"), color = colors.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("reader_contents_close")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭目录")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("目录", style = MaterialTheme.typography.titleLarge)
                        Text(book.title, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${book.chapters.size} 章" + if (sections > 0) " · $sections 小节" else "",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp))
                    onChapterRules?.let { callback ->
                        TextButton(onClick = callback, modifier = Modifier.testTag("reader_contents_rules")) { Text("规则") }
                    }
                    if (canGenerateSpeech) IconButton(onClick = {
                        selectingSpeech = true; speechSelection = emptySet()
                    }, enabled = !speechGenerationBusy, modifier = Modifier.testTag("reader_contents_generate_speech")) {
                        ReaderSpeechStatusIcon(speechBookComplete,
                            if (speechBookComplete) "全书当前音色语音已缓存" else "批量生成 AI 语音",
                            Modifier.testTag("reader_contents_book_speech_status"))
                    }
                }
                HorizontalDivider()
                if (generation != null && onCancelSpeech != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancelSpeech, modifier = Modifier.testTag("reader_contents_stop_speech"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("中止本书转化", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (selectingSpeech && canGenerateSpeech) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("reader_contents_speech_actions"), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = {
                        speechSelection = if (speechSelection.size == book.chapters.size) emptySet() else book.chapters.indices.toSet()
                    }, enabled = !speechGenerationBusy, modifier = Modifier.testTag("reader_contents_speech_select_all"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (speechSelection.size == book.chapters.size) "全不选" else "全选", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { onGenerateSpeech?.invoke(speechSelection.toSet()) },
                        enabled = speechSelection.isNotEmpty() && !speechGenerationBusy,
                        modifier = Modifier.testTag("reader_contents_speech_generate"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("已选 ${speechSelection.size} 章 · 生成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { selectingSpeech = false; speechSelection = emptySet() },
                        modifier = Modifier.testTag("reader_contents_speech_cancel"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("reader_contents_list"), state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(entries, key = { _, item -> item.key }, contentType = { _, _ -> "chapter" }) { index, item ->
                        val selected = index == current
                        Row(Modifier.fillMaxWidth().testTag("reader_contents_entry_${item.key}")
                            .clickable(enabled = !selectingSpeech || !speechGenerationBusy) {
                                if (selectingSpeech && canGenerateSpeech) toggleSpeechChapter(item.chapter)
                                else onJump(item.chapter, item.startPage)
                            }
                            .padding(start = 24.dp + 16.dp * item.depth, end = 24.dp, top = 16.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (selectingSpeech && canGenerateSpeech && item.depth == 0) Checkbox(item.chapter in speechSelection,
                                { toggleSpeechChapter(item.chapter) }, enabled = !speechGenerationBusy,
                                modifier = Modifier.testTag("reader_contents_select_${item.chapter}"))
                            Text(if (item.depth == 0) "${item.chapter + 1}".padStart(2, '0') else "·",
                                Modifier.width(32.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
                            Text(item.title, Modifier.weight(1f), color = if (selected) colors.primary else colors.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.depth == 0) {
                                val chapterProgress = generation?.chapterProgress?.get(item.chapter) ?: generation?.takeIf {
                                    item.chapter == it.chapterIndex
                                }?.let { com.eyue.reader.data.AiSpeechChapterProgress(it.chapterCompleted, it.chapterTotal) }
                                if (item.chapter in cachedSpeechChapters) ReaderSpeechStatusIcon(true, "本章当前音色语音已缓存",
                                    Modifier.padding(horizontal = 6.dp).size(22.dp).testTag("reader_contents_cached_speech_${item.chapter}"))
                                else if (generation != null && chapterProgress != null &&
                                    item.chapter !in generation.completedChapters) Column(Modifier.width(58.dp).padding(horizontal = 4.dp)
                                    .testTag("reader_contents_speech_progress_${item.chapter}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                    val fraction = chapterProgress.total.takeIf { it > 0 }?.let {
                                        (chapterProgress.completed.toFloat() / it).coerceIn(0f, 1f)
                                    }
                                    Text(fraction?.let { "${(it * 100).toInt()}%" } ?: "准备中", fontSize = 11.sp, color = colors.primary)
                                    Box(Modifier.fillMaxWidth().height(2.dp).testTag("reader_contents_speech_progress_bar_${item.chapter}")) {
                                        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxSize())
                                        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize())
                                    }
                                    Icon(Icons.Outlined.Headphones, "本章 AI 语音转化中", Modifier.padding(top = 3.dp).size(12.dp)
                                        .testTag("reader_contents_generating_speech_icon_${item.chapter}"), tint = colors.primary)
                                } else if (generation != null && item.chapter in generation.selectedChapters &&
                                    item.chapter !in generation.completedChapters) Text("等待", Modifier.padding(horizontal = 6.dp)
                                    .testTag("reader_contents_speech_waiting_${item.chapter}"), fontSize = 11.sp, color = colors.onSurfaceVariant)
                                else if (item.chapter in otherCachedSpeechChapters) Column(Modifier.padding(horizontal = 6.dp)
                                    .testTag("reader_contents_other_cached_speech_${item.chapter}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.Headphones, "本章其他配置语音已缓存", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                                    Text("其他配置", fontSize = 10.sp, color = colors.onSurfaceVariant)
                                }
                            }
                            if (selected) Text("在读", fontSize = 11.sp, color = colors.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderPanelTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReaderSettingLabel(label: String, value: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        if (value.isNotEmpty()) Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }
}

@Composable
private fun ReaderToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

internal fun readerSearchHighlight(text: String, query: String, color: Color): AnnotatedString {
    val result = AnnotatedString.Builder(text)
    if (query.isNotBlank()) {
        var from = 0
        while (from < text.length) {
            val found = text.indexOf(query, from, ignoreCase = true)
            if (found < 0) break
            result.addStyle(SpanStyle(background = color), found, found + query.length)
            from = found + query.length
        }
    }
    return result.toAnnotatedString()
}
