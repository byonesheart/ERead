package com.eyue.reader.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyEpubCompatibilityRobolectricTest {
    private lateinit var context: Context
    private lateinit var root: File
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "legacy-epub-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun previouslyImportedSpineIndexKeepsChapterIdsAndCharacterPositions() = runBlocking {
        val source = EpubFixtures.create(context, root, "old-index", oneSpine = true, footnotes = true)
        val html = ZipFile(source).use { zip -> zip.getInputStream(zip.getEntry("OPS/body.xhtml")).use { decodeEpubMarkup(it.readBytes()) } }
        val text = SourceHtmlEdit.plain(html) { "image" }
        val chapter = Chapter(id = "unchanged-chapter-id", title = "原目录", characterCount = text.length)
        val book = Book(id = UUID.randomUUID().toString(), title = "旧书库", format = BookFormat.EPUB,
            mode = LibraryMode.NOVEL, chapters = listOf(chapter), contentVersion = 8, sourcePath = source.absolutePath,
            characterOffset = text.indexOf("第二节"))
        val index = LegacyEpubIndex.file(context, book)
        index.parentFile!!.mkdirs()
        index.writeText(JSONObject().put("version", 1).put("contentVersion", 8)
            .put("spine", JSONArray().put(JSONObject().put("href", "OPS/body.xhtml").put("chapterIndex", 0).put("chapterId", chapter.id)))
            .put("manifest", JSONObject().put("readingOrder", JSONArray().put(JSONObject().put("href", "OPS/body.xhtml"))))
            .toString())
        try {
            // Re-parsing the TOC would split this one spine resource into two chapters.
            assertEquals(2, EpubArchive.read(source).sections.size)
            EbookSourceStore(context, book).use { store ->
                val document = store.load(0, text)
                assertEquals(text, document.canonicalText)
                assertTrue(document.html.contains("第二节"))
                assertTrue(document.noteTargets.values.any { it.contains("标准 EPUB 注释正文") })
                assertTrue(book.characterOffset > 0)
                assertEquals("unchanged-chapter-id", book.chapters.single().id)
                assertEquals("image/png", store.openResource(document.baseUrl.replace("body.xhtml", "image.png"))!!.let {
                    it.stream.close(); it.mimeType
                })
            }
        } finally {
            index.delete()
            index.parentFile!!.delete()
        }
    }

    @Test fun standardEpub2And3KeepCssImagesFontsAndUtf16Text() = runBlocking {
        for ((name, version, utf16) in listOf(Triple("epub2", "2.0", false), Triple("epub3", "3.0", false), Triple("utf16", "3.0", true))) {
            val source = EpubFixtures.create(context, root, name, version = version, utf16 = utf16, footnotes = true)
            val book = EpubTestImporter(context).import(source, File(root, name).apply { mkdirs() }, name, name)
            assertEquals(2, book.chapters.size)
            EbookSourceStore(context, book).use { store ->
                val document = store.load(0, readChapterText(book, 0))
                assertTrue(document.html.contains("正文甲"))
                assertTrue(document.html.contains("style.css"))
                assertTrue(document.noteTargets.isNotEmpty())
                for ((path, type) in listOf("style.css" to "text/css", "image.png" to "image/png", "font.otf" to "font/otf")) {
                    val resource = requireNotNull(store.openResource(document.baseUrl.replace("body.xhtml", path)))
                    assertEquals(type, resource.mimeType)
                    assertTrue(resource.stream.use { it.read() } >= 0)
                }
            }
        }
    }

    @Test fun publicStandardSamplesOpenWithTheLegacyImporter() = runBlocking {
        val configured = System.getenv("EREAD_EPUB_SAMPLES_DIR")
        assumeTrue("Set EREAD_EPUB_SAMPLES_DIR for public EPUB regression samples", !configured.isNullOrBlank())
        val sources = File(requireNotNull(configured)).listFiles()!!.filter { it.extension == "epub" }.sortedBy { it.name }
        assertTrue(sources.size >= 9)
        for (source in sources) {
            val id = UUID.randomUUID().toString()
            val book = EpubTestImporter(context).import(source, File(root, id).apply { mkdirs() }, id, source.nameWithoutExtension)
            assertTrue("Empty publication: ${source.name}", book.chapters.isNotEmpty())
            if (book.mode == LibraryMode.NOVEL) EbookSourceStore(context, book).use { store ->
                for (chapter in listOf(0, book.chapters.size / 2, book.chapters.lastIndex).distinct()) {
                    val text = readChapterText(book, chapter)
                    assertEquals(text, store.load(chapter, text).canonicalText)
                }
            }
            println("Legacy EPUB: ${source.name}, mode=${book.mode}, chapters=${book.chapters.size}")
        }
    }
}
