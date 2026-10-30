package com.eyue.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal fun readerTranslationIntent(text: String) = Intent(Intent.ACTION_PROCESS_TEXT).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_PROCESS_TEXT, text as CharSequence)
    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
}

internal fun openReaderTranslation(context: Context, text: String) {
    val intent = readerTranslationIntent(text)
    try {
        if (context.packageManager.queryIntentActivities(intent, 0).none { it.activityInfo?.exported == true })
            throw ActivityNotFoundException()
        context.startActivity(Intent.createChooser(intent, "系统翻译").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        val toast = Toast.makeText(context, "未找到支持选中文字的翻译应用", Toast.LENGTH_SHORT)
        toast.show(); Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 1000)
    }
}
