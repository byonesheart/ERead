package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eyue.reader.BuildConfig
import com.eyue.reader.R
import com.eyue.reader.data.AppUpdateClient
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.DeveloperModePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val AUTHOR_REPOSITORY = "https://github.com/byonesheart/ERead"
internal const val AUTHOR_PROJECTS = "https://byonesheart.beer"
internal const val AUTHOR_BILIBILI = "https://space.bilibili.com/481766725"
internal const val AUTHOR_ISSUES = "https://github.com/byonesheart/ERead/issues"
internal const val AUTHOR_QQ_GROUP = "https://qun.qq.com/universal-share/share?ac=1&authKey=uDvL1dKO4N9Ml3frBbU53mJkECbDfdfOzcEXej2d1tWiP%2BXUQBnNFRCVLGmvmDd8&busi_data=eyJncm91cENvZGUiOiI1MzgyNjIwODgiLCJ0b2tlbiI6IjNZTDVoWmg5c3h4a1lhQXNoNDBrNC9wemRqanhCSk1YWHFYQjUwVzZrQ2hOZk81VHY1Uk9YVmhmZlRmWGdzd0IiLCJ1aW4iOiIzNjE1NDMwODM2In0%3D&data=XZA8m6dBfhaiQ7ZIeOmEyzUudX0Mt1VFSoJmaaCRZ1Pi2LJXFNcyb_Bnl5WHVtRVG5tfe5M7jWEht6EDL8BY8g&svctype=4&tempid=h5_group_info"

internal fun openAuthorLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).apply {
        selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(intent) }
    catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show() }
    }
}

@Composable internal fun BrowserLink(label: String, url: String, tag: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = { openAuthorLink(context, url) }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, Modifier.weight(1f))
        Icon(Icons.Outlined.OpenInNew, "在浏览器打开", Modifier.size(18.dp))
    }
}

@Composable internal fun AuthorContent(developerMode: Boolean = false, onEnableDeveloperMode: () -> Unit = {}) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.author_avatar), "作者头像", Modifier.size(112.dp).clip(CircleShape).clickable {
            if (!developerMode) {
                val now = android.os.SystemClock.elapsedRealtime()
                taps = if (taps > 0 && now - lastTap <= 1500L) taps + 1 else 1
                lastTap = now
                if (taps == 10) { taps = 0; onEnableDeveloperMode() }
            }
        }.testTag("author_avatar"), contentScale = ContentScale.Crop)
        Text("byonesheart", style = MaterialTheme.typography.titleLarge)
        if (developerMode) Text("开发者模式已开启", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("developer_mode_enabled"))
        BrowserLink("GitHub · ERead", AUTHOR_REPOSITORY, "author_repository")
        BrowserLink("B站 · ByOnesHeart", AUTHOR_BILIBILI, "author_bilibili")
    }
}

@Composable internal fun ContributorsContent() {
    Column(Modifier.fillMaxWidth().testTag("contributors_content"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.contributor_7mrlan), "7Mrlan 的头像", Modifier.size(88.dp).clip(appShape(12.dp)),
            contentScale = ContentScale.Crop)
        Text("7Mrlan", style = MaterialTheme.typography.titleLarge)
        Text("提供星彩主题的界面设计与参考源码，参与软件测试，发现并反馈多项 Bug。",
            style = MaterialTheme.typography.bodyMedium)
        BrowserLink("GitHub · 7Mrlan", "https://github.com/7Mrlan", "contributor_7mrlan_github")
    }
}

@Composable internal fun SupportContent() {
    var enlarged by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("如果觉得好用，可以给我点个免费的赞吗？", style = MaterialTheme.typography.titleMedium)
        BrowserLink("GitHub · ERead", AUTHOR_REPOSITORY, "support_repository")
        BrowserLink("B站 · ByOnesHeart", AUTHOR_BILIBILI, "support_bilibili")
        HorizontalDivider()
        Text("打赏作者", style = MaterialTheme.typography.titleMedium)
        Text("打赏完全自愿，不影响软件功能", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("微信赞赏码 · 点击查看大图", style = MaterialTheme.typography.bodySmall)
        Image(painterResource(R.drawable.support_wechat), "微信赞赏码", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).clickable { enlarged = true }.testTag("support_wechat"))
    }
    if (enlarged) Dialog(onDismissRequest = { enlarged = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Image(painterResource(R.drawable.support_wechat), "微信赞赏码大图", Modifier.fillMaxWidth().weight(1f, fill = false), contentScale = ContentScale.Fit)
                TextButton(onClick = { enlarged = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable internal fun FeedbackContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("反馈时请附上软件版本、手机型号与复现步骤。日志或截图中的密钥、密码和私人内容请先遮盖。")
        BrowserLink("加入QQ群 · E阅交流（538262088）", AUTHOR_QQ_GROUP, "feedback_qq")
        BrowserLink("B站 · ByOnesHeart", AUTHOR_BILIBILI, "feedback_bilibili")
        BrowserLink("GitHub · 问题反馈", AUTHOR_ISSUES, "feedback_github")
    }
}

@Composable internal fun AboutIntro(onCheckUpdate: (suspend () -> AppUpdateResult)? = null, onOpenChangelog: () -> Unit = {},
    availableUpdate: AppUpdateResult? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { AppUpdateClient() }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AppUpdateResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("E阅", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Muted)
            TextButton(onClick = {
                checking = true; result = null; error = null
                scope.launch {
                    try { result = onCheckUpdate?.invoke() ?: client.check(BuildConfig.VERSION_CODE, DeveloperModePreferences(context).enabled) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "无法连接更新服务器，请稍后重试" }
                    finally { checking = false }
                }
            }, enabled = !checking, modifier = Modifier.testTag("settings_check_update")) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(if (checking) "检查中…" else "检查更新", Modifier.padding(start = 4.dp))
            }
        }
        error?.let { Text("检查失败：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings_update_error")) }
        (result ?: availableUpdate)?.let { update ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("settings_update_result")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when { !update.published -> "尚未发布更新信息"; update.available && update.prerelease -> "发现测试版本 ${update.versionName}"
                        update.available -> "发现新版本 ${update.versionName}"; else -> "✓ 当前已是最新版本" }, style = MaterialTheme.typography.titleSmall)
                    if (!update.published) {
                        Text(update.changelog, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openAuthorLink(context, update.releaseUrl) }) { Text("查看 GitHub 仓库") }
                    }
                    if (update.available) {
                        if (update.changelog.isNotBlank()) Text(update.changelog, style = MaterialTheme.typography.bodySmall)
                        BrowserLink("GitHub Releases · 下载", update.downloadUrl ?: update.releaseUrl, "update_github_download")
                        if (!update.prerelease) update.mirrors.forEachIndexed { index, mirror ->
                            BrowserLink(mirror.name + if (mirror.extractionCode.isBlank()) "" else " · 提取码 ${mirror.extractionCode}",
                                mirror.url, "update_mirror_$index")
                        }
                    }
                }
            }
        }
        Text("本地小说与漫画阅读器，支持 EPUB、TXT、MOBI、PDF、图片和漫画文件夹。管理个人书架，记录书签笔记，使用系统或 AI 语音朗读，并可选用 WebDAV 备份。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenChangelog, modifier = Modifier.testTag("settings_open_changelog")) { Text("更新日志") }
    }
}

@Composable internal fun CurrentChangelogContent(onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("settings_current_changelog")) {
        Text("E阅 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
        Text("• 拖拽翻页时纸张实时跟随手指，支持拖回、松手翻页或回弹。\n• 滑动条直接显示，仅一级设置分组保留折叠与重置。\n• 修复反复拖动滑动条时的断续与旧数值回写。", style = MaterialTheme.typography.bodyMedium)
        Text("更多功能请自行体验。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onHistory, modifier = Modifier.testTag("settings_history_changelog")) { Text("历史更新日志") }
    }
}

@Composable internal fun LegalDocumentContent(name: String) {
    val context = LocalContext.current
    val text = remember(name, context) { context.assets.open("legal/$name.md").bufferedReader().use { it.readText() } }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("legal_document_$name")) {
            text.split(Regex("\\r?\\n\\s*\\r?\\n")).forEach { paragraph ->
                val title = paragraph.startsWith("#")
                val line = paragraph.trimStart('#', ' ')
                val linkColor = MaterialTheme.colorScheme.primary
                val annotated = buildAnnotatedString {
                    append(line)
                    Regex("https?://[^\\s；，。]+").findAll(line).forEach { match ->
                        addLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.let { openAuthorLink(context, it.url) } }),
                            match.range.first, match.range.last + 1)
                    }
                }
                Text(annotated, style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    BrowserLink("联系作者 · GitHub 问题反馈", AUTHOR_ISSUES, "legal_contact")
}

@Composable internal fun OpenSourceContent() {
    LegalDocumentContent("open_source")
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    val files = remember(context) { context.assets.list("legal/licenses").orEmpty().sorted() }
    Spacer(Modifier.height(12.dp))
    Text("许可证与上游声明", style = MaterialTheme.typography.titleSmall)
    files.forEach { file -> TextButton(onClick = { selected = file }, modifier = Modifier.fillMaxWidth()) { Text(file) } }
    selected?.let { file ->
        val content = remember(file) { context.assets.open("legal/licenses/$file").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(file) }, text = {
            SelectionContainer { Text(content, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}
