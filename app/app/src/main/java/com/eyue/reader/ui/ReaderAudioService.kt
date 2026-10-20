package com.eyue.reader.ui

import android.app.Notification
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.eyue.reader.MainActivity
import com.eyue.reader.R

internal fun readerAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

/** The reader keeps the exact text anchor; system controls invoke the same UI operations. */
internal class ReaderAudioSession(context: Context, val title: String) {
    private val app = context.applicationContext as Application
    private val serviceIntent = Intent(app, ReaderAudioService::class.java)
    internal var service: ReaderAudioService? = null
    internal var pending: (() -> Unit)? = null
    internal var playing = false
    internal var speed = 1f
    var onPause: () -> Unit = {}
    var onPlay: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onError: (String) -> Unit = {}

    fun play(start: () -> Unit) {
        pending = start
        ReaderAudioService.owner = this
        try {
            ContextCompat.startForegroundService(app, serviceIntent)
        } catch (error: RuntimeException) {
            pending = null
            onError("系统未允许启动朗读：${error.localizedMessage.orEmpty().take(100)}")
        }
    }

    fun update(isPlaying: Boolean, active: Boolean, rate: Float) {
        val changed = playing != isPlaying || speed != rate
        playing = isPlaying
        speed = rate
        if (!active) stop() else if (changed) service?.publish()
    }

    fun pause() { pending = null; playing = false; service?.publish() }

    fun stop() {
        pending = null
        playing = false
        if (ReaderAudioService.owner === this) {
            service?.detach(this)
            ReaderAudioService.owner = null
            app.stopService(serviceIntent)
        }
        service = null
    }
}

class ReaderAudioService : Service() {
    private lateinit var media: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private var focused = false
    private var lastMediaKey: Triple<Long, Long, Int>? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Notifications request ducking; only an exclusive audio interruption pauses speech.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            callbacks.onPause()
            abandonFocus()
            // A newer music/video player must receive the headset's next play/pause press.
            media.isActive = false
        }
    }
    private val focus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(readerAudioAttributes()).setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper())).build() }
    internal val callbacks = object : MediaSession.Callback() {
        override fun onPlay() {
            currentSession()?.takeIf { !it.playing && it.pending == null }?.onPlay?.invoke()
        }
        override fun onPause() {
            currentSession()?.takeIf { it.playing || it.pending != null }?.onPause?.invoke()
        }
        override fun onStop() { currentSession()?.onStop?.invoke() }
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            if (mediaButtonIntent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val event = readerMediaKeyEvent(mediaButtonIntent) ?: return false
            if (event.keyCode !in READER_MEDIA_KEYS || currentSession() == null) return false
            // Both the active MediaSession and the receiver use this single dispatch path.
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
            val key = Triple(event.downTime, event.eventTime, event.keyCode)
            if (lastMediaKey == key) return true
            lastMediaKey = key
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> onPlay()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> onPause()
                KeyEvent.KEYCODE_MEDIA_STOP -> onStop()
                else -> if (currentSession()?.let { it.playing || it.pending != null } == true) onPause() else onPlay()
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "有声朗读", NotificationManager.IMPORTANCE_LOW))
        media = MediaSession(this, "EReadSpeech").apply {
            setCallback(callbacks, Handler(Looper.getMainLooper()))
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackToLocal(readerAudioAttributes())
            setSessionActivity(PendingIntent.getActivity(this@ReaderAudioService, 5710,
                Intent(this@ReaderAudioService, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val receiver = ComponentName(this@ReaderAudioService, ReaderMediaButtonReceiver::class.java)
            if (Build.VERSION.SDK_INT >= 31) setMediaButtonBroadcastReceiver(receiver)
            else setMediaButtonReceiver(PendingIntent.getBroadcast(this@ReaderAudioService, 5712,
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver), PendingIntent.FLAG_UPDATE_CURRENT))
        }
        owner?.service = this
        if (owner != null) publish() else foreground(notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = owner
        if (session == null) { stopSelf(); return START_NOT_STICKY }
        session.service = this
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> callbacks.onMediaButtonEvent(requireNotNull(intent))
            PAUSE -> callbacks.onPause()
            PLAY -> callbacks.onPlay()
            STOP -> callbacks.onStop()
            else -> {
                val start = session.pending
                session.pending = null
                if (start != null) {
                    // Android 15 requires a visible app or an active foreground service for focus.
                    media.isActive = true
                    foreground(notification())
                    if (focused || audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        focused = true
                        start()
                    } else { session.onPause(); media.isActive = false; session.onError("无法获得音频播放权限，请稍后重试") }
                }
            }
        }
        if (owner === session) publish()
        return START_NOT_STICKY
    }

    internal fun publish() {
        val session = currentSession() ?: return
        if (session.playing || session.pending != null) media.isActive = true
        media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, session.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, session.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "E阅朗读").build())
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (session.pending != null) PlaybackState.STATE_BUFFERING else if (session.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (session.playing) session.speed else 0f).build())
        if (!session.playing && session.pending == null) abandonFocus()
        // Keep a paused reading session resumable in the system player and from a headset.
        foreground(notification())
    }

    private fun currentSession(): ReaderAudioSession? = owner?.takeIf { it.service === this }

    internal fun detach(session: ReaderAudioSession) {
        if (currentSession() !== session) return
        abandonFocus()
        media.isActive = false
        // The ComponentName API crashes with null on some Android 16 systems; this API clears both registrations safely.
        @Suppress("DEPRECATION")
        media.setMediaButtonReceiver(null)
        media.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED,
            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).build())
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTICE)
    }

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTICE, notification)
    }

    private fun notification(): Notification {
        val preparing = owner?.pending != null
        val playing = owner?.playing == true || preparing
        fun command(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, ReaderAudioService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentTitle(owner?.title ?: "E阅朗读").setContentText(if (preparing) "正在准备朗读" else if (playing) "正在朗读" else "朗读已暂停")
            .setContentIntent(media.controller.sessionActivity).setOnlyAlertOnce(true).setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setDeleteIntent(command(STOP))
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续", command(if (playing) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", command(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }

    private fun abandonFocus() { if (focused) { audio.abandonAudioFocusRequest(focus); focused = false } }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { owner?.onStop?.invoke(); stopSelf() }
    override fun onDestroy() {
        val session = owner?.takeIf { it.service === this }
        session?.service = null
        session?.onPause?.invoke()
        abandonFocus()
        media.isActive = false
        media.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTICE)
        super.onDestroy()
    }

    companion object {
        internal var owner: ReaderAudioSession? = null
        private const val CHANNEL = "reader-speech"
        private const val NOTICE = 5711
        private const val PAUSE = "com.eyue.reader.SPEECH_PAUSE"
        private const val PLAY = "com.eyue.reader.SPEECH_PLAY"
        private const val STOP = "com.eyue.reader.SPEECH_STOP"
    }
}

internal val READER_MEDIA_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)

@Suppress("DEPRECATION")
internal fun readerMediaKeyEvent(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= 33)
    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
