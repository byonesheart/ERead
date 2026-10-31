package com.eyue.reader

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal data class ExternalFileRequest(val uris: List<Uri>, val mimeType: String?, val grantFlags: Int = 0)

/** Keep the granted URIs intact; the importer determines each document's actual format. */
internal fun externalFilesFromIntent(intent: Intent?): ExternalFileRequest? {
    if (intent == null || intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val uris = mutableListOf<Uri>()
    intent.data?.let(uris::add)
    when (intent.action) {
        Intent.ACTION_SEND -> runCatching {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }.getOrNull()?.let(uris::add)
        Intent.ACTION_SEND_MULTIPLE -> runCatching {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }.getOrNull()?.let(uris::addAll)
    }
    intent.clipData?.let { clips ->
        repeat(clips.itemCount) { index -> clips.getItemAt(index).uri?.let(uris::add) }
    }
    val documents = uris.filter { it.scheme == "content" || it.scheme == "file" }.distinct()
    return if (documents.isEmpty()) null else ExternalFileRequest(documents, intent.type, intent.flags)
}
