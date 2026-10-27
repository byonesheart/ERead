package com.eyue.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.Book
import java.io.File

internal fun bookFileManagerIntent(context: Context, book: Book): Intent? {
    if (book.cloudOnly || book.isDemo) return null
    val original = book.sourceUri?.let(Uri::parse)
    val document = original?.takeIf { it.scheme == "content" && DocumentsContract.isDocumentUri(context, it) }
        ?: run {
            val path = if (original?.scheme == "file") original.path else book.sourcePath
            val file = path?.let { runCatching { File(it).canonicalFile }.getOrNull() } ?: return null
            val roots = if (Build.VERSION.SDK_INT >= 30) context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
                val id = if (volume.isPrimary) "primary" else volume.uuid
                volume.directory?.let { root -> id?.let { it to root } }
            } else listOf("primary" to Environment.getExternalStorageDirectory())
            roots.firstNotNullOfOrNull { (id, root) ->
                file.relativeToOrNull(root)?.takeUnless { it.path.startsWith("..") }?.let { relative ->
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$id:${relative.invariantSeparatorsPath}")
                }
            } ?: return null
        }
    return Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, document).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Composable internal fun BookLocationDialog(book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val location = bookStorageLocation(book)
    val intent = remember(book) { bookFileManagerIntent(context, book) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("book_location_dialog"), title = { Text("书籍位置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectionContainer { Text(location, style = MaterialTheme.typography.bodySmall) }
            if (book.sourceUri != null && book.sourceUri != location) {
                Text("原文件", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(book.sourceUri, style = MaterialTheme.typography.bodySmall) }
            }
            if (intent == null && !book.cloudOnly && !book.isDemo)
                Text("应用私有目录或此文档提供方不支持直接定位，可复制位置。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = { clipboard.setText(AnnotatedString(location)); copied = true }, modifier = Modifier.testTag("book_location_copy")) {
            Text(if (copied) "已复制" else "复制位置")
        }
    }, dismissButton = {
        Row {
            TextButton(enabled = intent != null, onClick = {
                try { context.startActivity(intent) }
                catch (_: ActivityNotFoundException) { error = "未找到系统文件管理器" }
                catch (_: SecurityException) { error = "无法访问该文件位置，可复制位置后手动查找" }
            }, modifier = Modifier.testTag("book_location_open")) { Text("文件管理器定位") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    })
}
