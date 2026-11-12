package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.eyue.reader.data.*
import com.eyue.reader.data.cloud.CloudOperationProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ShelfCloudAction {
    UPLOAD, DOWNLOAD, CANCEL;

    val label: String get() = when (this) {
        UPLOAD -> "上传或同步"
        DOWNLOAD -> "下载"
        CANCEL -> "中断传输"
    }
}

@Composable fun ReadingHome(books: List<Book>, readingSeconds: Long, onOpen: (Book) -> Unit, onShelf: () -> Unit, onImport: () -> Unit,
    onCloud: ((Book) -> Unit)? = null, cloudBusy: Boolean = false, cloudBookId: String? = null,
    cloudTaskLabel: String = "", cloudFraction: Float? = null, onCancelCloud: ((Book) -> Unit)? = null,
    cloudCanCancel: Boolean = false, cloudTransfers: Map<String, CloudTransferProgress> = emptyMap(),
    cloudDirtyBookIds: Set<String> = emptySet(), showRecentReading: Boolean = true) {
    fun transfer(book: Book) = cloudTransfers[book.id] ?: if (cloudBookId == book.id)
        CloudTransferProgress(cloudTaskLabel, cloudFraction, cloudCanCancel) else null
    val xingcai = LocalAppThemeStyle.current == AppThemeStyle.XINGCAI
    val history = books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }
    val featured = history.firstOrNull() ?: books.firstOrNull()
    LazyColumn(Modifier.fillMaxSize().testTag("reading_home"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { if (featured != null) FeaturedBook(featured, history.isNotEmpty(), { onOpen(featured) },
            onCloud?.let { { it(featured) } }, cloudBusy || transfer(featured) != null, transfer(featured) != null,
            transfer(featured)?.stage.orEmpty(), transfer(featured)?.fraction,
            onCancelCloud?.let { { it(featured) } }, transfer(featured)?.cancellable == true, featured.id in cloudDirtyBookIds) else EmptyLibrary(onImport) }
        item {
            val stats = if (xingcai) Modifier.fillMaxWidth().padding(vertical = 12.dp)
                else Modifier.fillMaxWidth().clip(appShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 17.dp)
            Row(stats, verticalAlignment = Alignment.CenterVertically) {
                StatCell("${readingSeconds / 60}", "累计分钟", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(28.dp).background(Line))
                StatCell("${books.size}", "书架藏书", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(28.dp).background(Line))
                StatCell("${books.count { it.progress >= .999f }}", "已读完", Modifier.weight(1f))
            }
        }
        if (showRecentReading) item {
            Row(Modifier.fillMaxWidth().testTag("recent_reading"), verticalAlignment = Alignment.CenterVertically) {
                Text("最近阅读", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("  ${history.size}", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShelf, contentPadding = PaddingValues(0.dp)) {
                    Text("查看书架", fontSize = 12.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(15.dp))
                }
            }
        }
        if (showRecentReading && history.isEmpty()) item {
            Column(Modifier.fillMaxWidth().clip(appShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.History, null, tint = Muted, modifier = Modifier.size(30.dp))
                Text("暂无阅读记录", fontSize = 14.sp, modifier = Modifier.padding(top = 11.dp))
            }
        }
        items(if (showRecentReading) history.take(6) else emptyList(), key = { it.id }) { book -> HistoryRow(book, { onOpen(book) },
            onCloud?.let { { it(book) } }, cloudBusy || transfer(book) != null, transfer(book) != null,
            transfer(book)?.stage.orEmpty(), transfer(book)?.fraction,
            onCancelCloud?.let { { it(book) } }, transfer(book)?.cancellable == true, book.id in cloudDirtyBookIds) }
    }
}

@Composable private fun FeaturedBook(book: Book, resume: Boolean, onOpen: () -> Unit, onCloud: (() -> Unit)?,
    cloudBusy: Boolean, cloudInProgress: Boolean, cloudTaskLabel: String, cloudFraction: Float?,
    onCancelCloud: (() -> Unit)?, cloudCanCancel: Boolean, cloudDirty: Boolean) {
    if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) {
        XingcaiFeaturedBook(book, resume, onOpen, onCloud, cloudBusy, cloudInProgress, cloudTaskLabel, cloudFraction,
            onCancelCloud, cloudCanCancel, cloudDirty)
        return
    }
    Column(Modifier.fillMaxWidth().clip(appShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp)) {
        Row(Modifier.clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(book.title, fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.displayAuthor, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text(if (resume) chapterLabel(book) else if (book.isDemo) "原创示例 · 即刻试读" else "${book.format.name} · ${book.chapters.size} 章",
                    fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            }
            BookCover(book, Modifier.width(90.dp).height(130.dp), onCloud, cloudBusy, cloudInProgress, cloudTaskLabel, cloudFraction,
                onCancelCloud, cloudCanCancel, cloudDirty)
        }
        Spacer(Modifier.height(19.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("已读 ${(book.progress * 100).toInt()}%", fontSize = 11.sp, color = Muted)
                LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp),
                    color = Olive, trackColor = Line)
            }
            Button(onClick = { if (book.cloudOnly) onCloud?.invoke() else onOpen() },
                enabled = !book.cloudOnly || onCloud != null && !cloudBusy,
                shape = appShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)) {
                Text(if (book.cloudOnly) "下载书籍" else if (resume) "继续阅读" else "开始阅读", fontSize = 12.sp)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 8.dp).size(15.dp))
            }
        }
    }
}

@Composable private fun StatCell(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) 19.sp else 22.sp,
            fontFamily = if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) FontFamily.Default else FontFamily.Serif, color = Olive)
        Text(label, fontSize = if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) 12.sp else 10.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun HistoryRow(book: Book, onOpen: () -> Unit, onCloud: (() -> Unit)? = null, cloudBusy: Boolean = false,
    cloudInProgress: Boolean = false, cloudTaskLabel: String = "", cloudFraction: Float? = null,
    onCancelCloud: (() -> Unit)? = null, cloudCanCancel: Boolean = false, cloudDirty: Boolean = false,
    onRename: (() -> Unit)? = null, shelfLayout: Boolean = false, onMore: (() -> Unit)? = null) {
    val xingcai = shelfLayout || LocalAppThemeStyle.current == AppThemeStyle.XINGCAI
    val longPress: (() -> Unit)? = if (shelfLayout) ({ onMore?.invoke() }) else onRename
    Row(Modifier.fillMaxWidth().testTag(if (shelfLayout) "shelf_list_row_${book.id}" else "history_row_${book.id}")
        .combinedClickable(onClick = onOpen,
            onLongClick = longPress), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.width(if (xingcai) 56.dp else 53.dp).height(if (xingcai) 78.dp else 74.dp), onCloud, cloudBusy, cloudInProgress, cloudTaskLabel, cloudFraction,
            onCancelCloud, cloudCanCancel, cloudDirty)
        Column(Modifier.weight(1f).then(if (xingcai) Modifier.height(80.dp) else Modifier).padding(start = 15.dp)) {
            Text(book.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 2, minLines = if (shelfLayout) 2 else 1, softWrap = true, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("shelf_book_title_${book.id}").then(if (onRename == null && !shelfLayout) Modifier else
                    Modifier.combinedClickable(onClick = onOpen,
                        onLongClick = onRename ?: longPress)))
            Text(listOf(book.displayAuthor, chapterLabel(book)).filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (xingcai) {
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    XingcaiProgressTrack(book.progress, Modifier.weight(1f))
                    Text("${(book.progress * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = Olive, modifier = Modifier.padding(start = 10.dp))
                }
            } else Text("${relativeTime(book.lastReadAt)} · 第 ${book.chapterIndex + 1}/${book.chapters.size} 章 · ${(book.progress * 100).toInt()}%",
                color = Olive, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (shelfLayout) {
            if (onMore != null) ShelfMoreButton(book, onClick = onMore)
        } else Icon(Icons.Outlined.ChevronRight, "打开书籍", tint = Muted, modifier = Modifier.size(20.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun Bookshelf(books: List<Book>, query: String, onQuery: (String) -> Unit, onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit, onImport: () -> Unit, groups: List<ShelfGroup> = emptyList(),
    onCreateGroup: (String) -> Unit = {}, onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {}, onMoveBook: (String, String?) -> Unit = { _, _ -> }, pending: PendingImport? = null,
    onCloud: ((Book) -> Unit)? = null, cloudBusy: Boolean = false, cloudBookId: String? = null,
    cloudTaskLabel: String = "", cloudFraction: Float? = null, onCancelCloud: ((Book) -> Unit)? = null,
    onDeleteCloud: ((Book) -> Unit)? = null, cloudCanCancel: Boolean = false,
    onCloudBatch: ((List<Book>) -> Unit)? = null, onClearAiSpeechCache: ((Book) -> Unit)? = null,
    onRestoreCloudBookInfo: (() -> Unit)? = null, onGenerateSpeech: ((String) -> Unit)? = null,
    onVoiceRoles: ((String) -> Unit)? = null, speechCacheRevision: Int = 0,
    onRemoveWithCloud: ((Book, Boolean) -> Unit)? = null, onClearSpeechWithCloud: ((Book, Boolean) -> Unit)? = null,
    cloudConfigured: Boolean = false, meteredNetwork: Boolean = false,
    cloudTransfers: Map<String, CloudTransferProgress> = emptyMap(),
    cloudDirtyBookIds: Set<String> = emptySet(),
    pendingImports: List<PendingImport> = listOfNotNull(pending),
    onRemoveBatch: ((List<Book>, Boolean) -> Unit)? = null,
    restoreProgress: CloudOperationProgress? = null,
    onRenameBook: ((String, String) -> Boolean)? = null,
    onRemovalCloudBytes: (suspend (List<Book>) -> Map<String, Long>)? = null,
    onRemoveOriginal: ((List<Book>, Set<String>, Boolean) -> Unit)? = null,
    libraryMode: LibraryMode = books.firstOrNull()?.mode ?: groups.firstOrNull()?.mode ?: LibraryMode.NOVEL,
    onCreateSubgroup: ((String, String) -> Unit)? = null,
    onMoveBooks: ((List<String>, String?) -> Unit)? = null,
    onEditTags: ((String, List<String>) -> Unit)? = null,
    onEditCover: ((String, Uri) -> Unit)? = null,
    onReorderGroup: (String, String) -> Unit = { _, _ -> },
    searchVisible: Boolean = false, searchRequest: Int = 0, onCloseSearch: () -> Unit = {}) {
    fun transfer(book: Book) = cloudTransfers[book.id] ?: if (cloudBookId == book.id)
        CloudTransferProgress(cloudTaskLabel, cloudFraction, cloudCanCancel) else null
    var selectingBackup by remember { mutableStateOf(false) }
    var backupSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chooseCloudBatch by remember { mutableStateOf(false) }
    fun closeBatch() { selectingBackup = false; backupSelection = emptySet(); chooseCloudBatch = false }
    fun selectBackup(book: Book) { backupSelection = if (book.id in backupSelection) backupSelection - book.id else backupSelection + book.id }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Book?>(null) }
    var deletingBatch by remember { mutableStateOf<List<Book>?>(null) }
    var deletingCloud by remember { mutableStateOf<Book?>(null) }
    var clearingAiSpeech by remember { mutableStateOf<Book?>(null) }
    var details by remember { mutableStateOf<Book?>(null) }
    var locating by remember { mutableStateOf<Book?>(null) }
    var actionBookId by remember { mutableStateOf<String?>(null) }
    val shelfContext = LocalContext.current
    val browsePreferences = remember(shelfContext, libraryMode) { ShelfBrowsePreferences(shelfContext, libraryMode) }
    var storedBrowse by remember(browsePreferences) { mutableStateOf(browsePreferences.read()) }
    var hiddenRevision by remember { mutableIntStateOf(0) }
    var hiddenScope by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var editingTags by remember { mutableStateOf<String?>(null) }
    var coverBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverBookId
        coverBookId = null
        if (uri != null && id != null) {
            retainBookAccess(shelfContext, uri)
            onEditCover?.invoke(id, uri)
        }
    }
    var managingGroups by remember { mutableStateOf(false) }
    var groupEditor by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ShelfGroup?>(null) }
    var newParentId by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var deletingGroup by remember { mutableStateOf<ShelfGroup?>(null) }
    var editingChip by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<List<Book>?>(null) }
    var renaming by remember { mutableStateOf<Book?>(null) }
    var renamedTitle by remember { mutableStateOf("") }
    fun rename(book: Book) { renaming = book; renamedTitle = book.title }
    fun createGroup(parentId: String?) {
        editingGroup = null; newParentId = parentId; groupName = ""; groupEditor = true
    }
    fun editGroup(group: ShelfGroup) {
        editingGroup = group; newParentId = null; groupName = group.name; groupEditor = true
    }
    val groupRows = remember(groups) { shelfGroupRows(groups) }
    val tags = remember(books) { books.flatMap { it.tags }.distinct().sorted() }
    fun editHiddenTags(id: String?) {
        val title = when (id) {
            null -> "全局隐藏标签"
            UNGROUPED_FILTER -> "未分组 · 隐藏标签"
            else -> groups.firstOrNull { it.id == id }?.let { "${shelfGroupLabel(groups, it)} · 隐藏标签" } ?: return
        }
        hiddenScope = id to title
    }
    val hiddenTags = remember(browsePreferences, groups, hiddenRevision) { hiddenShelfTags(groups, browsePreferences::hidden) }
    val groupBooks = remember(books, hiddenTags) { books.filter { book -> book.tags.none { it in hiddenTags[book.groupId].orEmpty() } } }
    val groupCounts = remember(groupBooks, groups) {
        val parents = groups.associate { it.id to it.parentId }
        val counts = groupBooks.groupingBy { it.groupId }.eachCount().toMutableMap()
        groupBooks.forEach { book -> parents[book.groupId]?.let { parent -> counts[parent] = (counts[parent] ?: 0) + 1 } }
        counts
    }
    val browse = remember(storedBrowse, groups, tags) { storedBrowse.available(groups, tags.toSet()) }
    fun selectBrowse(next: ShelfBrowseFilter) {
        storedBrowse = next
        browsePreferences.save(next)
        editingChip = null
    }
    val subgroupChoices = remember(groups, browse.groupId) { groups.filter { it.parentId != null && it.parentId == browse.groupId } }
    val childIds = remember(subgroupChoices) { subgroupChoices.mapTo(mutableSetOf()) { it.id } }
    val activeTag = browse.tag.takeIf { browse.tagMode }
    val displayed = remember(books, groupBooks, query, searchVisible, browse, childIds, activeTag, filter, sort) {
        val matched = (if (browse.tagMode) books else groupBooks).filter {
            (!searchVisible || it.title.contains(query, true) || it.displayAuthor.contains(query, true)) &&
                browse.includesGroup(it, childIds) && (activeTag == null || activeTag in it.tags) &&
                (filter == 0 || filter == 1 && it.lastReadAt > 0 && it.progress < .999f || filter == 2 && it.progress >= .999f)
        }
        when (sort) {
            1 -> matched.sortedBy { it.title }
            2 -> matched.sortedByDescending { it.progress }
            else -> matched.sortedByDescending { maxOf(it.addedAt, it.lastReadAt) }
        }
    }
    val gridState = rememberLazyGridState()
    val bookListState = rememberLazyListState()
    LaunchedEffect(browse, query, filter, sort, grid) {
        if (grid) gridState.requestScrollToItem(0) else bookListState.requestScrollToItem(0)
    }
    val selected = remember(books, backupSelection) { books.filter { it.id in backupSelection } }
    val batchActions = selected.groupBy { book ->
        val activeTransfer = transfer(book)
        when {
            activeTransfer?.cancellable == true && onCancelCloud != null -> ShelfCloudAction.CANCEL
            activeTransfer != null || cloudBusy || onCloudBatch == null -> null
            book.cloudOnly -> ShelfCloudAction.DOWNLOAD
            else -> ShelfCloudAction.UPLOAD
        }
    }
    fun runBatch(action: ShelfCloudAction) {
        val targets = batchActions[action].orEmpty()
        if (targets.isEmpty()) return
        if (action == ShelfCloudAction.CANCEL) onCancelCloud?.let { cancel -> targets.forEach(cancel) }
        else onCloudBatch?.invoke(targets)
        closeBatch()
    }
    BackHandler(enabled = selectingBackup || searchVisible || editingChip != null) {
        when {
            editingChip != null -> editingChip = null
            selectingBackup -> closeBatch()
            else -> onCloseSearch()
        }
    }
    Column(Modifier.fillMaxSize().testTag("bookshelf")) {
        if (searchVisible) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            if (searchRequest > 0) LaunchedEffect(searchRequest) { focus.requestFocus(); keyboard?.show() }
            OutlinedTextField(query, onQuery, placeholder = { Text("搜索书名或作者", fontSize = 13.sp, color = Muted) }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                trailingIcon = { IconButton(onClick = { keyboard?.hide(); onCloseSearch() }) { Icon(Icons.Outlined.Close, "关闭搜索") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).focusRequester(focus).testTag("shelf_search"),
                shape = appShape(13.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Olive,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface))
        }
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val rowPadding = PaddingValues(start = 24.dp, end = 8.dp)
            val rowSpacing = Arrangement.spacedBy(8.dp)
            if (browse.tagMode) LazyRow(Modifier.weight(1f).testTag("shelf_tags"), contentPadding = rowPadding,
                horizontalArrangement = rowSpacing) {
                item { FilterChip(activeTag == null, { selectBrowse(browse.copy(tag = null)) }, { Text("全部标签", fontSize = 12.sp) }) }
                items(tags, key = { it }) { tag ->
                    FilterChip(activeTag == tag, { selectBrowse(browse.copy(tag = tag.takeUnless { it == activeTag })) },
                        { Text(tag, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 175.dp).testTag("shelf_tag_$tag"))
                }
            } else {
                val parents = remember(groups) { groups.filter { it.parentId == null } }
                val listState = rememberLazyListState()
                val drag = rememberShelfGroupDrag(listState, parents, horizontal = true,
                    onReorder = { id, target -> editingChip = null; onReorderGroup(id, target) },
                    onLongPress = { editingChip = it.takeUnless { id -> editingChip == id } })
                LazyRow(Modifier.weight(1f).then(drag.gesture), state = listState, contentPadding = rowPadding, horizontalArrangement = rowSpacing) {
                    item {
                        FilterChip(browse.groupId == null, { selectBrowse(browse.copy(groupId = null, subgroupIds = emptySet())) },
                            { Text("全部 ${groupBooks.size}", fontSize = 12.sp) }, modifier = Modifier.testTag("shelf_group_all"))
                    }
                    item(UNGROUPED_FILTER) {
                        ShelfGroupChip(null, "未分组 ${groupCounts[null] ?: 0}", browse.groupId == UNGROUPED_FILTER,
                            editingChip == UNGROUPED_FILTER, onSelect = {
                                if (drag.groupId == null) selectBrowse(browse.copy(groupId = UNGROUPED_FILTER, subgroupIds = emptySet()))
                            }, onEdit = { editingChip = UNGROUPED_FILTER }, onHideTags = { editHiddenTags(UNGROUPED_FILTER) },
                            onAddChild = null, onDelete = null)
                    }
                    items(parents, key = { it.id }) { group ->
                        ShelfGroupChip(group, "${group.name} ${groupCounts[group.id] ?: 0}", browse.groupId == group.id,
                            editingChip == group.id, drag.item(group.id), onSelect = {
                                if (drag.groupId == null) selectBrowse(browse.copy(groupId = group.id, subgroupIds = emptySet()))
                            }, onEdit = { editingChip = group.id }, onHideTags = { editHiddenTags(group.id) },
                            onAddChild = onCreateSubgroup?.let { { createGroup(group.id) } }, onDelete = { deletingGroup = group })
                    }
                }
            }
            IconButton(onClick = { selectBrowse(browse.copy(tagMode = !browse.tagMode)) }, modifier = Modifier.size(40.dp).testTag("shelf_tag_mode")) {
                Icon(if (browse.tagMode) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.Label,
                    if (browse.tagMode) "分组" else "标签", Modifier.size(21.dp), tint = Muted)
            }
            IconButton(onClick = { editingChip = null; managingGroups = true }, modifier = Modifier.size(40.dp).testTag("shelf_manage_groups")) {
                Icon(Icons.Outlined.FolderOpen, "管理分组", Modifier.size(21.dp), tint = Muted)
            }
        }
        if (!browse.tagMode && subgroupChoices.isNotEmpty()) key(browse.groupId) {
            val listState = rememberLazyListState()
            val drag = rememberShelfGroupDrag(listState, subgroupChoices, horizontal = true,
                onReorder = { id, target -> editingChip = null; onReorderGroup(id, target) },
                onLongPress = { editingChip = it.takeUnless { id -> editingChip == id } })
            LazyRow(Modifier.fillMaxWidth().testTag("shelf_subgroups").then(drag.gesture), state = listState,
                contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(browse.subgroupIds.isEmpty(), { selectBrowse(browse.copy(subgroupIds = emptySet())) },
                        { Text("全部子组", fontSize = 12.sp) })
                }
                items(subgroupChoices, key = { it.id }) { group ->
                    val checked = group.id in browse.subgroupIds
                    ShelfGroupChip(group, group.name, checked, editingChip == group.id, drag.item(group.id),
                        onSelect = { if (drag.groupId == null) selectBrowse(browse.copy(subgroupIds =
                            if (checked) browse.subgroupIds - group.id else browse.subgroupIds + group.id)) },
                        onEdit = { editingChip = group.id }, onHideTags = { editHiddenTags(group.id) }, onAddChild = null,
                        onDelete = { deletingGroup = group })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp).testTag("shelf_status_filters"), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "在读", "读完").forEachIndexed { i, label ->
                Text(label, fontSize = 12.sp, color = if (filter == i) Olive else Muted,
                    fontWeight = if (filter == i) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(appShape(9.dp)).clickable { filter = i }.padding(end = 19.dp, top = 8.dp, bottom = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onCloudBatch != null || onRemoveBatch != null || onMoveBooks != null) {
                IconButton(onClick = { if (selectingBackup) closeBatch() else selectingBackup = true },
                    modifier = Modifier.size(34.dp).testTag("shelf_batch_manage")) {
                    Icon(Icons.Outlined.Checklist, if (selectingBackup) "收起批量管理" else "批量管理",
                        Modifier.size(20.dp), tint = if (selectingBackup) Olive else Muted)
                }
            }
            if (onRestoreCloudBookInfo != null) IconButton(enabled = !cloudBusy && restoreProgress == null, onClick = onRestoreCloudBookInfo,
                modifier = Modifier.size(34.dp).testTag("shelf_restore_cloud_info")) {
                Icon(Icons.Outlined.CloudDownload, "还原书籍信息", Modifier.size(20.dp), tint = Muted)
            }
            Box {
                IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Sort, "排序", Modifier.size(20.dp), tint = Muted) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("最近阅读 / 导入", "书名", "阅读进度").forEachIndexed { i, name ->
                        DropdownMenuItem(text = { Text(name, fontSize = 13.sp) }, onClick = { sort = i; sortMenu = false },
                            trailingIcon = { if (sort == i) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) })
                    }
                }
            }
            IconButton(onClick = { grid = !grid }, modifier = Modifier.size(34.dp)) {
                Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, "切换书架布局", Modifier.size(20.dp), tint = Muted)
            }
        }
        restoreProgress?.let { CloudRestoreProgressIndicator(it, "shelf_restore_progress",
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) }
        if (selectingBackup) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("shelf_backup_actions"), verticalAlignment = Alignment.CenterVertically) {
            val available = displayed.map { it.id }.toSet()
            val actions = ShelfCloudAction.entries.filter { it in batchActions }
            Checkbox(available.isNotEmpty() && backupSelection.containsAll(available), { checked ->
                backupSelection = if (checked) backupSelection + available else backupSelection - available
            }, modifier = Modifier.size(36.dp).testTag("shelf_backup_select_all"))
            Text("已选 ${selected.size} 本", Modifier.weight(1f), fontSize = 11.sp, color = Muted, maxLines = 1)
            if (onCloudBatch != null || onCancelCloud != null) IconButton(enabled = actions.isNotEmpty(), onClick = {
                if (actions.size == 1 && null !in batchActions) runBatch(actions.single()) else chooseCloudBatch = true
            }, modifier = Modifier.size(40.dp).testTag("shelf_batch_transfer")) {
                Icon(Icons.Outlined.Cloud, "批量云端操作", Modifier.size(21.dp), tint = if (actions.isEmpty()) Muted else Olive)
            }
            if (onMoveBooks != null) IconButton(enabled = selected.isNotEmpty(), onClick = { moving = selected },
                modifier = Modifier.size(40.dp).testTag("shelf_batch_move")) {
                Icon(Icons.Outlined.FolderOpen, "移动分组", Modifier.size(21.dp))
            }
            if (onRemoveBatch != null) IconButton(enabled = selected.isNotEmpty(), onClick = { deletingBatch = selected },
                modifier = Modifier.size(40.dp).testTag("shelf_batch_delete")) {
                Icon(Icons.Outlined.DeleteOutline, "批量删除", Modifier.size(21.dp),
                    tint = if (selected.isEmpty()) Muted else MaterialTheme.colorScheme.error)
            }
        }
        if (displayed.isEmpty() && pendingImports.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                if (books.isEmpty()) EmptyLibrary(onImport)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.SearchOff, null, Modifier.size(34.dp), tint = Muted)
                    Text("暂时没有匹配的书", color = Muted, modifier = Modifier.padding(top = 12.dp))
                }
            }
        } else if (grid) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverHeight = ((maxWidth - 88.dp) / 3f / .8f).coerceAtLeast(1.dp)
            LazyVerticalGrid(GridCells.Fixed(3), state = gridState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(pendingImports, key = { "import-${it.id}" }) { PendingShelfBook(it, grid = true, coverHeight = coverHeight) }
                items(displayed, key = { it.id }) { book ->
                    Column {
                        Box {
                            BookCover(book, Modifier.fillMaxWidth().height(coverHeight).testTag("shelf_book_cover_${book.id}")
                                .combinedClickable(
                                    onClick = { if (selectingBackup) selectBackup(book) else onOpen(book) },
                                    onLongClick = { if (!selectingBackup) actionBookId = book.id }),
                                onCloud?.takeUnless { selectingBackup }?.let { { it(book) } }, cloudBusy || transfer(book) != null, transfer(book) != null,
                                transfer(book)?.stage.orEmpty(), transfer(book)?.fraction,
                                onCancelCloud?.takeUnless { selectingBackup }?.let { { it(book) } },
                                transfer(book)?.cancellable == true, book.id in cloudDirtyBookIds)
                            if (selectingBackup || book.id !in LocalSourceChangeIds.current && book.id !in LocalSourceReloadIds.current) {
                                Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = appShape(5.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                                    if (selectingBackup) Checkbox(book.id in backupSelection, { selectBackup(book) },
                                        modifier = Modifier.testTag("backup_select_${book.id}"))
                                    else Text(if (book.isDemo) "示例" else book.format.name, fontSize = 9.sp, color = Olive, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                }
                            }
                            if (!selectingBackup) ShelfMoreButton(book, Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape),
                                onClick = { actionBookId = book.id })
                        }
                        Text(book.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,
                            minLines = 2, maxLines = 2, softWrap = true,
                            style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 11.dp)
                                .testTag("shelf_book_title_${book.id}").combinedClickable(
                                onClick = { if (selectingBackup) selectBackup(book) else onOpen(book) },
                                onLongClick = { if (!selectingBackup && onRenameBook != null) rename(book) }))
                        Text(book.displayAuthor, fontSize = 10.sp, color = Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).testTag("shelf_book_author_${book.id}"))
                        Text("第 ${book.chapterIndex + 1} / ${book.chapters.size} 章", fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = Olive,
                            modifier = Modifier.padding(top = 9.dp).testTag("shelf_book_chapter_${book.id}"))
                        Text("${(book.progress * 100).toInt()}%", fontSize = 10.sp, color = Olive,
                            modifier = Modifier.padding(top = 3.dp).testTag("shelf_book_percent_${book.id}"))
                        LinearProgressIndicator(progress = { book.progress }, color = Olive, trackColor = Line,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp))
                    }
                }
            }
            }
        } else {
            LazyColumn(state = bookListState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(pendingImports, key = { "import-${it.id}" }) { PendingShelfBook(it, grid = false) }
                items(displayed, key = { it.id }) { book ->
                    Column {
                        if (selectingBackup) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(book.id in backupSelection, { selectBackup(book) },
                                modifier = Modifier.testTag("backup_select_${book.id}"))
                            Text(book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HistoryRow(book, { if (selectingBackup) selectBackup(book) else onOpen(book) },
                            onCloud?.takeUnless { selectingBackup }?.let { { it(book) } }, cloudBusy || transfer(book) != null,
                            transfer(book) != null, transfer(book)?.stage.orEmpty(), transfer(book)?.fraction,
                            onCancelCloud?.takeUnless { selectingBackup }?.let { { it(book) } },
                            transfer(book)?.cancellable == true, book.id in cloudDirtyBookIds,
                            shelfLayout = true, onMore = if (!selectingBackup) { { actionBookId = book.id } } else null,
                            onRename = if (!selectingBackup && onRenameBook != null) { { rename(book) } } else null)
                    }
                }
            }
        }
    }
    books.firstOrNull { it.id == actionBookId }?.let { book ->
        ShelfBookActions(book, transfer(book), cloudConfigured, cloudBusy || transfer(book) != null,
            cloudDirty = book.id in cloudDirtyBookIds,
            onDismiss = { actionBookId = null },
            onDetails = { actionBookId = null; details = book },
            onLocation = { actionBookId = null; locating = book },
            onMove = { actionBookId = null; moving = listOf(book) },
            onEditTags = onEditTags?.let { { actionBookId = null; editingTags = book.id } },
            onEditCover = if (onEditCover != null && !book.cloudOnly) {
                { actionBookId = null; coverBookId = book.id; coverPicker.launch(arrayOf("image/*")) }
            } else null,
            onRename = onRenameBook?.let { { actionBookId = null; rename(book) } },
            onGenerateSpeech = if (book.hasSpeechText() && onGenerateSpeech != null) {
                { actionBookId = null; onGenerateSpeech(book.id) }
            } else null,
            onVoiceRoles = if (book.mode == LibraryMode.NOVEL && onVoiceRoles != null) {
                { actionBookId = null; onVoiceRoles(book.id) }
            } else null,
            onCloud = onCloud?.let { { actionBookId = null; it(book) } },
            onDeleteCloud = if (book.cloudEnabled && onDeleteCloud != null) {
                { actionBookId = null; deletingCloud = book }
            } else null,
            onClearSpeech = if (book.mode == LibraryMode.NOVEL && !book.cloudOnly &&
                (onClearAiSpeechCache != null || onClearSpeechWithCloud != null)) {
                { actionBookId = null; clearingAiSpeech = book }
            } else null,
            onDelete = { actionBookId = null; deleting = book })
    }
    books.firstOrNull { it.id == editingTags }?.let { book ->
        ShelfTagsDialog(book, tags, { onEditTags?.invoke(book.id, it) }, { editingTags = null })
    }
    hiddenScope?.let { (id, title) ->
        ShelfHiddenTagsDialog(title, tags, browsePreferences.hidden(id), onSave = { hidden ->
            browsePreferences.saveHidden(id, hidden)
            hiddenRevision++
        }, onDismiss = { hiddenScope = null })
    }
    if (chooseCloudBatch) AlertDialog(onDismissRequest = { chooseCloudBatch = false },
        modifier = Modifier.testTag("shelf_batch_cloud_choices"), title = { Text("选择云端操作") }, text = {
            Column {
                ShelfCloudAction.entries.forEach { action ->
                    val count = batchActions[action]?.size ?: 0
                    if (count > 0) TextButton(onClick = { runBatch(action) },
                        modifier = Modifier.fillMaxWidth().testTag("shelf_batch_cloud_${action.name.lowercase(Locale.ROOT)}")) {
                        Text("${action.label} $count 本")
                    }
                }
                batchActions[null]?.size?.let { Text("$it 本书暂时无法执行云端操作", color = Muted, fontSize = 12.sp) }
            }
        }, confirmButton = { TextButton(onClick = { chooseCloudBatch = false }) { Text("关闭") } })
    renaming?.let { book ->
        AlertDialog(onDismissRequest = { renaming = null },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f).testTag("shelf_rename_dialog"),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text("重命名书籍") }, text = {
                OutlinedTextField(renamedTitle, { renamedTitle = it }, label = { Text("书籍名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("shelf_rename_title"))
            }, confirmButton = {
                TextButton(enabled = renamedTitle.isNotBlank(), onClick = {
                    if (onRenameBook?.invoke(book.id, renamedTitle.trim()) == true) renaming = null
                }, modifier = Modifier.testTag("shelf_rename_save")) { Text("保存") }
            }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    deletingBatch?.let { selected ->
        BatchRemoveBooksDialog(selected, speechCacheRevision, cloudConfigured, meteredNetwork,
            onConfirm = { cloudIds, original ->
                if (onRemoveOriginal != null) onRemoveOriginal(selected, cloudIds, original)
                else onRemoveBatch?.invoke(selected, cloudIds.isNotEmpty())
                deletingBatch = null; closeBatch()
            }, onDismiss = { deletingBatch = null }, onCloudBytes = onRemovalCloudBytes, canDeleteOriginal = onRemoveOriginal != null)
    }
    if (managingGroups) {
        ShelfGroupManager(groups, groupCounts, onOpen = { group ->
            selectBrowse(browse.copy(groupId = group?.parentId ?: group?.id ?: UNGROUPED_FILTER,
                subgroupIds = if (group?.parentId != null) setOf(group.id) else emptySet(), tagMode = false))
            managingGroups = false
        }, onAdd = ::createGroup, onAddChild = onCreateSubgroup != null, onRename = ::editGroup,
            onDelete = { deletingGroup = it }, onHideTags = ::editHiddenTags, onReorder = onReorderGroup,
            onDismiss = { managingGroups = false })
    }
    if (groupEditor) {
        AlertDialog(onDismissRequest = { groupEditor = false }, title = { Text(when {
            editingGroup != null -> "重命名分组"
            newParentId != null -> "新建二级分组"
            else -> "新建分组"
        }) },
            text = { OutlinedTextField(groupName, { groupName = it }, label = { Text("分组名称") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("shelf_group_name_input")) },
            confirmButton = { TextButton(onClick = {
                val name = groupName.trim()
                when {
                    editingGroup != null -> onRenameGroup(requireNotNull(editingGroup).id, name)
                    newParentId != null -> onCreateSubgroup?.invoke(requireNotNull(newParentId), name)
                    else -> onCreateGroup(name)
                }
                groupEditor = false
            }, enabled = groupName.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { groupEditor = false }) { Text("取消") } })
    }
    deletingGroup?.let { group ->
        AlertDialog(onDismissRequest = { deletingGroup = null }, title = { Text("删除分组《${group.name}》？") },
            text = { Text(if (groups.any { it.parentId == group.id }) "下属二级分组也会删除，书籍将移入未分组。"
                else "书籍将移入未分组。") },
            confirmButton = { TextButton(onClick = {
                groups.filter { it.id == group.id || it.parentId == group.id }.forEach { browsePreferences.saveHidden(it.id, emptySet()) }
                onDeleteGroup(group.id)
                if (browse.groupId == group.id) selectBrowse(browse.copy(groupId = UNGROUPED_FILTER, subgroupIds = emptySet()))
                else if (group.id in browse.subgroupIds) selectBrowse(browse.copy(subgroupIds = browse.subgroupIds - group.id))
                editingChip = null
                deletingGroup = null
            }) { Text("删除分组", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingGroup = null }) { Text("取消") } })
    }
    moving?.let { selected ->
        fun moveTo(group: String?) {
            if (onMoveBooks != null) onMoveBooks(selected.map { it.id }, group) else selected.forEach { onMoveBook(it.id, group) }
            moving = null
            if (selectingBackup) closeBatch()
        }
        AlertDialog(onDismissRequest = { moving = null }, modifier = Modifier.testTag("shelf_move_dialog"),
            title = { Text(if (selected.size == 1) "移动《${selected.first().title}》" else "移动 ${selected.size} 本书") }, text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item {
                        ShelfMoveChoice("未分组", selected.all { it.groupId == null }, Modifier.testTag("shelf_move_ungrouped")) { moveTo(null) }
                    }
                    items(groupRows, key = { it.id }) { group ->
                        ShelfMoveChoice(shelfGroupLabel(groups, group), selected.all { it.groupId == group.id },
                            Modifier.testTag("shelf_move_${group.id}")) { moveTo(group.id) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { moving = null }) { Text("取消") } })
    }
    details?.let { book ->
        val context = LocalContext.current
        val speechCacheBytes by produceState<Long?>(null, book.id, book.mode, speechCacheRevision) {
            if (book.mode == LibraryMode.NOVEL) value = withContext(Dispatchers.IO) { AiSpeechCache(context).bytes(book.id) }
        }
        val storageBytes by produceState<Long?>(null, book.id, book.contentVersion, speechCacheRevision) {
            value = localBookStorageBytes(context, book)
        }
        val characterCount by produceState<Result<Long>?>(null, book.id, book.contentVersion, book.chapters, book.cloudOnly) {
            if (!book.cloudOnly) value = try { Result.success(bookCharacterCount(book)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        }
        AlertDialog(onDismissRequest = { details = null }, title = { Text(book.title, fontFamily = FontFamily.Serif) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("作者：${book.displayAuthor}")
                Text("格式：${book.format.name}${if (book.isDemo) " · 原创示例" else ""}")
                Text("目录：${book.chapters.size} 章")
                Text("书籍文件：" + (book.sourceFileBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) } ?: "未知"),
                    modifier = Modifier.testTag("book_source_size"))
                Text("应用占用（含缓存）：" + (storageBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) } ?: "统计中…"),
                    modifier = Modifier.testTag("book_storage_size"))
                Text("总字数：" + when {
                    book.cloudOnly -> "下载后统计"
                    characterCount == null -> "统计中…"
                    characterCount!!.isFailure -> "统计失败"
                    else -> "${characterCount!!.getOrThrow()} 字"
                }, modifier = Modifier.testTag("book_character_count"))
                if (book.mode == LibraryMode.NOVEL) Text("AI 语音缓存：" + (speechCacheBytes?.let {
                    android.text.format.Formatter.formatShortFileSize(context, it)
                } ?: "统计中…"), modifier = Modifier.testTag("book_ai_speech_cache_size"))
                Text("分组：${groups.firstOrNull { it.id == book.groupId }?.let { shelfGroupLabel(groups, it) } ?: "未分组"}")
                Text("标签：${book.tags.joinToString(" · ").ifEmpty { "无" }}", modifier = Modifier.testTag("book_info_tags"))
                if (!book.isDemo) Text(if (book.cloudOnly) "存储：云端，尚未下载" else if (book.sourceLinked) "存储：引用原文件" else "存储：应用内保存")
                TextButton(onClick = { details = null; locating = book }, modifier = Modifier.testTag("book_storage_location")) { Text("书籍位置") }
                if (book.cloudBackupAt > 0) Text("云端备份：${SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(book.cloudBackupAt))}")
                Text("进度：第 ${book.chapterIndex + 1} 章 · ${(book.progress * 100).toInt()}%")
                Text("最新章节：${book.chapters.lastOrNull()?.title.orEmpty()}")
                Text("导入于 ${SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date(book.addedAt))}", fontSize = 11.sp, color = Muted)
            } }, confirmButton = { TextButton(onClick = {
                details = null
                if (book.cloudOnly) onCloud?.invoke(book) else onOpen(book)
            }, enabled = !book.cloudOnly || onCloud != null && !cloudBusy && transfer(book) == null) { Text(if (book.cloudOnly) "下载书籍" else "阅读") } },
            dismissButton = { TextButton(onClick = { details = null; deleting = book }) { Text("移出书架", color = MaterialTheme.colorScheme.error) } })
    }
    deleting?.let { book ->
        BatchRemoveBooksDialog(listOf(book), speechCacheRevision, cloudConfigured, meteredNetwork,
            onConfirm = { cloudIds, original ->
                if (onRemoveOriginal != null) onRemoveOriginal(listOf(book), cloudIds, original)
                else if (onRemoveWithCloud != null) onRemoveWithCloud(book, cloudIds.isNotEmpty()) else onDelete(book)
                deleting = null
            }, onDismiss = { deleting = null }, onCloudBytes = onRemovalCloudBytes,
            canDeleteOriginal = onRemoveOriginal != null, tag = "shelf_remove_book")
    }
    locating?.let { book -> BookLocationDialog(book) { locating = null } }
    deletingCloud?.let { book ->
        AlertDialog(onDismissRequest = { deletingCloud = null }, modifier = Modifier.testTag("shelf_delete_cloud_dialog"),
            title = { Text("删除《${book.title}》的云端文件？") },
            text = { Text("云端的书籍文件及阅读记录将删除，并关闭该书的自动备份。" +
                if (book.cloudOnly) "尚未下载的书架占位也将移除。" else "本地书籍、阅读进度和标注保留。") },
            confirmButton = { TextButton(onClick = { onDeleteCloud?.invoke(book); deletingCloud = null },
                enabled = !cloudBusy && transfer(book) == null, modifier = Modifier.testTag("shelf_delete_cloud_confirm")) {
                Text("删除云端文件", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deletingCloud = null }, modifier = Modifier.testTag("shelf_delete_cloud_cancel")) { Text("取消") } })
    }
    clearingAiSpeech?.let { book ->
        val context = LocalContext.current
        val bytes by produceState<Result<Long>?>(null, book.id, speechCacheRevision) {
            value = try { Result.success(withContext(Dispatchers.IO) { AiSpeechCache(context).bytes(book.id) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
        CleanupDialog("清理《${book.title}》的 AI 语音缓存？", "删除该书已生成的 AI 音频，下次朗读时重新生成。书籍、阅读进度和标注保留。",
            bytes?.getOrNull(), cloudConfigured && onClearSpeechWithCloud != null, meteredNetwork,
            "云端 AI 音频占用：0 B。AI 音频仅保存在本机，勾选也不会删除云端书籍或阅读记录。", onConfirm = { cloud ->
                if (onClearSpeechWithCloud != null) onClearSpeechWithCloud(book, cloud) else onClearAiSpeechCache?.invoke(book)
                clearingAiSpeech = null
            }, onDismiss = { clearingAiSpeech = null }, tag = "shelf_clear_ai_speech", sizeError = bytes?.isFailure == true)
    }
}

@Composable private fun PendingShelfBook(pending: PendingImport, grid: Boolean, coverHeight: androidx.compose.ui.unit.Dp? = null) {
    val fraction = pending.progress?.fraction?.coerceIn(0f, 1f)
    val stage = if (pending.queued) "等待导入" else pending.progress?.stage ?: "准备导入"
    val cover: @Composable () -> Unit = {
        Box((if (grid) Modifier.fillMaxWidth().then(coverHeight?.let { Modifier.height(it) } ?: Modifier.aspectRatio(.80f))
            else Modifier.width(56.dp).height(78.dp))
            .clip(appShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("pending_import_cover"), contentAlignment = Alignment.Center) {
            val indicator = Modifier.size(if (grid) 42.dp else 26.dp).testTag("pending_import_progress")
            if (fraction == null) CircularProgressIndicator(modifier = indicator, color = Olive, strokeWidth = 3.dp)
            else CircularProgressIndicator(progress = { fraction }, modifier = indicator, color = Olive, trackColor = Line, strokeWidth = 3.dp)
        }
    }
    val description: @Composable () -> Unit = {
        Text(pending.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,
            minLines = if (grid) 2 else 1, maxLines = if (grid) 2 else 1, overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stage, Modifier.weight(1f), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            fraction?.let { Text("${(it * 100).toInt()}%", fontSize = 11.sp, color = Olive, modifier = Modifier.padding(start = 6.dp)) }
        }
        pending.progress?.currentItem?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 10.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (pending.completed > 0) Text("已导入 ${pending.completed} 本，可直接阅读", fontSize = 10.sp, color = Olive,
            modifier = Modifier.padding(top = 4.dp))
    }
    if (grid) Column(Modifier.testTag("pending_import_book")) {
        cover()
        Column(Modifier.fillMaxWidth().padding(top = 11.dp)) { description() }
    } else Row(Modifier.fillMaxWidth().testTag("pending_import_book"), verticalAlignment = Alignment.CenterVertically) {
        cover()
        Column(Modifier.weight(1f).padding(start = 15.dp)) { description() }
    }
}

@Composable private fun ShelfMoreButton(book: Book, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(26.dp).testTag("shelf_book_more_${book.id}")) {
        Icon(Icons.Outlined.MoreHoriz, "更多选项：${book.title}", Modifier.size(18.dp), tint = Ink)
    }
}

@Composable private fun ShelfMoveChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, "当前分组", Modifier.size(20.dp), tint = Olive)
    }
}

internal val LocalSourceReloadIds = staticCompositionLocalOf<Set<String>> { emptySet() }
internal val LocalSourceChangeIds = staticCompositionLocalOf<Set<String>> { emptySet() }
internal val LocalSourceReimport = staticCompositionLocalOf<(Book) -> Unit> { {} }

@Composable fun BookCover(book: Book, modifier: Modifier = Modifier, onCloud: (() -> Unit)? = null,
    cloudBusy: Boolean = false, cloudInProgress: Boolean = false, cloudTaskLabel: String = "", cloudFraction: Float? = null,
    onCancelCloud: (() -> Unit)? = null, cloudCanCancel: Boolean = false, cloudDirty: Boolean = false) {
    var showSourceChange by remember(book.id) { mutableStateOf(false) }
    val reimportSource = LocalSourceReimport.current
    if (showSourceChange) AlertDialog(onDismissRequest = { showSourceChange = false }, title = { Text("源文件已修改") },
        text = { Text("将使用外部原文件更新本书正文缓存，可能覆盖应用内未保存的正文编辑。书签、笔记、标记、划线和阅读记录保留。") },
        confirmButton = { TextButton(onClick = { showSourceChange = false; reimportSource(book) }) { Text("同步原文件") } },
        dismissButton = { TextButton(onClick = { showSourceChange = false }) { Text("关闭") } })
    val sourceReloading = book.id in LocalSourceReloadIds.current
    val sourceChanged = book.id in LocalSourceChangeIds.current
    val bitmap by rememberBookCoverBitmap(book)
    val colors = listOf(Color(0xFF63705D), Color(0xFF7F8972), Color(0xFF6E8290), Color(0xFF9A816A))
    val base = colors[(book.title.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(modifier.clip(appShape(7.dp)).background(base)) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "《${book.title}》封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = .12f), size.width * .25f, androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .35f))
                val path = Path().apply { moveTo(0f, size.height * .85f); lineTo(size.width * .35f, size.height * .6f); lineTo(size.width * .65f, size.height * .82f); lineTo(size.width, size.height * .5f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(path, Color.White.copy(alpha = .1f))
                drawLine(Color.White.copy(alpha = .2f), androidx.compose.ui.geometry.Offset(size.width * .11f, size.height * .12f), androidx.compose.ui.geometry.Offset(size.width * .11f, size.height * .87f), 1.dp.toPx())
            }
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("E READ  /  ${book.format.name}", color = Color.White.copy(alpha = .55f), fontSize = 7.sp, letterSpacing = 1.sp)
                Text(book.title, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 27.sp)
                Text(book.displayAuthor, color = Color.White.copy(alpha = .65f), fontSize = 8.sp)
            }
        }
        if (sourceChanged || sourceReloading) Surface(Modifier.align(Alignment.TopStart).padding(3.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
            IconButton(onClick = { showSourceChange = true }, enabled = !sourceReloading,
                modifier = Modifier.size(30.dp).testTag("local_source_changed_${book.id}")) {
                if (sourceReloading) SourceReloadIcon(book.id)
                else Icon(Icons.Outlined.Sync, "源文件有修改", Modifier.size(19.dp), tint = Olive)
            }
        }
        if (book.cloudOnly) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .22f))
            .testTag("cloud_book_cover_${book.id}"))
        if (cloudInProgress) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        val indicator = Modifier.size(28.dp).testTag("cloud_book_progress_${book.id}")
                        if (cloudFraction == null) CircularProgressIndicator(indicator, color = Olive, strokeWidth = 2.dp)
                        else CircularProgressIndicator(progress = { cloudFraction.coerceIn(0f, 1f) }, modifier = indicator,
                            color = Olive, trackColor = Line, strokeWidth = 2.dp)
                        if (cloudCanCancel && onCancelCloud != null) IconButton(onClick = onCancelCloud,
                            modifier = Modifier.matchParentSize().testTag("cloud_book_cancel_${book.id}")) {
                            Icon(Icons.Outlined.Close, "取消${if (book.cloudOnly) "下载" else "上传"}《${book.title}》", Modifier.size(16.dp), tint = Ink)
                        }
                    }
                    Text(cloudTaskLabel, fontSize = 9.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp, start = 3.dp, end = 3.dp))
                }
            }
        }
        if (onCloud != null) {
            Surface(Modifier.align(Alignment.BottomEnd).padding(3.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), shape = CircleShape) {
                IconButton(onClick = onCloud, enabled = !cloudBusy, modifier = Modifier.size(32.dp).testTag("cloud_book_${book.id}")) {
                    val needsUpdate = book.cloudEnabled && !book.cloudOnly && cloudDirty
                    Icon(if (book.cloudOnly) Icons.Outlined.CloudDownload else if (needsUpdate) Icons.Outlined.CloudSync
                        else if (book.cloudEnabled) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                        if (book.cloudOnly) "下载《${book.title}》" else if (needsUpdate) "更新《${book.title}》云端备份" else "备份《${book.title}》",
                        Modifier.size(19.dp).testTag(if (needsUpdate) "cloud_book_dirty_${book.id}" else if (book.cloudEnabled && !book.cloudOnly)
                            "cloud_book_synced_${book.id}" else "cloud_book_action_${book.id}"),
                        tint = if (book.cloudEnabled || book.cloudOnly) Olive else Muted)
                }
            }
        }
    }
}

@Composable fun EmptyLibrary(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(appShape(18.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(42.dp), tint = Olive)
        Text("暂无书籍", fontFamily = FontFamily.Serif, fontSize = 21.sp, modifier = Modifier.padding(top = 17.dp))
        Text("从本地导入小说、漫画或 PDF", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
        Button(onClick = onImport, modifier = Modifier.padding(top = 20.dp), shape = appShape(10.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Text("导入第一本书", fontSize = 12.sp) }
    }
}

private fun chapterLabel(book: Book): String = book.chapters.getOrNull(book.chapterIndex)?.title ?: "尚未开始"
private fun relativeTime(time: Long): String {
    if (time == 0L) return "尚未阅读"
    val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60000
    return when { minutes < 1 -> "刚刚"; minutes < 60 -> "${minutes} 分钟前"; minutes < 1440 -> "${minutes / 60} 小时前"; else -> "${minutes / 1440} 天前" }
}
