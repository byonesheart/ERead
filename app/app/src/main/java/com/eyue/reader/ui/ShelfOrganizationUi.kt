package com.eyue.reader.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.Book
import com.eyue.reader.data.ShelfGroup
import com.eyue.reader.data.UNGROUPED_FILTER
import com.eyue.reader.data.normalizeShelfTags

internal fun shelfGroupRows(groups: List<ShelfGroup>): List<ShelfGroup> =
    groups.filter { it.parentId == null }.flatMap { parent -> listOf(parent) + groups.filter { it.parentId == parent.id } }

internal fun shelfGroupLabel(groups: List<ShelfGroup>, group: ShelfGroup): String =
    groups.find { it.id == group.parentId }?.let { "${it.name} / ${group.name}" } ?: group.name

@Composable
internal fun ShelfTagsDialog(book: Book, existingTags: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(book.id) { mutableStateOf(book.tags) }
    var name by remember(book.id) { mutableStateOf("") }
    val newTag = normalizeShelfTags(listOf(name)).singleOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑标签") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(60) }, modifier = Modifier.weight(1f).testTag("shelf_tag_name"),
                    singleLine = true, label = { Text("新标签") })
                TextButton(enabled = newTag != null && newTag !in selected && selected.size < 64, onClick = {
                    selected = normalizeShelfTags(selected + requireNotNull(newTag)); name = ""
                }, modifier = Modifier.testTag("shelf_add_tag")) { Text("添加") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items((selected + existingTags).distinct(), key = { it }) { tag ->
                    ShelfTagCheckbox(tag, tag in selected, tag in selected || selected.size < 64) {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        onSave(normalizeShelfTags(selected + listOfNotNull(newTag))); onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ShelfHiddenTagsDialog(groups: List<ShelfGroup>, tags: List<String>, initialGroup: String?,
    hidden: (String?) -> Set<String>, onSave: (String?, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf(initialGroup) }
    var drafts by remember { mutableStateOf<Map<String?, Set<String>>>(emptyMap()) }
    var choosingGroup by remember { mutableStateOf(false) }
    val scoped = drafts[group] ?: hidden(group)
    val label = when (group) {
        null -> "全局"
        UNGROUPED_FILTER -> "未分组"
        else -> groups.firstOrNull { it.id == group }?.let { shelfGroupLabel(groups, it) }.orEmpty()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("分组模式下隐藏标签") }, text = {
        Column {
            Box {
                OutlinedButton(onClick = { choosingGroup = true }, modifier = Modifier.testTag("shelf_hidden_tags_scope")) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(choosingGroup, { choosingGroup = false }) {
                    listOf(null to "全局", UNGROUPED_FILTER to "未分组").plus(
                        shelfGroupRows(groups).map { it.id to shelfGroupLabel(groups, it) }).forEach { (id, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { group = id; choosingGroup = false })
                    }
                }
            }
            if (tags.isEmpty()) Text("暂无标签", color = Muted)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 290.dp)) {
                items(tags, key = { it }) { tag ->
                    ShelfTagCheckbox(tag, tag in scoped) {
                        drafts = drafts + (group to if (tag in scoped) scoped - tag else scoped + tag)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        drafts.forEach { (scope, values) -> onSave(scope, values) }; onDismiss()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ShelfTagCheckbox(tag: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
        Text(tag, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
