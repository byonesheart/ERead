package com.eyue.reader.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
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
import com.eyue.reader.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun BatchRemoveBooksDialog(books: List<Book>, speechCacheRevision: Int, cloudConfigured: Boolean,
    meteredNetwork: Boolean, onConfirm: (Set<String>, Boolean) -> Unit, onDismiss: () -> Unit,
    onCloudBytes: (suspend (List<Book>) -> Map<String, Long>)? = null, canDeleteOriginal: Boolean = false,
    tag: String = "shelf_batch_remove") {
    val context = LocalContext.current
    var includeCloud by remember(books) { mutableStateOf(false) }
    var original by remember(books) { mutableStateOf(false) }
    var allowMetered by remember(books) { mutableStateOf(false) }
    var retry by remember(books) { mutableIntStateOf(0) }
    val sizes by produceState<Map<String, Result<Long>>?>(null, books, speechCacheRevision) {
        value = withContext(Dispatchers.IO) { books.associate { book -> book.id to runCatching { localBookStorageBytes(context, book) } } }
    }
    val originals by produceState<Map<String, String>>(emptyMap(), books, canDeleteOriginal) {
        value = if (!canDeleteOriginal) emptyMap() else withContext(Dispatchers.IO) {
            books.mapNotNull { book -> bookOriginalSource(context, book)?.let { book.id to it } }.toMap()
        }
    }
    val cloudSizes by produceState<Result<Map<String, Long>>?>(null, books, cloudConfigured, retry) {
        value = null
        value = try { Result.success(if (cloudConfigured && onCloudBytes != null) onCloudBytes(books) else emptyMap()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    val remote = cloudSizes?.getOrNull().orEmpty().filterKeys { id -> books.any { it.id == id } }
    val cloud = includeCloud && remote.isNotEmpty()
    val ready = sizes?.let { measured -> books.all { measured[it.id]?.isSuccess == true } } == true
    val canRemove = ready && (!cloud || !meteredNetwork || allowMetered)
    fun sizeLabel(bytes: Result<Long>?): String = when {
        bytes == null -> "统计中…"
        bytes.isFailure -> "统计失败"
        else -> Formatter.formatShortFileSize(context, bytes.getOrThrow())
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("${tag}_dialog"),
        title = { Text(if (books.size == 1) "移出《${books.single().title}》？" else "删除选中的 ${books.size} 本书？") }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("删除本应用中的书籍缓存、AI 音频、阅读进度及标注。原文件默认保留，文件夹不会被删除。", style = MaterialTheme.typography.bodySmall) }
                items(books, key = { it.id }) { book ->
                    Column {
                        Text(book.title, style = MaterialTheme.typography.bodyMedium)
                        Text("本地占用：${sizeLabel(sizes?.get(book.id))}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("${tag}_${book.id}_space"))
                        remote[book.id]?.let { Text("云端占用：${Formatter.formatShortFileSize(context, it)}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("${tag}_${book.id}_cloud_space")) }
                        if (original) originals[book.id]?.let { Text("将删除原文件：$it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item { Text("本地占用合计：" + if (ready) Formatter.formatShortFileSize(context, sizes!!.values.sumOf { it.getOrThrow() }) else "统计中…",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${tag}_space")) }
                if (cloudSizes == null && cloudConfigured) item { Text("正在检查云端备份…", style = MaterialTheme.typography.bodySmall) }
                if (cloudSizes?.isFailure == true) item {
                    Text("云端占用查询失败，可重试或仅移除本地数据。", style = MaterialTheme.typography.bodySmall)
                    TextButton({ retry++ }) { Text("重试") }
                }
                if (remote.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().clickable { includeCloud = !includeCloud; allowMetered = false }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cloud, { includeCloud = it; allowMetered = false }, modifier = Modifier.testTag("${tag}_cloud"))
                        Text("同时清空云端数据", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("云端占用合计：${Formatter.formatShortFileSize(context, remote.values.sum())}（含正文、记录和封面）", style = MaterialTheme.typography.bodySmall)
                }
                if (originals.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().clickable { original = !original }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(original, { original = it }, modifier = Modifier.testTag("${tag}_original"))
                        Text("删除本地原文件", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("勾选后删除上方列出的原文件，无法通过本地书架恢复；未勾选时保留原件。", style = MaterialTheme.typography.bodySmall)
                }
                if (cloud && meteredNetwork) item {
                    Row(Modifier.fillMaxWidth().clickable { allowMetered = !allowMetered }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowMetered, { allowMetered = it }, modifier = Modifier.testTag("${tag}_metered"))
                        Text("当前为计费网络，允许本次云端操作", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = canRemove, onClick = { onConfirm(if (cloud) remote.keys else emptySet(), original) },
                modifier = Modifier.testTag("${tag}_confirm")) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}_cancel")) { Text("取消") } })
}
