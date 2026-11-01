package com.eyue.reader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable internal fun MeteredBackupDialog(defaultAllowed: Boolean, onCancel: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var allowed by remember(defaultAllowed) { mutableStateOf(defaultAllowed) }
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("metered_backup_dialog"),
        title = { Text("计费网络备份") }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allowed, { allowed = it }, Modifier.testTag("metered_backup_allowed"))
                Text("允许本次计费网络备份，可能使用移动数据")
            }
        }, confirmButton = { TextButton({ onConfirm(allowed) }, enabled = allowed,
            modifier = Modifier.testTag("metered_backup_confirm")) { Text("上传") } },
        dismissButton = { TextButton(onCancel) { Text("取消") } })
}
