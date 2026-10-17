package com.eyue.reader.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE, shadows = [ReaderMediaSessionShadow::class])
class ReaderAudioServiceRobolectricTest {
    private lateinit var session: ReaderAudioSession
    private lateinit var controller: ServiceController<ReaderAudioService>
    private lateinit var service: ReaderAudioService
    private var starts = 0
    private var pauses = 0
    private var stops = 0
    private var destroyed = false

    @Before fun setup() {
        session = ReaderAudioSession(RuntimeEnvironment.getApplication(), "测试书籍")
        session.onPause = { pauses++; session.pause() }
        session.onPlay = { session.play { starts++; session.update(true, true, 1.3f) } }
        session.onStop = { stops++; session.stop() }
        session.play { starts++; session.update(true, true, 1f) }
        controller = Robolectric.buildService(ReaderAudioService::class.java).create()
        service = controller.get()
    }

    @After fun cleanup() { session.stop(); if (!destroyed) controller.destroy(); ReaderAudioService.owner = null }

    @Test fun playbackRequiresFullSpeechFocusAndPauseAbandonsIt() {
        service.onStartCommand(null, 0, 1)
        assertEquals(1, starts)
        val audio = Shadows.shadowOf(service.getSystemService(AudioManager::class.java))
        val request = requireNotNull(audio.lastAudioFocusRequest)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.durationHint)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioFocusRequest.audioAttributes.contentType)
        assertFalse(request.audioFocusRequest.willPauseWhenDucked())
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, pauses)
        assertTrue(session.playing)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, pauses)
        assertEquals(request.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
        assertFalse(session.playing)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, starts)
    }

    @Test fun anotherMediaPlayerTakingFocusPausesSpeech() {
        service.onStartCommand(null, 0, 1)
        val request = requireNotNull(Shadows.shadowOf(service.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, pauses)
        assertFalse(session.playing)
    }

    @Test fun deniedFocusDoesNotStartSpeech() {
        var error: String? = null
        session.onError = { error = it }
        Shadows.shadowOf(service.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        service.onStartCommand(null, 0, 1)
        assertEquals(0, starts)
        assertEquals(1, pauses)
        assertNotNull(error)
    }

    @Test fun mediaPauseResumeAndStopUseReaderCallbacksAndUpdateNotification() {
        service.onStartCommand(null, 0, 1)
        val notifications = Shadows.shadowOf(service.getSystemService(NotificationManager::class.java))
        assertEquals("暂停", notifications.getNotification(5711).actions[0].title.toString())
        service.callbacks.onPause()
        assertEquals("继续", notifications.getNotification(5711).actions[0].title.toString())
        service.callbacks.onPlay()
        service.onStartCommand(null, 0, 2)
        assertEquals(2, starts)
        assertTrue(session.playing)
        service.callbacks.onStop()
        assertEquals(1, stops)
        assertNull(ReaderAudioService.owner)
    }

    @Test fun publishesDiscoverableLocalSpeechSessionAndKeepsPausedPlayerInForeground() {
        val media = ReflectionHelpers.getField<MediaSession>(service, "media")
        val recorded = Shadow.extract<ReaderMediaSessionShadow>(media)
        assertTrue(media.isActive)
        assertEquals(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS, recorded.capturedFlags)
        assertEquals(AudioAttributes.USAGE_MEDIA, recorded.attributes?.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, recorded.attributes?.contentType)
        assertTrue(recorded.receiver != null || recorded.broadcastReceiver != null)
        assertEquals(PlaybackState.STATE_BUFFERING, recorded.playback?.state)
        assertEquals("测试书籍", recorded.capturedMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
        service.onStartCommand(null, 0, 1)
        assertEquals(PlaybackState.STATE_PLAYING, recorded.playback?.state)
        assertEquals(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
            recorded.playback?.actions)
        val notice = Shadows.shadowOf(service).lastForegroundNotification
        assertEquals(Notification.CATEGORY_TRANSPORT, notice.category)
        assertNotNull(notice.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
        service.callbacks.onPause()
        assertEquals(PlaybackState.STATE_PAUSED, recorded.playback?.state)
        assertTrue(media.isActive)
        assertFalse(Shadows.shadowOf(service).isForegroundStopped)
        assertTrue(Shadows.shadowOf(service).isLastForegroundNotificationAttached)
        assertEquals("继续", Shadows.shadowOf(service).lastForegroundNotification.actions[0].title.toString())
        service.callbacks.onStop()
        assertFalse(media.isActive)
        assertEquals(PlaybackState.STATE_STOPPED, recorded.playback?.state)
        assertNull(recorded.receiver)
        assertNull(recorded.broadcastReceiver)
        assertTrue(Shadows.shadowOf(service).isForegroundStopped)
    }

    @Test fun headsetSinglePressPausesAndResumesWithoutKeyUpOrRepeatDoubleToggle() {
        service.onStartCommand(null, 0, 1)
        val down = mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 100)
        assertTrue(service.callbacks.onMediaButtonEvent(down))
        assertEquals(1, pauses)
        assertFalse(session.playing)
        service.callbacks.onMediaButtonEvent(down) // A receiver forwarding the same event cannot toggle twice.
        service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 100, KeyEvent.ACTION_UP))
        service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 100, repeat = 1))
        assertNull(session.pending)
        assertEquals(1, starts)
        service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 200))
        assertNotNull(session.pending)
        service.onStartCommand(null, 0, 2)
        assertEquals(2, starts)
        assertTrue(session.playing)
    }

    @Test fun mediaButtonReceiverAndServiceIntentsRoutePausePlayAndStop() {
        service.onStartCommand(null, 0, 1)
        val receiver = ReaderMediaButtonReceiver()
        receiver.onReceive(service, mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, 100))
        assertEquals(1, pauses)
        receiver.onReceive(service, mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, 200))
        service.onStartCommand(null, 0, 2)
        assertEquals(2, starts)
        service.onStartCommand(mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 300), 0, 3)
        assertEquals(2, pauses)
        service.onStartCommand(mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 400), 0, 4)
        service.onStartCommand(null, 0, 5)
        assertEquals(3, starts)
        receiver.onReceive(service, mediaKey(KeyEvent.KEYCODE_MEDIA_STOP, 500))
        assertEquals(1, stops)
        receiver.onReceive(service, mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, 600))
        assertNull(ReaderAudioService.owner)
        assertEquals(3, starts)
        assertFalse(service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 700)))
    }

    @Test fun anotherPlayersFocusLossDeactivatesHeadsetPriorityAndExplicitResumeRestoresIt() {
        service.onStartCommand(null, 0, 1)
        val media = ReflectionHelpers.getField<MediaSession>(service, "media")
        val audio = Shadows.shadowOf(service.getSystemService(AudioManager::class.java))
        requireNotNull(audio.lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(media.isActive)
        service.callbacks.onPlay()
        service.onStartCommand(null, 0, 2)
        assertTrue(media.isActive)
        assertEquals(2, starts)
    }

    @Test fun cancelBeforeServiceConnectionPreventsPendingSpeechAndDestroyRemovesNotification() {
        session.stop()
        service.onStartCommand(null, 0, 1)
        assertEquals(0, starts)
        controller.destroy()
        destroyed = true
        assertNull(Shadows.shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(5711))
    }

    @Test fun repeatedStopDuringPreparationClearsBothMediaRegistrationsAndDoesNotResumeSpeech() {
        val media = ReflectionHelpers.getField<MediaSession>(service, "media")
        val recorded = Shadow.extract<ReaderMediaSessionShadow>(media)
        assertEquals(PlaybackState.STATE_BUFFERING, recorded.playback?.state)
        if (RuntimeEnvironment.getApiLevel() >= 31) assertNotNull(recorded.broadcastReceiver)
        session.stop()
        session.stop()
        service.onStartCommand(null, 0, 1)
        service.callbacks.onPlay()
        service.callbacks.onPause()
        service.callbacks.onStop()
        assertEquals(0, starts)
        assertEquals(0, pauses)
        assertEquals(0, stops)
        assertNull(session.pending)
        assertNull(session.service)
        assertNull(ReaderAudioService.owner)
        assertFalse(media.isActive)
        assertEquals(PlaybackState.STATE_STOPPED, recorded.playback?.state)
        assertNull(recorded.receiver)
        assertNull(recorded.broadcastReceiver)
        assertNull(Shadows.shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(5711))
        controller.destroy()
        destroyed = true
        assertEquals(0, pauses)
    }

    @Test fun stoppedSessionCanStartANewSessionWithHeadsetControls() {
        service.onStartCommand(null, 0, 1)
        session.stop()
        controller.destroy()
        session.play { starts++; session.update(true, true, 1f) }
        controller = Robolectric.buildService(ReaderAudioService::class.java).create()
        service = controller.get()
        service.onStartCommand(null, 0, 2)
        val media = ReflectionHelpers.getField<MediaSession>(service, "media")
        val recorded = Shadow.extract<ReaderMediaSessionShadow>(media)
        assertEquals(2, starts)
        assertTrue(media.isActive)
        assertTrue(recorded.receiver != null || recorded.broadcastReceiver != null)
        service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 100))
        assertEquals(1, pauses)
        service.callbacks.onMediaButtonEvent(mediaKey(KeyEvent.KEYCODE_HEADSETHOOK, 200))
        service.onStartCommand(null, 0, 3)
        assertEquals(3, starts)
        assertTrue(session.playing)
    }

    private fun mediaKey(code: Int, time: Long, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(time, time, action, code, repeat))
}

/** Robolectric does not persist the platform session's remote metadata and registration calls. */
@Implements(MediaSession::class)
class ReaderMediaSessionShadow : ShadowMediaSession() {
    var capturedFlags = 0
    var attributes: AudioAttributes? = null
    var receiver: PendingIntent? = null
    var broadcastReceiver: ComponentName? = null
    var capturedMetadata: MediaMetadata? = null
    var playback: PlaybackState? = null

    @Implementation fun setFlags(value: Int) { capturedFlags = value }
    @Implementation fun setPlaybackToLocal(value: AudioAttributes) { attributes = value }
    @Implementation fun setMediaButtonReceiver(value: PendingIntent?) {
        receiver = value
        if (value == null) broadcastReceiver = null
    }
    // Reproduce the reported Android 16 system-server failure instead of silently accepting null.
    @Implementation(minSdk = 31) fun setMediaButtonBroadcastReceiver(value: ComponentName?) {
        broadcastReceiver = value ?: throw NullPointerException("Media button broadcast receiver cannot be null")
    }
    @Implementation fun setMetadata(value: MediaMetadata?) { capturedMetadata = value }
    @Implementation fun setPlaybackState(value: PlaybackState?) { playback = value }
}
