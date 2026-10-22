package com.eyue.reader.data

import android.content.Context

/** This store must remain independent of the library, network clients and scheduled work. */
class ConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("legal-consent", Context.MODE_PRIVATE)

    fun isAccepted(): Boolean = preferences.getString("privacy_version", null) == DOCUMENT_VERSION &&
        preferences.getString("terms_version", null) == DOCUMENT_VERSION && acceptedAt() > 0

    fun acceptedAt(): Long = preferences.getLong("accepted_at", 0)

    fun accept(now: Long = System.currentTimeMillis()): Boolean = preferences.edit()
        .putString("privacy_version", DOCUMENT_VERSION)
        .putString("terms_version", DOCUMENT_VERSION)
        .putLong("accepted_at", now)
        .commit()

    companion object {
        const val DOCUMENT_VERSION = "2026-10-22"
    }
}
