package com.eyue.reader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** The document provider can grant editing access together with the original import. */
internal class OpenBookDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
    private val delegate = ActivityResultContracts.OpenMultipleDocuments()
    override fun createIntent(context: Context, input: Array<String>): Intent = delegate.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> = delegate.parseResult(resultCode, intent)
}

internal class GrantBookWriteAccess : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input))
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

internal fun retainBookAccess(context: Context, uri: Uri) {
    if (uri.scheme != "content") return
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(uri, read) } }
}
