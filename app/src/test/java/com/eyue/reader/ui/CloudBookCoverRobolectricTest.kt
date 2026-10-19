package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudBookCoverRobolectricTest {
    @Test fun softensArtworkWithoutChangingTheLocalCoverOnAndroidEight() {
        val source = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(120 * 180) { if (it % 120 < 60) Color.BLACK else Color.WHITE }
        source.setPixels(pixels, 0, 120, 0, 0, 120, 180)
        val cover = cloudBookCover(source)
        try {
            assertNotSame(source, cover)
            assertEquals(source.width, cover.width)
            assertEquals(source.height, cover.height)
            assertFalse(source.isRecycled)
            assertEquals(Color.BLACK, source.getPixel(59, 90))
            assertEquals(Color.WHITE, source.getPixel(60, 90))
            assertTrue(Color.red(cover.getPixel(59, 90)) in 1..254)
            assertTrue(Color.red(cover.getPixel(60, 90)) in 1..254)
            assertEquals(Color.BLACK, cover.getPixel(52, 90))
            assertEquals(Color.WHITE, cover.getPixel(67, 90))
        } finally { cover.recycle(); source.recycle() }
    }

    @Test fun supportsSinglePixelArtworkWithoutRecyclingTheSource() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val cover = cloudBookCover(source)
        try {
            assertNotSame(source, cover)
            assertEquals(Color.BLUE, cover.getPixel(0, 0))
            assertFalse(source.isRecycled)
        } finally { cover.recycle(); source.recycle() }
    }
}
