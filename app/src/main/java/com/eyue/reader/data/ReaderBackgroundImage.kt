package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Keep a bounded, portable image rather than a picker grant or a full-resolution bitmap. */
internal suspend fun importReaderBackground(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val temporary = File.createTempFile("reader-background-", ".tmp", context.cacheDir)
    var destination: File? = null
    try {
        openDocumentStream(context, uri.toString()).use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 32L * 1024 * 1024) { "背景图片不能超过 32 MB" }
                output.write(buffer, 0, count)
            }
        } }
        val orientation = runCatching { ExifInterface(temporary.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        var bitmap = requireNotNull(BitmapLoader.decodeBounded(temporary, if (rotated) 2880 else 1440,
            if (rotated) 1440 else 2880, 2_000_000)) { "图片损坏或系统不支持此格式" }
        try {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            }
            if (!matrix.isIdentity) {
                val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (corrected !== bitmap) bitmap.recycle()
                bitmap = corrected
            }
            val folder = File(context.filesDir, "backgrounds").apply { check(mkdirs() || isDirectory) }
            val transparent = bitmap.hasAlpha()
            val image = File(folder, "${UUID.randomUUID()}.${if (transparent) "png" else "jpg"}")
            destination = image
            image.outputStream().use { check(bitmap.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)) }
            currentCoroutineContext().ensureActive()
            image.absolutePath
        } finally { bitmap.recycle() }
    } catch (error: Throwable) { destination?.delete(); throw error }
      finally { temporary.delete() }
}
