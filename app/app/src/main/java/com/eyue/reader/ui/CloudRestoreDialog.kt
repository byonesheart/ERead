package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.CloudRestoreConflict
import com.eyue.reader.data.CloudRestoreSelection
import com.eyue.reader.data.Book

@Composable internal fun CloudRestoreDialog(conflicts: List<CloudRestoreConflict>, metered: Boolean, busy: Boolean,
    onCancel: () -> Unit, onRestore: (Map<String, CloudRestoreSelection>, Boolean) -> Unit, uploading: Boolean = false) {
    var choices by remember(uploading, conflicts.map { it.remote.id to it.locals.map { local -> local.id } }) {
        mutableStateOf(conflicts.associate { it.remote.id to CloudRestoreSelection(it.locals.map { local -> local.id }.toSet(), true) })
    }
    var useMetered by remember { mutableStateOf(false) }
    val valid = choices.values.all { it.keepRemote || it.keepLocalIds.isNotEmpty() }
    val changesCloud = uploading || choices.values.any { it.keepLocalIds.size + (if (it.keepRemote) 1 else 0) == 1 }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("需要确认同名书籍") }, text = {
        Column {
            Text(if (uploading) "无法确定是否为同一本书，请勾选每组要保留的书籍，至少一本。所选本地书会上传；保留多本时分别保存。未选中的书架或云端副本会移除，原始文件保留。"
                else "无法确定是否为同一本书，请勾选每组要保留的书籍，至少一本。保留一本会更新云端备份；保留多本不改云端。同一本地书在各组的选择同步，云端正文仍需手动下载。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                items(conflicts, key = { it.remote.id }) { conflict ->
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(conflict.remote.title, style = MaterialTheme.typography.titleSmall)
                        conflict.locals.forEachIndexed { index, local ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(local.id in choices.getValue(conflict.remote.id).keepLocalIds, onCheckedChange = { checked ->
                                    choices = choices.mapValues { (remoteId, value) ->
                                        if (conflicts.first { it.remote.id == remoteId }.locals.any { it.id == local.id })
                                            value.copy(keepLocalIds = if (checked) value.keepLocalIds + local.id else value.keepLocalIds - local.id)
                                        else value
                                    }
                                }, enabled = !busy, modifier = Modifier.testTag("cloud_restore_local_${conflict.remote.id}_${local.id}"))
                                Column(Modifier.weight(1f)) {
                                    Text("本地 ${index + 1} · ${local.format} · ${(local.progress * 100).toInt()}%" +
                                        local.author.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    CloudConflictSource(local)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(choices.getValue(conflict.remote.id).keepRemote, onCheckedChange = { checked ->
                                choices = choices + (conflict.remote.id to choices.getValue(conflict.remote.id).copy(keepRemote = checked))
                            }, enabled = !busy, modifier = Modifier.testTag("cloud_restore_remote_${conflict.remote.id}"))
                            Column(Modifier.weight(1f)) {
                                Text("云端 · ${conflict.remote.format} · ${(conflict.remote.progress * 100).toInt()}%" +
                                    conflict.remote.author.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                CloudConflictSource(conflict.remote)
                            }
                        }
                    }
                }
            }
            if (!valid) Text("每组至少勾选一本书籍", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cloud_restore_selection_error"))
            if (metered && changesCloud) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(useMetered, { useMetered = it }, enabled = !busy, modifier = Modifier.testTag("cloud_restore_metered"))
                Text("允许使用当前计费网络更新备份", style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy && valid && (!metered || !changesCloud || useMetered),
        modifier = Modifier.testTag("cloud_restore_confirm"), onClick = { onRestore(choices, useMetered) }) { Text(if (uploading) "确认上传" else "确认还原") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("取消") } })
}

@Composable private fun CloudConflictSource(book: Book) {
    val context = LocalContext.current
    val size = book.sourceFileBytes?.takeIf { it >= 0 }?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
    Text(book.sourceFileName ?: "原文件名未知", maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if (size == null) "原书文件大小未知" else "原书文件 $size", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
