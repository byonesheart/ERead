package com.eyue.reader.data.cloud

import android.content.Context
import android.util.AtomicFile
import com.eyue.reader.data.Book
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.HtmlChapterParser
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.PreparedChapter
import com.eyue.reader.data.loadChapterContent
import com.eyue.reader.data.mapCanonicalFootnotes
import com.eyue.reader.data.mapCanonicalStyles
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Cloud materialization can reuse prepared metadata without loading immutable chapter strings. */
internal suspend fun materializeCloudBookCached(context: Context, book: Book, cache: CloudResourceHashCache,
    check: () -> Unit = {}, load: suspend (Int) -> PreparedChapter = { loadChapterContent(context, book, it) }): Book {
    val repository = LibraryRepository(context)
    return book.copy(chapters = book.chapters.mapIndexed { index, chapter ->
        val source = chapter.lazySourcePath ?: return@mapIndexed chapter
        check()
        val structure = CloudArchive.hex(MessageDigest.getInstance("SHA-256")
            .digest("cloud-prepared-v1:${book.contentVersion}\n${repository.writeChapter(chapter)}".toByteArray()))
        cache.preparedChapter(chapter.id, structure, repository, check)?.let { return@mapIndexed it }

        val sourceBefore = cache.snapshot(listOf(source))?.singleOrNull()
        val manifest = runCatching { JSONObject(AtomicFile(File(source)).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        val cssPath = manifest?.optString("cssPath")?.takeIf { it.isNotBlank() }
        val inputs = listOfNotNull(chapter.textPath, source, cssPath)
        val before = cache.snapshot(inputs)?.takeIf { versions ->
            chapter.textPath != null && manifest != null && sourceBefore != null && sourceBefore in versions
        }
        val loaded = load(index)
        check()
        // The reader's existing prepared file is keyed by text hash only. Reproject on a cache miss
        // so an external source/CSS edit is also reflected in the cloud metadata.
        val result = if (before != null && manifest != null) projectCloudChapter(loaded.chapter, loaded.text, manifest, check)
            else loaded.chapter
        val portable = result.copy(lazySourcePath = null)
        val versions = cache.snapshot(inputs + "$source.prepared.json")
        if (before != null && versions != null && versions.dropLast(1) == before) {
            check()
            cache.rememberPreparedChapter(chapter.id, structure, versions, portable)
        }
        portable
    })
}

private fun projectCloudChapter(chapter: Chapter, text: String, manifest: JSONObject, check: () -> Unit): Chapter {
    val images = manifest.getJSONObject("images")
    val css = if (manifest.has("cssPath")) File(manifest.getString("cssPath")).readText() else manifest.optString("css")
    val targets = manifest.optJSONObject("noteTargets")
    val noteTargets = targets?.keys()?.asSequence()?.associateWith { targets.getString(it) }.orEmpty()
    val styled = HtmlChapterParser.readWithNotes(chapter.title, manifest.getString("html"), css,
        checkActive = check, noteTargets = noteTargets) { attributes -> images.optString(attributes).takeIf { it.isNotEmpty() } }
    check()
    return chapter.copy(characterCount = text.length, sourceStyles = mapCanonicalStyles(styled.text, text, styled.sourceStyles),
        sourceCssStyles = mapCanonicalStyles(styled.text, text, styled.sourceCssStyles),
        sourceFootnotes = mapCanonicalFootnotes(styled.text, text, styled.sourceFootnotes))
}
