package com.eyue.reader.ui

import androidx.lifecycle.ViewModelStore
import com.eyue.reader.data.AppUpdateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    @Test fun startupChecksOnlyOnceAndReadingTheBadgeKeepsAvailableLinks() {
        var checks = 0
        val session = session { checks++; update() }
        session.checkOnStartup()
        session.checkOnStartup()
        assertEquals(1, checks)
        assertTrue(session.hasUnreadUpdate)
        session.setSettingsVisible(true)
        assertFalse(session.hasUnreadUpdate)
        assertEquals(99, session.availableUpdate!!.versionCode)
        session.setSettingsVisible(false)
        assertFalse(session.hasUnreadUpdate)
        assertNotNull(session.availableUpdate)
    }

    @Test fun aCompletedCheckWhileSettingsAreVisibleIsAlreadyRead() {
        val pending = CompletableDeferred<AppUpdateResult>()
        val session = session { pending.await() }
        session.checkOnStartup()
        session.setSettingsVisible(true)
        pending.complete(update())
        assertNotNull(session.availableUpdate)
        assertFalse(session.hasUnreadUpdate)
        session.setSettingsVisible(false)
        assertFalse(session.hasUnreadUpdate)
    }

    @Test fun acknowledgedVersionsStayReadButANewerVersionCanLightTheBadge() = runBlocking {
        var code = 99
        val session = session { update(code) }
        session.checkOnStartup()
        session.setSettingsVisible(true)
        session.setSettingsVisible(false)
        session.checkNow()
        assertFalse(session.hasUnreadUpdate)
        code = 100
        session.checkNow()
        assertTrue(session.hasUnreadUpdate)
        assertEquals(100, session.availableUpdate!!.versionCode)
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
        assertTrue(session.hasUnreadUpdate)
    }

    @Test fun automaticFailuresAreSilentAndDoNotPreventAManualRetry() = runBlocking {
        var shouldFail = true
        var checks = 0
        val session = session { checks++; if (shouldFail) throw IOException("网络不可用") else update() }
        session.checkOnStartup()
        assertFalse(session.hasUnreadUpdate)
        assertNull(session.availableUpdate)
        session.checkOnStartup()
        assertEquals(1, checks)
        try { session.checkNow(); fail("Manual checks must surface network failures") }
        catch (failure: IOException) { assertEquals("网络不可用", failure.message) }
        shouldFail = false
        assertTrue(session.checkNow().available)
        assertTrue(session.hasUnreadUpdate)
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
        assertTrue(session.hasUnreadUpdate)
    }

    @Test fun latestAndUnpublishedResultsDoNotInventANewVersion() = runBlocking {
        var result = update(available = false)
        val session = session { result }
        session.checkOnStartup()
        assertFalse(session.hasUnreadUpdate)
        assertNull(session.availableUpdate)
        result = update().copy(published = false)
        session.checkNow()
        assertFalse(session.hasUnreadUpdate)
        assertNull(session.availableUpdate)
    }
}
