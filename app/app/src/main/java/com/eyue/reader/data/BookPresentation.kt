package com.eyue.reader.data

val Book.displayAuthor: String get() = author.trim().ifEmpty { "佚名" }
