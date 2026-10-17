package com.eyue.reader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/** Routes a system media-button restart only while a resumable reader session exists. */
class ReaderMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = readerMediaKeyEvent(intent) ?: return
        if (event.keyCode !in READER_MEDIA_KEYS) return
        val session = ReaderAudioService.owner ?: return
        session.service?.let { it.callbacks.onMediaButtonEvent(intent); return }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        // A process-death restart has no text anchor or callbacks and must not steal another player's key.
        try {
            ContextCompat.startForegroundService(context, Intent(context, ReaderAudioService::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
        } catch (error: RuntimeException) {
            session.onError("系统未允许恢复朗读：${error.localizedMessage.orEmpty().take(100)}")
        }
    }
}
