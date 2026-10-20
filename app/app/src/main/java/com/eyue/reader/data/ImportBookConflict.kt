package com.eyue.reader.data

data class ImportBookConflict(val incoming: Book, val existing: List<Book>)

internal fun importBookCandidates(books: List<Book>, incoming: Book): List<Book> = books.filter { book ->
    book.id != incoming.id && (book.mode == incoming.mode && book.title.trim().equals(incoming.title.trim(), ignoreCase = true) ||
        !book.sourceFileName.isNullOrBlank() && book.sourceFileName == incoming.sourceFileName)
}
