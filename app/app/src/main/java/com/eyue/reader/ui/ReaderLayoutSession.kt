package com.eyue.reader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class ReaderLayoutPhase { UNPREPARED, PREPARING, RESTORING, READY, FAILED }

private data class ReaderLayoutSnapshot(
    val generation: Int = 0,
    val key: List<Any?>? = null,
    val restoreRequest: Int = 0,
    val preparedGeneration: Int = -1,
    val restoredGeneration: Int = -1,
    val phase: ReaderLayoutPhase = ReaderLayoutPhase.UNPREPARED,
    val progress: Float = 0f,
    val error: String? = null
)

/** Preparation and viewport restoration must both finish for the same layout/request. */
internal class ReaderLayoutSession {
    private var state by mutableStateOf(ReaderLayoutSnapshot())

    val generation get() = state.generation
    val key get() = state.key
    val restoreRequest get() = state.restoreRequest
    val preparing get() = state.phase == ReaderLayoutPhase.PREPARING
    val restoring get() = state.phase != ReaderLayoutPhase.READY
    val preparedGeneration get() = state.preparedGeneration
    val restoredGeneration get() = state.restoredGeneration
    val phase get() = state.phase
    val progress get() = state.progress
    val error get() = state.error

    fun begin(key: List<Any?>, prepared: Boolean = false) {
        val generation = state.generation + 1
        state = ReaderLayoutSnapshot(generation = generation, key = key.toList(),
            restoreRequest = state.restoreRequest + 1, preparedGeneration = if (prepared) generation else -1,
            phase = if (prepared) ReaderLayoutPhase.RESTORING else ReaderLayoutPhase.PREPARING)
    }

    fun requestRestore(needsPreparation: Boolean = false) {
        val current = state
        if (needsPreparation && current.phase == ReaderLayoutPhase.FAILED && current.key != null) {
            begin(current.key)
            return
        }
        state = state.copy(restoreRequest = state.restoreRequest + 1,
            preparedGeneration = if (needsPreparation) -1 else state.preparedGeneration, restoredGeneration = -1,
            phase = if (needsPreparation) ReaderLayoutPhase.PREPARING else ReaderLayoutPhase.RESTORING,
            progress = 0f, error = null)
    }

    fun prepare(ready: Boolean) {
        val remainsReady = ready && state.phase == ReaderLayoutPhase.READY
        state = state.copy(preparedGeneration = if (ready) state.generation else -1,
            restoredGeneration = if (remainsReady) state.restoredGeneration else -1,
            phase = when {
                !ready -> ReaderLayoutPhase.PREPARING
                remainsReady -> ReaderLayoutPhase.READY
                else -> ReaderLayoutPhase.RESTORING
            }, progress = 0f, error = null)
    }

    fun prepared(generation: Int) {
        if (generation != state.generation || state.key == null || state.phase == ReaderLayoutPhase.FAILED) return
        state = state.copy(preparedGeneration = generation,
            phase = if (state.phase == ReaderLayoutPhase.READY) ReaderLayoutPhase.READY else ReaderLayoutPhase.RESTORING,
            error = null)
    }

    fun restored(generation: Int, request: Int) {
        if (generation != state.generation || request != state.restoreRequest || state.key == null ||
            state.phase != ReaderLayoutPhase.RESTORING || state.preparedGeneration != generation || state.error != null) return
        state = state.copy(restoredGeneration = generation, phase = ReaderLayoutPhase.READY)
    }

    fun failed(generation: Int, message: String) {
        if (generation != state.generation) return
        state = state.copy(preparedGeneration = -1, restoredGeneration = -1, phase = ReaderLayoutPhase.FAILED, error = message)
    }

    fun reportProgress(generation: Int, fraction: Float) {
        if (generation != state.generation) return
        state = state.copy(progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f)
    }
}
