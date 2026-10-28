package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.Book

@Composable internal fun BookLocationDialog(book: Book, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val location = bookStorageLocation(book)
    var copied by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("book_location_dialog"), title = { Text("书籍位置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Text(location, style = MaterialTheme.typography.bodySmall) }
            if (book.sourceUri != null && book.sourceUri != location) {
                Text("原文件", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(book.sourceUri, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { clipboard.setText(AnnotatedString(location)); copied = true }, modifier = Modifier.testTag("book_location_copy")) {
            Text(if (copied) "已复制" else "复制位置")
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text("关闭") }
    })
}
