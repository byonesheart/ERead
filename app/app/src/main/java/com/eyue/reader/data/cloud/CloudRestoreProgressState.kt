package com.eyue.reader.data.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Late network callbacks cannot revive a completed or superseded restore operation. */
internal class CloudRestoreProgressState {
    var progress by mutableStateOf<CloudOperationProgress?>(null)
        private set
    private var generation = 0

    fun begin(stage: String): Int {
        generation++
        progress = CloudOperationProgress(stage)
        return generation
    }

    fun update(token: Int, value: CloudOperationProgress) {
        if (generation == token && progress != null) progress = value
    }

    fun finish(token: Int): Boolean {
        if (generation != token) return false
        progress = null
        return true
    }
}
