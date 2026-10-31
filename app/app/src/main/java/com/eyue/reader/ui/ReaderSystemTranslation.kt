package com.eyue.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal fun readerTranslationIntent(text: String) = Intent(Intent.ACTION_TRANSLATE).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text as CharSequence)
}

internal fun readerTranslationTargets(context: Context, text: String): List<Intent> {
    val manager = context.packageManager
    fun targets(intent: Intent, processText: Boolean) = manager.queryIntentActivities(intent, 0).mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        if (!activity.exported) return@mapNotNull null
        if (processText) {
            val system = (activity.applicationInfo?.flags ?: 0) and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val label = resolved.loadLabel(manager).toString() + " " + activity.name
            if (!system || !Regex("翻译|translate|translation|translator", RegexOption.IGNORE_CASE).containsMatchIn(label)) return@mapNotNull null
        }
        Intent(intent).setComponent(ComponentName(activity.packageName, activity.name))
    }
    val direct = targets(readerTranslationIntent(text), false)
    if (direct.isNotEmpty()) return direct
    return targets(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text as CharSequence).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true), true)
}

internal fun openReaderTranslation(context: Context, text: String) {
    try {
        val targets = readerTranslationTargets(context, text)
        if (targets.isEmpty()) throw ActivityNotFoundException()
        val intent = if (targets.size == 1) targets.single() else Intent.createChooser(targets.first(), "系统翻译")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        val toast = Toast.makeText(context, "此设备未提供可调用的系统翻译", Toast.LENGTH_SHORT)
        toast.show(); Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000)
    }
}
