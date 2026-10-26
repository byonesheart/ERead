package com.eyue.reader.data

import android.content.Context
import java.io.File

/** Exercises the production EPUB importer with local fixtures, without a DocumentsProvider. */
internal class EpubTestImporter(private val context: Context) {
    fun import(source: File, directory: File, id: String, title: String): Book {
        val method = DocumentImporter::class.java.getDeclaredMethod("importEpub", File::class.java,
            File::class.java, String::class.java, String::class.java, LibraryMode::class.java).apply { isAccessible = true }
        return method.invoke(DocumentImporter(context), source, directory, id, title, LibraryMode.NOVEL) as Book
    }
}
