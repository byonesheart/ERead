package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.SpeechApiProtocol
import com.eyue.reader.data.SpeechEngine
import com.eyue.reader.data.requiresApiKey
import com.eyue.reader.data.resolveAiSpeechConfigDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

internal fun safeAiSpeechTestMessage(message: String, secrets: List<String>): String {
    var safe = message
    secrets.filter { it.isNotBlank() }.distinct().forEach { secret -> safe = safe.replace(secret, "[已隐藏]", ignoreCase = true) }
    safe = safe.replace(Regex("(?i)bearer\\s+[^\\s,;\"']+"), "Bearer [已隐藏]")
        .replace(Regex("(?i)sk-[a-z0-9_-]+"), "[已隐藏]")
        .replace(Regex("[\\r\\n\\p{Cntrl}]+"), " ").trim()
    if (safe.contains('{') || safe.contains('<') || safe.startsWith('[') && !safe.startsWith("[已隐藏]")) {
        val code = Regex("\\b[45][0-9]{2}\\b").find(safe)?.value
        return (code?.let { "服务返回 $it，" } ?: "") + "请检查服务器地址、模型和密钥"
    }
    return safe.take(350).ifBlank { "连接失败，请检查地址、模型和密钥" }
}

@Composable internal fun SpeechEngineDialog(engine: SpeechEngine, config: AiSpeechConfig?,
    onEngine: (SpeechEngine) -> Unit, onSave: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit, profiles: List<AiSpeechConfig> = emptyList(), onSelectProfile: (String) -> Unit = {},
    onSaveProfile: ((AiSpeechConfig) -> Unit)? = null,
    onTestConnection: (suspend (AiSpeechConfig) -> String)? = null,
    fallbackEnabled: Boolean = false, onFallbackEnabled: (Boolean) -> Unit = {}, onBookRoles: (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("speech_engine_dialog"), title = { Text("朗读设置") },
        text = { Column {
            SpeechEngineSettings(engine, config, onEngine, onSave, profiles, onSelectProfile, onSaveProfile, onTestConnection,
                fallbackEnabled, onFallbackEnabled)
            if (onBookRoles != null) OutlinedButton(onClick = onBookRoles, modifier = Modifier.fillMaxWidth().testTag("speech_book_roles")) {
                Text("本书角色音色")
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable internal fun SpeechEngineSettings(engine: SpeechEngine, config: AiSpeechConfig?,
    onEngine: (SpeechEngine) -> Unit, onSave: (String, String, String, String, String) -> Unit,
    profiles: List<AiSpeechConfig> = emptyList(), onSelectProfile: (String) -> Unit = {},
    onSaveProfile: ((AiSpeechConfig) -> Unit)? = null,
    onTestConnection: (suspend (AiSpeechConfig) -> String)? = null,
    fallbackEnabled: Boolean = false, onFallbackEnabled: (Boolean) -> Unit = {}) {
    var showConfig by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(config) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("朗读引擎", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前使用：", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (engine == SpeechEngine.AI) onEngine(SpeechEngine.SYSTEM)
                else if (config != null) onEngine(SpeechEngine.AI)
                else { editing = config; showConfig = true }
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), modifier = Modifier.weight(1f).testTag("speech_engine_toggle")) {
                Text(if (engine == SpeechEngine.SYSTEM) "系统语音" else "AI 语音")
            }
            if (engine == SpeechEngine.AI) IconButton(onClick = { editing = config; showConfig = true },
                modifier = Modifier.testTag("ai_speech_settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = "AI 语音配置")
            }
        }
        if (engine == SpeechEngine.AI) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("失败时使用系统语音", style = MaterialTheme.typography.bodyMedium)
                Text("同一段重试 3 次仍失败后替代该段，再继续 AI 朗读。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(fallbackEnabled, onFallbackEnabled, modifier = Modifier.testTag("ai_speech_fallback"))
        }
    }
    if (showConfig) key(editing?.id) {
        var id by remember { mutableStateOf(editing?.id ?: UUID.randomUUID().toString()) }
        var name by remember { mutableStateOf(editing?.name ?: "") }
        var protocol by remember { mutableStateOf(editing?.protocol ?: SpeechApiProtocol.OPENAI) }
        var url by remember { mutableStateOf(editing?.url ?: "") }
        var apiKey by remember { mutableStateOf("") }
        var model by remember { mutableStateOf(editing?.model ?: "") }
        var voice by remember { mutableStateOf(editing?.voice ?: "") }
        var instructions by remember { mutableStateOf(editing?.instructions ?: "") }
        var rolePromptEnabled by remember { mutableStateOf(editing?.rolePromptEnabled ?: false) }
        var profileMenu by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var testJob by remember { mutableStateOf<Job?>(null) }
        var testGeneration by remember { mutableIntStateOf(0) }
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var testFailed by remember { mutableStateOf(false) }
        fun clearTest() {
            testGeneration++
            testJob?.cancel(); testJob = null
            testing = false; testResult = null
        }
        DisposableEffect(Unit) { onDispose { testJob?.cancel() } }
        val voiceDesign = protocol == SpeechApiProtocol.MIMO && model.trim() == "mimo-v2.5-tts-voicedesign"
        val draftResult = remember(url, apiKey, model, voice, instructions, protocol, name, id, rolePromptEnabled) {
            runCatching { AiSpeechConfig(url.trim(), apiKey.trim(), model.trim(), voice.trim(), instructions.trim(), protocol, name.trim(), id, rolePromptEnabled) }
        }
        val draft = draftResult.getOrNull()
        val error = draftResult.exceptionOrNull()?.message
        val savedProfiles = profiles + listOfNotNull(config, editing)
        val savedKeyAvailable = draft?.let { candidate ->
            val withoutKey = AiSpeechConfig(candidate.url, "", candidate.model, candidate.voice, candidate.instructions,
                candidate.protocol, candidate.name, candidate.id, candidate.rolePromptEnabled)
            resolveAiSpeechConfigDraft(withoutKey, savedProfiles).key.isNotBlank()
        } ?: false
        val missingKey = draft?.requiresApiKey == true && apiKey.isBlank() && !savedKeyAvailable
        AlertDialog(onDismissRequest = { clearTest(); showConfig = false }, title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 语音配置")
                if (testing || testResult != null) Surface(Modifier.fillMaxWidth().testTag("ai_speech_test_status"),
                    shape = MaterialTheme.shapes.small,
                    color = if (testing) MaterialTheme.colorScheme.surfaceContainerHigh else if (testFailed)
                        MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
                    if (testing) Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在测试连接…", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("ai_speech_test_running").semantics { liveRegion = LiveRegionMode.Polite })
                    } else Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (testFailed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                            if (testFailed) "测试连接失败" else "测试连接成功", Modifier.size(24.dp)
                                .testTag(if (testFailed) "ai_speech_test_failed_icon" else "ai_speech_test_success_icon"),
                            tint = if (testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (testFailed) "连接失败" else "连接成功", fontWeight = FontWeight.Bold,
                                color = if (testFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(testResult.orEmpty(), modifier = Modifier.testTag("ai_speech_test_result")
                                .semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 6, overflow = TextOverflow.Ellipsis,
                                color = if (testFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box {
                        TextButton(onClick = { profileMenu = true }, enabled = profiles.isNotEmpty()) { Text("选择已保存配置") }
                        DropdownMenu(profileMenu, { profileMenu = false }) {
                            profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                                clearTest(); profileMenu = false; editing = profile; onSelectProfile(profile.id)
                            }) }
                        }
                    }
                    TextButton(onClick = { clearTest(); editing = null; id = UUID.randomUUID().toString(); name = ""; apiKey = ""; rolePromptEnabled = false }) { Text("新建") }
                }
                OutlinedTextField(name, { clearTest(); name = it }, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ai_profile_name"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(protocol == SpeechApiProtocol.OPENAI, { clearTest(); protocol = SpeechApiProtocol.OPENAI }, { Text("OpenAI 兼容") })
                    FilterChip(protocol == SpeechApiProtocol.MIMO, { clearTest(); protocol = SpeechApiProtocol.MIMO }, { Text("MiMo") }, modifier = Modifier.testTag("ai_protocol_mimo"))
                }
                Text(if (protocol == SpeechApiProtocol.MIMO) "使用 chat/completions 接口，返回 WAV 音频。正文将发送给你配置的服务。"
                    else "使用 audio/speech 接口，返回 MP3 音频。正文将发送给你配置的服务。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { clearTest(); url = it }, label = { Text("服务器或完整接口地址") }, singleLine = true,
                    placeholder = { Text(if (protocol == SpeechApiProtocol.MIMO) "https://api.xiaomimimo.com/v1" else "https://服务器/v1") }, modifier = Modifier.fillMaxWidth().testTag("ai_speech_url"))
                OutlinedTextField(apiKey, { clearTest(); apiKey = it }, label = { Text("API 密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("ai_speech_key"),
                    supportingText = { Text(if (savedKeyAvailable) "已保存密钥，留空沿用此服务的密钥"
                        else if (draft?.requiresApiKey == true) "此服务没有可沿用的已保存密钥，请填写 API 密钥"
                        else "没有可沿用的已保存密钥；无需密钥的服务可留空") })
                OutlinedTextField(model, { clearTest(); model = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ai_speech_model"))
                if (!voiceDesign) OutlinedTextField(voice, { clearTest(); voice = it }, label = { Text(if (protocol == SpeechApiProtocol.MIMO) "预置声音（选填）" else "声音") },
                    supportingText = { if (protocol == SpeechApiProtocol.MIMO) Text("例如：mimo_default、冰糖；留空使用服务默认音色") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ai_speech_voice"))
                OutlinedTextField(instructions, { clearTest(); instructions = it }, label = { Text(if (voiceDesign) "音色描述与朗读风格（必填）" else "朗读风格提示（选填）") },
                    placeholder = { Text(if (voiceDesign) "年轻成年女声，普通话清晰，温柔自然，适合小说朗读" else "平静自然，语速舒缓；对白区分情绪") }, minLines = 3, maxLines = 5,
                    supportingText = { Text(if (voiceDesign) "描述性别、年龄、音色、语气和节奏；此模型无需填写声音 ID。"
                        else if (protocol == SpeechApiProtocol.MIMO) "填写语气、情绪、停顿等要求，作为 user 消息发送。"
                        else "填写语气、情绪、停顿等要求。需模型支持 instructions，不支持时请留空。") }, modifier = Modifier.fillMaxWidth().testTag("ai_speech_instructions"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("角色音色加入提示词", style = MaterialTheme.typography.bodyMedium)
                        Text("此功能尚不稳定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(rolePromptEnabled, { clearTest(); rolePromptEnabled = it }, modifier = Modifier.testTag("ai_speech_role_prompt"))
                }
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (onTestConnection != null) {
                    OutlinedButton(enabled = draft != null && !missingKey && !testing, modifier = Modifier.fillMaxWidth().testTag("ai_speech_test"), onClick = {
                        val candidate = draft ?: return@OutlinedButton
                        val generation = ++testGeneration
                        fun safeMessage(message: String) = safeAiSpeechTestMessage(message, savedProfiles.map { it.key } + candidate.key)
                        testing = true; testResult = null
                        testJob = scope.launch {
                            try {
                                val result = onTestConnection(candidate)
                                if (generation == testGeneration) { testResult = "测试成功：${safeMessage(result)}"; testFailed = false }
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (failure: Exception) {
                                if (generation == testGeneration) {
                                    testResult = "测试失败：${safeMessage(failure.message ?: "连接失败，请检查地址、模型和密钥")}"; testFailed = true
                                }
                            } finally { if (generation == testGeneration) { testing = false; testJob = null } }
                        }
                    }) {
                        if (testing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    Text("使用当前填写内容合成短句，不保存配置。", style = MaterialTheme.typography.bodySmall)
                    if (missingKey) Text("请先填写 API 密钥，再测试连接。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(enabled = error == null, onClick = {
            val saved = AiSpeechConfig(url.trim(), apiKey.trim(), model.trim(), voice.trim(), instructions.trim(), protocol, name.trim(), id, rolePromptEnabled)
            clearTest()
            if (onSaveProfile != null) onSaveProfile(saved) else onSave(url, apiKey, model, voice, instructions)
            showConfig = false
        }) { Text("保存并启用") } }, dismissButton = { TextButton(onClick = { clearTest(); showConfig = false }) { Text("取消") } })
    }
}
