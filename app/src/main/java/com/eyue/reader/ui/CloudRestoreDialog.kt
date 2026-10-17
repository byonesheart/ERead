package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.CloudRestoreConflict
import com.eyue.reader.data.CloudRestoreSelection

@Composable internal fun CloudRestoreDialog(conflicts: List<CloudRestoreConflict>, metered: Boolean, busy: Boolean,
    onCancel: () -> Unit, onRestore: (Map<String, CloudRestoreSelection>, Boolean) -> Unit) {
    var choices by remember(conflicts.map { it.remote.id to it.locals.map { local -> local.id } }) {
        mutableStateOf(conflicts.associate { it.remote.id to CloudRestoreSelection(it.locals.map { local -> local.id }.toSet(), true) })
    }
    var useMetered by remember { mutableStateOf(false) }
    val valid = choices.values.all { it.keepRemote || it.keepLocalIds.isNotEmpty() }
    val changesCloud = choices.values.any { it.keepLocalIds.size + (if (it.keepRemote) 1 else 0) == 1 }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("需要确认同名书籍") }, text = {
        Column {
            Text("无法确定是否为同一本书，请勾选每组要保留的书籍，至少一本。保留一本会更新云端备份；保留多本不改云端。同一本地书在各组的选择同步，云端正文仍需手动下载。", style = MaterialTheme.typography.bodySmall)
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
                                Text("本地 ${index + 1} · ${local.format} · ${(local.progress * 100).toInt()}%" +
                                    local.author.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(choices.getValue(conflict.remote.id).keepRemote, onCheckedChange = { checked ->
                                choices = choices + (conflict.remote.id to choices.getValue(conflict.remote.id).copy(keepRemote = checked))
                            }, enabled = !busy, modifier = Modifier.testTag("cloud_restore_remote_${conflict.remote.id}"))
                            Text("云端 · ${conflict.remote.format} · ${(conflict.remote.progress * 100).toInt()}%" +
                                conflict.remote.author.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
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
        modifier = Modifier.testTag("cloud_restore_confirm"), onClick = { onRestore(choices, useMetered) }) { Text("确认还原") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("取消") } })
}
