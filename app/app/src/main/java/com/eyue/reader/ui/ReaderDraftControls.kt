package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.BookSaveProgress

@Composable
internal fun ReaderDraftSaveAction(progress: BookSaveProgress?, busy: Boolean, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onSave, enabled = !busy && progress == null,
            contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.width(48.dp).testTag("reader_save_book")) {
            Text("保存", fontSize = 12.sp, maxLines = 1)
        }
        if (progress != null) Column(Modifier.width(36.dp).testTag("reader_save_progress")
            .semantics { contentDescription = progress.stage }, horizontalAlignment = Alignment.CenterHorizontally) {
            val fraction = progress.fraction?.coerceIn(0f, 1f)
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Text(fraction?.let { "${(it * 100).toInt()}%" } ?: "保存中", fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun ReaderUnsavedChangesDialog(titles: List<String>, saving: Boolean, onSave: () -> Unit,
    onDiscard: () -> Unit, onCancel: () -> Unit, preparing: Boolean = false, progress: BookSaveProgress? = null) {
    AlertDialog(onDismissRequest = { if (!saving) onCancel() },
        modifier = Modifier.testTag("reader_unsaved_dialog"),
        title = { Text("有未保存的正文修改") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titles.joinToString("、") { "《$it》" })
            Text("保存后再离开，或放弃本次修改。")
            Text("放弃会还原正文和阅读位置，并丢弃预览期间新增或修改的标注。", style = MaterialTheme.typography.bodySmall)
            if (saving) Column(Modifier.fillMaxWidth().testTag("reader_unsaved_progress"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fraction = progress?.fraction
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text((progress?.stage ?: if (preparing) "正在准备修改…" else "正在保存…") +
                    (fraction?.let { " ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: ""))
            }
        } },
        confirmButton = { TextButton(onClick = onSave, enabled = !saving,
            modifier = Modifier.testTag("reader_unsaved_save")) { Text("保存并离开") } },
        dismissButton = { Row {
            TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.testTag("reader_unsaved_discard")) { Text("放弃修改") }
            TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.testTag("reader_unsaved_cancel")) { Text("继续编辑") }
        } }, shape = RoundedCornerShape(20.dp))
}
