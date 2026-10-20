package com.eyue.reader.data

import android.content.Context
import com.eyue.reader.ui.ReaderSpeechSegment
import com.eyue.reader.ui.aiSpeechChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class AiSpeechChapterProgress(val completed: Int = 0, val total: Int = 0)

private val batchSpeechRequests = Semaphore(3)

data class AiSpeechBatchProgress(
    val bookId: String,
    val title: String,
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val running: Boolean = true,
    val error: String? = null,
    val cancelled: Boolean = false,
    val chapterCompleted: Int = 0,
    val chapterTotal: Int = 0,
    val selectedChapters: Set<Int> = emptySet(),
    val completedChapters: Set<Int> = emptySet(),
    val chapterProgress: Map<Int, AiSpeechChapterProgress> = emptyMap()
) {
    val fraction: Float? get() = total.takeIf { it > 0 }?.let {
        val partial = if (chapterProgress.isEmpty()) {
            if (chapterCompleted in 1 until chapterTotal) chapterCompleted.toFloat() / chapterTotal else 0f
        } else chapterProgress.filterKeys { index -> index !in completedChapters }.values.sumOf { chapter ->
            if (chapter.total > 0) (chapter.completed.toDouble() / chapter.total).coerceIn(0.0, 1.0) else 0.0
        }.toFloat()
        ((completed + partial) / it).coerceIn(0f, 1f)
    }
}

/** Read only one chapter at a time; PDF text retains page boundaries used by live narration. */
internal fun aiSpeechChapterSegments(book: Book, chapterIndex: Int): Sequence<ReaderSpeechSegment> = sequence {
    val chapter = book.chapters[chapterIndex]
    if (chapter.textPath != null) {
        yield(ReaderSpeechSegment(chapterIndex, 0, 0, File(chapter.textPath).readText()))
    } else {
        chapter.pages.forEachIndexed { index, page ->
            page.textPath?.let { yield(ReaderSpeechSegment(chapterIndex, index, 0, File(it).readText())) }
        }
    }
}

/** Only speaker inference is sequential; the resulting chapter seeds are immutable during parallel work. */
internal suspend fun aiSpeechChapterSeeds(book: Book, config: AiSpeechConfig, chapters: Set<Int>,
    commonRoles: List<BookVoiceRole>): Map<Int, String?> {
    if (!config.rolePromptEnabled || chapters.isEmpty()) return emptyMap()
    return aiSpeechRoleChapterSeeds(book, chapters, commonRoles)
}

internal suspend fun aiSpeechRoleChapterSeeds(book: Book, chapters: Set<Int>,
    commonRoles: List<BookVoiceRole>): Map<Int, String?> {
    if (chapters.isEmpty()) return emptyMap()
    val active = currentCoroutineContext()
    val planner = AiSpeechRolePlanner(book.id, book.voiceRoles, commonRoles) { active.ensureActive() }
    val seeds = mutableMapOf<Int, String?>()
    var previous: String? = null
    for (index in 0..chapters.max()) {
        active.ensureActive()
        if (index in chapters) seeds[index] = previous
        previous = planner.seedRoleId(aiSpeechChapterSegments(book, index).toList(), previous)
    }
    return seeds
}

internal fun aiSpeechPlannedChapter(book: Book, config: AiSpeechConfig, chapter: Int,
    commonRoles: List<BookVoiceRole>, seedRoleId: String?, checkActive: () -> Unit = {}): Sequence<PlannedAiSpeechChunk> {
    val source = aiSpeechChapterSegments(book, chapter).toList()
    return if (config.rolePromptEnabled) AiSpeechRolePlanner(book.id, book.voiceRoles, commonRoles, checkActive).plan(source, seedRoleId)
        else aiSpeechChunks(source).map { checkActive(); PlannedAiSpeechChunk(it, "", null) }
}

/** A selected chapter is complete only when all its live-narration chunks have valid cached audio. */
internal class AiSpeechBatch(
    private val context: Context,
    private val commonRoles: List<BookVoiceRole>? = null,
    private val synthesize: (suspend (AiSpeechConfig, String, File) -> Unit)? = null
) {
    private val speechClient = AiSpeechClient()
    suspend fun generate(book: Book, config: AiSpeechConfig, chapters: Set<Int>? = null,
                         onProgress: suspend (AiSpeechBatchProgress) -> Unit): AiSpeechBatchProgress = withContext(Dispatchers.IO) {
        require(book.mode == LibraryMode.NOVEL) { "漫画无需转化 AI 语音" }
        require(!book.cloudOnly) { "请先下载书籍正文" }
        val selected = chapters?.sorted() ?: book.chapters.indices.toList()
        require(selected.isNotEmpty() && selected.all { it in book.chapters.indices }) { "请选择有效章节" }
        val cache = AiSpeechCache(context)
        val commonRoles = if (config.rolePromptEnabled) this@AiSpeechBatch.commonRoles ?: LibraryRepository(context).load().commonVoiceRoles else emptyList()
        val generation = cache.generation(book.id)
        val extension = if (config.protocol == SpeechApiProtocol.MIMO) "wav" else "mp3"
        var progress = AiSpeechBatchProgress(book.id, book.title)
        onProgress(progress)
        val seeds = aiSpeechChapterSeeds(book, config, selected.toSet(), commonRoles)
        val active = currentCoroutineContext()
        val counts = selected.associateWith { index ->
            currentCoroutineContext().ensureActive()
            aiSpeechPlannedChapter(book, config, index, commonRoles, seeds[index]) { active.ensureActive() }.count()
        }.filterValues { it > 0 }
        require(counts.isNotEmpty()) { "所选章节没有可转化的正文" }
        progress = progress.copy(total = counts.size, selectedChapters = counts.keys.toSet())
        onProgress(progress)
        val progressLock = Mutex()
        suspend fun reportChapter(index: Int, completed: Int, count: Int, complete: Boolean = false) {
            progressLock.withLock {
                val finished = if (complete) progress.completedChapters + index else progress.completedChapters
                progress = progress.copy(chapterIndex = index, chapterTitle = book.chapters[index].title,
                    chapterCompleted = completed, chapterTotal = count, completed = finished.size, completedChapters = finished,
                    chapterProgress = progress.chapterProgress + (index to AiSpeechChapterProgress(completed, count)))
                onProgress(progress)
            }
        }
        suspend fun generateChapter(index: Int, count: Int) {
            currentCoroutineContext().ensureActive()
            checkGeneration(cache, book.id, generation)
            val chapterContext = currentCoroutineContext()
            val chunks = aiSpeechPlannedChapter(book, config, index, commonRoles, seeds[index]) { chapterContext.ensureActive() }.toList()
            val keys = chunks.map { AiSpeechCache.key(config, it.chunk.text, it.roleInstructions) }
            val complete = keys.all { cache.find(book.id, it, extension) != null }
            checkGeneration(cache, book.id, generation)
            reportChapter(index, 0, count)
            if (complete) {
                check(cache.recordPlannedChapterCompletion(book, config, index, commonRoles, seeds[index], keys, generation)) {
                    "AI 语音缓存已清理，转化已停止"
                }
                reportChapter(index, count, count, complete = true)
                return
            }
            var completed = 0
            for (planned in chunks) {
                currentCoroutineContext().ensureActive()
                checkGeneration(cache, book.id, generation)
                val chunk = planned.chunk
                val rolePrompt = planned.roleInstructions
                val key = AiSpeechCache.key(config, chunk.text, rolePrompt)
                val temporary = File(context.cacheDir, "speech-batch-${UUID.randomUUID()}.$extension")
                try {
                    batchSpeechRequests.withPermit {
                        currentCoroutineContext().ensureActive()
                        checkGeneration(cache, book.id, generation)
                        if (synthesize == null) speechClient.synthesize(config, chunk.text, temporary, rolePrompt)
                        else synthesize.invoke(config, chunk.text, temporary)
                    }
                    currentCoroutineContext().ensureActive()
                    if (cache.commit(book.id, key, extension, temporary, generation, overwrite = true) == null) {
                        throw IOException("AI 语音缓存已清理，转化已停止")
                    }
                } finally {
                    temporary.delete()
                }
                currentCoroutineContext().ensureActive()
                checkGeneration(cache, book.id, generation)
                completed++
                if (completed == count) check(cache.recordPlannedChapterCompletion(book, config, index,
                    commonRoles, seeds[index], keys, generation)) { "AI 语音缓存已清理，转化已停止" }
                reportChapter(index, completed, count, complete = completed == count)
            }
        }
        val chapters = counts.entries.toList()
        val next = AtomicInteger()
        coroutineScope {
            repeat(minOf(3, chapters.size)) {
                launch {
                    while (true) {
                        val position = next.getAndIncrement()
                        if (position >= chapters.size) break
                        val chapter = chapters[position]
                        generateChapter(chapter.key, chapter.value)
                    }
                }
            }
        }
        progress.copy(running = false).also { onProgress(it) }
    }

    private fun checkGeneration(cache: AiSpeechCache, bookId: String, generation: Long) {
        if (cache.generation(bookId) != generation) throw IOException("AI 语音缓存已清理，转化已停止")
    }
}
