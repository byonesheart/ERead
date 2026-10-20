package com.eyue.reader.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal data class ReaderSpeechSegment(
    val chapter: Int,
    val page: Int,
    val offset: Int,
    val text: String,
    val roleContext: String? = null
)

/** The current chapter is ready; subsequent chapters are loaded only as playback reaches them. */
internal class ReaderSpeechSource(
    val firstChapter: Int,
    val chapterCount: Int,
    val initial: List<ReaderSpeechSegment>,
    val loadChapter: suspend (Int) -> List<ReaderSpeechSegment>
) {
    companion object {
        fun fromSegments(segments: List<ReaderSpeechSegment>): ReaderSpeechSource {
            val first = segments.firstOrNull()?.chapter ?: 0
            return ReaderSpeechSource(first, (segments.maxOfOrNull { it.chapter } ?: first) + 1,
                segments.filter { it.chapter == first }) { chapter -> segments.filter { it.chapter == chapter } }
        }
    }
}

internal interface ReaderSpeechController {
    val playing: StateFlow<Boolean>
    val error: StateFlow<String?>
    // AI cache chunks must stay independent of font-dependent page boundaries.
    val usesChapterSegments: Boolean get() = false
    fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit)
    fun start(source: ReaderSpeechSource, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) =
        start(source.initial, speed, onLocation)
    fun setSpeed(speed: Float)
    fun clearError()
    fun pause(): Boolean = false
    fun resume(): Boolean = false
    fun stop()
    fun release()
}

internal val LocalReaderSpeechFactory = staticCompositionLocalOf<(Context) -> ReaderSpeechController> { { ReaderSpeech(it) } }

/** Keeps one utterance in the engine at a time so long books do not fill its queue. */
internal class ReaderSpeech(context: Context) : ReaderSpeechController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadChapter: (suspend (Int) -> List<ReaderSpeechSegment>)? = null
    private var chapterCount = 0
    private var nextChapter = 0
    private var chapterLoad: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val playingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)
    override val playing = playingState.asStateFlow()
    override val error = errorState.asStateFlow()
    private var ready = false
    private var initializationError: String? = null
    private var pendingStart: (() -> Unit)? = null
    private var disposed = false
    private var paused = false
    private var generation = 0
    private var segments = emptyList<ReaderSpeechSegment>()
    private var segmentIndex = 0
    private var segmentOffset = 0
    private var pendingEnd = 0
    private var spokenOffset = 0
    private var speed = 1f
    private var location: ((ReaderSpeechSegment, Int) -> Unit)? = null
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                if (!disposed) {
                    if (status == TextToSpeech.SUCCESS) {
                        val language = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        ready = language != TextToSpeech.LANG_MISSING_DATA &&
                            language != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!ready) initializationError = "系统缺少中文语音，请先在系统文字转语音设置中安装。"
                    } else {
                        initializationError = "系统朗读引擎不可用，请检查系统文字转语音设置。"
                    }
                    pendingStart?.let { start ->
                        if (!ready) {
                            pendingStart = null
                            errorState.value = initializationError
                            playingState.value = false
                        } else if (!paused) {
                            pendingStart = null
                            start()
                        }
                    }
                }
            }
        }
        engine?.setAudioAttributes(readerAudioAttributes())
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                handler.post {
                    if (utteranceId == "$generation:$segmentIndex:$segmentOffset" && playingState.value) {
                        segments.getOrNull(segmentIndex)?.let { segment ->
                            val relative = start.coerceIn(0, pendingEnd - segmentOffset)
                            spokenOffset = segmentOffset + relative
                            location?.invoke(segment, segment.offset + segmentOffset + relative)
                        }
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (utteranceId == "$generation:$segmentIndex:$segmentOffset" && playingState.value) {
                        segments.getOrNull(segmentIndex)?.let { segment ->
                            location?.invoke(segment, segment.offset + pendingEnd)
                        }
                        segmentOffset = pendingEnd
                        speakNext()
                    }
                }
            }
            @Deprecated("Required by Android")
            override fun onError(utteranceId: String?) {
                handler.post {
                    if (utteranceId == "$generation:$segmentIndex:$segmentOffset" && playingState.value) {
                        errorState.value = "朗读失败，请检查系统语音是否安装完整。"
                        playingState.value = false
                    }
                }
            }
        })
    }

    override fun start(
        source: List<ReaderSpeechSegment>,
        speed: Float,
        onLocation: (ReaderSpeechSegment, Int) -> Unit
    ) = begin(source, null, speed, onLocation)

    override fun start(source: ReaderSpeechSource, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) =
        begin(source.initial, source, speed, onLocation)

    private fun begin(source: List<ReaderSpeechSegment>, chapters: ReaderSpeechSource?, speed: Float,
        onLocation: (ReaderSpeechSegment, Int) -> Unit) {
        stop()
        if (!ready) {
            if (initializationError != null) { errorState.value = initializationError; return }
            setSpeed(speed)
            errorState.value = null
            pendingStart = { begin(source, chapters, this.speed, onLocation) }
            playingState.value = true
            return
        }
        segments = source.filter { it.text.isNotBlank() }
        loadChapter = chapters?.loadChapter
        chapterCount = chapters?.chapterCount ?: 0
        nextChapter = (chapters?.firstChapter ?: 0) + 1
        if (segments.isEmpty() && (chapters == null || nextChapter >= chapters.chapterCount)) {
            errorState.value = "这一页没有可朗读的文字。漫画导入时不提取文字；图片和扫描 PDF 需先识别文字。"
            return
        }
        errorState.value = null
        location = onLocation
        segmentIndex = 0
        segmentOffset = 0
        setSpeed(speed)
        playingState.value = true
        speakNext()
    }

    private fun speakNext() {
        while (segmentIndex < segments.size && segmentOffset >= segments[segmentIndex].text.length) {
            segmentIndex++
            segmentOffset = 0
        }
        if (segmentIndex >= segments.size) {
            val load = loadChapter
            if (load != null && nextChapter < chapterCount) {
                if (chapterLoad?.isActive == true) return
                chapterLoad = scope.launch {
                    try {
                        do {
                            val chapter = nextChapter++
                            segments = withContext(Dispatchers.IO) { load(chapter) }
                                .filter { it.text.isNotBlank() }
                        } while (segments.isEmpty() && nextChapter < chapterCount)
                        generation++
                        segmentIndex = 0; segmentOffset = 0; spokenOffset = 0
                        chapterLoad = null
                        if (!paused) speakNext()
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (error: Exception) {
                        errorState.value = error.message ?: "无法读取下一章正文"
                        playingState.value = false
                    }
                }
                return
            }
            loadChapter = null
            segments = emptyList()
            playingState.value = false
            return
        }
        val segment = segments[segmentIndex]
        // Engines without range timing still advance the reader at these bounded sentence chunks.
        val limit = minOf(TextToSpeech.getMaxSpeechInputLength() - 1, 240).coerceAtLeast(1)
        var end = minOf(segment.text.length, segmentOffset + limit)
        val paragraphEnd = segment.text.indexOf('\n', segmentOffset)
        if (paragraphEnd in segmentOffset until end) end = paragraphEnd + 1
        else if (end < segment.text.length) {
            var breakAt = end - 1
            while (breakAt > segmentOffset + limit / 2 && segment.text[breakAt] !in "。！？\n.!?") breakAt--
            if (breakAt > segmentOffset + limit / 2) end = breakAt + 1
            if (end > segmentOffset && Character.isHighSurrogate(segment.text[end - 1])) end--
        }
        pendingEnd = end
        spokenOffset = segmentOffset
        location?.invoke(segment, segment.offset + segmentOffset)
        val result = engine?.speak(
            segment.text.substring(segmentOffset, end), TextToSpeech.QUEUE_FLUSH, null,
            "$generation:$segmentIndex:$segmentOffset"
        )
        if (result == TextToSpeech.ERROR) {
            errorState.value = "系统语音无法开始朗读。"
            playingState.value = false
        }
    }

    override fun clearError() { errorState.value = null }

    override fun pause(): Boolean {
        if (paused) return true
        if (!playingState.value) return false
        if (pendingStart != null) {
            paused = true
            playingState.value = false
            return true
        }
        generation++
        paused = true
        playingState.value = false
        engine?.stop()
        segmentOffset = spokenOffset
        val text = segments.getOrNull(segmentIndex)?.text.orEmpty()
        if (segmentOffset in 1 until text.length && Character.isLowSurrogate(text[segmentOffset]) &&
            Character.isHighSurrogate(text[segmentOffset - 1])) segmentOffset--
        return true
    }

    override fun resume(): Boolean {
        if (!paused || disposed) return false
        paused = false
        playingState.value = true
        pendingStart?.let { start ->
            if (ready) { pendingStart = null; start() }
            return playingState.value
        }
        speakNext()
        return playingState.value
    }

    override fun setSpeed(speed: Float) {
        val rate = speed.coerceIn(0.5f, 2f)
        val changed = this.speed != rate
        this.speed = rate
        engine?.setSpeechRate(rate)
        if (changed && playingState.value && pendingStart == null) {
            generation++
            engine?.stop()
            segmentOffset = spokenOffset
            val text = segments.getOrNull(segmentIndex)?.text.orEmpty()
            if (segmentOffset in 1 until text.length && Character.isLowSurrogate(text[segmentOffset]) &&
                Character.isHighSurrogate(text[segmentOffset - 1])) segmentOffset--
            speakNext()
        }
    }

    override fun stop() {
        generation++
        paused = false
        pendingStart = null
        playingState.value = false
        engine?.stop()
        chapterLoad?.cancel(); chapterLoad = null; loadChapter = null
        segments = emptyList()
        location = null
    }

    override fun release() {
        disposed = true
        stop()
        engine?.shutdown()
        engine = null
        scope.cancel()
    }
}
