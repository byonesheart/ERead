package com.eyue.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ConsentStore

@Composable
internal fun ConsentGate(onExit: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ConsentStore(context) }
    var accepted by remember { mutableStateOf(store.isAccepted()) }
    if (accepted) {
        content()
        return
    }
    var checked by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    BackHandler { if (document != null) document = null else onExit() }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkAppColors else LightAppColors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (document != null) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { document = null }, modifier = Modifier.testTag("consent_document_back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                        Text(if (document == "privacy") "隐私政策" else "用户协议", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        LegalDocumentContent(document!!)
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text("欢迎使用 E阅", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(20.dp))
                        Text("E阅是一款本地小说与漫画阅读器。请先阅读并同意隐私政策和用户协议。不同意可退出。")
                        Spacer(Modifier.height(12.dp))
                        Text("书籍与阅读记录主要保存在本机。WebDAV 备份和在线 AI 语音均由你选择开启；启用后，所选书籍或朗读文本会发送给你配置的服务。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { document = "privacy" }, modifier = Modifier.testTag("consent_privacy")) { Text("隐私政策") }
                            TextButton(onClick = { document = "terms" }, modifier = Modifier.testTag("consent_terms")) { Text("用户协议") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, { checked = it }, Modifier.testTag("consent_checkbox"))
                            Text("我已阅读并同意隐私政策和用户协议", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (saveFailed) Text("同意记录保存失败，请检查存储空间后重试。", color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onExit, Modifier.weight(1f).testTag("consent_exit")) { Text("不同意并退出") }
                        Button(onClick = { if (store.accept()) accepted = true else saveFailed = true },
                            enabled = checked, modifier = Modifier.weight(1f).testTag("consent_accept")) { Text("同意并进入") }
                    }
                }
            }
        }
    }
}
