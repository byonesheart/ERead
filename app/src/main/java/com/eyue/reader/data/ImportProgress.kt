package com.eyue.reader.data

data class ImportProgress(
    val stage: String,
    val currentItem: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Float? = null
)

/** A temporary shelf item; parsed books are saved separately and revealed when the task finishes. */
data class PendingImport(
    val title: String,
    val mode: LibraryMode,
    val progress: ImportProgress? = null,
    val bookIds: Set<String> = emptySet()
)
