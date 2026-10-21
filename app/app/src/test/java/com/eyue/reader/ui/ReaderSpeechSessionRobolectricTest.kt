package com.eyue.reader.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderSpeechSessionRobolectricTest {
    private lateinit var scope: CoroutineScope
    private lateinit var audio: ReaderAudioSession
    private lateinit var session: ReaderSpeechSession
    private val engine = CapturingSpeech()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private var started = 0

    @Before fun setup() {
        ReaderAudioService.owner = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        audio = ReaderAudioSession(RuntimeEnvironment.getApplication(), "会话测试")
        session = ReaderSpeechSession(scope, audio)
    }

    @After fun cleanup() {
        releases.forEach { it.complete(Unit) }
        session.stop(engine)
        scope.cancel()
        runBlocking { withTimeout(2_000) { scope.coroutineContext[Job]!!.join() } }
        ReaderAudioService.owner = null
    }

    @Test fun aBodyReadErrorDoesNotRequestTheServiceOrStartTheEngineAndCanBeCleared() {
        engine.error.value = "原引擎错误"
        start { throw IOException("无法读取《第九章》的正文") }
        assertEquals("无法读取《第九章》的正文", session.readError)
        assertFalse(session.active)
        assertNull(session.preparation)
        assertNull(audio.pending)
        assertNull(ReaderAudioService.owner)
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(engine.sources.isEmpty())
        assertEquals(0, started)

        session.clearError(engine)
        assertNull(session.readError)
        assertNull(engine.error.value)
        assertEquals(1, engine.clears)
    }

    @Test fun anImmediatelyReadySourceDoesNotRetainACompletedPreparationJob() {
        var speed = 1f
        session.start(engine, { speed }, { started++ }, { _, _ -> }, source = { source("当前正文") })
        assertTrue(session.active)
        assertNull(session.preparation)
        assertTrue(engine.sources.isEmpty())
        assertNotNull(audio.pending)
        assertEquals("An idle engine does not need a pause request", 0, engine.pauses)
        speed = 1.6f
        deliverServiceStart()
        assertEquals("当前正文", engine.sources.single().single().text)
        assertEquals(1.6f, engine.lastSpeed)
        assertEquals(1.6f, audio.speed)
        assertEquals(1, started)
        assertTrue(audio.playing)
        assertNull(session.preparation)
    }

    @Test fun reachingTheEndWithNoNextSourceLeavesTheSessionPausedWithoutAStaleJobOrError() {
        start { source("全书最后一段") }
        deliverServiceStart()
        start { null }
        assertTrue(session.active)
        assertNull(session.preparation)
        assertNull(audio.pending)
        assertFalse(audio.playing)
        assertFalse(engine.playing.value)
        assertNull(session.readError)
        assertEquals(1, engine.sources.size)
        assertEquals(1, started)
    }

    @Test fun steppingPastTheEndRetainsThePausedNodeForResumeInsteadOfStartingAgain() {
        start { source("全书最后一段，继续之前的节点。") }
        deliverServiceStart()
        engine.currentOffset = 8
        session.pause(engine) // The reader pauses before preparing a fast-forward operation.
        start { null }
        assertTrue(session.active)
        assertNull(session.preparation)
        assertNull(audio.pending)
        assertTrue(engine.canResume)
        assertEquals(8, engine.currentOffset)
        assertFalse(engine.playing.value)
        assertNull(session.readError)

        audio.play {
            assertTrue(engine.resume())
            audio.update(engine.playing.value, true, 1f)
        }
        deliverServiceStart()
        assertTrue(engine.playing.value)
        assertTrue(audio.playing)
        assertEquals(listOf(8), engine.resumedOffsets)
        assertEquals(1, engine.sources.size)
        assertEquals(1, started)
    }

    @Test fun preparingAReplacementPausesPlaybackButKeepsItsNodeUntilTheNewSourceStarts() = runBlocking {
        start { source("当前正在朗读的段落") }
        deliverServiceStart()
        engine.currentOffset = 5
        val previousPauses = engine.pauses
        val previousStops = engine.stops
        val release = release()
        start { release.await(); source("新的目标段落") }
        val preparation = requireNotNull(session.preparation)
        assertEquals(previousPauses + 1, engine.pauses)
        assertEquals(previousStops, engine.stops)
        assertEquals(5, engine.currentOffset)
        assertTrue(engine.canResume)
        assertFalse(engine.playing.value)
        assertNull(audio.pending)

        release.complete(Unit)
        withTimeout(2_000) { preparation.join() }
        assertEquals(previousStops, engine.stops)
        assertEquals(5, engine.currentOffset)
        deliverServiceStart()
        assertEquals(previousStops + 1, engine.stops)
        assertEquals("新的目标段落", engine.sources.last().single().text)
        assertEquals(0, engine.currentOffset)
    }

    @Test fun aPlayingEngineThatCannotPauseIsStoppedBeforePreparingAnotherSource() = runBlocking {
        engine.playing.value = true
        engine.pauseAllowed = false
        val release = release()
        start { release.await(); null }
        val preparation = requireNotNull(session.preparation)
        assertEquals(1, engine.pauses)
        assertEquals(1, engine.stops)
        assertFalse(engine.playing.value)
        assertNull(audio.pending)
        release.complete(Unit)
        withTimeout(2_000) { preparation.join() }
        assertNull(session.preparation)
        assertNull(session.readError)
    }

    @Test fun completionOfCancelledOldPreparationCannotClearOrStartTheNewSession() = runBlocking {
        val oldRelease = release()
        start { withContext(NonCancellable) { oldRelease.await(); source("旧正文") } }
        val oldJob = requireNotNull(session.preparation)
        val newRelease = release()
        start { newRelease.await(); source("新正文") }
        val newJob = requireNotNull(session.preparation)
        assertNotSame(oldJob, newJob)
        oldRelease.complete(Unit)
        withTimeout(2_000) { oldJob.join() }
        assertSame(newJob, session.preparation)
        assertTrue(newJob.isActive)
        assertNull(audio.pending)
        assertTrue(engine.sources.isEmpty())
        assertNull(session.readError)

        newRelease.complete(Unit)
        withTimeout(2_000) { newJob.join() }
        deliverServiceStart()
        assertEquals(listOf("新正文"), engine.sources.map { it.single().text })
        assertEquals(1, started)
        assertNull(session.preparation)
    }

    @Test fun aLateReadFailureFromTheOldPreparationCannotOverwriteANewerPlayingSession() = runBlocking {
        val oldRelease = release()
        start { withContext(NonCancellable) { oldRelease.await(); throw IOException("旧正文已损坏") } }
        val oldJob = requireNotNull(session.preparation)
        start { source("新书正文") }
        deliverServiceStart()
        oldRelease.complete(Unit)
        withTimeout(2_000) { oldJob.join() }
        assertNull(session.readError)
        assertTrue(session.active)
        assertTrue(engine.playing.value)
        assertTrue(audio.playing)
        assertEquals(listOf("新书正文"), engine.sources.map { it.single().text })
        assertEquals(1, started)
    }

    @Test fun pausingSlowPreparationDoesNotStartItWhenItsReadEventuallyReturns() = runBlocking {
        val release = release()
        start { withContext(NonCancellable) { release.await(); source("暂停前正文") } }
        val pending = requireNotNull(session.preparation)
        val previousPauseRequests = engine.pauses
        session.pause(engine)
        assertTrue(session.active)
        assertNull(session.preparation)
        assertEquals(previousPauseRequests + 1, engine.pauses)
        release.complete(Unit)
        withTimeout(2_000) { pending.join() }
        assertNull(audio.pending)
        assertNull(ReaderAudioService.owner)
        assertTrue(engine.sources.isEmpty())
        assertEquals(0, started)
        assertNull(session.readError)
    }

    @Test fun stoppingSlowPreparationEndsTheSessionAndRejectsItsLateRead() = runBlocking {
        val release = release()
        start { withContext(NonCancellable) { release.await(); source("停止前正文") } }
        val pending = requireNotNull(session.preparation)
        session.stop(engine)
        assertFalse(session.active)
        assertNull(session.preparation)
        release.complete(Unit)
        withTimeout(2_000) { pending.join() }
        assertNull(audio.pending)
        assertNull(ReaderAudioService.owner)
        assertFalse(audio.playing)
        assertTrue(engine.sources.isEmpty())
        assertEquals(0, started)
    }

    @Test fun aServiceStartQueuedBeforePauseOrStopCannotRestartTheEngine() {
        start { source("等待系统服务") }
        val pausedCallback = requireNotNull(audio.pending)
        session.pause(engine)
        pausedCallback()
        assertTrue(engine.sources.isEmpty())
        assertEquals(0, started)
        assertNull(audio.pending)

        start { source("再次等待系统服务") }
        val stoppedCallback = requireNotNull(audio.pending)
        session.stop(engine)
        stoppedCallback()
        assertTrue(engine.sources.isEmpty())
        assertEquals(0, started)
        assertNull(audio.pending)
        assertFalse(session.active)
    }

    @Test fun detachingAPlayingEngineRequestsRestartAndReleasesTheOldEngine() {
        start { source("切换引擎") }
        deliverServiceStart()
        session.detach(engine)
        assertTrue(session.restartForEngine)
        assertTrue(session.active)
        assertNull(session.preparation)
        assertNull(audio.pending)
        assertFalse(audio.playing)
        assertEquals(1, engine.releases)

        val replacement = CapturingSpeech()
        session.restartForEngine = false
        session.start(replacement, { 1.2f }, { started++ }, { _, _ -> }, source = { source("切换后的正文") })
        deliverServiceStart()
        assertEquals("切换后的正文", replacement.sources.single().single().text)
        assertEquals(1.2f, replacement.lastSpeed)
        assertEquals(2, started)
        session.stop(replacement)
    }

    @Test fun detachingWhileTheServiceIsPendingRejectsItsOldCallback() {
        start { source("等待服务时切换") }
        val stale = requireNotNull(audio.pending)
        session.detach(engine)
        stale()
        assertTrue(session.restartForEngine)
        assertEquals(1, engine.releases)
        assertTrue(engine.sources.isEmpty())
        assertNull(audio.pending)
        assertEquals(0, started)
    }

    @Test fun detachingDuringPreparationRequestsRestartAndCancelsTheOldSource() = runBlocking {
        val release = release()
        start { withContext(NonCancellable) { release.await(); source("切换前仍在读取") } }
        val pending = requireNotNull(session.preparation)
        session.detach(engine)
        assertTrue(session.restartForEngine)
        assertNull(session.preparation)
        assertEquals(1, engine.releases)
        release.complete(Unit)
        withTimeout(2_000) { pending.join() }
        assertTrue(engine.sources.isEmpty())
        assertNull(audio.pending)
        assertEquals(0, started)
    }

    @Test fun detachingAnExplicitlyPausedSessionDoesNotRequestAnAutomaticRestart() {
        start { source("用户暂停后切换") }
        deliverServiceStart()
        session.pause(engine)
        session.detach(engine)
        assertTrue(session.active)
        assertFalse(session.restartForEngine)
        assertEquals(1, engine.releases)
        assertNull(audio.pending)
        assertFalse(audio.playing)
    }

    private fun start(source: suspend () -> ReaderSpeechSource?) {
        session.start(engine, { 1f }, { started++ }, { _, _ -> }, source)
    }

    /** Robolectric records foreground-service requests without delivering them automatically. */
    private fun deliverServiceStart() {
        val pending = requireNotNull(audio.pending)
        audio.pending = null
        pending()
    }

    private fun source(text: String) = ReaderSpeechSource(0, 1,
        listOf(ReaderSpeechSegment(0, 0, 0, text, text))) { emptyList() }

    private fun release() = CompletableDeferred<Unit>().also { releases += it }

    private class CapturingSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        val sources = mutableListOf<List<ReaderSpeechSegment>>()
        var lastSpeed = 0f
        var pauses = 0
        var releases = 0
        var clears = 0
        var stops = 0
        var pauseAllowed = true
        var canResume = false
        var currentOffset: Int? = null
        val resumedOffsets = mutableListOf<Int>()
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            sources += source
            lastSpeed = speed
            currentOffset = source.firstOrNull()?.offset
            canResume = currentOffset != null
            playing.value = true
        }
        override fun pause(): Boolean {
            pauses++
            if (!pauseAllowed || !canResume) return false
            playing.value = false
            return true
        }
        override fun resume(): Boolean {
            if (!canResume) return false
            resumedOffsets += requireNotNull(currentOffset)
            playing.value = true
            return true
        }
        override fun stop() { stops++; canResume = false; currentOffset = null; playing.value = false }
        override fun release() { releases++; stop() }
        override fun clearError() { clears++; error.value = null }
        override fun setSpeed(speed: Float) { lastSpeed = speed }
    }
}
