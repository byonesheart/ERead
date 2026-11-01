package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Prepared generations are durable, but an open reader keeps its original files until it closes. */
internal class LocalSourceReloads(private val context: Context) {
    private val repository = LibraryRepository(context)
    private fun root(id: String): File {
        val parent = File(context.filesDir, "books").canonicalFile
        return File(parent, id).canonicalFile.also { require(it.parentFile == parent) }
    }
    private fun manifest(id: String) = AtomicFile(File(root(id), "source-reload.json"))
    fun hasPending(book: Book): Boolean {
        val file = manifest(book.id)
        return file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    }
    fun pending(book: Book): Book? {
        val file = manifest(book.id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val row = JSONObject(file.readFully().toString(Charsets.UTF_8))
        if (row.getInt("baseVersion") != book.contentVersion) return null
        return repository.readBook(row.getJSONObject("book"))
    }
    suspend fun prepare(book: Book, rule: String): Book {
        val source = book.sourceUri ?: book.sourcePath ?: error("书籍没有外部源文件")
        val imported = DocumentImporter(context).importFiles(listOf(documentFile(source)?.let(Uri::fromFile) ?: Uri.parse(source)), book.mode, rule,
            forceCopy = true, reloadBook = book).single()
        val prepared = book.copy(format = imported.format, mode = imported.mode, author = imported.author,
            coverPath = imported.coverPath, chapters = imported.chapters, sourcePath = imported.sourcePath,
            sourceUri = source, sourceLinked = imported.sourceLinked, sourceFileName = imported.sourceFileName,
            sourceFileBytes = imported.sourceFileBytes, publicationContents = imported.publicationContents,
            contentVersion = maxOf(book.contentVersion + 1, imported.contentVersion), hasTextEdits = false)
        val file = manifest(book.id)
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("baseVersion", book.contentVersion).put("source", source)
                .put("book", repository.writeBook(prepared)).toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) { file.failWrite(output); throw failure }
        // Compare future scans against the copied generation, never against a source changed mid-import.
        if (prepared.format != BookFormat.IMAGES && prepared.sourcePath != null)
            LocalSourceChanges(context).accepted(prepared.copy(sourceUri = prepared.sourcePath))
        else LocalSourceChanges(context).accepted(prepared)
        return prepared
    }
    fun merge(current: Book, prepared: Book): Book {
        val old = current.chapters.getOrNull(current.chapterIndex)
        val chapter = prepared.chapters.indexOfFirst { it.title == old?.title }.takeIf { it >= 0 }
            ?: current.chapterIndex.coerceIn(0, prepared.chapters.lastIndex.coerceAtLeast(0))
        val length = prepared.chapters.getOrNull(chapter)?.characterCount?.takeIf { it >= 0 } ?: Int.MAX_VALUE
        return prepared.copy(title = current.title, sourceUri = current.sourceUri ?: prepared.sourceUri,
            sourceFileName = if (current.sourceUri != prepared.sourceUri) current.sourceFileName ?: prepared.sourceFileName else prepared.sourceFileName, groupId = current.groupId, voiceRoles = current.voiceRoles,
            cloudEnabled = current.cloudEnabled, cloudBackupAt = current.cloudBackupAt, addedAt = current.addedAt,
            chapterIndex = chapter, characterOffset = current.characterOffset.coerceAtMost(length), pageIndex = 0,
            progress = current.progress, lastReadAt = current.lastReadAt, positionImagePath = null)
    }
    fun annotation(original: Book, updated: Book, record: Annotation): Annotation {
        val oldTitle = original.chapters.getOrNull(record.chapterIndex)?.title
        val chapter = updated.chapters.indexOfFirst { it.title == oldTitle }.takeIf { it >= 0 }
            ?: record.chapterIndex.coerceIn(0, updated.chapters.lastIndex.coerceAtLeast(0))
        val target = updated.chapters.getOrNull(chapter) ?: return record
        if (updated.mode != LibraryMode.NOVEL) return record.copy(chapterIndex = chapter,
            pageIndex = record.pageIndex.coerceAtMost(target.pages.lastIndex.coerceAtLeast(0)))
        val text = readChapterText(target)
        val offset = record.quote.takeIf { it.isNotBlank() }?.let { text.indexOf(it).takeIf { found -> found >= 0 } }
            ?: record.characterOffset.coerceIn(0, text.length)
        return record.copy(chapterIndex = chapter, characterOffset = offset, pageIndex = 0,
            endOffset = (offset + (record.endOffset - record.characterOffset).coerceAtLeast(0)).coerceAtMost(text.length))
    }
    val recoveryIssues = mutableMapOf<String, String>()
    fun recover(state: LibraryState): LibraryState {
        var records = state.annotations
        val books = state.books.map { book ->
            try {
                val prepared = pending(book) ?: return@map book
                val merged = merge(book, prepared)
                val mapped = records.map { if (it.bookId == book.id) annotation(book, merged, it) else it }
                records = mapped
                merged
            } catch (failure: Exception) {
                recoveryIssues[book.id] = failure.message ?: "已准备的同步缓存无法读取，已保留原书"
                book
            }
        }
        return state.copy(books = books, annotations = records)
    }
    /** Call only after the new library snapshot is durable and no reader uses the old generation. */
    fun finish(book: Book, records: List<Annotation> = emptyList()) {
        val staged = runCatching { pending(book) }
        if (staged.isFailure || staged.getOrNull() != null) return
        manifest(book.id).delete()
        val owned = root(book.id)
        val retained = records.filter { it.bookId == book.id }.mapNotNull { it.imagePath } + listOfNotNull(book.sourceUri, book.sourcePath, book.coverPath) + book.chapters.flatMap {
            listOfNotNull(it.textPath, it.lazySourcePath) + it.inlineImages.map { image -> image.path } + it.pages.map { page -> page.path }
        }
        val hasGeneration = retained.any { path -> documentFile(path)?.canonicalFile?.toPath()?.let { it.startsWith(owned.toPath()) &&
            owned.toPath().relativize(it).firstOrNull()?.toString()?.startsWith("source-") == true } == true }
        owned.listFiles()?.filter { hasGeneration || it.isDirectory && it.name.startsWith("source-") }?.forEach { file ->
            if (retained.none { path -> documentFile(path)?.canonicalFile?.toPath()?.startsWith(file.canonicalFile.toPath()) == true }) {
                check(file.canonicalFile.parentFile == owned)
                file.deleteRecursively()
            }
        }
    }
}
