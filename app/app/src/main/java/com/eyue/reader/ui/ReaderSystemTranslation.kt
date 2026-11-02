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
        if (!activity.exported || !activity.permission.isNullOrBlank() &&
            context.checkSelfPermission(activity.permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@mapNotNull null
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

private fun translatorLabel(context: Context, component: ComponentName): String = runCatching {
    val info = context.packageManager.getActivityInfo(component, 0)
    val app = info.applicationInfo
    val name = if (app != null && (app.nonLocalizedLabel != null || app.labelRes != 0)) app.loadLabel(context.packageManager)
        else info.loadLabel(context.packageManager)
    name.toString().replace(Regex("\\s*[(（]?\\s*(?:https?://|www\\.)\\S+.*$", RegexOption.IGNORE_CASE), "")
        .trim().trimEnd('-', '—', '|', '(', '（').trim().ifBlank { "翻译应用" }
}.getOrDefault("翻译应用")

internal fun selectedReaderTranslatorLabel(context: Context): String {
    val remembered = context.getSharedPreferences(TRANSLATION_PREFERENCES, 0).getString(DEFAULT_TRANSLATOR, null)
    val targets = readerTranslationTargets(context, "")
    val component = targets.firstOrNull { it.component?.flattenToString() == remembered }?.component
        ?: context.packageManager.resolveActivity(readerTranslationIntent(""), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { info -> ComponentName(info.packageName, info.name) }?.takeIf { resolved -> targets.any { it.component == resolved } }
        ?: targets.singleOrNull()?.component
    return component?.let { translatorLabel(context, it) }
        ?: "未选择 · 使用系统选择"
}

private fun translatorChoices(context: Context): List<Pair<String, Intent>> {
    val targets = readerTranslationTargets(context, "")
    val labels = targets.map { target -> translatorLabel(context, target.component!!) }
    return targets.mapIndexed { index, target ->
        val label = labels[index]
        (if (labels.count { it == label } > 1) "$label（${labels.take(index + 1).count { it == label }}）" else label) to target
    }
}

/** Supply only eligible choices to Android's icon-bearing activity picker, never all PROCESS_TEXT apps. */
@Suppress("DEPRECATION")
internal fun readerTranslationPickerIntent(context: Context): Intent? {
    val choices = translatorChoices(context)
    if (choices.isEmpty()) { showMissingTranslation(context); return null }
    val icons = ArrayList(choices.map { (_, target) ->
        runCatching {
            val info = context.packageManager.getActivityInfo(target.component!!, 0)
            val icon = info.icon.takeIf { it != 0 } ?: info.applicationInfo.icon
            val resources = context.packageManager.getResourcesForApplication(info.applicationInfo)
            Intent.ShortcutIconResource().apply { packageName = info.packageName; resourceName = resources.getResourceName(icon) }
        }.getOrElse { Intent.ShortcutIconResource().apply {
            packageName = "android"; resourceName = "android:drawable/sym_def_app_icon"
        } }
    })
    return Intent(Intent.ACTION_PICK_ACTIVITY)
        .putExtra(Intent.EXTRA_INTENT, Intent("com.eyue.reader.PICK_TRANSLATOR").setPackage(context.packageName))
        .putExtra(Intent.EXTRA_TITLE, "默认翻译应用")
        .putStringArrayListExtra(Intent.EXTRA_SHORTCUT_NAME, ArrayList(choices.map { it.first }))
        .putParcelableArrayListExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, icons)
}

@Suppress("DEPRECATION")
internal fun rememberReaderTranslator(context: Context, resultCode: Int, data: Intent?) {
    if (resultCode != android.app.Activity.RESULT_OK) return
    val choices = translatorChoices(context)
    val component = data?.component ?: data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)?.let { selected ->
        choices.singleOrNull { it.first == selected }?.second?.component
    } ?: return
    if (choices.none { it.second.component == component }) { showMissingTranslation(context); return }
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
