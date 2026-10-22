package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
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
import java.security.MessageDigest
import java.util.UUID

/** Optional private fixtures: EREAD_NOTES_SOURCE_DIR points at the user's unmodified source folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProvidedEbookSourceNotesRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        root = File("build/provided-ebook-notes-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        check(root.deleteRecursively())
    }

    @Test fun providedEpubNotesKeepTheirOffsetsAndRecoverWithoutLegacyTargetMap() = runBlocking {
        withSample("epub") { copy ->
            val section = EpubArchive.read(copy).sections.first { it.title.startsWith("第一回") }
            verifyFirstChapter(BookFormat.EPUB, section.html, section.css, section.sourceNoteTargets)
        }
    }

    @Test fun providedMobiNotesKeepTheirOffsetsAndRecoverWithoutLegacyTargetMap() = runBlocking {
        withSample("mobi") { copy ->
            val book = MobiArchive.read(copy)
            val section = book.sections.first { it.title.startsWith("第一回") }
            verifyFirstChapter(BookFormat.MOBI, section.html, book.css, section.sourceNoteTargets)
        }
    }

    private suspend fun verifyFirstChapter(format: BookFormat, html: String, css: String, targets: Map<String, String>) {
        val parsed = HtmlChapterParser.readPlainWithNotes("第一回", html, noteTargets = targets) { null }
        assertEquals(32, parsed.sourceFootnotes.size)
        val first = parsed.sourceFootnotes.first()
        assertEquals(92, first.start)
        assertEquals(95, first.end)
        assertEquals("[1]", parsed.text.substring(first.start, first.end))
        assertTrue(first.content.startsWith("《西游释厄传》"))
        val legacy = HtmlChapterParser.readPlain("第一回", html) { null }
        assertEquals(parsed.text, legacy.text)
        assertEquals(parsed.sourceFootnotes, legacy.sourceFootnotes)
        val chapter = saveLazyChapter(root, 0, legacy, html, css, emptyMap()).copy(sourceFootnotes = emptyList())
        val book = Book(title = "西游记", format = format, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        val prepared = loadChapterContent(context, book, 0)
        assertEquals(parsed.text, prepared.text)
        assertEquals(parsed.sourceFootnotes, prepared.chapter.sourceFootnotes)
        assertEquals(prepared, loadChapterContent(context, book, 0))
    }

    private suspend fun withSample(extension: String, verify: suspend (File) -> Unit) {
        val directory = System.getenv("EREAD_NOTES_SOURCE_DIR")
        assumeTrue("Private source folder is optional", !directory.isNullOrBlank())
        val original = File(requireNotNull(directory), "西游记[lunarora.com].$extension")
        assumeTrue("Private source file is optional", original.isFile)
        val originalHash = hash(original)
        val copy = original.copyTo(File(root, "source.$extension"))
        try { verify(copy) } finally { assertArrayEquals(originalHash, hash(original)) }
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
