package com.eyue.reader.data

import android.content.Context

/** Device-local update preference, deliberately separate from portable library records. */
class DeveloperModePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("developer_mode", false)

    fun enable() {
        check(preferences.edit().putBoolean("developer_mode", true).commit()) { "设置保存失败，请重试" }
    }
}
