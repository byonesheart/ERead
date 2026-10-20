package com.eyue.reader.ui

import com.eyue.reader.data.AiSpeechRolePlanner
import com.eyue.reader.data.PlannedAiSpeechChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AiSpeechChapterChunk(val planned: PlannedAiSpeechChunk, val seedRoleId: String?, val chapterEnd: Boolean)

/** Holds a single chapter plan; the audio queue bounds how far this cursor can read ahead. */
internal class AiSpeechChapterCursor(
    source: ReaderSpeechSource,
    private val planner: AiSpeechRolePlanner?,
    initialSeed: String? = null,
    private val onChapterPlanned: (Int, String?, String?, Boolean) -> Unit = { _, _, _, _ -> }
) {
    private val chapterCount = source.chapterCount
    private val loadChapter = source.loadChapter
    private var chapter = source.firstChapter
    private var seed = initialSeed
    private var plans = plan(source.initial)
    private var chapterRecorded = false
    private var emptyChapter = source.initial.firstOrNull()?.let { it.page == 0 && (it.offset == 0 || it.roleContext != null) } == true &&
        source.initial.all { (it.roleContext ?: it.text).isBlank() }

    private fun plan(segments: List<ReaderSpeechSegment>) = (planner?.plan(segments, seed)
        ?: aiSpeechChunks(segments).map { PlannedAiSpeechChunk(it, "", null) }).iterator()

    suspend fun next(): AiSpeechChapterChunk? {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (plans.hasNext()) {
                val planned = plans.next()
                val end = !plans.hasNext()
                if (end) recordChapter()
                return AiSpeechChapterChunk(planned, seed, end)
            }
            recordChapter()
            if (++chapter >= chapterCount) return null
            seed = planner?.lastDialogueRoleId
            val segments = loadChapter(chapter)
            emptyChapter = segments.all { (it.roleContext ?: it.text).isBlank() }
            plans = plan(segments)
            chapterRecorded = false
        }
    }

    private fun recordChapter() {
        if (!chapterRecorded) {
            onChapterPlanned(chapter, seed, planner?.lastDialogueRoleId, emptyChapter)
            chapterRecorded = true
        }
    }
}
