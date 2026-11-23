package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.cloud.WebDavProfile

internal enum class CloudProvider(val label: String) { WEBDAV("WebDAV"), S3("S3"), COS("腾讯云 COS") }
internal val WebDavProfile.provider: CloudProvider get() = when {
    cos -> CloudProvider.COS
    s3 -> CloudProvider.S3
    else -> CloudProvider.WEBDAV
}

@Composable
internal fun CloudProfileControl(profiles: List<WebDavProfile>, configured: Boolean, busy: Boolean,
    onActivate: (String) -> Unit, onDisconnect: () -> Unit, onEdit: (WebDavProfile) -> Unit,
    onCreate: (CloudProvider) -> Unit, onRemove: (String) -> Unit, error: String? = null) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val selected = profiles.firstOrNull { it.active }
        Text("当前配置：${selected?.let { "${it.provider.label} · ${it.name}" } ?: if (configured) "读取中…" else "未启用"}",
            modifier = Modifier.testTag("cloud_current_profile"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("cloud_open_account")) {
            Text("切换配置")
        }
    }
    if (open) CloudProfilePicker(profiles, busy, onActivate, onDisconnect, onEdit, onCreate, onRemove, error) { open = false }
}

@Composable
internal fun CloudProfilePicker(profiles: List<WebDavProfile>, busy: Boolean,
    onActivate: (String) -> Unit, onDisconnect: () -> Unit, onEdit: (WebDavProfile) -> Unit,
    onCreate: (CloudProvider) -> Unit, onRemove: (String) -> Unit, error: String? = null, onClose: () -> Unit) {
    var provider by remember { mutableStateOf<CloudProvider?>(null) }
    var removing by remember { mutableStateOf<WebDavProfile?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text(provider?.let { "${it.label} 配置" } ?: "选择备份方式") },
        modifier = Modifier.testTag("cloud_profile_picker"), text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == null) CloudProvider.entries.forEach { kind ->
                    OutlinedButton(onClick = { provider = kind },
                        modifier = Modifier.fillMaxWidth().testTag("cloud_provider_${kind.name}")) { Text(kind.label) }
                } else {
                    TextButton(onClick = { provider = null }, modifier = Modifier.testTag("cloud_provider_back")) { Text("返回选择备份方式") }
                    Text("点击配置名称可编辑；开关控制启用，最多启用一个配置。", style = MaterialTheme.typography.bodySmall)
                    val choices = profiles.filter { it.provider == provider }
                    if (choices.isEmpty()) Text("暂无配置")
                    choices.forEach { profile ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onEdit(profile) }, enabled = !busy,
                                    modifier = Modifier.weight(1f).testTag("cloud_edit_${profile.id}")) {
                                    Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Switch(checked = profile.active, enabled = !busy, onCheckedChange = {
                                    if (it) onActivate(profile.id) else onDisconnect()
                                }, modifier = Modifier.testTag("cloud_toggle_${profile.id}"))
                                TextButton(onClick = { removing = profile }, enabled = !busy,
                                    modifier = Modifier.testTag("cloud_remove_${profile.id}")) { Text("删除") }
                            }
                            HorizontalDivider()
                        }
                    }
                    TextButton(onClick = { onCreate(requireNotNull(provider)) }, enabled = !busy,
                        modifier = Modifier.testTag("cloud_create_profile")) { Text("新增配置") }
                    if (busy) Text("正在保存配置…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
    removing?.let { profile -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除备份配置？") },
        text = { Text("删除“${profile.name}”的本机配置；若已启用，将同时停用。云端备份保留。") },
        confirmButton = { TextButton(onClick = { onRemove(profile.id); removing = null },
            modifier = Modifier.testTag("cloud_confirm_remove")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
