package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.PdfCrop
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderImageCropRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun displayedImageAndItsCacheFollowCropChangesAndRestore() {
        val file = fixture(400, 600, Bitmap.CompressFormat.PNG) { it.drawColor(Color.GREEN) }
        var crop by mutableStateOf(PdfCrop())
        try {
            compose.setContent { MaterialTheme { Column(Modifier.width(200.dp).height(800.dp)) {
                ReaderImagePage(PageSource(file.path), 400, false, androidx.compose.ui.graphics.Color.Black,
                    {}, {}, modifier = Modifier.testTag("image-crop-surface"), description = "裁边测试图片", pdfCrop = crop)
            } } }
            fun waitForHeight(height: Float) { compose.waitUntil(5_000) {
                val bounds = compose.onNodeWithTag("image-crop-surface").getUnclippedBoundsInRoot()
                compose.onAllNodesWithContentDescription("裁边测试图片").fetchSemanticsNodes().size == 1 &&
                    kotlin.math.abs(bounds.bottom.value - bounds.top.value - height) < 1f
            } }
            waitForHeight(300f)
            compose.runOnIdle { crop = PdfCrop(true, .25f) }
            waitForHeight(400f)
            compose.runOnIdle { crop = PdfCrop() }
            waitForHeight(300f)
        } finally { file.delete() }
    }

    @Test fun nativeEbookConfigurationPassesCropToBothBrowserSurfaces() {
        var settings by mutableStateOf(ReaderSettings(pdfCrop = PdfCrop(true, .1f, .2f, .15f, .05f)))
        var config: JSONObject? = null
        compose.setContent { MaterialTheme { config = rememberEbookConfig(settings, androidx.compose.ui.graphics.Color.Black) } }
        compose.runOnIdle {
            val crop = requireNotNull(config).getJSONObject("imageCrop")
            assertTrue(crop.getBoolean("enabled")); assertEquals(.2, crop.getDouble("right"), .0001)
            assertEquals(.15, crop.getDouble("top"), .0001)
            settings = settings.copy(pdfCrop = PdfCrop())
        }
        compose.runOnIdle {
            assertTrue(requireNotNull(config).getJSONObject("imageCrop").getBoolean("enabled"))
            assertEquals(0.0, requireNotNull(config).getJSONObject("imageCrop").getDouble("left"), .0001)
        }
    }

    @Test fun pngAndWebpCropAllFourSidesAndRestoreWithoutChangingTheSource() {
        listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP).forEach { format ->
            val file = fixture(400, 600, format) { canvas ->
                canvas.drawColor(Color.WHITE)
                canvas.drawRect(40f, 60f, 320f, 480f, Paint().apply { color = Color.GREEN })
            }
            try {
                val original = file.readBytes()
                val crop = PdfCrop(true, .1f, .2f, .1f, .2f)
                val image = decodeReaderPage(context, PageSource(file.path), 400, crop)
                val restored = decodeReaderPage(context, PageSource(file.path), 400, PdfCrop())
                try {
                    assertEquals(280, image.width); assertEquals(420, image.height)
                    assertGreen(image.getPixel(2, 2)); assertGreen(image.getPixel(277, 417))
                    assertEquals(400, restored.width); assertEquals(600, restored.height)
                    assertTrue(Color.red(restored.getPixel(2, 2)) > 240)
                    assertArrayEquals(original, file.readBytes())
                } finally { image.recycle(); restored.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun jpegCropSidesFollowTheDisplayedExifOrientation() {
        val file = fixture(400, 200, Bitmap.CompressFormat.JPEG) { canvas ->
            canvas.drawColor(Color.RED)
            canvas.drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE })
        }
        try {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = file.readBytes()
            val full = decodeReaderPage(context, PageSource(file.path), 800)
            val cropped = decodeReaderPage(context, PageSource(file.path), 800, PdfCrop(true, .25f, 0f, .1f, .2f))
            try {
                assertEquals(200, full.width); assertEquals(400, full.height)
                assertEquals(150, cropped.width); assertEquals(280, cropped.height)
                assertEquals(full.getPixel(70, 60), cropped.getPixel(20, 20))
                assertEquals(full.getPixel(180, 300), cropped.getPixel(130, 260))
                assertArrayEquals(original, file.readBytes())
            } finally { full.recycle(); cropped.recycle() }
        } finally { file.delete() }
    }

    @Test fun smallestImagesAndMalformedMarginsRemainReadable() {
        val file = fixture(1, 1, Bitmap.CompressFormat.PNG) { it.drawColor(Color.GREEN) }
        try {
            listOf(PdfCrop(true, .4f, .4f, .4f, .4f), PdfCrop(true, -1f, Float.NaN, Float.POSITIVE_INFINITY, 99f)).forEach { crop ->
                val image = decodeReaderPage(context, PageSource(file.path), 400, crop)
                try { assertEquals(1, image.width); assertEquals(1, image.height); assertGreen(image.getPixel(0, 0)) }
                finally { image.recycle() }
            }
        } finally { file.delete() }
    }

    private fun assertGreen(pixel: Int) {
        assertTrue("Expected retained green content", Color.green(pixel) > 235 && Color.red(pixel) < 20 && Color.blue(pixel) < 20)
    }

    private fun fixture(width: Int, height: Int, format: Bitmap.CompressFormat, paint: (Canvas) -> Unit): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            paint(Canvas(bitmap))
            File.createTempFile("image-crop-", ".image", context.cacheDir).also { file ->
                file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
}
