package com.eyue.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
    val document = original?.takeIf { it.scheme == "content" }?.let { uri ->
        when {
            DocumentsContract.isDocumentUri(context, uri) -> uri
            DocumentsContract.isTreeUri(uri) -> DocumentsContract.buildDocumentUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri)).takeIf { DocumentsContract.isDocumentUri(context, it) }
            else -> null
        }
    }
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
    // DocumentsUI resolves the document's provider path, opening its parent for a file
    // or the folder itself for a directory. A picker initial-URI hint can be ignored.
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val packages = context.packageManager
    val navigators = packages.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PackageManager.MATCH_DEFAULT_ONLY)
        .filter { it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
        .map { it.activityInfo.packageName }.toSet()
    // The reader also accepts comic-folder VIEW intents. Target the system document
    // navigator explicitly so this never reimports the folder or invokes a generic viewer.
    val target = packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull {
        it.activityInfo.exported && it.activityInfo.packageName != context.packageName && it.activityInfo.packageName in navigators
    } ?: return null
    return intent.setClassName(target.activityInfo.packageName, target.activityInfo.name)
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
                Text("此位置或系统文件管理器不支持直接定位，可复制位置。", style = MaterialTheme.typography.bodySmall)
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
