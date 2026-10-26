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
            java.util.zip.ZipOutputStream(source.outputStream()).use { zip -> sections.forEach {
                zip.putNextEntry(java.util.zip.ZipEntry(it.path)); zip.write(html.toByteArray()); zip.closeEntry()
            } }
            val parsed = ParsedEpub("测试", "", sections, null, emptyList())
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            val check = {
                if (calls.incrementAndGet() >= 8) cancelled.set(true)
                if (cancelled.get()) throw kotlinx.coroutines.CancellationException("取消测试")
            }
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                DocumentImporter::class.java.declaredMethods.single { it.name == "epubLazyChapters" }.apply { isAccessible = true }
                    .invoke(DocumentImporter(context), source, parsed, directory, check)
            }
            assertTrue(error.cause is kotlinx.coroutines.CancellationException)
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
            var tocMs = 0L
            val parsed = EpubArchive.read(source!!, onDirectoryReady = { tocMs = (System.nanoTime() - started) / 1_000_000 })
            val parseMs = (System.nanoTime() - started) / 1_000_000
            val chapters = DocumentImporter::class.java.declaredMethods.single { it.name == "epubLazyChapters" }.apply { isAccessible = true }
                .invoke(DocumentImporter(context), source, parsed, directory, {}) as List<*>
            val totalMs = (System.nanoTime() - started) / 1_000_000
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
                .writeText(JSONObject().put("chapters", typed.size).put("sourceBytes", source.length()).put("tocMs", tocMs)
                    .put("parseMs", parseMs).put("cacheMs", totalMs - parseMs).put("totalMs", totalMs)
                    .put("importerLocation", DocumentImporter::class.java.protectionDomain.codeSource?.location?.toString())
                    .put("parallelImporter", DocumentImporter::class.java.declaredMethods.any {
                        it.name.startsWith("epubLazyChapters") && it.returnType == Chapter::class.java
                    })
                    .toString(2)) }
        } finally { directory.deleteRecursively() }
    }
}
