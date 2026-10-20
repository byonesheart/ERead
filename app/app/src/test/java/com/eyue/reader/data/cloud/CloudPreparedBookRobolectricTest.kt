package com.eyue.reader.data.cloud

import android.content.Context
import android.content.ContextWrapper
import com.eyue.reader.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudPreparedBookRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var cacheFile: File
    private val revisions = mutableMapOf<String, Long>()
    private var reliable = true

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "cloud-prepared-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        cacheFile = File(root, "hashes.json")
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun metadataOnlyMaterializationAcrossCacheReconstructionDoesNotReadAnyChapterAgain() = runBlocking {
        val book = book()
        var reads = 0
        val firstCache = cache()
        val original = materializeCloudBookCached(context, book, firstCache, load = { index ->
            reads++; loadChapterContent(context, book, index)
        })
        firstCache.save()
        assertEquals(book.chapters.size, reads)
        assertTrue(original.chapters.all { it.lazySourcePath == null })
        assertFalse("The cache stores chapter metadata rather than canonical body strings", cacheFile.readText().contains("CANONICAL_BODY_0"))
        val moved = book.copy(chapterIndex = 1, pageIndex = 7, characterOffset = 800, progress = .8f, lastReadAt = 99)
        val next = cache()
        val repeated = materializeCloudBookCached(context, moved, next, load = { error("Metadata-only materialization must not read chapter $it") })
        next.save()
        assertEquals(original.chapters, repeated.chapters)
        assertEquals(moved.characterOffset, repeated.characterOffset)
        assertEquals(book.chapters.size, reads)
        val changed = moved.copy(contentVersion = moved.contentVersion + 1)
        materializeCloudBookCached(context, changed, cache(), load = { index -> reads++; loadChapterContent(context, changed, index) })
        assertEquals("A real book content revision must invalidate the prepared metadata", book.chapters.size * 2, reads)
    }

    @Test fun sourceCssAndPreparedFileChangesInvalidateTheRelevantPreparedMetadata() = runBlocking {
        val book = book()
        var reads = 0
        suspend fun materialize(): Book {
            val cache = cache()
            val result = materializeCloudBookCached(context, book, cache, load = { index ->
                reads++; loadChapterContent(context, book, index)
            })
            cache.save()
            return result
        }
        val original = materialize()
        val source = File(requireNotNull(book.chapters.first().lazySourcePath))
        val manifest = JSONObject(source.readText())
        source.writeText(manifest.put("html", manifest.getString("html").replace("CANONICAL_BODY_0", "<strong>CANONICAL_BODY_0</strong>")).toString())
        changed(source)
        val sourceChanged = materialize()
        assertEquals("Only the changed chapter's source requires a text read", 3, reads)
        assertFalse(original.chapters.first().sourceStyles.any { it.bold == true })
        assertTrue("A text-hash-only prepared file must not conceal a changed source projection",
            sourceChanged.chapters.first().sourceStyles.any { it.bold == true })

        val css = File(manifest.getString("cssPath"))
        css.writeText("p { font-style:italic; }")
        changed(css)
        val cssChanged = materialize()
        assertEquals("Both chapters refer to the changed shared CSS file", 5, reads)
        assertTrue("The changed CSS projection must be reflected in both chapters",
            cssChanged.chapters.all { chapter -> chapter.sourceCssStyles.any { it.italic == true } })
        val prepared = File("${source.path}.prepared.json")
        prepared.writeText(prepared.readText())
        changed(prepared)
        materialize()
        assertEquals("The prepared metadata file itself is also part of the reliable version", 6, reads)
    }

    @Test fun inputsChangedDuringPreparationAndUnknownVersionsCannotPopulateTheFastPath() = runBlocking {
        val book = book().let { it.copy(chapters = it.chapters.take(1)) }
        val text = File(requireNotNull(book.chapters.first().textPath))
        var reads = 0
        val first = cache()
        materializeCloudBookCached(context, book, first, load = { index ->
            reads++
            loadChapterContent(context, book, index).also { changed(text) }
        })
        first.save()
        val second = cache()
        materializeCloudBookCached(context, book, second, load = { index -> reads++; loadChapterContent(context, book, index) })
        assertEquals("A change observed during the first read must force the next read", 2, reads)
        reliable = false
        repeat(2) {
            val unknown = cache()
            materializeCloudBookCached(context, book, unknown, load = { index -> reads++; loadChapterContent(context, book, index) })
            unknown.save()
        }
        assertEquals("Unknown versions must always fall back to reading chapter content", 4, reads)
    }

    private fun cache() = CloudResourceHashCache(cacheFile) { path ->
        val file = File(path).canonicalFile
        if (!reliable || !file.isFile) null else CloudFileVersion(file.path, 1, (file.path.hashCode().toLong() and 0x7fffffff) + 1,
            file.length(), 100, 1, 100, revisions[file.path] ?: 1)
    }
    private fun changed(file: File) { val path = file.canonicalPath; revisions[path] = (revisions[path] ?: 1) + 1 }
    private fun book(): Book {
        val directory = File(root, "chapters").apply { mkdirs() }
        val chapters = (0 until 2).map { index ->
            val html = "<body>" + (0 until 40).joinToString("") { "<p>CANONICAL_BODY_$index 正文和河岸边的灯光。</p>" } + "</body>"
            val parsed = HtmlChapterParser.readPlain("第${index + 1}章", html) { null }
            saveLazyChapter(directory, index, parsed, html, "p { font-size:100%; }", emptyMap())
        }
        return Book(id = "cloud-prepared-book", title = "按需云元数据", format = BookFormat.MOBI, mode = LibraryMode.NOVEL, chapters = chapters)
    }
}
