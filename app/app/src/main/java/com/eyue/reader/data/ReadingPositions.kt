package com.eyue.reader.data

/** Reading activity is persisted separately from the much larger book/catalog metadata. */
internal fun Book.withoutReadingPosition(): Book = copy(chapterIndex = 0, pageIndex = 0, characterOffset = 0,
    progress = 0f, lastReadAt = 0, positionImagePath = null)

internal fun LibraryState.withoutReadingPositions(): LibraryState = copy(
    books = books.map { it.withoutReadingPosition() }, readingSeconds = 0)
