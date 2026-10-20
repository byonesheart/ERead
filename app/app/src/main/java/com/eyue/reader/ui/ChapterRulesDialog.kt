package com.eyue.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.ChapterRulePreset
import com.eyue.reader.data.DEFAULT_CHAPTER_RULE
import com.eyue.reader.data.TextParser
import java.util.UUID

@Composable fun ChapterRulesDialog(presets: List<ChapterRulePreset>, activeId: String, onDismiss: () -> Unit,
    onSavePreset: (ChapterRulePreset) -> Unit, onSelect: (String) -> Unit, onDelete: (String) -> Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    val current = presets.firstOrNull { it.id == editingId }
    var name by remember(editingId, current?.name) { mutableStateOf(current?.name.orEmpty()) }
    var pattern by remember(editingId, current?.pattern) { mutableStateOf(current?.pattern ?: DEFAULT_CHAPTER_RULE) }
    val error = remember(pattern) { runCatching { TextParser.validateRule(pattern) }.exceptionOrNull()?.message }
    fun returnToList() { editingId = null }
    AlertDialog(onDismissRequest = { if (editingId == null) onDismiss() else returnToList() }, modifier = Modifier.testTag("chapter_rules_dialog"),
        title = { Text(if (editingId == null) "目录规则" else if (current == null) "新增规则" else "编辑规则") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editingId == null) {
                Text("为 TXT 保存多套目录规则，点击圆钮切换。EPUB / MOBI 优先使用文件目录。", fontSize = 12.sp, color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("chapter_rules_list")) {
                    items(presets, key = { it.id }) { preset ->
                        Row(Modifier.fillMaxWidth().testTag("chapter_rule_edit_${preset.id}")
                            .clickable { editingId = preset.id }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(preset.id == activeId, onClick = { onSelect(preset.id) },
                                modifier = Modifier.testTag("chapter_rule_select_${preset.id}"))
                            Text(preset.name, Modifier.weight(1f), fontSize = 13.sp)
                            IconButton(onClick = {
                                onDelete(preset.id)
                            }, enabled = preset.id != "default" && preset.id != activeId,
                                modifier = Modifier.testTag("chapter_rule_delete_${preset.id}")) {
                                Icon(Icons.Outlined.DeleteOutline, "删除规则 ${preset.name}", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = { editingId = UUID.randomUUID().toString() }, modifier = Modifier.testTag("chapter_rule_add")) {
                    Text("新增规则")
                }
                } else {
                OutlinedTextField(name, { name = it }, label = { Text("规则名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chapter_rule_name"), isError = name.isBlank())
                OutlinedTextField(pattern, { pattern = it }, label = { Text("正则表达式") }, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("chapter_rule_pattern"), isError = error != null,
                    supportingText = { Text(error ?: "按行匹配章节标题", fontSize = 10.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                if (editingId == activeId && editingId != "default") Text("正在使用的规则需先切换后删除。", fontSize = 11.sp, color = Muted)
                }
            }
        }, confirmButton = {
            if (editingId == null) TextButton(onClick = onDismiss) { Text("完成") }
            else TextButton(onClick = {
                onSavePreset(ChapterRulePreset(requireNotNull(editingId), name.trim(), pattern)); returnToList()
            }, enabled = name.isNotBlank() && error == null, modifier = Modifier.testTag("chapter_rule_save")) { Text("保存规则") }
        }, dismissButton = {
            if (editingId != null) TextButton(onClick = { returnToList() }, modifier = Modifier.testTag("chapter_rule_back")) { Text("返回") }
        })
}
