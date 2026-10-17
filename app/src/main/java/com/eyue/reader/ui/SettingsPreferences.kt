package com.eyue.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.*
import com.eyue.reader.data.cloud.DEFAULT_WEBDAV_SERVER
import com.eyue.reader.data.cloud.normalizeWebDavServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class SettingsPage(val title: String) {
    SPEECH("朗读引擎"), CLOUD("WebDAV 备份与还原"), CACHE("清理缓存"),
    FEEDBACK("问题反馈"), SUPPORT("支持作者"), ABOUT("关于"), AUTHOR("作者"),
    PRIVACY("隐私政策"), TERMS("用户协议"), OPEN_SOURCE("开源相关"), CHANGELOG("更新日志")
}

@Suppress("UNUSED_PARAMETER")
@Composable fun SettingsScreen(state: LibraryState, onSettings: (ReaderSettings) -> Unit,
    onChapterRule: (String) -> Unit, onImportFont: () -> Unit, onClearHistory: () -> Unit, mode: LibraryMode = LibraryMode.NOVEL,
    cloudAccount: String = "", cloudServerUrl: String = DEFAULT_WEBDAV_SERVER,
    cloudConfigured: Boolean = false, cloudBusy: Boolean = false, cloudTaskLabel: String = "",
    cloudFraction: Float? = null, onSaveCloudAccount: (String, String, String) -> Unit = { _, _, _ -> },
    onTestCloudConnection: () -> Unit = {}, onCloudAutoBackup: (Boolean) -> Unit = {},
    onRestoreCloudBookInfo: () -> Unit = {}, onDisconnectCloud: () -> Unit = {},
    onCloudAutoBackupMetered: (Boolean) -> Unit = {}, aiSpeechConfig: AiSpeechConfig? = null,
    onSpeechEngine: (SpeechEngine) -> Unit = {}, onSaveAiSpeech: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    aiSpeechProfiles: List<AiSpeechConfig> = emptyList(), onSelectAiSpeechProfile: (String) -> Unit = {},
    onSaveAiSpeechProfile: ((AiSpeechConfig) -> Unit)? = null,
    onTestAiSpeechConnection: (suspend (AiSpeechConfig) -> String)? = null,
    onThemeMode: (ThemeMode) -> Unit = {}, onClearAiSpeechCaches: () -> Unit = {},
    onTestCloudDraftConnection: (suspend (account: String, password: String, server: String) -> String)? = null,
    onAiSpeechFallback: (Boolean) -> Unit = {}, onBookVoiceRoles: (String) -> Unit = {},
    onCleanup: ((LibraryCleanupKind, Boolean) -> Unit)? = null, speechCacheRevision: Int = 0,
    meteredNetwork: Boolean = false, onCommonVoiceRoles: () -> Unit = {},
    onEinkMode: (Boolean) -> Unit = {}, onTabletDualPage: (Boolean) -> Unit = {},
    onCheckUpdate: (suspend () -> AppUpdateResult)? = null) {
    var pages by remember { mutableStateOf(emptyList<SettingsPage>()) }
    var cleanup by remember { mutableStateOf<LibraryCleanupKind?>(null) }
    val page = pages.lastOrNull()
    val context = LocalContext.current
    val cleanupUsage by produceState<Result<LibraryCleanupUsage>?>(null, state, speechCacheRevision, page == SettingsPage.CACHE) {
        value = if (page != SettingsPage.CACHE) null else try {
            Result.success(withContext(Dispatchers.IO) { measureLibraryCleanup(context, state) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }
    fun open(value: SettingsPage) { pages = pages + value }
    fun back() { pages = pages.dropLast(1) }
    BackHandler(enabled = page != null, onBack = ::back)
    Column(Modifier.fillMaxSize().testTag("settings_screen")) {
        if (page != null) Row(Modifier.fillMaxWidth().height(52.dp).semantics(mergeDescendants = true) {}.testTag("settings_subpage_header"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back, modifier = Modifier.testTag("settings_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
            Text(page.title, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                null -> {
                    SettingsCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("settings_theme")) {
                            Text("夜间模式", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色", ThemeMode.SYSTEM to "系统").forEach { (value, label) ->
                                    FilterChip(selected = state.themeMode == value, onClick = { onThemeMode(value) },
                                        label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f).testTag("settings_theme_${value.name.lowercase()}"))
                                }
                            }
                        }
                        SettingsSeparator()
                        SettingsToggle("墨水屏模式", "高对比度显示，关闭翻页动画", state.einkMode, onEinkMode, "settings_eink_mode")
                        SettingsSeparator()
                        SettingsToggle("平板横屏双页", "宽屏横向翻页时并排显示两页", state.tabletDualPage, onTabletDualPage, "settings_tablet_dual_page")
                        SettingsSeparator()
                        SettingsLink(Icons.Outlined.Headphones, "朗读引擎", if (state.speechEngine == SpeechEngine.AI) "AI 语音" else "系统语音",
                            "settings_speech", { open(SettingsPage.SPEECH) })
                        SettingsSeparator()
                        SettingsLink(Icons.Outlined.Cloud, "WebDAV 备份与还原", "", "settings_cloud", { open(SettingsPage.CLOUD) })
                        SettingsSeparator()
                        SettingsLink(Icons.Outlined.CleaningServices, "清理缓存", "", "settings_cache", { open(SettingsPage.CACHE) })
                    }
                    SettingsCard {
                        SettingsLink(Icons.Outlined.Feedback, "问题反馈", "", "settings_feedback", { open(SettingsPage.FEEDBACK) })
                        SettingsSeparator()
                        SettingsLink(Icons.Outlined.FavoriteBorder, "支持作者", "", "settings_support", { open(SettingsPage.SUPPORT) })
                        SettingsSeparator()
                        SettingsLink(Icons.Outlined.Info, "关于", "", "settings_about", { open(SettingsPage.ABOUT) })
                    }
                }
                SettingsPage.SPEECH -> {
                    SpeechEngineSettings(state.speechEngine, aiSpeechConfig, onSpeechEngine, onSaveAiSpeech,
                    profiles = aiSpeechProfiles, onSelectProfile = onSelectAiSpeechProfile, onSaveProfile = onSaveAiSpeechProfile,
                    onTestConnection = onTestAiSpeechConnection,
                    fallbackEnabled = state.aiSpeechFallback, onFallbackEnabled = onAiSpeechFallback)
                    SettingsCard {
                        SettingsLink(Icons.Outlined.RecordVoiceOver, "通用角色", "${state.commonVoiceRoles.size} 个角色",
                            "settings_common_voice_roles", onCommonVoiceRoles)
                    }
                    SettingsCard {
                        Text("各书角色音色", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                        state.books.filter { it.mode == LibraryMode.NOVEL && it.voiceRoles.isNotEmpty() }.forEach { book ->
                            SettingsLink(Icons.Outlined.RecordVoiceOver, book.title, "${book.voiceRoles.size} 个角色",
                                "settings_book_roles_${book.id}", { onBookVoiceRoles(book.id) })
                        }
                        if (state.books.none { it.mode == LibraryMode.NOVEL && it.voiceRoles.isNotEmpty() }) Text("暂无角色信息", Modifier.padding(16.dp))
                    }
                }
                SettingsPage.CLOUD -> CloudBackupSettings(cloudAccount, cloudConfigured, state.cloudAutoBackup, cloudBusy,
                    cloudTaskLabel, cloudFraction, onSaveCloudAccount, onTestCloudConnection, onCloudAutoBackup,
                    onRestoreCloudBookInfo, onDisconnectCloud, cloudServerUrl, state.cloudAutoBackupMetered,
                    onCloudAutoBackupMetered, onTestCloudDraftConnection)
                SettingsPage.CACHE -> {
                    SettingsCard {
                        LibraryCleanupKind.entries.forEachIndexed { index, kind ->
                            if (index > 0) SettingsSeparator()
                            SettingsLink(when (kind) {
                                LibraryCleanupKind.HISTORY -> Icons.Outlined.History
                                LibraryCleanupKind.AI_AUDIO -> Icons.Outlined.Headphones
                                LibraryCleanupKind.ANNOTATIONS -> Icons.Outlined.EditNote
                                LibraryCleanupKind.ROLES -> Icons.Outlined.RecordVoiceOver
                            }, cleanupTitle(kind), when {
                                cleanupUsage == null -> "统计中…"
                                cleanupUsage!!.isFailure -> "统计失败"
                                else -> android.text.format.Formatter.formatShortFileSize(context, cleanupUsage!!.getOrThrow().bytes(kind))
                            }, cleanupTag(kind), { cleanup = kind })
                        }
                    }
                }
                SettingsPage.ABOUT -> {
                    SettingsCard {
                        AboutIntro(onCheckUpdate, onOpenChangelog = { open(SettingsPage.CHANGELOG) })
                    }
                    SettingsCard {
                        listOf(SettingsPage.AUTHOR, SettingsPage.PRIVACY, SettingsPage.TERMS, SettingsPage.OPEN_SOURCE).forEachIndexed { index, destination ->
                            if (index > 0) SettingsSeparator()
                            SettingsLink(Icons.Outlined.ChevronRight, destination.title, "", "settings_about_${destination.name.lowercase()}", { open(destination) })
                        }
                    }
                }
                SettingsPage.AUTHOR -> AuthorContent()
                SettingsPage.SUPPORT -> SupportContent()
                SettingsPage.FEEDBACK -> FeedbackContent()
                SettingsPage.PRIVACY -> LegalDocumentContent("privacy")
                SettingsPage.TERMS -> LegalDocumentContent("terms")
                SettingsPage.OPEN_SOURCE -> OpenSourceContent()
                SettingsPage.CHANGELOG -> CurrentChangelogContent()
            }
        }
    }
    cleanup?.let { kind ->
        CleanupDialog(cleanupTitle(kind) + "？", when (kind) {
            LibraryCleanupKind.HISTORY -> "阅读首页的历史记录将清空。书架、阅读进度和书签笔记继续保留。"
            LibraryCleanupKind.AI_AUDIO -> "删除所有书籍已生成的 AI 音频，下次朗读时重新生成。书籍、阅读进度和标注保留。"
            LibraryCleanupKind.ANNOTATIONS -> "清空全部书籍的笔记、彩色标记和划线，保留书签及阅读进度。"
            LibraryCleanupKind.ROLES -> "清空通用及全部书籍的角色名字、音色、描述及场景资料。"
        }, cleanupUsage?.getOrNull()?.bytes(kind), cloudConfigured && onCleanup != null, meteredNetwork,
            if (kind == LibraryCleanupKind.AI_AUDIO) "云端 AI 音频占用：0 B。AI 音频仅保存在本机，勾选也不会删除云端书籍或阅读记录。"
            else "勾选后同步清空云端备份中的对应记录，保留云端书籍文件。", onConfirm = { cloud ->
                if (onCleanup != null) onCleanup(kind, cloud)
                else when (kind) {
                    LibraryCleanupKind.HISTORY -> onClearHistory()
                    LibraryCleanupKind.AI_AUDIO -> onClearAiSpeechCaches()
                    else -> Unit
                }
                cleanup = null
            }, onDismiss = { cleanup = null }, tag = cleanupTag(kind),
            confirmLabel = if (kind == LibraryCleanupKind.AI_AUDIO) "清理" else "清空", sizeError = cleanupUsage?.isFailure == true)
    }
}

@Composable private fun SettingsToggle(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChecked, modifier = Modifier.testTag(tag))
    }
}

private fun cleanupTitle(kind: LibraryCleanupKind) = when (kind) {
    LibraryCleanupKind.HISTORY -> "清空阅读历史"
    LibraryCleanupKind.AI_AUDIO -> "清理 AI 语音缓存"
    LibraryCleanupKind.ANNOTATIONS -> "清空阅读笔记"
    LibraryCleanupKind.ROLES -> "清空角色信息"
}

private fun cleanupTag(kind: LibraryCleanupKind) = "settings_clear_" + when (kind) {
    LibraryCleanupKind.HISTORY -> "history"; LibraryCleanupKind.AI_AUDIO -> "ai_speech"
    LibraryCleanupKind.ANNOTATIONS -> "annotations"; LibraryCleanupKind.ROLES -> "roles"
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable private fun SettingsSeparator() = HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))

@Composable private fun SettingsLink(icon: ImageVector, title: String, value: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = Olive)
        Text(title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp)
        if (value.isNotBlank()) Text(value, Modifier.widthIn(max = 100.dp).padding(start = 8.dp), color = Muted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 6.dp).size(18.dp), tint = Muted)
    }
}

@Composable internal fun CloudBackupSettings(account: String, configured: Boolean, autoBackup: Boolean, busy: Boolean,
    taskLabel: String, fraction: Float?, onSave: (String, String, String) -> Unit, onTest: () -> Unit,
    onAutoBackup: (Boolean) -> Unit, onRestore: () -> Unit, onDisconnect: () -> Unit,
    serverUrl: String = DEFAULT_WEBDAV_SERVER, autoBackupMetered: Boolean = false,
    onAutoBackupMetered: (Boolean) -> Unit = {},
    onTestDraftConnection: (suspend (account: String, password: String, server: String) -> String)? = null) {
    var accountDialog by remember { mutableStateOf(false) }
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("cloud_settings"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = { accountDialog = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("cloud_open_account")) {
                Text(if (configured) "网盘配置 · $account" else "网盘配置", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(17.dp), color = Olive, strokeWidth = 2.dp)
                Text(taskLabel, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                fraction?.let { Text("${(it.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 12.sp) }
            }
            CloudPreferenceToggle("自动备份", "仅更新已手动上传的书籍及其记录", autoBackup, configured && !busy,
                "cloud_auto_backup", onAutoBackup)
            CloudPreferenceToggle("允许计费网络自动备份", "可能使用移动数据", autoBackupMetered, configured && !busy,
                "cloud_auto_backup_metered", onAutoBackupMetered)
            HorizontalDivider(color = Line)
            Button(onClick = onRestore, enabled = configured && !busy, modifier = Modifier.fillMaxWidth().testTag("cloud_restore_info")) {
                Text("手动还原书籍信息")
            }
            Text("先还原书架、分组及阅读记录，再点击书籍云图标下载正文。", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (configured) TextButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.align(Alignment.End).testTag("cloud_disconnect")) {
                Text("解绑此账号")
            }
        }
    }
    if (accountDialog) CloudAccountDialog(account, serverUrl, configured, busy, onSave, onTestDraftConnection, onTest) { accountDialog = false }
}

@Composable private fun CloudPreferenceToggle(title: String, detail: String, checked: Boolean, enabled: Boolean, tag: String,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable private fun CloudAccountDialog(account: String, serverUrl: String, configured: Boolean, busy: Boolean,
    onSave: (String, String, String) -> Unit,
    onTestDraft: (suspend (String, String, String) -> String)?, onTestSaved: () -> Unit, onDismiss: () -> Unit) {
    var accountDraft by remember(account) { mutableStateOf(account) }
    var serverDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var passwordDraft by remember(account, serverUrl, configured) { mutableStateOf("") }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val normalized = remember(serverDraft) { runCatching { normalizeWebDavServer(serverDraft) } }
    val error = normalized.exceptionOrNull()?.message
    val sameAccount = configured && accountDraft.trim() == account && normalized.getOrNull() == serverUrl
    val valid = error == null && accountDraft.isNotBlank() && (passwordDraft.isNotBlank() || sameAccount)
    fun invalidateTest() { generation++; testJob?.cancel(); testJob = null; testing = false; testResult = null }
    fun close() { invalidateTest(); onDismiss() }
    DisposableEffect(Unit) { onDispose { testJob?.cancel() } }
    AlertDialog(onDismissRequest = ::close, modifier = Modifier.testTag("cloud_account_dialog"), title = { Text("网盘配置") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(serverDraft, { invalidateTest(); serverDraft = it }, label = { Text("WebDAV 服务器") },
                modifier = Modifier.fillMaxWidth().testTag("cloud_server"), enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = error != null,
                supportingText = { Text(error ?: if (normalized.getOrNull()?.startsWith("http:") == true)
                    "HTTP 会明文传输账号密码，建议使用 HTTPS" else "填写完整 WebDAV 路径，备份保存在其 ERead 子目录") })
            OutlinedTextField(accountDraft, { invalidateTest(); accountDraft = it }, label = { Text("账号") },
                modifier = Modifier.fillMaxWidth().testTag("cloud_account"), enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(passwordDraft, { invalidateTest(); passwordDraft = it }, label = { Text("密码 / 应用密码") },
                modifier = Modifier.fillMaxWidth().testTag("cloud_password"), enabled = !busy, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(if (sameAccount) "留空可沿用已保存的密码" else "坚果云需使用第三方应用密码；更换账号或服务器时重新填写") })
            OutlinedButton(enabled = valid && !busy && !testing && (onTestDraft != null || sameAccount),
                modifier = Modifier.fillMaxWidth().testTag("cloud_test_connection"), onClick = {
                    invalidateTest()
                    if (onTestDraft == null) onTestSaved() else {
                        val token = generation
                        val draftAccount = accountDraft.trim(); val draftPassword = passwordDraft; val draftServer = normalized.getOrThrow()
                        testing = true
                        testJob = scope.launch {
                            try {
                                val message = onTestDraft(draftAccount, draftPassword, draftServer)
                                if (generation == token) { testResult = message; testFailed = false }
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (failure: Exception) { if (generation == token) { testResult = failure.message ?: "测试连接失败"; testFailed = true } }
                            finally { if (generation == token) { testing = false; testJob = null } }
                        }
                    }
                }) { Text(if (testing) "正在测试…" else "测试连接") }
            if (testing) TextButton(onClick = ::invalidateTest, modifier = Modifier.testTag("cloud_cancel_test")) { Text("取消测试") }
            testResult?.let { Text(it, modifier = Modifier.testTag("cloud_test_result"),
                color = if (testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = {
        invalidateTest(); onSave(accountDraft.trim(), passwordDraft, normalized.getOrThrow()); passwordDraft = ""
    }, enabled = !busy && !testing && valid, modifier = Modifier.testTag("cloud_save_account")) { Text("保存账号") } },
        dismissButton = { TextButton(onClick = ::close) { Text("关闭") } })
}
