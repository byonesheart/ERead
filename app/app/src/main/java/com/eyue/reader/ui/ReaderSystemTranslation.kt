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

/** The platform picker owns app labels/icons and returns a component without translating empty text. */
internal fun readerTranslationPickerIntent(context: Context): Intent? {
    val targets = readerTranslationTargets(context, "")
    if (targets.isEmpty()) { showMissingTranslation(context); return null }
    val query = Intent(targets.first()).setComponent(null)
    return Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, query)
        .putExtra(Intent.EXTRA_TITLE, "默认翻译应用")
}

internal fun rememberReaderTranslator(context: Context, resultCode: Int, data: Intent?) {
    if (resultCode != android.app.Activity.RESULT_OK) return
    val component = data?.component ?: return
    if (readerTranslationTargets(context, "").none { it.component == component }) {
        showMissingTranslation(context)
        return
    }
    context.getSharedPreferences(TRANSLATION_PREFERENCES, 0).edit()
        .putString(DEFAULT_TRANSLATOR, component.flattenToString()).apply()
}

private fun launchTranslation(context: Context, intent: Intent) {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun chooseReaderTranslation(context: Context, text: String) {
    val targets = readerTranslationTargets(context, text)
    if (targets.isEmpty()) throw ActivityNotFoundException()
    val preferences = context.getSharedPreferences(TRANSLATION_PREFERENCES, 0)
    val remembered = preferences.getString(DEFAULT_TRANSLATOR, null)
    val default = targets.firstOrNull { it.component?.flattenToString() == remembered }
    if (default != null) {
        try { launchTranslation(context, default); return }
        catch (_: ActivityNotFoundException) { preferences.edit().remove(DEFAULT_TRANSLATOR).apply() }
    }
    if (targets.size == 1) { launchTranslation(context, targets.single()); return }
    if (targets.first().action == Intent.ACTION_TRANSLATE) {
        // Normal resolution preserves the system's "just once/always" choice and app icons.
        launchTranslation(context, readerTranslationIntent(text))
    } else {
        val base = Intent(targets.first()).setComponent(null)
        val eligible = targets.mapNotNull { it.component }.toSet()
        val excluded = context.packageManager.queryIntentActivities(base, 0).mapNotNull { it.activityInfo?.let { activity ->
            ComponentName(activity.packageName, activity.name).takeUnless(eligible::contains)
        } }.toTypedArray()
        launchTranslation(context, Intent.createChooser(base, "系统翻译")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded))
    }
}

internal fun showMissingTranslation(context: Context) {
    val toast = Toast.makeText(context, "此设备未提供可调用的系统翻译", Toast.LENGTH_SHORT)
    toast.show(); Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000)
}

internal fun openReaderTranslation(context: Context, text: String) {
    try { chooseReaderTranslation(context, text) }
    catch (_: ActivityNotFoundException) { showMissingTranslation(context) }
    catch (_: SecurityException) { showMissingTranslation(context) }
}
