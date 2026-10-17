package com.eyue.reader.data

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechParallelRobolectricTest {
    private val context = RuntimeEnvironment.getApplication()
    private val folder = File(context.cacheDir, "parallel-speech-${UUID.randomUUID()}").apply { mkdirs() }
    private val config = AiSpeechConfig("https://voice.example.org/v1", "offline-test-key", "tts", "voice", id = "parallel-profile")
    private val books = mutableListOf<Book>()
    private val plans = mutableListOf<String>()

    @After fun clean() {
        books.forEach { AiSpeechCache(context).clear(it.id) }
        plans.forEach { AiSpeechAutomation.planFile(context, it).delete() }
        folder.deleteRecursively()
    }

    @Test fun chaptersRunConcurrentlyFinishOutOfOrderAndCancellationKeepsOnlyCompletedAudio() = runBlocking {
        withTimeout(15_000) {
            val book = book(*Array(4) { "Start$it\nEnd$it" })
            val gates = List(4) { CompletableDeferred<Unit>() }
            val started = Channel<String>(Channel.UNLIMITED)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val latest = AtomicReference<AiSpeechBatchProgress>()
            val secondChapterFinished = CompletableDeferred<Unit>()
            val task = launch {
                AiSpeechBatch(context) { _, text, target ->
                    val inFlight = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, inFlight) }
                    try {
                        started.send(text)
                        gates[text.last().digitToInt()].await()
                        target.writeBytes(byteArrayOf(1))
                    } finally { active.decrementAndGet() }
                }.generate(book, config) { progress ->
                    latest.set(progress)
                    if (2 in progress.completedChapters) secondChapterFinished.complete(Unit)
                }
            }
            val first = List(3) { started.receive() }.toSet()
            assertEquals(setOf("Start0", "Start1", "Start2"), first)
            assertEquals(3, peak.get())
            assertEquals(setOf(0, 1, 2), latest.get().chapterProgress.keys)
            gates[2].complete(Unit)
            secondChapterFinished.await()
            while (started.receive() != "Start3") { }
            val progress = latest.get()
            assertEquals(setOf(2), progress.completedChapters)
            assertEquals(1, progress.completed)
            assertEquals(AiSpeechChapterProgress(2, 2), progress.chapterProgress[2])
            assertEquals(AiSpeechChapterProgress(0, 2), progress.chapterProgress[0])
            task.cancelAndJoin()
            assertEquals(0, active.get())
            assertEquals(setOf(2), AiSpeechCache(context).completedChapters(book, config))
            assertEquals(3, peak.get())
        }
        Unit
    }

    @Test fun multipleBooksShareThreeRequestSlotsAndCancellingAQueuedBookDoesNotStopTheOthers() = runBlocking {
        withTimeout(15_000) {
            val sources = List(5) { book("Book$it") }
            val release = CompletableDeferred<Unit>()
            val started = Channel<String>(Channel.UNLIMITED)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val jobs = sources.associate { source -> source.id to async {
                runAiSpeechConversion(context, request(source).workSpec.input, loadBook = { source }, loadConfig = { config },
                    synthesize = { _, _, target ->
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        try { started.send(source.id); release.await(); target.writeBytes(byteArrayOf(1)) }
                        finally { active.decrementAndGet() }
                    }) { }
            } }
            val admitted = List(3) { started.receive() }.toSet()
            assertEquals(3, admitted.size)
            assertNull(withTimeoutOrNull(150) { started.receive() })
            val waiting = sources.first { it.id !in admitted }
            jobs.getValue(waiting.id).cancelAndJoin()
            release.complete(Unit)
            jobs.filterKeys { it != waiting.id }.values.forEach { assertEquals(1, it.await().completed) }
            assertEquals(3, peak.get())
            assertEquals(0, active.get())
            assertEquals(0L, AiSpeechCache(context).bytes(waiting.id))
            sources.filter { it.id != waiting.id }.forEach { assertEquals(setOf(0), AiSpeechCache(context).completedChapters(it, config)) }
        }
        Unit
    }

    @Test fun recreatedViewModelRestoresTwoBooksAndCancelsOnlyTheRequestedBook() {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val sources = List(3) { book("Stored book $it") }
        LibraryRepository(context).save(LibraryState(books = sources))
        val requests = sources.take(2).map { source -> request(source).also { request ->
            request.workSpec.expedited = false
            request.workSpec.initialDelay = TimeUnit.DAYS.toMillis(1)
            manager.enqueueUniqueWork("eread-ai-conversion-${source.id}", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        } }
        val model = ReaderViewModel(context)
        try {
            awaitMain { !model.loading && sources.take(2).all { model.aiSpeechGenerations[it.id]?.running == true } }
            val duplicate = runBlocking { AiSpeechAutomation.enqueue(context, sources[0], config) }
            assertEquals(requests[0].id, duplicate)
            model.generateAiSpeech(sources[1].id)
            assertTrue(model.message.orEmpty().contains("本书正在"))
            model.generateAiSpeech(sources[2].id)
            assertEquals("请先配置 AI 语音接口", model.message)
            model.cancelAiSpeechGeneration(sources[0].id)
            awaitMain { model.aiSpeechGenerations[sources[0].id]?.cancelled == true }
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(requests[0].id).get(10, TimeUnit.SECONDS)!!.state)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(requests[1].id).get(10, TimeUnit.SECONDS)!!.state)
            assertTrue(model.aiSpeechGenerations.getValue(sources[1].id).running)
        } finally {
            model.viewModelScope.cancel()
            runBlocking { AiSpeechAutomation.cancelAllAndAwait(context) }
            manager.pruneWork().result.get(10, TimeUnit.SECONDS)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("Background book state did not settle", condition())
    }
    private fun request(book: Book) = AiSpeechAutomation.request(context, book, config, null).also {
        plans += requireNotNull(it.workSpec.input.getString(AiSpeechAutomation.KEY_PLAN))
    }
    private fun book(vararg texts: String) = Book(title = "Parallel book", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = texts.mapIndexed { index, text -> Chapter(title = "Chapter $index", textPath =
            File(folder, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath) }).also { books += it }
}
