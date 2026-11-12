package com.eyue.reader.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eyue.reader.MainActivity

internal fun NotificationCompat.Builder.bookTransferProgress(stage: String, fraction: Float?): NotificationCompat.Builder {
    val percent = fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)?.let { (it * 100).toInt() }
    return setContentText(if (percent == null) stage else "$stage · $percent%")
        .setProgress(100, percent ?: 0, percent == null)
}

internal class BookTransferNotification(private val context: Context, private val tag: String,
    private val downloading: Boolean) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var closed = false
    private var lastUpdate = -500L
    private var lastStage = ""

    @Synchronized
    fun update(title: String, stage: String, fraction: Float?) {
        if (closed || !manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = SystemClock.elapsedRealtime()
        if (stage == lastStage && now - lastUpdate < 500L && fraction != 1f) return
        lastUpdate = now
        lastStage = stage
        val channel = "book-transfers"
        manager.createNotificationChannel(NotificationChannel(channel, "书籍传输", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(if (downloading) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .bookTransferProgress(stage, fraction).build()
        try { manager.notify(tag, 1, notification) } catch (_: SecurityException) { }
    }

    @Synchronized
    fun close() {
        closed = true
        manager.cancel(tag, 1)
    }
}
