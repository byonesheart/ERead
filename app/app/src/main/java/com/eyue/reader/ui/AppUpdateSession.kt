package com.eyue.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eyue.reader.BuildConfig
import com.eyue.reader.data.AppUpdateClient
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.DeveloperModePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Shares update requests and retains an available version until the app is upgraded. */
class AppUpdateSession(
    private val checker: suspend () -> AppUpdateResult = { AppUpdateClient().check(BuildConfig.VERSION_CODE) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val previewChecker: suspend () -> AppUpdateResult = { AppUpdateClient().check(BuildConfig.VERSION_CODE, includePrereleases = true) }
) : ViewModel() {
    var availableUpdate by mutableStateOf<AppUpdateResult?>(null)
        private set
    var automaticChecks by mutableStateOf(true)
        private set
    val hasPendingUpdate get() = availableUpdate != null
    var developerMode by mutableStateOf(false)
        private set
    private var startupChecked = false
    private var preferences: DeveloperModePreferences? = null
    private var request: Deferred<AppUpdateResult>? = null
    private var requestGeneration = 0

    fun configurePreferences(store: DeveloperModePreferences) {
        preferences = store
        automaticChecks = store.automaticChecks
        configureDeveloperMode(store.enabled)
        restorePendingUpdate()
    }

    fun configureDeveloperMode(enabled: Boolean) {
        if (developerMode == enabled) return
        developerMode = enabled
        requestGeneration++
        request?.cancel(); request = null
        startupChecked = false
        availableUpdate = null
        restorePendingUpdate()
    }

    fun changeAutomaticChecks(enabled: Boolean) {
        automaticChecks = enabled
        preferences?.automaticChecks = enabled
        if (enabled) checkOnStartup()
    }

    fun checkOnStartup() {
        if (!automaticChecks || startupChecked) return
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
        if (result.published && result.available && (!result.prerelease || developerMode)) {
            preferences?.rememberUpdate(result)
            if (result.versionCode >= (availableUpdate?.versionCode ?: 0)) availableUpdate = result
        }
    }

    private fun restorePendingUpdate() {
        val store = preferences ?: return
        val stable = store.pendingUpdate(preview = false)
        val preview = if (developerMode) store.pendingUpdate(preview = true) else null
        listOfNotNull(availableUpdate, stable, preview).maxWithOrNull(compareBy<AppUpdateResult> { it.versionCode }
            .thenBy { !it.prerelease })?.let { availableUpdate = it }
    }
}
