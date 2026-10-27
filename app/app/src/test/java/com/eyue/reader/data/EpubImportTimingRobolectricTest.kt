package com.eyue.reader.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EpubImportTimingRobolectricTest {
    @Test fun cancellingAParallelCacheBatchStopsItsWritersBeforeReturning() {
        val context = RuntimeEnvironment.getApplication() as Context
        val directory = File(context.cacheDir, "import-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val html = "<html><body><p>${"正文。".repeat(300)}</p></body></html>"
            val sections = List(64) { EpubSection("第${it + 1}章", html, "chapter-$it.xhtml") }
            val source = File(directory, "test.epub")
            java.util.zip.ZipOutputStream(source.outputStream()).use { zip ->
                val files = linkedMapOf("META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                    "book.opf" to "<package><manifest>" + sections.mapIndexed { i, ch -> "<item id='c$i' href='${ch.path}'/>" }.joinToString("") +
                        "</manifest><spine>" + sections.indices.joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>")
                sections.forEach { files[it.path] = html }
                files.forEach { (path, text) -> zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            val check = {
                if (calls.incrementAndGet() >= 8 && File(directory, "chapter-0.txt").isFile) cancelled.set(true)
                if (cancelled.get()) throw kotlinx.coroutines.CancellationException("取消测试")
            }
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                DocumentImporter::class.java.declaredMethods.single { it.name == "epubLazyChapters" }.apply { isAccessible = true }
                    .invoke(DocumentImporter(context), source, directory, "测试", check)
            }
            assertTrue(error.cause is kotlinx.coroutines.CancellationException)
            assertTrue("Cancel after a real cache writer has started", File(directory, "chapter-0.txt").isFile)
            val stoppedAt = calls.get()
            Thread.sleep(100)
            assertEquals("Cancelled workers must not continue after the import returns", stoppedAt, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun completeLargeEpubImportRetainsEveryChapterAndSource() {
        val source = System.getenv("EREAD_LARGE_EPUB_SOURCE")?.let(::File)
        assumeTrue(source?.isFile == true)
        val context = RuntimeEnvironment.getApplication() as Context
        val directory = File(context.cacheDir, "import-timing-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val started = System.nanoTime()
            val importer = DocumentImporter(context)
            val diagnostics = ImportDiagnostics()
            DocumentImporter::class.java.getDeclaredField("diagnostics").apply { isAccessible = true }.set(importer, diagnostics)
            val result = DocumentImporter::class.java.declaredMethods.single { it.name == "epubLazyChapters" }.apply { isAccessible = true }
                .invoke(importer, source, directory, "样本", {}) as Pair<*, *>
            val totalMs = (System.nanoTime() - started) / 1_000_000
            val profile = diagnostics.snapshot("EPUB", "complete")
            val chapters = result.second as List<*>
            val index = result.first as ParsedEpub
            assertTrue(index.sections.all { it.html.isEmpty() && it.css.isEmpty() && it.sourceNoteTargets.isEmpty() })
            // Existing full parser is the compatibility oracle, outside the timed streaming import.
            val parsed = EpubArchive.read(source!!)
            assertEquals(parsed.sections.size, chapters.size)
            val typed = chapters.filterIsInstance<Chapter>()
            assertTrue(typed.all { File(it.textPath!!).isFile && File(it.lazySourcePath!!).isFile })
            assertTrue(typed.sumOf { it.characterCount } > 0)
            typed.forEachIndexed { index, chapter ->
                val manifest = JSONObject(File(chapter.lazySourcePath!!).readText())
                val images = manifest.getJSONObject("images")
                val section = parsed.sections[index]
                val expected = HtmlChapterParser.readPlainWithNotes(section.title, section.html, noteTargets = section.sourceNoteTargets) {
                    images.optString(it).takeIf(String::isNotBlank)
                }
                assertEquals("Canonical offsets must not change in chapter $index", expected.text, File(chapter.textPath!!).readText())
                assertEquals(expected.inlineImages, chapter.inlineImages)
                assertEquals(expected.sourceFootnotes, chapter.sourceFootnotes)
                assertEquals(section.css, File(manifest.getString("cssPath")).readText())
            }
            System.getenv("EREAD_TEST_ARTIFACTS")?.let { output -> File(output).apply { mkdirs() }.resolve("large-epub-timing.json")
                .writeText(profile.put("chapters", typed.size).put("sourceBytes", source.length())
                    .put("totalMs", totalMs).put("cacheBytes", directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                    .put("streamingIndex", true).toString(2)) }
        } finally { directory.deleteRecursively() }
    }
}
