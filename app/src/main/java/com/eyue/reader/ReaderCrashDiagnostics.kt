package com.eyue.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File

/** Saves the failure locally before Android's normal crash handler terminates the process. */
internal class ReaderCrashHandler(
    private val file: File,
    private val device: String,
    private val previous: Thread.UncaughtExceptionHandler
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val report = buildString {
                appendLine(device)
                appendLine("Thread: ${thread.name}")
                var cause: Throwable? = error
                repeat(8) {
                    val current = cause ?: return@repeat
                    appendLine("${current.javaClass.name}: ${current.message.orEmpty().take(16_384)}")
                    current.stackTrace.take(64).forEach { appendLine("    at $it") }
                    cause = current.cause?.takeUnless { it === current }
                    if (cause != null) appendLine("Caused by:")
                }
            }.take(32_768)
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(report.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
            }
        } catch (_: Throwable) {
            // A full disk or exhausted heap must not block Android's crash handling.
        } finally {
            previous.uncaughtException(thread, error)
        }
    }
}

internal object ReaderCrashDiagnostics {
    private fun file(context: Context) = File(context.filesDir, "diagnostics/last-crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        if (previous is ReaderCrashHandler) return
        val app = context.applicationContext
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        Thread.setDefaultUncaughtExceptionHandler(ReaderCrashHandler(file(app),
            "E阅 $version | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", previous))
    }

    fun showPending(activity: Activity) {
        val saved = file(activity)
        if (!saved.isFile) return
        val report = runCatching { saved.readText() }.getOrNull() ?: return
        AlertDialog.Builder(activity).setTitle("上次异常退出")
            .setMessage("请复制下面的错误信息，发送给开发者以定位闪退。\n\n$report")
            .setPositiveButton("复制错误信息") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("E阅错误信息", report))
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { saved.delete() }
            .show()
    }
}
