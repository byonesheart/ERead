package com.eyue.reader.ui

// Bottom action panel adapted from the supplied ebook UI; actions use ERead's existing callbacks.
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.AppThemeStyle
import com.eyue.reader.data.Book
import com.eyue.reader.data.CloudTransferProgress
import com.eyue.reader.data.displayAuthor
import com.eyue.reader.ui.AppIcon as Icon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShelfBookActions(book: Book, transfer: CloudTransferProgress?, cloudConfigured: Boolean,
    cloudBusy: Boolean, cloudDirty: Boolean, onDismiss: () -> Unit, onDetails: () -> Unit, onMove: () -> Unit,
    onRename: (() -> Unit)?, onGenerateSpeech: (() -> Unit)?, onVoiceRoles: (() -> Unit)?,
    onCloud: (() -> Unit)?, onDeleteCloud: (() -> Unit)?, onClearSpeech: (() -> Unit)?, onDelete: () -> Unit,
    onLocation: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val sheetMaxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .82f
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember(book.id) { mutableStateOf(false) }
    fun choose(action: () -> Unit) {
        if (!closing) {
            closing = true
            scope.launch { sheetState.hide(); action() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface,
        shape = appShape(24.dp), sheetState = sheetState, sheetGesturesEnabled = false, dragHandle = null,
        modifier = Modifier.testTag("book_action_sheet")) {
        Column(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                BookCover(book, Modifier.width(44.dp).height(60.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(book.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.displayAuthor, fontSize = 12.sp, color = Muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text("${book.chapters.getOrNull(book.chapterIndex)?.title ?: "尚未开始"} · ${(book.progress * 100).toInt()}%",
                        fontSize = 11.sp, color = Olive, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = { choose(onDismiss) }, modifier = Modifier.size(36.dp).testTag("book_action_close")) {
                    Icon(Icons.Outlined.Close, "关闭更多选项", Modifier.size(20.dp))
                }
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = Line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                ShelfQuickAction(Icons.Outlined.Info, "书籍信息", Color(0xFF7FA9F0), Modifier.weight(1f)) { choose(onDetails) }
                ShelfQuickAction(Icons.Outlined.FolderOpen, "移动分组", Color(0xFFFFC24B), Modifier.weight(1f)) { choose(onMove) }
                onGenerateSpeech?.let {
                    ShelfQuickAction(Icons.Outlined.GraphicEq, "转化 AI 语音", Color(0xFF5FD0A0),
                        Modifier.weight(1f).testTag("shelf_book_generate_speech_${book.id}")) { choose(it) }
                }
                onVoiceRoles?.let {
                    ShelfQuickAction(Icons.Outlined.Mic, "角色音色", Color(0xFFA78BFA),
                        Modifier.weight(1f).testTag("shelf_book_voice_roles_${book.id}")) { choose(it) }
                }
            }
            val actions = buildList {
                onLocation?.let { add(ShelfAction(Icons.Outlined.FolderOpen, "书籍位置", "shelf_book_location_${book.id}", onClick = { choose(it) })) }
                onRename?.let { add(ShelfAction(Icons.Outlined.Edit, "重命名书籍", "shelf_book_rename_${book.id}", onClick = { choose(it) })) }
                onCloud?.let {
                    add(ShelfAction(if (book.cloudOnly) Icons.Outlined.CloudDownload else if (cloudDirty) Icons.Outlined.CloudSync
                        else Icons.Outlined.CloudUpload,
                        if (book.cloudOnly) "下载到本机" else if (cloudDirty) "更新云端备份" else "备份到云端",
                        "shelf_book_cloud_${book.id}",
                        transfer?.stage ?: if (cloudConfigured) "已连接" else "未配置", !cloudBusy) { choose(it) })
                }
                onDeleteCloud?.let { add(ShelfAction(Icons.Outlined.CloudOff, "删除云端文件",
                    "shelf_book_delete_cloud_${book.id}", enabled = !cloudBusy, onClick = { choose(it) })) }
                onClearSpeech?.let { add(ShelfAction(Icons.Outlined.AutoAwesome, "清理 AI 语音缓存",
                    "shelf_book_clear_ai_speech_${book.id}", onClick = { choose(it) })) }
            }
            if (actions.isNotEmpty()) {
                HorizontalDivider(color = Line)
                Text("书籍管理", fontSize = 11.sp, color = Muted,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(appShape(14.dp)).background(colors.surfaceContainerLow)) {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp, end = 14.dp), color = Line)
                        ShelfActionRow(action)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .clip(appShape(14.dp)).background(colors.errorContainer).clickable { choose(onDelete) }
                .padding(horizontal = 14.dp, vertical = 11.dp).testTag("sheet_remove_book"),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = colors.onErrorContainer)
                Text("移出书架", color = colors.onErrorContainer, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

internal fun bookStorageLocation(book: Book): String = when {
    book.cloudOnly -> "云端，尚未下载到本机"
    book.isDemo -> "应用内置示例"
    book.sourceLinked -> book.sourceUri ?: book.sourcePath ?: "原文件位置不可用"
    else -> book.sourceUri ?: book.sourcePath ?: "本地文件位置不可用"
}

@Composable private fun ShelfQuickAction(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val colorful = LocalAppThemeStyle.current == AppThemeStyle.XINGCAI && !LocalAppEinkMode.current
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(appShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).background(if (colorful) color else colors.primaryContainer, appShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (colorful) colors.surface else colors.onPrimaryContainer)
        }
        Text(label, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

private data class ShelfAction(val icon: ImageVector, val title: String, val tag: String,
    val value: String = "", val enabled: Boolean = true, val onClick: () -> Unit)

@Composable private fun ShelfActionRow(action: ShelfAction) {
    val tint = Muted.copy(alpha = if (action.enabled) 1f else .5f)
    Row(Modifier.fillMaxWidth().clickable(enabled = action.enabled, onClick = action.onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp).testTag(action.tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(action.icon, null, Modifier.size(20.dp), tint = tint)
        Text(action.title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 13.sp,
            color = Ink.copy(alpha = if (action.enabled) 1f else .5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action.value.isNotEmpty()) Text(action.value, fontSize = 11.sp, color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp).padding(end = 4.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = tint)
    }
}
