package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.*

@Composable internal fun SourceScanSettings(state: LibraryState, access: Map<String, Boolean>, issues: Map<String, String>,
    scanning: Boolean, syncing: Set<String>, pending: Set<String>,
    onBooks: (Set<String>) -> Unit, onAutoReload: (Boolean) -> Unit, onGrant: (Set<String>) -> Unit,
    onFolder: () -> Unit, onMode: (SourceScanMode) -> Unit, onScan: () -> Unit) {
    val books = state.books.filterNot { it.cloudOnly || it.isDemo }
    var warn by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("source_scan_page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceScanMode.entries.forEach { mode ->
                    FilterChip(state.sourceScanMode == mode, { onMode(mode) }, label = { Text(mode.displayName) },
                        modifier = Modifier.weight(1f).testTag("source_scan_mode_${mode.name}"))
                }
            }
            Text("仅扫描下方勾选的书籍。自动：启动、返回前台时检查，启用自动重载后运行期间也会定期检查。手动：点击下方按钮检查。关闭：不检查本地修改。", style = MaterialTheme.typography.bodySmall)
            if (state.sourceScanMode == SourceScanMode.MANUAL) OutlinedButton(onScan, enabled = !scanning,
                modifier = Modifier.testTag("source_scan_now")) { Text("立即扫描") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动重载", Modifier.weight(1f))
                Switch(state.autoReloadSources, { if (it) warn = true else onAutoReload(false) },
                    Modifier.testTag("source_auto_reload"))
            }
            Text("同步保留书签、笔记、标记、划线和阅读记录。正在阅读的书，下次打开时使用新正文。", style = MaterialTheme.typography.bodySmall)
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("source_scan_progress"))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onBooks(books.map { it.id }.toSet()) }, Modifier.testTag("source_scan_all")) { Text("全选") }
                TextButton({ onBooks(emptySet()) }, Modifier.testTag("source_scan_none")) { Text("清空") }
                Text("已选 ${state.scannedBookIds.intersect(books.map { it.id }.toSet()).size} 本", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onGrant(state.scannedBookIds) }, enabled = state.scannedBookIds.isNotEmpty(),
                    modifier = Modifier.testTag("source_grant_batch")) { Text("批量授权") }
                OutlinedButton(onFolder, Modifier.testTag("source_grant_folder")) { Text("授权文件夹") }
            }
            Text("批量授权时可多选原文件。文件夹授权会应用到该文件夹及子文件夹内可匹配的书籍。", style = MaterialTheme.typography.bodySmall)
        }
        items(books, key = { it.id }) { book ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(book.id in state.scannedBookIds, { checked ->
                            onBooks(if (checked) state.scannedBookIds + book.id else state.scannedBookIds - book.id)
                        }, Modifier.testTag("source_scan_book_${book.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(when (access[book.id]) { true -> "已授权"; false -> "未授权"; null -> "检查授权中" },
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("source_access_${book.id}"))
                            if (book.id in syncing) Text("正在同步", style = MaterialTheme.typography.bodySmall)
                            else if (book.id in pending) Text("已同步，下次打开生效", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ onGrant(setOf(book.id)) }, Modifier.testTag("source_scan_access_${book.id}")) { Text("授权") }
                    }
                    issues[book.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (warn) AlertDialog(onDismissRequest = { warn = false }, modifier = Modifier.testTag("source_reload_warning"),
        title = { Text("开启自动重载？") },
        text = { Text("自动重载会以外部原文件替换应用内正文缓存，可能覆盖同步前在应用内尚未保存的正文编辑。书签、笔记、标记、划线和阅读记录会保留。") },
        confirmButton = { TextButton({ warn = false; onAutoReload(true) }, Modifier.testTag("source_reload_confirm")) { Text("开启") } },
        dismissButton = { TextButton({ warn = false }) { Text("取消") } })
}
