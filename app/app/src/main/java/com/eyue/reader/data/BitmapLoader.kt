package com.eyue.reader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.IOException

object BitmapLoader {
    private val decodeLock = Any()

    fun decodeBounded(file: File, maxWidth: Int, maxHeight: Int, maxPixels: Int): Bitmap? = synchronized(decodeLock) {
        val bounds = bounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@synchronized null
        BitmapFactory.decodeFile(file.absolutePath, options(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight, maxPixels))
    }

    fun decodeThumbnail(file: File): Bitmap? = synchronized(decodeLock) {
        val bounds = bounds(file)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@synchronized null
        val cropHeight = minOf(bounds.outHeight.toLong(), bounds.outWidth.toLong() * 3 / 2).toInt().coerceAtLeast(1)
        val decoder = try { BitmapRegionDecoder.newInstance(file.absolutePath, false) }
            catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
        if (decoder != null) {
            try {
                return@synchronized decoder.decodeRegion(Rect(0, 0, bounds.outWidth, cropHeight),
                    options(bounds.outWidth, cropHeight, 480, 720, 480 * 720))
            } finally { decoder.recycle() }
        }
        decodeBounded(file, 480, 720, 480 * 720)
    }

    fun createThumbnail(source: File, directory: File): String {
        val bitmap = requireNotNull(decodeThumbnail(source)) { "图片损坏或系统不支持此格式" }
        val transparent = bitmap.hasAlpha()
        val destination = File(directory, if (transparent) "cover-thumbnail.png" else "cover-thumbnail.jpg")
        try {
            destination.outputStream().use { output ->
                check(bitmap.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, output))
            }
        } finally { bitmap.recycle() }
        return destination.absolutePath
    }

    private fun bounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
    }

    private fun options(width: Int, height: Int, maxWidth: Int, maxHeight: Int, maxPixels: Int): BitmapFactory.Options {
        require(maxWidth > 0 && maxHeight > 0 && maxPixels > 0)
        var sample = 1
        while (true) {
            val sampledWidth = (width.toLong() + sample - 1) / sample
            val sampledHeight = (height.toLong() + sample - 1) / sample
            if (sampledWidth <= maxWidth && sampledHeight <= maxHeight && sampledWidth * sampledHeight <= maxPixels) break
            sample *= 2
        }
        return BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false }
    }
}
