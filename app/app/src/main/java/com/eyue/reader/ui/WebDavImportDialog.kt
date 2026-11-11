package com.eyue.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.cloud.CloudCredentials
import com.eyue.reader.data.cloud.WebDavCloudTransport
import com.eyue.reader.data.cloud.WebDavImportEntry
import com.eyue.reader.data.cloud.WebDavImportProfile
import com.eyue.reader.data.cloud.WebDavImportProfiles
import com.eyue.reader.data.cloud.WebDavImports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WebDavImportDialog(mode: LibraryMode, onDismiss: () -> Unit, onQueued: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { WebDavImportProfiles(context) }
    val scope = rememberCoroutineScope()
    var profileRevision by remember { mutableIntStateOf(0) }
    val profiles by produceState(emptyList(), store, profileRevision) { value = withContext(Dispatchers.IO) { store.list() } }
    var profile by remember { mutableStateOf<WebDavImportProfile?>(null) }
    var removing by remember { mutableStateOf<WebDavImportProfile?>(null) }
    var folder by remember { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<WebDavImportEntry>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var configuring by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runTask(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "网盘操作失败" }
            finally { busy = false }
        }
    }
    fun browse(target: WebDavImportProfile, path: List<String>) = runTask {
        val contents = withContext(Dispatchers.IO) {
            webDavFolder(requireNotNull(store.read(target.id)) { "请重新填写网盘连接信息" }, path)
        }
        profile = target
        folder = path
        entries = contents
        selected = emptySet()
    }
    fun connect() = runTask {
        val credentials = CloudCredentials(account.trim(), password, server)
        val (saved, contents) = withContext(Dispatchers.IO) {
            val contents = webDavFolder(credentials, emptyList())
            store.save(credentials) to contents
        }
        profile = saved
        profileRevision++
        entries = contents
        folder = emptyList()
        selected = emptySet()
        configuring = false
        password = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 WebDAV 导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (configuring) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(server, { server = it }, label = { Text("WebDAV 服务器地址") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(account, { account = it }, label = { Text("账户") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    }
                } else if (profile == null) {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(profiles, key = { it.id }) { saved ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { browse(saved, emptyList()) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    Text("${saved.account} · ${saved.server}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { removing = saved }, enabled = !busy) {
                                    Icon(Icons.Outlined.Close, "移除网盘配置")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { configuring = true; error = null }, enabled = !busy) { Text("新增网盘") }
                } else {
                    Text(folder.joinToString("/", prefix = "/"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = { profile = null; selected = emptySet(); error = null }, enabled = !busy) { Text("切换网盘") }
                        if (folder.isNotEmpty()) TextButton(onClick = { browse(requireNotNull(profile), folder.dropLast(1)) },
                            enabled = !busy) { Text("上一级") }
                    }
                    if (entries.isEmpty()) Text("这个目录没有可导入的图书")
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(entries, key = { it.name }) { entry ->
                            fun choose() {
                                if (entry.directory) browse(requireNotNull(profile), folder + entry.name)
                                else selected = if (entry.name in selected) selected - entry.name else selected + entry.name
                            }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = ::choose),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (entry.directory) TextButton(onClick = ::choose, enabled = !busy) { Text("打开") }
                                else Checkbox(entry.name in selected, onCheckedChange = { choose() }, enabled = !busy)
                                Text(entry.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                configuring -> TextButton(onClick = ::connect, enabled = !busy &&
                    server.isNotBlank() && account.isNotBlank() && password.isNotBlank()) { Text("连接并保存") }
                profile != null -> TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                    runTask {
                        WebDavImports.enqueue(context, requireNotNull(profile), folder, entries.filter { it.name in selected }, mode)
                        onQueued()
                    }
                }) { Text("导入 ${selected.size} 本") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
    removing?.let { saved ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除网盘配置？") },
            text = { Text(saved.server) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    runTask {
                        withContext(Dispatchers.IO) { store.remove(saved) }
                        profileRevision++
                    }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}

private suspend fun webDavFolder(credentials: CloudCredentials, path: List<String>): List<WebDavImportEntry> = coroutineScope {
    val job = currentCoroutineContext()
    val transport = WebDavCloudTransport(credentials) { job.ensureActive() }
    val watcher = launch {
        try { while (true) delay(200) } finally { transport.cancel() }
    }
    try { transport.importEntries(path) } finally {
        transport.cancel()
        watcher.cancel()
    }
}
