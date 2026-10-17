package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BitmapLoaderTest {
    private lateinit var directory: File

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "bitmap-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun narrowLongComicUsesTheTopRegionForSmallCoverAndBoundsFullPagePixels() {
        val source = File(directory, "long-comic.png")
        val original = Bitmap.createBitmap(400, 30_000, Bitmap.Config.RGB_565)
        try {
            original.eraseColor(Color.RED)
            Canvas(original).drawRect(0f, 15_000f, 400f, 30_000f, Paint().apply { color = Color.BLUE })
            source.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { original.recycle() }
        val cover = requireNotNull(BitmapLoader.decodeThumbnail(source))
        try {
            assertTrue(cover.width <= 480 && cover.height <= 720)
            assertTrue(cover.allocationByteCount <= 480 * 720 * 4)
            assertEquals(Color.RED, cover.getPixel(cover.width / 2, cover.height / 2))
        } finally { cover.recycle() }
        val page = requireNotNull(BitmapLoader.decodeBounded(source, 1600, 3200, 2_560_000))
        try {
            assertTrue(page.width <= 1600 && page.height <= 3200)
            assertTrue(page.width.toLong() * page.height <= 2_560_000L)
        } finally { page.recycle() }
        val path = BitmapLoader.createThumbnail(source, directory)
        assertTrue(File(path).isFile)
        assertTrue(source.isFile)
    }
}
