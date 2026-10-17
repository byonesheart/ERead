package com.eyue.reader.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CleanupDialog(title: String, description: String, localBytes: Long?, cloudConfigured: Boolean,
    meteredNetwork: Boolean, cloudDescription: String, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit,
    tag: String, confirmLabel: String = "清理", sizeError: Boolean = false) {
    var includeCloud by remember(tag) { mutableStateOf(false) }
    var allowMetered by remember(tag) { mutableStateOf(false) }
    val cloud = includeCloud && cloudConfigured
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("${tag}_dialog"), title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Text("本地占用：" + when {
                sizeError -> "统计失败"
                localBytes == null -> "统计中…"
                else -> Formatter.formatShortFileSize(LocalContext.current, localBytes)
            }, modifier = Modifier.testTag("${tag}_space"), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().clickable(enabled = cloudConfigured) { includeCloud = !includeCloud; allowMetered = false },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cloud, { includeCloud = it; allowMetered = false }, enabled = cloudConfigured,
                    modifier = Modifier.testTag("${tag}_cloud"))
                Text("同时清空云端对应数据", style = MaterialTheme.typography.bodyMedium)
            }
            Text(cloudDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!cloud) Text("仅清本地时，本次不更新云端；后续备份仍按本地记录更新。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!cloudConfigured) Text("配置 WebDAV 网盘后可同步清理云端。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cloud && meteredNetwork) Row(Modifier.fillMaxWidth().clickable { allowMetered = !allowMetered },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allowMetered, { allowMetered = it }, modifier = Modifier.testTag("${tag}_metered"))
                Text("当前为计费网络，允许本次云端操作", style = MaterialTheme.typography.bodySmall)
            }
        } }, confirmButton = { TextButton(enabled = !cloud || !meteredNetwork || allowMetered,
            onClick = { onConfirm(cloud) }, modifier = Modifier.testTag("${tag}_confirm")) {
            Text(confirmLabel, color = if (!cloud || !meteredNetwork || allowMetered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}_cancel")) { Text("取消") } })
}
