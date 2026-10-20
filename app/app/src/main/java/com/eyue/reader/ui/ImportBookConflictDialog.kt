package com.eyue.reader.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ImportBookConflict

@Composable internal fun ImportBookConflictDialog(conflict: ImportBookConflict, onChoose: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val books = conflict.existing + conflict.incoming
    var selected by remember(conflict.incoming.id) { mutableStateOf(books.map { it.id }.toSet()) }
    AlertDialog(onDismissRequest = { onChoose(conflict.existing.map { it.id }.toSet()) },
        title = { Text("导入发现同名书籍") },
        text = {
            Column {
                Text("请选择保留一本或多本。未勾选的已有副本及其本地记录会移出书架，原文件和云端备份保留。",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(books, key = { it.id }) { book ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(book.id in selected, { checked -> selected = if (checked) selected + book.id else selected - book.id },
                                Modifier.testTag("import_keep_${book.id}"))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text((if (book.id == conflict.incoming.id) "正在导入" else if (book.cloudOnly) "云端待下载" else "已有书籍") + " · ${book.title}")
                                Text(book.sourceFileName ?: "原文件名未知", style = MaterialTheme.typography.bodySmall)
                                Text(book.sourceFileBytes?.let { "原书文件 ${Formatter.formatShortFileSize(context, it)}" } ?: "原书文件大小未知",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (selected.isEmpty()) Text("至少保留一本", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { onChoose(selected) }, enabled = selected.isNotEmpty(),
            modifier = Modifier.testTag("import_conflict_confirm")) { Text("保留所选") } },
        dismissButton = { TextButton(onClick = { onChoose(conflict.existing.map { it.id }.toSet()) },
            modifier = Modifier.testTag("import_conflict_skip")) { Text("跳过导入") } })
}
