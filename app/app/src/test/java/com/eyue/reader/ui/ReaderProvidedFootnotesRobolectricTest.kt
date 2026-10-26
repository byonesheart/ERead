package com.eyue.reader.ui

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.DEFAULT_CHAPTER_RULE
import com.eyue.reader.data.DocumentImporter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.loadChapterContent
import com.eyue.reader.data.materializeCloudBook
import com.eyue.reader.data.MobiBitmapRegionDecoderShadow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Native cached-text fallback checks; actual source DOM is covered by browser/device tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi", shadows = [MobiBitmapRegionDecoderShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderProvidedFootnotesRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var sources: File
    private val originalDigests = mutableMapOf<File, ByteArray>()

    @Before fun setUp() {
        sources = File(System.getenv("EREAD_NOTES_SOURCE_DIR").orEmpty())
        assumeTrue("Set EREAD_NOTES_SOURCE_DIR to the provided 西游记 source directory", sources.isDirectory &&
            System.getenv("EREAD_NOTES_SOURCE_DIR")?.isNotBlank() == true)
        root = File("build/robolectric-provided-footnotes-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        originalDigests.forEach { (file, digest) -> assertArrayEquals("The provided source must remain unchanged", digest, sha256(file)) }
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == File("build").canonicalFile)
            for (attempt in 0 until 5) {
                if (root.deleteRecursively()) break
                Thread.sleep(50)
            }
            check(!root.exists())
        }
    }

    @Test fun nativeFallbackEpubFirstChapterReferenceOpensTheProvidedNoteInScrollMode() {
        verifyImportedReference(BookFormat.EPUB, ReadingMode.SCROLL, useSourceCss = false)
    }

    @Test fun nativeFallbackMobiFirstChapterReferenceOpensTheProvidedNoteInPagedSourceCssMode() {
        verifyImportedReference(BookFormat.MOBI, ReadingMode.PAGED, useSourceCss = true)
    }

    @Test fun mobiTextCacheWithoutLinkMetadataStillDisplaysAndOpensTheOriginalNote() {
        verifyImportedReference(BookFormat.MOBI, ReadingMode.SCROLL, useSourceCss = false, textOnlyCache = true)
    }

    private fun verifyImportedReference(format: BookFormat, mode: ReadingMode, useSourceCss: Boolean, textOnlyCache: Boolean = false) {
        val original = File(sources, "西游记[lunarora.com].${format.name.lowercase()}")
        assumeTrue("The provided ${format.name} sample is not installed", original.isFile)
        originalDigests[original] = sha256(original)
        val staged = original.copyTo(File(root, original.name))
        // Exercise the public import path and the same book serialization used by the shelf.
        val imported = runBlocking {
            DocumentImporter(context).importFiles(listOf(providerUri(staged)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single()
        }
        assertEquals(format, imported.format)
        assertEquals(LibraryMode.NOVEL, imported.mode)
        assertTrue(imported.chapters.size > 100)
        val repository = LibraryRepository(context)
        // This test deliberately removes the source and simulates an older, fully cached shelf.
        val materialized = runBlocking { materializeCloudBook(context, imported) }
        val reopened = repository.readBook(repository.writeBook(materialized))
        val chapterIndex = reopened.chapters.indexOfFirst { chapter -> chapter.sourceFootnotes.any { it.content.contains("《西游释厄传》") } }
        assertTrue("Import must retain the first chapter's original note through shelf serialization", chapterIndex >= 0)
        val prepared = runBlocking { loadChapterContent(context, reopened, chapterIndex) }
        val footnote = prepared.chapter.sourceFootnotes.first { it.content.contains("《西游释厄传》") }
        assertEquals("[1]", prepared.text.substring(footnote.start, footnote.end))
        assertTrue(footnote.content.contains("是较早的《西游记》传本之一"))
        val book = reopened.copy(chapterIndex = chapterIndex, pageIndex = 0, characterOffset = 0, sourcePath = null, sourceUri = null,
            chapters = if (textOnlyCache) reopened.chapters.map { it.copy(lazySourcePath = null, sourceFootnotes = emptyList()) }
                else reopened.chapters)
        val position = AtomicReference<Pair<Int, Int>>()
        val host = AtomicReference<View>()
        var noteWrites = 0
        compose.setContent {
            host.set(LocalView.current)
            CompositionLocalProvider(LocalContext provides context, LocalReaderSpeechFactory provides { SilentSpeech() }) {
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(readingMode = mode, useSourceCss = useSourceCss,
                        showTime = false, showBattery = false), emptyList(), {},
                        { chapter, page, _, _, _ -> position.set(chapter to page) }, {}, { noteWrites++ }, { noteWrites++ }, {}, emptyFlow())
                }
            }
        }
        compose.waitUntil(30_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            position.get()?.first == chapterIndex &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
        }
        val bodies = compose.onAllNodesWithContentDescription("阅读正文")
        val target = (0 until bodies.fetchSemanticsNodes().size).firstNotNullOfOrNull { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            bodies[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.singleOrNull()?.takeIf { it.layoutInput.text.text.contains("[1]") }?.let { index to it }
        }
        assertNotNull("The actual first [1] reference should be on the starting page", target)
        val (bodyIndex, layout) = requireNotNull(target)
        val marker = layout.layoutInput.text.text.indexOf("[1]")
        assertTrue("A source link must be visibly marked", layout.layoutInput.text.spanStyles.any {
            it.start <= marker && it.end > marker && it.item.textDecoration == TextDecoration.Underline
        })
        val before = position.get()
        bodies[bodyIndex].performTouchInput { click(layout.getBoundingBox(marker + 1).center) }
        compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("reader_source_footnote_popup").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_source_footnote_popup").assertIsDisplayed()
        compose.onNodeWithTag("reader_source_footnote_content").assertTextEquals(footnote.content)
        compose.onNodeWithTag("reader_note_popup").assertDoesNotExist()
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, position.get()); assertEquals(0, noteWrites) }
        System.getenv("EREAD_TEST_ARTIFACTS")?.takeIf { it.isNotBlank() }?.let { directory ->
            compose.runOnIdle {
                val view = requireNotNull(host.get())
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    view.draw(canvas)
                    // Compose Popup lives in its own Android window, outside the host view.
                    val manager = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    ReflectionHelpers.getField<List<View>>(manager, "mViews")
                        .filter { it.javaClass.simpleName == "PopupLayout" && it.visibility == View.VISIBLE }.forEach { popup ->
                            val location = IntArray(2).also(popup::getLocationOnScreen)
                            canvas.save()
                            canvas.translate((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
                            popup.draw(canvas)
                            canvas.restore()
                        }
                    File(directory, "provided-footnote-${format.name.lowercase()}${if (textOnlyCache) "-text-cache" else ""}.png").apply { parentFile?.mkdirs() }
                        .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
    }

    // A real content-provider boundary also works on Windows, where Android file:// paths
    // do not round-trip the host's drive letters. The provider only exposes the test copy.
    private fun providerUri(file: File): Uri {
        val providerAuthority = "provided-footnotes-${UUID.randomUUID()}"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = if (file.extension == "epub") "application/epub+zip" else "application/x-mobipocket-ebook"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file.name, file.length())) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                check(mode == "r")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = providerAuthority; exported = true })
        ShadowContentResolver.registerProviderInternal(providerAuthority, provider)
        return Uri.Builder().scheme("content").authority(providerAuthority).appendPath(file.name).build()
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest()
    }

    private class SilentSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() = Unit
        override fun pause() = false
        override fun resume() = false
        override fun stop() = Unit
        override fun release() = Unit
    }
}
