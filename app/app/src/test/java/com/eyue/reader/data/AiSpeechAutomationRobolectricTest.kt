package com.eyue.reader.data

import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechAutomationRobolectricTest {
    private val context = RuntimeEnvironment.getApplication()
    private val folder = File(context.cacheDir, "background-speech-${UUID.randomUUID()}").apply { mkdirs() }
    private val config = AiSpeechConfig("https://voice.example.org/v1", "test-private-key", "model", "voice", id = "saved-profile")
    private val books = mutableListOf<Book>()
    private val plans = mutableListOf<String>()

    @After fun tearDown() {
        books.forEach { AiSpeechCache(context).clear(it.id) }
        plans.forEach { AiSpeechAutomation.planFile(context, it).delete() }
        folder.deleteRecursively()
    }

    @Test fun durableRequestKeepsProfileAndChapterSelectionWithoutApiKeyOrBookText() {
        val book = book("private book text", "Another chapter")
        val request = request(book, setOf(1))
        val spec = request.workSpec
        val input = Data.fromByteArray(spec.input.toByteArray())
        assertEquals(AiSpeechConversionWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.expedited)
        assertEquals(config.id, input.getString(AiSpeechAutomation.KEY_PROFILE))
        assertEquals(book.contentVersion, input.getInt(AiSpeechAutomation.KEY_VERSION, -1))
        assertEquals(AiSpeechCache.configurationIdentity(config), input.getString(AiSpeechAutomation.KEY_CONFIG))
        val bytes = spec.input.toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(config.key))
        assertFalse(bytes.contains("private book text"))
        val plan = AiSpeechAutomation.planFile(context, requireNotNull(input.getString(AiSpeechAutomation.KEY_PLAN))).readFully().toString(Charsets.UTF_8)
        assertEquals("[1]", JSONObject(plan).getJSONArray("chapters").toString())
        val queued = WorkInfo(request.id, WorkInfo.State.ENQUEUED, request.tags)
        assertEquals(book.id, AiSpeechAutomation.progress(queued)?.bookId)
        assertEquals(book.title, AiSpeechAutomation.progress(queued)?.title)
        assertTrue(requireNotNull(AiSpeechAutomation.progress(queued)).running)
    }

    @Test fun reconstructedExecutionSkipsCompleteChaptersAndRegeneratesTheEntirePartialChapter() = runBlocking {
        val book = book("Chapter one", "Chapter two A\nChapter two B")
        LibraryRepository(context).save(LibraryState(books = listOf(book)))
        val input = Data.fromByteArray(request(book).workSpec.input.toByteArray())
        val firstRequests = Collections.synchronizedList(mutableListOf<String>())
        val firstChapterComplete = CompletableDeferred<Unit>()
        val error = runCatching {
            runAiSpeechConversion(context, input, loadConfig = { config }, synthesize = { _, text, target ->
                firstRequests += text
                if (text == "Chapter two B") { firstChapterComplete.await(); throw IOException("temporary service failure") }
                target.writeBytes(byteArrayOf(1))
            }) { if (0 in it.completedChapters) firstChapterComplete.complete(Unit) }
        }.exceptionOrNull()
        assertEquals("temporary service failure", error?.message)
        assertEquals(setOf(0), AiSpeechCache(context).completedChapters(book, config))
        val nextRequests = mutableListOf<String>()
        val final = runAiSpeechConversion(context, Data.fromByteArray(input.toByteArray()), loadConfig = { config },
            synthesize = { _, text, target -> nextRequests += text; target.writeBytes(byteArrayOf(2)) }) { }
        assertEquals(listOf("Chapter two A", "Chapter two B"), nextRequests)
        assertArrayEquals(byteArrayOf(2), requireNotNull(AiSpeechCache(context).find(book.id,
            AiSpeechCache.key(config, "Chapter two A"), "mp3")).readBytes())
        assertEquals(2, final.completed)
        assertEquals(2, final.total)
        assertEquals(setOf(0, 1), AiSpeechCache(context).completedChapters(book, config))
    }

    @Test fun cancelledBackgroundExecutionAbortsCurrentRequestAndCanResumeWithItsDurablePlan() = runBlocking {
        val book = book("Complete chapter", "Partial chapter\nPending chunk")
        val input = request(book).workSpec.input
        val pending = CompletableDeferred<File>()
        val firstChapterComplete = CompletableDeferred<Unit>()
        val task = launch {
            runAiSpeechConversion(context, input, loadBook = { book }, loadConfig = { config }, synthesize = { _, text, target ->
                target.writeBytes(byteArrayOf(1))
                if (text == "Pending chunk") { firstChapterComplete.await(); pending.complete(target); awaitCancellation() }
            }) { if (0 in it.completedChapters) firstChapterComplete.complete(Unit) }
        }
        val temporary = pending.await()
        task.cancelAndJoin()
        assertFalse(temporary.exists())
        assertEquals(setOf(0), AiSpeechCache(context).completedChapters(book, config))
        val requests = mutableListOf<String>()
        val result = runAiSpeechConversion(context, input, loadBook = { book }, loadConfig = { config },
            synthesize = { _, text, target -> requests += text; target.writeBytes(byteArrayOf(3)) }) { }
        assertEquals(listOf("Partial chapter", "Pending chunk"), requests)
        assertArrayEquals(byteArrayOf(3), requireNotNull(AiSpeechCache(context).find(book.id,
            AiSpeechCache.key(config, "Partial chapter"), "mp3")).readBytes())
        assertEquals(2, result.completed)
    }

    @Test fun sameProfileIdWithChangedAudioConfigurationStopsBeforeSendingAnyRequest() = runBlocking {
        val book = book("Text")
        val input = request(book).workSpec.input
        val changed = AiSpeechConfig(config.url, config.key, "new-model", config.voice, id = config.id)
        var requests = 0
        val error = runCatching {
            runAiSpeechConversion(context, input, loadBook = { book }, loadConfig = { changed },
                synthesize = { _, _, _ -> requests++ }) { }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("配置已修改"))
        assertEquals(0, requests)
    }

    @Test fun queuedConversionRejectsChangedBookVersionBeforeSendingAnyRequest() = runBlocking {
        val book = book("Originally selected chapter")
        val input = Data.fromByteArray(request(book).workSpec.input.toByteArray())
        var requests = 0
        val updated = book.copy(contentVersion = book.contentVersion + 1,
            chapters = listOf(Chapter(title = "Different chapter", textPath = book.chapters.single().textPath)))
        val error = runCatching {
            runAiSpeechConversion(context, input, loadBook = { updated }, loadConfig = { config },
                synthesize = { _, _, _ -> requests++ }) { }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("正文或目录已修改"))
        assertEquals(0, requests)
    }

    @Test fun chapterCompletionRequiresEveryCurrentChunkAndIsInvalidatedByTextProfileAndCacheChanges() = runBlocking {
        val book = book("First chunk\nSecond chunk", "  \n")
        val cache = AiSpeechCache(context)
        fun save(text: String) {
            val temporary = File.createTempFile("cached", ".mp3", folder).apply { writeBytes(byteArrayOf(1)) }
            cache.commit(book.id, AiSpeechCache.key(config, text), "mp3", temporary, cache.generation(book.id))
        }
        save("First chunk")
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
        save("Second chunk")
        assertEquals(setOf(0), AiSpeechCache(context).completedChapters(book, config))
        val changed = AiSpeechConfig(config.url, config.key, config.model, "new-voice", id = config.id)
        assertEquals(emptySet<Int>(), cache.completedChapters(book, changed))
        File(requireNotNull(book.chapters[0].textPath)).writeText("First chunk\nEdited chunk")
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
        File(requireNotNull(book.chapters[0].textPath)).writeText("First chunk\nSecond chunk")
        assertEquals(setOf(0), cache.completedChapters(book, config))
        requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Second chunk"), "mp3")).writeBytes(byteArrayOf())
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
        cache.clear(book.id)
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
    }

    @Test fun cancelQueuedConversionPersistsCancellationForARecreatedScreen() = runBlocking {
        val manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build()); WorkManager.getInstance(context)
        }
        val book = book("Text")
        val request = request(book)
        request.workSpec.expedited = false
        request.workSpec.initialDelay = TimeUnit.DAYS.toMillis(1)
        manager.enqueueUniqueWork("speech-cancel-${book.id}", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.state)
        AiSpeechAutomation.cancelAndAwait(context, book.id)
        val cancelled = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!
        val restored = requireNotNull(AiSpeechAutomation.progress(cancelled))
        assertFalse(restored.running)
        assertTrue(restored.cancelled)
        manager.pruneWork().result.get(10, TimeUnit.SECONDS)
        Unit
    }

    @Test fun persistedProgressUsesChaptersAndRetainsInnerChapterDetailAndFailure() {
        val book = book("Text")
        val request = request(book)
        val progress = AiSpeechBatchProgress(book.id, book.title, chapterIndex = 2, chapterTitle = "Third chapter",
            completed = 2, total = 4, chapterCompleted = 1, chapterTotal = 2)
        val data = AiSpeechAutomation.progressData(progress)
        val running = WorkInfo(request.id, WorkInfo.State.RUNNING, request.tags, progress = data)
        assertEquals(progress, AiSpeechAutomation.progress(running))
        assertEquals(.625f, requireNotNull(AiSpeechAutomation.progress(running)?.fraction), 0f)
        val failure = progress.copy(running = false, error = "Failed chapter")
        val finished = WorkInfo(request.id, WorkInfo.State.FAILED, request.tags, outputData = AiSpeechAutomation.progressData(failure))
        assertEquals(failure, AiSpeechAutomation.progress(finished))
    }

    @Test fun queuedAndRunningChapterSelectionsRestoreFromTheFilteredPlanWithoutExpandingWorkData() = runBlocking {
        val source = book("Blank", "One", "Two")
        val request = request(source)
        val queued = WorkInfo(request.id, WorkInfo.State.ENQUEUED, request.tags)
        assertEquals(setOf(0, 1, 2), AiSpeechAutomation.progress(context, queued)?.selectedChapters)
        AiSpeechAutomation.writePlan(context, requireNotNull(request.workSpec.input.getString(AiSpeechAutomation.KEY_PLAN)),
            setOf(1, 2), setOf(2), mapOf(1 to AiSpeechChapterProgress(1, 4), 2 to AiSpeechChapterProgress(3, 3)))
        val report = AiSpeechBatchProgress(source.id, source.title, completed = 1, total = 2,
            selectedChapters = setOf(1, 2), completedChapters = setOf(2))
        val data = AiSpeechAutomation.progressData(report)
        val restored = AiSpeechAutomation.progress(context, WorkInfo(request.id, WorkInfo.State.RUNNING, request.tags,
            progress = Data.fromByteArray(data.toByteArray())))
        assertEquals(setOf(1, 2), restored?.selectedChapters)
        assertEquals(setOf(2), restored?.completedChapters)
        assertEquals(AiSpeechChapterProgress(1, 4), restored?.chapterProgress?.get(1))
        assertFalse(data.keyValueMap.containsKey("selectedChapters"))
        assertFalse(data.keyValueMap.containsKey("completedChapters"))
    }

    @Test fun thousandsOfSelectedChaptersStayOutOfTheWorkManagerInputAndProgressByteLimit() = runBlocking {
        val original = book("Shared source")
        val large = original.copy(chapters = List(4_000) { Chapter(title = "Chapter $it", textPath = original.chapters.single().textPath) })
        val request = request(large)
        assertTrue(request.workSpec.input.toByteArray().size < 2_048)
        val report = AiSpeechBatchProgress(large.id, large.title, completed = 2_000, total = 4_000,
            selectedChapters = large.chapters.indices.toSet(), completedChapters = (0 until 2_000).toSet())
        val data = AiSpeechAutomation.progressData(report)
        assertTrue(data.toByteArray().size < 2_048)
        AiSpeechAutomation.writePlan(context, requireNotNull(request.workSpec.input.getString(AiSpeechAutomation.KEY_PLAN)),
            report.selectedChapters, report.completedChapters, report.chapterProgress)
        val restored = AiSpeechAutomation.progress(context, WorkInfo(request.id, WorkInfo.State.RUNNING, request.tags, progress = data))
        assertEquals(4_000, restored?.selectedChapters?.size)
        assertEquals((0 until 2_000).toSet(), restored?.completedChapters)
    }

    @Test fun queuedWorkRejectsChangedBookRolesBeforeSendingAnyRequest() = runBlocking {
        val original = book("张三的正文")
        val enabled = AiSpeechConfig.fromJson(config.toJson().put("rolePromptEnabled", true))
        val input = AiSpeechAutomation.request(context, original, enabled, null).also {
            plans += requireNotNull(it.workSpec.input.getString(AiSpeechAutomation.KEY_PLAN))
        }.workSpec.input
        var requested = false
        val changed = original.copy(voiceRoles = listOf(BookVoiceRole("张三", "张三", "男声")))
        val error = runCatching {
            runAiSpeechConversion(context, input, loadBook = { changed }, loadConfig = { enabled },
                synthesize = { _, _, _ -> requested = true }) {}
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("角色音色已修改"))
        assertFalse(requested)
    }

    @Test fun disabledRolePromptingAcceptsRoleChangesButRejectsEnablingTheFeatureAfterEnqueue() = runBlocking {
        val original = book("张三的正文")
        val input = request(original).workSpec.input
        val changed = original.copy(voiceRoles = listOf(BookVoiceRole("张三", "张三", "男声")))
        var requests = 0
        val result = runAiSpeechConversion(context, input, loadBook = { changed }, loadConfig = { config },
            synthesize = { _, _, target -> requests++; target.writeBytes(byteArrayOf(1)) }) {}
        assertEquals(setOf(0), result.completedChapters)
        assertEquals(1, requests)
        val error = runCatching {
            runAiSpeechConversion(context, input, loadBook = { changed },
                loadConfig = { AiSpeechConfig.fromJson(config.toJson().put("rolePromptEnabled", true)) },
                synthesize = { _, _, _ -> requests++ }) {}
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("配置已修改"))
        assertEquals(1, requests)
    }

    private fun request(book: Book, chapters: Set<Int>? = null) = AiSpeechAutomation.request(context, book, config, chapters).also {
        plans += requireNotNull(it.workSpec.input.getString(AiSpeechAutomation.KEY_PLAN))
    }
    private fun book(vararg texts: String) = Book(title = "Background book", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
        chapters = texts.mapIndexed { index, text -> Chapter(title = "Chapter $index", textPath =
            File(folder, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath) }).also { books += it }
}
