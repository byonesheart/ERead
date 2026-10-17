package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBackgroundImageRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    @Before fun setup() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "background-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun importingLargeImageKeepsBoundedPortablePixelsAndOriginal() = runBlocking {
        val original = File(root, "original.jpg")
        val bitmap = Bitmap.createBitmap(2400, 3600, Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
        val uri = Uri.parse(original.absolutePath)
        val imported = File(importReaderBackground(context, uri))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imported.absolutePath, bounds)
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        assertTrue(bounds.outWidth <= 1440 && bounds.outHeight <= 2880)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= 2_000_000)
        assertTrue(original.exists())
        assertTrue(imported.absolutePath.startsWith(context.filesDir.absolutePath))
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun phoneExifRotationIsBakedIntoTheImportedBackground() = runBlocking {
        val original = File(root, "phone.jpg")
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
        androidx.exifinterface.media.ExifInterface(original.absolutePath).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val imported = importReaderBackground(context, Uri.parse(original.absolutePath))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imported, bounds)
        assertEquals(200, bounds.outWidth)
        assertEquals(100, bounds.outHeight)
    }

    @Test fun invalidImageLeavesNoBackgroundOrTemporaryFile() = runBlocking {
        val source = File(root, "bad.png").apply { writeText("not an image") }
        val result = runCatching { importReaderBackground(context, Uri.parse(source.absolutePath)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("图片损坏"))
        assertTrue(File(context.filesDir, "backgrounds").listFiles().orEmpty().isEmpty())
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun imagePreferencesRoundTripIndependentlyAndOldSettingsDefaultToNoImage() {
        val repository = LibraryRepository(context)
        val novel = ReaderSettings(background = ReaderBackground.IMAGE, backgroundImagePath = "/novel.jpg")
        val comic = ReaderSettings(background = ReaderBackground.IMAGE, backgroundImagePath = "/comic.jpg")
        repository.save(LibraryState(settings = novel, comicSettings = comic))
        val restored = repository.load()
        assertEquals(novel, restored.settings)
        assertEquals(comic, restored.comicSettings)
        assertNull(repository.readSettings(org.json.JSONObject()).backgroundImagePath)
    }
}
