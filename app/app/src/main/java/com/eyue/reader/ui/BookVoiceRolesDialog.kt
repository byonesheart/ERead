package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookVoiceRole
import com.eyue.reader.data.addName
import com.eyue.reader.data.VoiceRoleKind

internal enum class RoleTextTarget { NAME, DESCRIPTION, SCENE }

internal fun assignRoleText(role: BookVoiceRole?, text: String, target: RoleTextTarget): BookVoiceRole {
    val added = text.trim()
    require(added.isNotBlank()) { "请输入要添加的文字" }
    if (target == RoleTextTarget.NAME) return role?.addName(added) ?: BookVoiceRole(added, Regex.escape(added))
    requireNotNull(role) { "请先选择一个已有角色" }
    fun append(previous: String) = listOf(previous, added).filter { it.isNotBlank() }.joinToString("\n")
    return if (target == RoleTextTarget.DESCRIPTION) role.copy(description = append(role.description))
        else role.copy(scenes = append(role.scenes))
}

internal fun rolePatternWithName(name: String, pattern: String): String = when {
    pattern.isBlank() -> Regex.escape(name)
    name.isBlank() || Regex(pattern).containsMatchIn(name) -> pattern
    else -> "(?:$pattern)|${Regex.escape(name)}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookVoiceRolesDialog(book: Book, onSave: (List<BookVoiceRole>) -> Unit, onDismiss: () -> Unit,
                                  selectedText: String? = null, saving: Boolean = false) {
    var assigning by remember(book.id, selectedText) { mutableStateOf(selectedText != null) }
    var selection by remember(book.id, selectedText) { mutableStateOf(selectedText.orEmpty()) }
    var target by remember { mutableStateOf(RoleTextTarget.NAME) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var targetChosen by remember(book.id, selectedText) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<BookVoiceRole?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newRoleText by remember { mutableStateOf<Pair<RoleTextTarget, String>?>(null) }
    var deleting by remember(book.id) { mutableStateOf<BookVoiceRole?>(null) }
    LaunchedEffect(saving) { if (saving) { menu = false; deleting = null } }
    val editingKey = editing?.id ?: if (creating) "new" else "list"
    val original = editing
    var name by remember(editingKey) { mutableStateOf(original?.name.orEmpty()) }
    var pattern by remember(editingKey) { mutableStateOf(original?.nameRegex.orEmpty()) }
    var voice by remember(editingKey) { mutableStateOf(original?.voice.orEmpty()) }
    var description by remember(editingKey) { mutableStateOf(original?.description
        ?: newRoleText?.takeIf { creating && it.first == RoleTextTarget.DESCRIPTION }?.second.orEmpty()) }
    var scenes by remember(editingKey) { mutableStateOf(original?.scenes
        ?: newRoleText?.takeIf { creating && it.first == RoleTextTarget.SCENE }?.second.orEmpty()) }
    var enabled by remember(editingKey) { mutableStateOf(original?.enabled ?: true) }
    var kind by remember(editingKey) { mutableStateOf(original?.kind ?: VoiceRoleKind.CHARACTER) }
    val draft = runCatching {
        require(book.voiceRoles.none { it.id != original?.id && it.name == name.trim() }) {
            "该名字已存在，请选择已有角色"
        }
        BookVoiceRole(name.trim(), rolePatternWithName(name.trim(), pattern), voice, description, scenes,
            original?.aliases.orEmpty(), original?.id ?: "new-role-draft", enabled = enabled, kind = kind)
    }
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp.dp - 32.dp).coerceIn(1.dp, 560.dp)
    val dialogHeight = (configuration.screenHeightDp.dp - 48.dp).coerceIn(1.dp, 620.dp)
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(dialogWidth).height(dialogHeight).testTag("book_voice_roles_dialog"), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(if (assigning) "选择角色" else if (book.id == "__common_roles__") "通用角色" else "本书角色音色",
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saving) Text("正在保存角色音色…", modifier = Modifier.testTag("role_saving_status"))
                else {
                Text(book.title, style = MaterialTheme.typography.titleSmall)
                if (assigning) {
                    OutlinedTextField(selection, { selection = it; error = null }, label = { Text("选中文字（可修改）") },
                        maxLines = 5, modifier = Modifier.fillMaxWidth().height(112.dp).testTag("role_selected_text"))
                    Box {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("role_target_picker")) {
                            Text(book.voiceRoles.find { it.id == targetId }?.name ?: if (targetChosen) "新建角色" else "选择角色")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("新建角色") }, onClick = { targetId = null; targetChosen = true; menu = false },
                                modifier = Modifier.testTag("role_target_new"))
                            book.voiceRoles.forEach { role -> DropdownMenuItem(text = { Text(role.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { targetId = role.id; targetChosen = true; menu = false },
                                modifier = Modifier.testTag("role_target_${role.id}"), trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(role.enabled, { value -> onSave(book.voiceRoles.map { if (it.id == role.id) it.copy(enabled = value) else it }) },
                                        modifier = Modifier.testTag("role_target_enabled_${role.id}"))
                                    IconButton(onClick = { menu = false; deleting = role },
                                        modifier = Modifier.size(32.dp).testTag("role_target_delete_${role.id}")) {
                                        Icon(Icons.Outlined.Close, "删除角色《${role.name}》", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                    }
                                }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(RoleTextTarget.NAME to "角色名字", RoleTextTarget.DESCRIPTION to "角色描述", RoleTextTarget.SCENE to "角色场景").forEach { (value, label) ->
                            FilterChip(target == value, onClick = { target = value; error = null }, label = { Text(label) },
                                modifier = Modifier.weight(1f).testTag("role_assign_${value.name}"))
                        }
                    }
                    Text("名字自动加入名字正则；描述和场景可追加至已有角色，也可新建角色后在保存前命名。", style = MaterialTheme.typography.bodySmall)
                } else if (editing != null || creating) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("类别", Modifier.weight(1f))
                        FilterChip(kind == VoiceRoleKind.CHARACTER, { kind = VoiceRoleKind.CHARACTER }, { Text("角色") },
                            modifier = Modifier.testTag("role_kind_character"))
                        FilterChip(kind == VoiceRoleKind.NARRATOR, { kind = VoiceRoleKind.NARRATOR }, { Text("旁白") },
                            modifier = Modifier.testTag("role_kind_narrator"))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", Modifier.weight(1f))
                        Switch(enabled, { enabled = it }, modifier = Modifier.testTag("role_edit_enabled"))
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("名字（保存时必填）") }, modifier = Modifier.fillMaxWidth().testTag("role_name"))
                    OutlinedTextField(pattern, { pattern = it }, label = { Text("名字正则（留空按名字匹配）") },
                        maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("role_regex"))
                    OutlinedTextField(voice, { voice = it }, label = { Text("音色") },
                        maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("role_voice"))
                    OutlinedTextField(description, { description = it }, label = { Text("角色描述") },
                        maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("role_description"))
                    OutlinedTextField(scenes, { scenes = it }, label = { Text("角色场景") },
                        maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("role_scenes"))
                    val countedPattern = runCatching { rolePatternWithName(name.trim(), pattern) }.getOrDefault(pattern)
                    val count = listOf(name, countedPattern, voice, description, scenes).sumOf { it.codePointCount(0, it.length) }
                    Text("$count / 3000 字", color = if (count > 3000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("role_character_count"), style = MaterialTheme.typography.bodySmall)
                    draft.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text((if (book.id == "__common_roles__") "适用于全部小说；" else "仅保存至本书；") +
                        "需在 AI 配置中开启角色提示词。每个角色五项信息合计最多 3000 字。",
                        style = MaterialTheme.typography.bodySmall)
                    book.voiceRoles.forEach { role ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { editing = role }, modifier = Modifier.weight(1f).testTag("role_edit_${role.id}")) {
                                Text(role.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (role.kind == VoiceRoleKind.NARRATOR) " · 旁白" else " · 角色",
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    modifier = Modifier.testTag("role_kind_label_${role.id}"))
                            }
                            Switch(role.enabled, { value -> onSave(book.voiceRoles.map { if (it.id == role.id) it.copy(enabled = value) else it }) },
                                modifier = Modifier.testTag("role_enabled_${role.id}"))
                            IconButton(onClick = { deleting = role }, modifier = Modifier.size(36.dp).testTag("role_list_delete_${role.id}")) {
                                Icon(Icons.Outlined.Close, "删除角色《${role.name}》", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("role_error")) }
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(Modifier.fillMaxWidth().testTag("role_actions"), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!saving) {
                    if (assigning) Button(enabled = targetChosen && selection.isNotBlank(), onClick = {
                        if (targetId == null && target != RoleTextTarget.NAME) {
                            newRoleText = target to selection.trim()
                            creating = true; assigning = false; error = null
                        } else runCatching { assignRoleText(book.voiceRoles.find { it.id == targetId }, selection, target) }
                            .onSuccess { editing = it; assigning = false; error = null }
                            .onFailure { error = it.message }
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("role_assign_confirm")) { Text("添加并编辑") }
                    else if (editing != null || creating) {
                        Button(enabled = draft.isSuccess, onClick = {
                            val role = draft.getOrThrow().let { if (original == null) it.copy(id = java.util.UUID.randomUUID().toString()) else it }
                            onSave(book.voiceRoles.filterNot { it.id == role.id } + role)
                            editing = null; creating = false; newRoleText = null
                        }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("role_save")) { Text("保存角色") }
                        TextButton(onClick = { editing = null; creating = false; newRoleText = null },
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("role_return_list")) { Text("返回列表") }
                        if (original != null && book.voiceRoles.any { it.id == original.id }) TextButton(onClick = { deleting = original },
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("role_delete")) {
                            Text("删除角色", color = MaterialTheme.colorScheme.error)
                        }
                    } else Button(onClick = { newRoleText = null; creating = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("role_new")) { Text("新增角色") }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
            }
        }
        }
    }
    deleting?.let { role ->
        AlertDialog(onDismissRequest = { deleting = null }, modifier = Modifier.testTag("role_delete_dialog"),
            title = { Text("删除角色《${role.name}》？", maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = { Text("删除该角色的名字、音色、描述及场景资料。") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                if (targetId == role.id) { targetId = null; targetChosen = false }
                if (editing?.id == role.id) { editing = null; creating = false }
                onSave(book.voiceRoles.filterNot { it.id == role.id })
            }, enabled = !saving, modifier = Modifier.testTag("role_delete_confirm")) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.testTag("role_delete_cancel")) { Text("取消") } })
    }
}
