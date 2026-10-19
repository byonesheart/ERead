package com.eyue.reader.ui

import android.graphics.Bitmap

/** Blur the artwork itself so download controls stay sharp, including on Android 8. */
internal fun cloudBookCover(source: Bitmap): Bitmap {
    val scale = minOf(1f, 96f / maxOf(source.width, source.height))
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val reduced = Bitmap.createScaledBitmap(source, width, height, true)
    val pixels = IntArray(width * height)
    reduced.getPixels(pixels, 0, width, 0, 0, width, height)
    if (reduced !== source) reduced.recycle()
    val softened = IntArray(pixels.size)
    for (y in 0 until height) for (x in 0 until width) {
        var alpha = 0; var red = 0; var green = 0; var blue = 0; var count = 0
        for (sy in maxOf(0, y - 2)..minOf(height - 1, y + 2)) {
            for (sx in maxOf(0, x - 2)..minOf(width - 1, x + 2)) {
                val color = pixels[sy * width + sx]
                alpha += color ushr 24
                red += color ushr 16 and 255
                green += color ushr 8 and 255
                blue += color and 255
                count++
            }
        }
        softened[y * width + x] = (alpha / count shl 24) or (red / count shl 16) or
            (green / count shl 8) or (blue / count)
    }
    val blurred = Bitmap.createBitmap(softened, width, height, Bitmap.Config.ARGB_8888)
    val result = Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
    if (result !== blurred) blurred.recycle()
    return result
}
