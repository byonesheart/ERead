package com.eyue.reader

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.activity.ComponentActivity
import com.eyue.reader.data.ConsentStore
import com.eyue.reader.ui.ConsentGate
import com.eyue.reader.ui.EReadApp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val reader: ReaderViewModel by viewModels()
    private val volumeEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var externalRequestId: String? = null
    private var readerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Old navigator Fragment state has no owner in the Compose-only activity.
        savedInstanceState?.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key")?.remove("android:support:fragments")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalRequestId = savedInstanceState?.getString(EXTERNAL_REQUEST_ID)
        setContent {
            ConsentGate(onExit = ::finish) {
                LaunchedEffect(Unit) { initializeReader() }
                LaunchedEffect(reader.state.einkMode) { requestHighRefreshRate() }
                EReadApp(reader, volumeEvents)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalRequestId = null
        if (readerReady) openExternalIntent(intent)
    }

    private fun initializeReader() {
        if (readerReady || !ConsentStore(this).isAccepted()) return
        ReaderCrashDiagnostics.install(this)
        readerReady = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reader.resumeSession()
        openExternalIntent(intent)
        ReaderCrashDiagnostics.showPending(this)
    }

    private fun openExternalIntent(intent: Intent?) {
        val request = externalFilesFromIntent(intent) ?: return
        val requestId = externalRequestId ?: UUID.randomUUID().toString().also { externalRequestId = it }
        reader.openExternalFiles(request.uris, request.mimeType, requestId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTERNAL_REQUEST_ID, externalRequestId)
        super.onSaveInstanceState(outState)
    }

    // Public Activity callback; AndroidX Core's internal base-class annotation also
    // reaches this inherited method. Unhandled keys still follow normal dispatch.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = readerPageKeyDirection(event.keyCode)
        if (direction != null && readerReady && reader.activeBookId != null && reader.readerSettings.volumePaging) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!event.isCanceled) volumeEvents.tryEmit(direction)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (readerReady) reader.resumeSession()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && readerReady) requestHighRefreshRate()
    }

    private fun requestHighRefreshRate() {
        @Suppress("DEPRECATION")
        val currentDisplay = window.decorView.display ?: windowManager.defaultDisplay
        requestReaderRefreshRate(window, currentDisplay, reader.state.einkMode)
    }

    override fun onStop() {
        if (readerReady) reader.pauseSession()
        super.onStop()
    }

    companion object {
        private const val EXTERNAL_REQUEST_ID = "external_file_request_id"
    }
}
