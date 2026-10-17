package com.eyue.reader.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.eyue.reader.data.AiSpeechConfigStore
import com.eyue.reader.data.AiSpeechConfig
import com.eyue.reader.data.AiSpeechCache
import com.eyue.reader.data.AiSpeechClient
import com.eyue.reader.data.SpeechApiProtocol
import com.eyue.reader.data.retryAiSpeechSynthesis
import com.eyue.reader.data.BookVoiceRole
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.AiSpeechRolePlanner
import com.eyue.reader.data.PlannedAiSpeechChunk
import com.eyue.reader.data.Book
import com.eyue.reader.data.aiSpeechChapterSegments
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal data class AiSpeechChunk(val segment: ReaderSpeechSegment, val start: Int, val end: Int, val text: String)

internal fun aiSpeechChunks(source: List<ReaderSpeechSegment>): Sequence<AiSpeechChunk> = sequence {
    for (segment in source) {
        var offset = 0
        while (offset < segment.text.length) {
            val paragraphEnd = segment.text.indexOf('\n', offset).takeIf { it >= 0 } ?: segment.text.length
            var end = minOf(paragraphEnd, offset + 240)
            if (end > offset && Character.isHighSurrogate(segment.text[end - 1])) end--
            if (end <= offset) { offset++; continue }
            val text = segment.text.substring(offset, end)
            if (text.isNotBlank()) yield(AiSpeechChunk(segment, offset, end, text))
            offset = if (end == paragraphEnd) end + 1 else end
        }
    }
}

internal class AiReaderSpeech(private val context: Context, private val bookId: String? = null,
    private val configProvider: (() -> AiSpeechConfig?)? = null,
    client: OkHttpClient = AiSpeechClient.defaultClient(),
    private val fallbackEnabled: Boolean = false,
    fallbackFactory: (() -> ReaderSpeechController)? = null,
    private val rolesProvider: (() -> List<BookVoiceRole>)? = null,
    private val commonRolesProvider: (() -> List<BookVoiceRole>)? = null,
    private val bookProvider: (() -> Book?)? = null,
    private val onCacheChanged: () -> Unit = {},
    private val synthesize: (suspend (String, File) -> Unit)? = null) : ReaderSpeechController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    override val playing = playingState.asStateFlow()
    override val error = errorState.asStateFlow()
    override val usesChapterSegments = true
    private var job: Job? = null
    private val speechClient = AiSpeechClient(client)
    private var player: MediaPlayer? = null
    private var audio: File? = null
    private var speed = 1f
    private var generation = 0
    private val pauseState = MutableStateFlow(false)
    private var paused: Boolean
        get() = pauseState.value
        set(value) { pauseState.value = value }
    private var prepared = false
    private var progressJob: Job? = null
    private var reportLocation: (() -> Unit)? = null
    private val cache = AiSpeechCache(context)
    // Start initialization before a request can fail, rather than when the replacement must speak.
    private val fallback = if (fallbackEnabled) fallbackFactory?.invoke() ?: ReaderSpeech(context) else null
    private var fallbackActive = false

    private suspend fun playFallback(chunk: AiSpeechChunk, token: Int, cause: Exception,
        onLocation: (ReaderSpeechSegment, Int) -> Unit) {
        val reader = fallback ?: throw cause
        try {
            pauseState.first { !it }
            currentCoroutineContext().ensureActive()
            fallbackActive = true
            val segment = chunk.segment
            reader.start(listOf(segment.copy(offset = segment.offset + chunk.start, text = chunk.text)), speed) { _, offset ->
                if (token == generation && !paused) onLocation(segment, offset)
            }
            val result = combine(reader.playing, reader.error, pauseState) { playing, error, paused -> Triple(playing, error, paused) }
                .first { (playing, error, paused) -> error != null || !paused && !playing }
            (result.second ?: reader.error.value)?.let { throw IOException("${cause.message}；系统语音替代失败：$it", cause) }
        } finally {
            if (token == generation) { fallbackActive = false; reader.stop() }
        }
    }

    override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
        stop()
        this.speed = speed.coerceIn(.5f, 2f)
        val token = generation
        playingState.value = true
        errorState.value = null
        job = scope.launch {
            var temporary: File? = null
            val downloads = mutableSetOf<File>()
            try {
                val config = if (configProvider != null || synthesize == null) withContext(Dispatchers.IO) {
                    requireNotNull(configProvider?.invoke() ?: AiSpeechConfigStore(context).read()) { "请先在设置中配置 AI 语音接口" }
                } else null
                require(source.any { it.text.isNotBlank() }) { "当前位置没有可朗读的文字" }
                val liveBook = bookProvider?.invoke() ?: if (bookId != null) withContext(Dispatchers.IO) {
                    LibraryRepository(context).load().books.find { it.id == bookId }
                } else null
                val roles = rolesProvider?.invoke() ?: liveBook?.voiceRoles.orEmpty()
                val commonRoles = commonRolesProvider?.invoke() ?: if (config?.rolePromptEnabled == true) withContext(Dispatchers.IO) {
                    LibraryRepository(context).load().commonVoiceRoles
                } else emptyList()
                val useRoles = config?.rolePromptEnabled == true
                val planningContext = currentCoroutineContext()
                val plans = if (useRoles) withContext(Dispatchers.IO) {
                    val planner = AiSpeechRolePlanner(bookId.orEmpty(), roles, commonRoles) { planningContext.ensureActive() }
                    var seed: String? = null
                    liveBook?.let { book ->
                        for (chapter in 0 until source.first().chapter.coerceAtMost(book.chapters.size)) {
                            seed = planner.seedRoleId(aiSpeechChapterSegments(book, chapter).toList(), seed)
                        }
                    }
                    planner.plan(source, seed).iterator()
                } else aiSpeechChunks(source).map { PlannedAiSpeechChunk(it, "", null) }.iterator()
                val preparedKeys = ConcurrentHashMap<AiSpeechChunk, String>()
                val extension = if (config?.protocol == SpeechApiProtocol.MIMO) "wav" else "mp3"
                suspend fun prepareChunk(planned: PlannedAiSpeechChunk): File {
                        currentCoroutineContext().ensureActive()
                        val chunk = planned.chunk
                        val roleHint = planned.roleInstructions
                        val cacheKey = config?.let { AiSpeechCache.key(it, chunk.text, roleHint) }
                        if (cacheKey != null) preparedKeys[chunk] = cacheKey
                        val cacheGeneration = bookId?.let(cache::generation)
                        val existing = if (bookId != null && cacheKey != null) withContext(Dispatchers.IO) { cache.find(bookId, cacheKey, extension) } else null
                        if (existing != null) return existing
                        val download = File(context.cacheDir, "speech-${UUID.randomUUID()}.$extension")
                        downloads += download
                        try {
                            if (synthesize != null) retryAiSpeechSynthesis { synthesize.invoke(chunk.text, download) }
                            else speechClient.synthesize(requireNotNull(config), chunk.text, download, roleHint)
                            currentCoroutineContext().ensureActive()
                            if (bookId != null && cacheKey != null && cacheGeneration != null) {
                                val saved = withContext(Dispatchers.IO) { cache.commit(bookId, cacheKey, extension, download, cacheGeneration) }
                                if (saved != null) { downloads -= download; return saved }
                            }
                            return download
                        } catch (error: Exception) {
                            download.delete(); downloads -= download
                            throw error
                        }
                }
                supervisorScope {
                    val upcoming = ArrayDeque<Pair<PlannedAiSpeechChunk, Deferred<File>>>()
                    val pendingByKey = mutableMapOf<String, Deferred<File>>()
                    suspend fun fill() {
                        pendingByKey.entries.removeAll { it.value.isCompleted }
                        while (upcoming.size < 2) {
                            val planned = if (useRoles) withContext(Dispatchers.IO) { if (plans.hasNext()) plans.next() else null }
                                else if (plans.hasNext()) plans.next() else null
                            if (planned == null) break
                            val key = if (bookId != null) config?.let { AiSpeechCache.key(it, planned.chunk.text, planned.roleInstructions) } else null
                            val pending = if (key == null) async(start = CoroutineStart.LAZY) { prepareChunk(planned) }
                                else pendingByKey.getOrPut(key) { async(start = CoroutineStart.LAZY) { prepareChunk(planned) } }
                            upcoming.addLast(planned to pending)
                        }
                    }
                    fill()
                    while (upcoming.isNotEmpty()) {
                        val (planned, pending) = upcoming.removeFirst()
                        val chunk = planned.chunk
                        var failure: Exception? = null
                        val playbackFile = try { pending.await() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                if (!fallbackEnabled) throw error
                                failure = error
                                null
                            }
                        val cacheKey = preparedKeys.remove(chunk) ?: config?.let { AiSpeechCache.key(it, chunk.text, planned.roleInstructions) }
                        fill()
                        upcoming.forEach { it.second.start() }
                        ensureActive()
                        val segment = chunk.segment
                        val text = chunk.text
                        val offset = chunk.start
                        val end = chunk.end
                        if (playbackFile == null) {
                            playFallback(chunk, token, requireNotNull(failure), onLocation)
                            onLocation(segment, segment.offset + end)
                            continue
                        }
                        temporary = playbackFile.takeIf { it in downloads }
                        audio = temporary
                        try { suspendCancellableCoroutine<Unit> { continuation ->
                            prepared = false
                            val current = MediaPlayer()
                            current.setAudioAttributes(readerAudioAttributes())
                            player = current
                            var lastReported = -1
                            val chunkOffset = offset
                            // Generic speech APIs return no word timings. Follow actual audio progress approximately.
                            val report = {
                                if (token == generation && player === current && prepared && !paused && current.isPlaying) {
                                    val duration = current.duration
                                    var relative = if (duration > 0) (current.currentPosition.toLong() * text.length / duration)
                                        .toInt().coerceIn(0, text.lastIndex) else 0
                                    if (relative > 0 && Character.isLowSurrogate(text[relative]) && Character.isHighSurrogate(text[relative - 1])) relative--
                                    if (relative > lastReported) {
                                        lastReported = relative
                                        onLocation(segment, segment.offset + chunkOffset + relative)
                                    }
                                }
                            }
                            reportLocation = report
                            current.setDataSource(playbackFile.absolutePath)
                            current.setOnPreparedListener {
                                if (token == generation && player === current && continuation.isActive) {
                                    prepared = true
                                    if (!paused) {
                                        it.playbackParams = PlaybackParams().setSpeed(this@AiReaderSpeech.speed)
                                        it.start(); report()
                                    }
                                    progressJob = scope.launch {
                                        while (isActive && token == generation && player === current) {
                                            delay(200)
                                            report()
                                        }
                                    }
                                }
                            }
                            current.setOnCompletionListener {
                                if (token == generation && player === current && continuation.isActive) continuation.resumeWith(Result.success(Unit))
                            }
                            current.setOnErrorListener { _, _, _ ->
                                if (token == generation && player === current && continuation.isActive) continuation.resumeWith(Result.failure(IOException("语音音频无法播放，请检查接口返回的格式")))
                                true
                            }
                            continuation.invokeOnCancellation { /* stop() releases the player on the main thread. */ }
                            current.prepareAsync()
                        } } catch (error: Exception) {
                            if (error !is CancellationException && bookId != null && cacheKey != null) withContext(Dispatchers.IO) {
                                cache.remove(bookId, cacheKey, extension)
                            }
                            throw error
                        }
                        progressJob?.cancel(); progressJob = null; reportLocation = null
                        player?.release(); player = null; prepared = false
                        temporary?.let { it.delete(); downloads -= it }; audio = null
                        temporary = null
                        onLocation(segment, segment.offset + end)
                        if (liveBook != null && config != null && upcoming.firstOrNull()?.first?.chunk?.segment?.chapter != segment.chapter) {
                            if (withContext(Dispatchers.IO) { cache.recordChapterCompletion(liveBook, config, segment.chapter, commonRoles) }) onCacheChanged()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if (token == generation) errorState.value = error.message ?: "AI 朗读失败" }
            finally {
                temporary?.delete()
                downloads.forEach { it.delete() }
                if (token == generation) {
                    playingState.value = false
                    progressJob?.cancel(); progressJob = null; reportLocation = null
                    player?.release(); player = null
                    audio?.delete(); audio = null
                }
            }
        }
    }
    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(.5f, 2f)
        player?.takeIf { it.isPlaying }?.let { it.playbackParams = PlaybackParams().setSpeed(this.speed) }
        fallback?.setSpeed(this.speed)
    }
    override fun clearError() { errorState.value = null }
    override fun pause(): Boolean {
        if (job?.isActive != true) return false
        reportLocation?.invoke()
        paused = true
        if (prepared) player?.pause()
        if (fallbackActive) fallback?.pause()
        playingState.value = false
        return true
    }
    override fun resume(): Boolean {
        if (!paused || job?.isActive != true) return false
        if (fallbackActive) fallback?.resume()
        paused = false
        if (prepared) player?.let { it.playbackParams = PlaybackParams().setSpeed(speed); it.start(); reportLocation?.invoke() }
        playingState.value = true
        return true
    }
    override fun stop() {
        generation++
        progressJob?.cancel(); progressJob = null; reportLocation = null
        job?.cancel(); job = null
        fallback?.stop(); fallbackActive = false
        player?.release(); player = null
        prepared = false; paused = false
        audio?.delete(); audio = null
        playingState.value = false
    }
    override fun release() { stop(); fallback?.release(); scope.cancel() }
}
