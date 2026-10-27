package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ImportPipelineRobolectricTest {
    @Test fun readingLimitsTheImportQueueAndCompletionIncludesAllTextMarkupAndNotes() = runBlocking {
        withContext { context, root ->
            val source = epub(root, 24)
            val reader = ImportWorkPriority.readerSession()
            val book = try { DocumentImporter(context).importFiles(listOf(sourceUri(context, source)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single() }
                finally { reader.close() }
            assertEquals(24, book.chapters.size)
            assertEquals(source.readBytes().toList(), File(book.sourcePath!!).readBytes().toList())
            assertFalse(book.chapters.any { DeferredEbookContent.pending(it) })
            book.chapters.forEachIndexed { index, chapter ->
                assertTrue(File(chapter.textPath!!).readText().contains("正文$index🙂"))
                val manifest = JSONObject(File(chapter.lazySourcePath!!).readText())
                assertTrue(manifest.getString("html").contains("<p>"))
                assertTrue(File(manifest.getString("cssPath")).isFile)
                assertEquals(1, chapter.sourceFootnotes.size)
                assertEquals("注释$index", chapter.sourceFootnotes.single().content)
            }
            val report = File(context.cacheDir, "import-diagnostics").listFiles()!!.single().readText()
            val json = JSONObject(report)
            assertEquals("complete", json.getString("status"))
            assertEquals(1, json.getJSONObject("counts").getInt("pendingChapters"))
            assertEquals(24, json.getJSONObject("counts").getInt("chapters"))
            assertTrue(json.getJSONObject("counts").getInt("cachedSourceDocuments") <= 2)
            assertFalse(report.contains("private-fixture"))
            assertFalse(report.contains(root.path))
            assertFalse(report.contains("正文"))
            assertEquals(1, File(book.sourcePath!!).parentFile!!.listFiles()!!.count { it.name.startsWith("styles-") })
        }
    }

    @Test fun failedImportLeavesDiagnosticsButNoPublishedPartialBook() = runBlocking {
        withContext { context, root ->
            val source = File(root, "invalid.epub").apply { writeText("broken zip") }
            try { DocumentImporter(context).importFiles(listOf(sourceUri(context, source)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE); fail() }
            catch (_: IllegalArgumentException) { }
            assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
            val report = File(context.cacheDir, "import-diagnostics").listFiles()!!.single()
            assertEquals("failed", JSONObject(report.readText()).getString("status"))
        }
    }

    private fun sourceUri(context: Context, source: File): Uri = Uri.parse("content://import-pipeline/${source.name}").also {
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(it) { source.inputStream() }
    }

    @Test fun providedMobiImportsEveryChapterWithExactlyTheExistingCanonicalOffsets() = runBlocking {
        val source = System.getenv("EREAD_IMPORT_MOBI_SOURCE")?.let(::File)
        assumeTrue(source?.isFile == true)
        withContext { context, root ->
            val directory = File(root, "mobi").apply { mkdirs() }
            val importer = DocumentImporter(context)
            val timing = ImportDiagnostics()
            DocumentImporter::class.java.getDeclaredField("diagnostics").apply { isAccessible = true }.set(importer, timing)
            val at = System.nanoTime()
            val book = DocumentImporter::class.java.declaredMethods.single { it.name == "importMobi" }.apply { isAccessible = true }
                .invoke(importer, source, directory, "test", "样本", DEFAULT_CHAPTER_RULE, {}) as Book
            val elapsed = (System.nanoTime() - at) / 1_000_000
            val profile = timing.snapshot("MOBI", "complete")
            val expected = MobiArchive.read(source!!)
            assertEquals(expected.sections.size, book.chapters.size)
            book.chapters.forEachIndexed { index, chapter ->
                val section = expected.sections[index]
                val manifest = JSONObject(File(chapter.lazySourcePath!!).readText())
                assertEquals(section.html, manifest.getString("html"))
                val images = manifest.getJSONObject("images")
                val plain = HtmlChapterParser.readPlainWithNotes(section.title, section.html, noteTargets = section.sourceNoteTargets) { images.optString(it).takeIf(String::isNotBlank) }
                assertEquals(plain.text, File(chapter.textPath!!).readText())
                assertEquals(plain.inlineImages, chapter.inlineImages)
                assertEquals(plain.sourceFootnotes, chapter.sourceFootnotes)
            }
            System.getenv("EREAD_TEST_ARTIFACTS")?.let { path -> File(path).apply { mkdirs() }.resolve("mobi-import.json")
                .writeText(profile.put("chapters", book.chapters.size).put("totalMs", elapsed).toString(2)) }
        }
    }

    private suspend fun withContext(block: suspend (Context, File) -> Unit) {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "import-pipeline-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try { block(context, root) } finally { root.deleteRecursively() }
    }

    private fun epub(root: File, chapters: Int): File = File(root, "private-fixture.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            fun entry(path: String, text: String) { zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("META-INF/container.xml", "<container><rootfile full-path='book.opf'/></container>")
            entry("book.opf", "<package><manifest>" + (0 until chapters).joinToString("") { "<item id='c$it' href='c$it.xhtml'/>" } +
                "</manifest><spine>" + (0 until chapters).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>")
            repeat(chapters) { index -> entry("c$index.xhtml", "<html><head><style>p{margin:0}</style></head><body><p>正文$index🙂<a href='#fn1'>[1]</a></p><aside id='fn1'>注释$index</aside></body></html>") }
        }
    }
}
