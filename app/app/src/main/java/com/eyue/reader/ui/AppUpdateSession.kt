package com.eyue.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eyue.reader.BuildConfig
import com.eyue.reader.data.AppUpdateClient
import com.eyue.reader.data.AppUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Keeps one startup check and its read state across recompositions and activity recreation. */
class AppUpdateSession(
    private val checker: suspend () -> AppUpdateResult = { AppUpdateClient().check(BuildConfig.VERSION_CODE) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val previewChecker: suspend () -> AppUpdateResult = { AppUpdateClient().check(BuildConfig.VERSION_CODE, includePrereleases = true) }
) : ViewModel() {
    var availableUpdate by mutableStateOf<AppUpdateResult?>(null)
        private set
    var hasUnreadUpdate by mutableStateOf(false)
        private set
    var developerMode by mutableStateOf(false)
        private set
    private var startupChecked = false
    private var settingsVisible = false
    private var acknowledgedVersion: Int? = null
    private var request: Deferred<AppUpdateResult>? = null
    private var requestGeneration = 0

    fun configureDeveloperMode(enabled: Boolean) {
        if (developerMode == enabled) return
        developerMode = enabled
        requestGeneration++
        request?.cancel(); request = null
        startupChecked = false
        availableUpdate = null; hasUnreadUpdate = false
    }

    fun setSettingsVisible(visible: Boolean) {
        settingsVisible = visible
        if (visible) {
            availableUpdate?.let { acknowledgedVersion = it.versionCode }
            hasUnreadUpdate = false
        }
    }

    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        viewModelScope.launch(dispatcher) {
            try { checkNow() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Automatic checks stay silent; manual checks can report errors. */ }
        }
    }

    suspend fun checkNow(): AppUpdateResult {
        val generation = requestGeneration
        val preview = developerMode
        val pending = request?.takeUnless { it.isCompleted }
            ?: viewModelScope.async(dispatcher) {
                (if (preview) previewChecker() else checker()).also { if (generation == requestGeneration) acceptResult(it) }
            }.also { request = it }
        return pending.await()
    }

    private fun acceptResult(result: AppUpdateResult) {
        availableUpdate = result.takeIf { it.published && it.available }
        if (settingsVisible) availableUpdate?.let { acknowledgedVersion = it.versionCode }
        hasUnreadUpdate = availableUpdate?.let { !settingsVisible && it.versionCode != acknowledgedVersion } == true
    }
}
