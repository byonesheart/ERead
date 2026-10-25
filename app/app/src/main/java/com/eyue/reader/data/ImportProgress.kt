package com.eyue.reader.data

data class ImportProgress(
    val stage: String,
    val currentItem: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Float? = null
)

/** One queued batch. Completed books leave this placeholder and are immediately available on the shelf. */
data class PendingImport(
    val title: String,
    val mode: LibraryMode,
    val progress: ImportProgress? = null,
    val bookIds: Set<String> = emptySet(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val queued: Boolean = false,
    val completed: Int = 0,
    val total: Int = 1,
    val external: Boolean = false
)
