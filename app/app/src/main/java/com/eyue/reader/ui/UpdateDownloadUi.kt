package com.eyue.reader.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import com.eyue.reader.data.AppUpdateDownloads
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.UPDATE_READY_NOTIFICATION
import com.eyue.reader.data.UpdateDownload
import com.eyue.reader.data.UpdateDownloadStatus
import com.eyue.reader.data.safeUpdateLink
import com.eyue.reader.data.updateApk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class UpdateDownloadControls(
    val status: UpdateDownloadStatus?,
    val busy: Boolean,
    val start: (AppUpdateResult) -> Unit,
    val pause: (UpdateDownload) -> Unit,
    val resume: (UpdateDownload) -> Unit,
    val cancel: (UpdateDownload) -> Unit,
    val install: () -> Unit
) {
    val canStart get() = !busy && status == null
}

internal val LocalUpdateDownloadControls = compositionLocalOf<UpdateDownloadControls?> { null }

@Composable internal fun UpdateDownloadHost(openRequest: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val downloads = remember(appContext) { AppUpdateDownloads.get(appContext) }
    val status by downloads.status.collectAsState(initial = null)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompted by rememberSaveable { mutableStateOf<String?>(null) }
    var handledRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var showInstall by rememberSaveable { mutableStateOf(false) }
    var showTransfer by rememberSaveable { mutableStateOf(false) }
    var awaitingInstallPermission by rememberSaveable { mutableStateOf<String?>(null) }

    fun install(task: UpdateDownload) {
        try {
            val file = updateApk(context, task.token)
            check(file.isFile) { "安装包已失效，请取消任务后重新下载" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            context.getSystemService(NotificationManager::class.java).cancel(UPDATE_READY_NOTIFICATION)
        } catch (failure: Exception) {
            error = failure.takeIf { it is IllegalStateException }?.message ?: "无法打开系统安装界面"
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requested = awaitingInstallPermission
        awaitingInstallPermission = null
        val ready = status?.takeIf { it.ready && it.download.token.toString() == requested }
        if (ready != null && context.packageManager.canRequestPackageInstalls()) install(ready.download)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun command(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法处理下载任务，请稍后重试" }
            finally { busy = false }
        }
    }

    val controls = UpdateDownloadControls(status, busy,
        start = { update ->
            showTransfer = true
            command { downloads.start(update) }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        pause = { task -> command { downloads.pause(task) } },
        resume = { task -> command { downloads.resume(task) } },
        cancel = { task -> command { downloads.cancel(task) } },
        install = { showTransfer = false; showInstall = true })

    LaunchedEffect(downloads) {
        try { downloads.removeInstalledDownload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retry private cache cleanup the next time the app opens. */ }
    }
    LaunchedEffect(status, lifecycle, openRequest) {
        val transfer = status ?: return@LaunchedEffect
        if (lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        val requested = openRequest != null && openRequest != handledRequest
        if (requested) handledRequest = openRequest
        val token = transfer.download.token.toString()
        if (transfer.ready && (requested || prompted != token)) {
            prompted = token
            showTransfer = false
            showInstall = true
        } else if (requested) showTransfer = true
    }

    CompositionLocalProvider(LocalUpdateDownloadControls provides controls, content = content)
    if (showTransfer && status != null) AlertDialog(
        onDismissRequest = { showTransfer = false },
        title = { Text("更新下载") },
        text = { UpdateDownloadPanel(controls) },
        confirmButton = { TextButton(onClick = { showTransfer = false }) { Text("关闭") } }
    )
    val ready = status?.takeIf { it.ready }?.download
    if (showInstall && ready != null) AlertDialog(
        onDismissRequest = { showInstall = false },
        modifier = Modifier.testTag("update_install_prompt"),
        title = { Text("更新下载完成") },
        text = { Text("E阅 ${ready.versionName} 已下载。现在安装更新吗？") },
        dismissButton = { TextButton(onClick = { showInstall = false }) { Text("稍后") } },
        confirmButton = {
            TextButton(onClick = {
                showInstall = false
                if (context.packageManager.canRequestPackageInstalls()) install(ready)
                else {
                    awaitingInstallPermission = ready.token.toString()
                    try {
                        installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", context.packageName, null)))
                    } catch (_: Exception) {
                        awaitingInstallPermission = null
                        error = "请在系统设置中允许 E阅安装应用，再返回安装"
                    }
                }
            }) { Text("安装") }
        }
    )
    error?.let { message -> AlertDialog(
        onDismissRequest = { error = null },
        title = { Text("应用更新") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }
    ) }
}

@Composable internal fun UpdateDownloadPanel(controls: UpdateDownloadControls) {
    val status = controls.status ?: return
    val task = status.download
    val context = LocalContext.current
    val progress = status.progress
    var recentRate by remember(status.work?.progress, status.running) { mutableStateOf(status.rate) }
    LaunchedEffect(status.work?.progress, status.running) {
        if (recentRate != null) {
            delay(2_500)
            recentRate = null
        }
    }
    Column(Modifier.fillMaxWidth().testTag("update_download_status"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("E阅 ${task.versionName}", style = MaterialTheme.typography.titleSmall)
        Text(when {
            status.ready -> "下载完成"
            status.error != null -> status.error.orEmpty()
            status.work?.state == WorkInfo.State.ENQUEUED && status.work.runAttemptCount > 0 -> "等待网络或自动重试"
            status.work?.state == WorkInfo.State.ENQUEUED -> "等待网络或任务启动"
            status.work?.state == WorkInfo.State.FAILED -> "下载未完成，请继续下载"
            status.running && progress == 1f -> "正在校验安装包…"
            status.running -> status.phase ?: "正在下载…"
            else -> "下载已暂停"
        }, style = MaterialTheme.typography.bodySmall)
        if (!status.ready && (status.bytes > 0 || status.total != null)) {
            val received = Formatter.formatShortFileSize(context, status.bytes)
            val size = status.total?.let { " / ${Formatter.formatShortFileSize(context, it)}" }.orEmpty()
            val percent = status.total?.let { " · ${status.bytes * 100 / it}%" }.orEmpty()
            val rate = recentRate?.let { " · ${Formatter.formatShortFileSize(context, it)}/s" }.orEmpty()
            Text("已下载 $received$size$percent$rate", Modifier.testTag("update_download_bytes"),
                style = MaterialTheme.typography.bodySmall)
        }
        val bar = Modifier.fillMaxWidth().testTag("update_download_progress")
        if (!status.ready && progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar)
        else if (status.work?.state == WorkInfo.State.RUNNING) LinearProgressIndicator(bar)
        Row {
            when {
                status.ready -> TextButton(controls.install, enabled = !controls.busy) { Text("安装更新") }
                status.running -> TextButton({ controls.pause(task) }, enabled = !controls.busy) { Text("暂停") }
                else -> TextButton({ controls.resume(task) }, enabled = !controls.busy) { Text("继续下载") }
            }
            TextButton({ controls.cancel(task) }, enabled = !controls.busy) {
                Text(if (status.ready) "删除安装包" else "取消下载")
            }
        }
        if (!status.ready && safeUpdateLink(task.url, download = true)) {
            TextButton({ openAuthorLink(context, task.url) }) { Text("在浏览器下载") }
        }
    }
}
