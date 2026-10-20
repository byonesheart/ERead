package com.eyue.reader.data

/** [start, end) identifies the source's reference marker in the chapter's UTF-16 text. */
data class SourceFootnote(val start: Int, val end: Int, val label: String, val content: String,
    val imagePath: String? = null)
