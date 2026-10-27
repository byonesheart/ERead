package com.eyue.reader.data

import android.os.Trace
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Timings contain stage names and counts only, never titles, source paths or book text. */
internal class ImportDiagnostics {
    private val started = System.nanoTime()
    private val stages = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()

    fun count(name: String, value: Long = 1) = synchronized(this) { counts[name] = (counts[name] ?: 0) + value }
    fun maximum(name: String, value: Long) = synchronized(this) { counts[name] = maxOf(counts[name] ?: 0, value) }
    fun elapsed(stage: String, from: Long) = synchronized(this) { stages[stage] = (stages[stage] ?: 0) + System.nanoTime() - from }

    fun <T> measure(stage: String, block: () -> T): T {
        val at = System.nanoTime()
        Trace.beginSection("ERead.import.$stage")
        try { return block() } finally {
            Trace.endSection()
            synchronized(this) { stages[stage] = (stages[stage] ?: 0) + System.nanoTime() - at }
        }
    }

    fun snapshot(format: String, status: String): JSONObject = synchronized(this) {
        JSONObject().put("format", format).put("status", status).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            .put("stageMs", JSONObject(stages.mapValues { it.value / 1_000_000.0 })).put("counts", JSONObject(counts))
    }

    fun save(cacheDir: File, format: String, status: String) {
        // Diagnostics must never turn a successful import into a failed book.
        runCatching {
            val directory = File(cacheDir, "import-diagnostics").apply { mkdirs() }
            File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json").writeText(snapshot(format, status).toString(2))
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        }
    }
}
