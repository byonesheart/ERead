package com.eyue.reader.data

import android.content.Context
import com.eyue.reader.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Device-local update preference, deliberately separate from portable library records. */
class DeveloperModePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("developer_mode", false)
    var automaticChecks: Boolean
        get() = preferences.getBoolean("automatic_checks", true)
        set(value) { preferences.edit().putBoolean("automatic_checks", value).apply() }

    fun enable() {
        check(preferences.edit().putBoolean("developer_mode", true).commit()) { "设置保存失败，请重试" }
    }

    internal fun pendingUpdate(preview: Boolean): AppUpdateResult? {
        val key = if (preview) "pending_preview_update" else "pending_stable_update"
        val json = preferences.getString(key, null) ?: return null
        val update = runCatching { parseAppUpdate(json, BuildConfig.VERSION_CODE) }.getOrNull()
        if (update?.available == true && update.prerelease == preview) return update
        preferences.edit().remove(key).apply()
        return null
    }

    internal fun rememberUpdate(update: AppUpdateResult) {
        val key = if (update.prerelease) "pending_preview_update" else "pending_stable_update"
        if ((pendingUpdate(update.prerelease)?.versionCode ?: 0) > update.versionCode) return
        val json = JSONObject().put("versionName", update.versionName).put("versionCode", update.versionCode)
            .put("releaseUrl", update.releaseUrl).put("downloadUrl", update.downloadUrl)
            .put("changelog", update.changelog).put("prerelease", update.prerelease)
            .put("mirrors", JSONArray(update.mirrors.map { JSONObject().put("name", it.name).put("url", it.url)
                .put("extractionCode", it.extractionCode) }))
        preferences.edit().putString(key, json.toString()).apply()
    }
}
