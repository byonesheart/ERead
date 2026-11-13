package com.eyue.reader.ui

import androidx.lifecycle.ViewModelStore
import com.eyue.reader.BuildConfig
import com.eyue.reader.data.AppUpdateResult
import com.eyue.reader.data.DeveloperModePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateSessionRobolectricTest {
    private val store = ViewModelStore()

    @After fun cleanup() { store.clear() }

    private fun session(checker: suspend () -> AppUpdateResult) =
        AppUpdateSession(checker, Dispatchers.Unconfined).also { store.put("updates", it) }

    private fun update(code: Int = 99, available: Boolean = true) =
        AppUpdateResult(available, "next", code, "更新说明", null, "https://github.com/byonesheart/ERead/releases")

    @Test fun startupChecksOnlyOnceAndKeepsTheAvailableLinks() {
        var checks = 0
        val session = session { checks++; update() }
        session.checkOnStartup()
        session.checkOnStartup()
        assertEquals(1, checks)
        assertTrue(session.hasPendingUpdate)
        assertEquals(99, session.availableUpdate!!.versionCode)
    }

    @Test fun disablingAutomaticChecksPersistsButManualChecksAndKnownUpdatesStillWork() = runBlocking {
        val preferences = DeveloperModePreferences(RuntimeEnvironment.getApplication())
        assertTrue(preferences.automaticChecks)
        var checks = 0
        val next = update(BuildConfig.VERSION_CODE + 1)
        val first = session { checks++; next }
        first.configurePreferences(preferences)
        first.changeAutomaticChecks(false)
        first.checkOnStartup()
        assertEquals(0, checks)
        assertEquals(next, first.checkNow())
        assertTrue(first.hasPendingUpdate)
        val restarted = session { checks++; next }
        restarted.configurePreferences(DeveloperModePreferences(RuntimeEnvironment.getApplication()))
        restarted.checkOnStartup()
        assertFalse(restarted.automaticChecks)
        assertEquals(next, restarted.availableUpdate)
        assertEquals(1, checks)
        restarted.changeAutomaticChecks(true)
        assertEquals(2, checks)
    }

    @Test fun anOlderOrUnavailableResultDoesNotClearTheKnownUpdate() = runBlocking {
        var response = update(100)
        val session = session { response }
        session.checkOnStartup()
        response = update(99, available = false)
        session.checkNow()
        assertEquals(100, session.availableUpdate!!.versionCode)
        response = update(101)
        session.checkNow()
        assertEquals(101, session.availableUpdate!!.versionCode)
    }

    @Test fun anInstalledVersionClearsTheCachedArrowAndPreviewIsOptIn() {
        val preferences = DeveloperModePreferences(RuntimeEnvironment.getApplication())
        preferences.rememberUpdate(update(BuildConfig.VERSION_CODE))
        val preview = update(BuildConfig.VERSION_CODE + 1).copy(prerelease = true)
        preferences.rememberUpdate(preview)
        val session = session { update(available = false) }
        session.configurePreferences(preferences)
        assertNull(preferences.pendingUpdate(preview = false))
        assertFalse(session.hasPendingUpdate)
        session.configureDeveloperMode(true)
        assertEquals(preview, session.availableUpdate)
    }

    @Test fun manualAndStartupChecksShareAnInFlightRequest() = runBlocking {
        var checks = 0
        val pending = CompletableDeferred<AppUpdateResult>()
        val session = session { checks++; pending.await() }
        session.checkOnStartup()
        val manual = async(Dispatchers.Unconfined) { session.checkNow() }
        assertEquals(1, checks)
        pending.complete(update())
        assertEquals(99, manual.await().versionCode)
        assertTrue(session.hasPendingUpdate)
    }

    @Test fun automaticFailuresAreSilentAndDoNotPreventAManualRetry() = runBlocking {
        var shouldFail = true
        var checks = 0
        val session = session { checks++; if (shouldFail) throw IOException("网络不可用") else update() }
        session.checkOnStartup()
        assertFalse(session.hasPendingUpdate)
        assertNull(session.availableUpdate)
        session.checkOnStartup()
        assertEquals(1, checks)
        try { session.checkNow(); fail("Manual checks must surface network failures") }
        catch (failure: IOException) { assertEquals("网络不可用", failure.message) }
        shouldFail = false
        assertTrue(session.checkNow().available)
        assertTrue(session.hasPendingUpdate)
        assertEquals(3, checks)
    }

    @Test fun leavingTheAboutPageDoesNotDiscardAnAlreadyRunningCheck() = runBlocking {
        val pending = CompletableDeferred<AppUpdateResult>()
        val session = session { pending.await() }
        val manual = async(Dispatchers.Unconfined) { session.checkNow() }
        manual.cancel()
        pending.complete(update())
        manual.join()
        assertEquals(99, session.availableUpdate!!.versionCode)
        assertTrue(session.hasPendingUpdate)
    }

    @Test fun latestAndUnpublishedResultsDoNotInventANewVersion() = runBlocking {
        var result = update(available = false)
        val session = session { result }
        session.checkOnStartup()
        assertFalse(session.hasPendingUpdate)
        assertNull(session.availableUpdate)
        result = update().copy(published = false)
        session.checkNow()
        assertFalse(session.hasPendingUpdate)
        assertNull(session.availableUpdate)
    }

    @Test fun unlockingInvalidatesTheStableRequestAndChecksTheTestChannelWithoutAStaleResult() = runBlocking {
        var stableChecks = 0
        var previewChecks = 0
        val pending = CompletableDeferred<AppUpdateResult>()
        val session = AppUpdateSession({ stableChecks++; withContext(NonCancellable) { pending.await() } }, Dispatchers.Unconfined,
            { previewChecks++; update(100).copy(prerelease = true) }).also { store.put("updates", it) }
        session.checkOnStartup()
        assertEquals(1, stableChecks)
        session.configureDeveloperMode(true)
        assertTrue(session.developerMode)
        session.checkOnStartup()
        assertEquals(1, previewChecks)
        pending.complete(update(99))
        assertEquals(100, session.availableUpdate!!.versionCode)
        assertTrue(session.availableUpdate!!.prerelease)
        assertTrue(session.hasPendingUpdate)
        session.checkOnStartup()
        assertEquals(1, previewChecks)
    }

    @Test fun restoringTheEnabledPreferenceUsesOnlyTheOptedInChecker() = runBlocking {
        var normal = 0
        var preview = 0
        val session = AppUpdateSession({ normal++; update() }, Dispatchers.Unconfined,
            { preview++; update(100).copy(prerelease = true) }).also { store.put("updates", it) }
        session.configureDeveloperMode(true)
        session.checkOnStartup()
        assertEquals(0, normal); assertEquals(1, preview)
        session.checkNow()
        assertEquals(2, preview)
        session.configureDeveloperMode(false)
        assertNull(session.availableUpdate)
        assertFalse(session.hasPendingUpdate)
        session.checkOnStartup()
        assertEquals(1, normal)
    }
}
