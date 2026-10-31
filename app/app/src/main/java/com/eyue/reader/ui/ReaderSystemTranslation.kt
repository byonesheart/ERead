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

private const val TRANSLATION_PREFERENCES = "reader_translation"
private const val DEFAULT_TRANSLATOR = "component"

internal fun openReaderTranslationSettings(context: Context) {
    try { chooseReaderTranslation(context, "", false) }
    catch (_: ActivityNotFoundException) { showMissingTranslation(context) }
}

private fun launchTranslation(context: Context, intent: Intent) {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun chooseReaderTranslation(context: Context, text: String, open: Boolean) {
    val targets = readerTranslationTargets(context, text)
    if (targets.isEmpty()) throw ActivityNotFoundException()
    val preferences = context.getSharedPreferences(TRANSLATION_PREFERENCES, 0)
    val remembered = preferences.getString(DEFAULT_TRANSLATOR, null)
    val default = targets.firstOrNull { it.component?.flattenToString() == remembered }
    if (open && default != null) {
        try { launchTranslation(context, default); return }
        catch (_: ActivityNotFoundException) { preferences.edit().remove(DEFAULT_TRANSLATOR).apply() }
    }
    if (open && targets.size == 1) { launchTranslation(context, targets.single()); return }
    var selected = targets.indexOf(default).coerceAtLeast(0)
    val remember = android.widget.CheckBox(context).apply {
        this.text = "默认使用此应用"; isChecked = !open || default != null
        setPadding(32, 12, 16, 12)
    }
    val labels = targets.map { intent ->
        val component = requireNotNull(intent.component)
        runCatching { context.packageManager.getActivityInfo(component, 0).loadLabel(context.packageManager).toString() }
            .getOrDefault(component.packageName)
    }.toTypedArray()
    android.app.AlertDialog.Builder(context).setTitle("系统翻译")
        .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
        .setView(remember).setNegativeButton("取消", null)
        .setPositiveButton(if (open) "打开" else "保存") { _, _ ->
            val target = targets[selected]
            preferences.edit().apply {
                if (remember.isChecked) putString(DEFAULT_TRANSLATOR, target.component!!.flattenToString()) else remove(DEFAULT_TRANSLATOR)
            }.apply()
            if (open) try { launchTranslation(context, target) }
                catch (_: ActivityNotFoundException) { showMissingTranslation(context) }
        }.show()
}

private fun showMissingTranslation(context: Context) {
    val toast = Toast.makeText(context, "此设备未提供可调用的系统翻译", Toast.LENGTH_SHORT)
    toast.show(); Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000)
}

internal fun openReaderTranslation(context: Context, text: String) {
    try { chooseReaderTranslation(context, text, true) }
    catch (_: ActivityNotFoundException) { showMissingTranslation(context) }
    catch (_: SecurityException) { showMissingTranslation(context) }
}
