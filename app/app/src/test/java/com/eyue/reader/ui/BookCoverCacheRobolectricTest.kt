package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.os.Looper
import com.eyue.reader.data.BitmapLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [BookCoverRegionDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookCoverCacheRobolectricTest {
    private lateinit var root: File
    @Before fun setup() { root = File(RuntimeEnvironment.getApplication().cacheDir, "book-covers-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun repeatedAndConcurrentCoversShareOneDecodeOutsideTheMainThread() = runBlocking {
        val cover = image("cover", Color.RED)
        val calls = AtomicInteger()
        val cache = BookCoverCache(decode = { file ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            calls.incrementAndGet()
            BitmapLoader.decodeThumbnail(file)
        })
        val key = BookCoverKey(cover.path, 1, false)
        val covers = List(12) { async { cache.load(key) } }.awaitAll()
        val first = requireNotNull(covers.first())
        assertTrue(covers.all { it === first })
        assertSame(first, cache.peek(key))
        assertSame(first, cache.load(key))
        assertEquals(1, calls.get())
    }

    @Test fun changedFilesAndBookRevisionsReplaceTheCoverWithoutRecyclingPreviousReaders() = runBlocking {
        val cover = image("cover", Color.RED)
        val calls = AtomicInteger()
        val cache = BookCoverCache(decode = { file -> calls.incrementAndGet(); BitmapLoader.decodeThumbnail(file) })
        val key = BookCoverKey(cover.path, 1, false)
        val red = requireNotNull(cache.load(key))
        val oldModified = cover.lastModified()
        image("cover", Color.BLUE)
        assertTrue(cover.setLastModified(oldModified + 5_000))
        val blue = requireNotNull(cache.load(key))
        assertNotSame(red, blue)
        assertEquals(Color.BLUE, blue.getPixel(8, 8))
        assertFalse(red.isRecycled)
        assertEquals(Color.RED, red.getPixel(8, 8))
        assertNotSame(blue, cache.load(key.copy(contentVersion = 2)))
        assertEquals(3, calls.get())
    }

    @Test fun cloudVariantKeepsTheOriginalAndDownloadImmediatelyReusesItsSharpCover() = runBlocking {
        val cover = image("cover", Color.RED)
        val calls = AtomicInteger()
        val cache = BookCoverCache(decode = { file -> calls.incrementAndGet(); BitmapLoader.decodeThumbnail(file) })
        val cloud = BookCoverKey(cover.path, 1, true)
        val blurred = requireNotNull(cache.load(cloud))
        val local = requireNotNull(cache.peek(cloud.copy(cloudOnly = false)))
        assertNotSame(blurred, local)
        assertFalse(local.isRecycled)
        assertSame(blurred, cache.load(cloud))
        assertSame(local, cache.load(cloud.copy(cloudOnly = false)))
        assertEquals(1, calls.get())
    }

    @Test fun byteBoundedEvictionDoesNotRecycleABitmapStillDisplayedByAnotherPage() = runBlocking {
        val firstFile = image("first", Color.RED)
        val secondFile = image("second", Color.BLUE)
        val cache = BookCoverCache(maximumBytes = 16 * 16 * 4,
            decode = { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(if (it == firstFile) Color.RED else Color.BLUE) } })
        val first = BookCoverKey(firstFile.path, 1, false)
        val displayed = requireNotNull(cache.load(first))
        cache.load(BookCoverKey(secondFile.path, 1, false))
        assertNull(cache.peek(first))
        assertFalse(displayed.isRecycled)
        assertEquals(Color.RED, displayed.getPixel(8, 8))
    }

    @Test fun missingAndCorruptCoversAreNotKeptAsPermanentFailedEntries() = runBlocking {
        val missing = File(root, "missing.png")
        val cache = BookCoverCache()
        val key = BookCoverKey(missing.path, 1, false)
        assertNull(cache.load(key))
        missing.writeText("not an image")
        assertNull(cache.load(key))
        image("missing", Color.GREEN)
        assertEquals(Color.GREEN, requireNotNull(cache.load(key)).getPixel(8, 8))
        assertTrue(missing.delete())
        assertNull(cache.load(key))
        assertNull(cache.peek(key))
    }

    private fun image(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(root, "$name.png").also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
}

/** Windows Robolectric's region decoder returns blank pixels; use BitmapLoader's real fallback. */
@Implements(BitmapRegionDecoder::class)
class BookCoverRegionDecoderShadow {
    companion object {
        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun newInstance(path: String, isShareable: Boolean): BitmapRegionDecoder? = null
    }
}
