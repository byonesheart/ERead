package com.eyue.reader.ui

import android.graphics.Bitmap
import android.content.Context
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import com.eyue.reader.data.PdfCrop
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.documentSourceIdentity
import com.eyue.reader.data.withDocumentDescriptor
import com.eyue.reader.data.withLocalDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.sqrt
import kotlin.math.roundToInt

private val readerBitmapCache = object : LruCache<String, Bitmap>(minOf(Runtime.getRuntime().maxMemory() / 16, 12L * 1024 * 1024).toInt()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val readerDecodePermits = Semaphore(2)

internal suspend fun prepareReaderPageBitmap(context: Context, source: PageSource, width: Int, crop: PdfCrop): Result<Bitmap> =
    withContext(Dispatchers.IO) {
        readerDecodePermits.withPermit {
            try {
                val targetWidth = width.coerceIn(400, 1600)
                val key = "${documentSourceIdentity(context, source.path)}:${source.pdfPage}:$targetWidth:$crop"
                val bitmap = readerBitmapCache.get(key) ?: decodeReaderPage(context, source, targetWidth, crop).also {
                    readerBitmapCache.put(key, it)
                }
                Result.success(bitmap)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }

internal class ReaderPagedImages(
    val displayed: ReaderPageLocation,
    val bitmaps: Map<PageSource, Result<Bitmap>>,
    val isReady: (ReaderPageLocation) -> Boolean
)

@Composable
internal fun rememberReaderPagedImages(book: com.eyue.reader.data.Book, renderPages: List<List<ReaderTextPage>>,
    requestedSpread: ReaderPageLocation, pageCounts: List<Int>, dualPage: Boolean, raster: Boolean,
    enabled: Boolean, textWidth: Int, pdfCrop: PdfCrop): ReaderPagedImages {
    val context = LocalContext.current
    val renderChapters = book.chapters
    val crop = pdfCrop.normalized()
    val preparedImages = remember(book.id, book.contentVersion, textWidth, crop) {
        androidx.compose.runtime.mutableStateMapOf<PageSource, Result<android.graphics.Bitmap>>()
    }
    fun imageSource(location: ReaderPageLocation): PageSource? {
        val chapter = renderChapters.getOrNull(location.chapter) ?: return null
        if (raster) return chapter.pages.getOrNull(location.page)
        val imageIndex = renderPages.getOrNull(location.chapter)?.getOrNull(location.page)?.imageIndex ?: return null
        return chapter.inlineImages.getOrNull(imageIndex)?.let { PageSource(it.path) }
    }
    fun spreadImages(location: ReaderPageLocation): List<PageSource> = listOfNotNull(
        imageSource(location), if (dualPage) imageSource(location.copy(page = location.page + 1)) else null
    )
    var displayedSpread by remember(book.id, enabled, dualPage) { mutableStateOf(requestedSpread) }
    val previousSpread = if (enabled)
        readerAdjacentPage(requestedSpread.chapter, requestedSpread.page, pageCounts, -1, dualPage) else null
    val nextSpread = if (enabled)
        readerAdjacentPage(requestedSpread.chapter, requestedSpread.page, pageCounts, 1, dualPage) else null
    val requestedImages = if (enabled) spreadImages(requestedSpread) else emptyList()
    val adjacentImages = listOfNotNull(previousSpread, nextSpread).flatMap(::spreadImages)
    LaunchedEffect(book.id, book.contentVersion, textWidth, crop, requestedSpread, requestedImages, adjacentImages, enabled) {
        if (!enabled) {
            preparedImages.clear()
            return@LaunchedEffect
        }
        val retainedImages = (requestedImages + adjacentImages + spreadImages(displayedSpread)).toSet()
        preparedImages.keys.retainAll(retainedImages)
        for (source in requestedImages.distinct()) {
            if (source !in preparedImages) preparedImages[source] = prepareReaderPageBitmap(context, source, textWidth, crop)
        }
        displayedSpread = requestedSpread
        for (source in adjacentImages.distinct()) {
            if (source !in preparedImages) preparedImages[source] = prepareReaderPageBitmap(context, source, textWidth, crop)
        }
    }
    val displayed = if (requestedImages.all { it in preparedImages }) requestedSpread else displayedSpread
    return ReaderPagedImages(displayed, preparedImages) { location -> spreadImages(location).all { it in preparedImages } }
}

internal data class ReaderImagePreview(val bitmap: Bitmap, val zoom: Float, val focus: Offset)

@Composable
internal fun ReaderImageViewer(preview: ReaderImagePreview, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = preview.bitmap
    var zoom by remember(bitmap) { mutableStateOf(preview.zoom) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var surface by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    fun fit(): Offset {
        val scale = minOf(surface.width.toFloat() / bitmap.width, surface.height.toFloat() / bitmap.height)
        return Offset(bitmap.width * scale, bitmap.height * scale)
    }
    fun clamp(offset: Offset, scale: Float): Offset {
        val fitted = fit()
        val x = ((fitted.x * scale - surface.width) / 2f).coerceAtLeast(0f)
        val y = ((fitted.y * scale - surface.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
    LaunchedEffect(preview, surface) {
        zoom = preview.zoom
        val fitted = fit()
        pan = clamp(Offset(-preview.focus.x * fitted.x, -preview.focus.y * fitted.y) * (zoom - 1f), zoom)
    }
    Box(modifier.fillMaxSize().background(Color(0xFF121212)).clipToBounds()
        .onSizeChanged { surface = it }.testTag("reader_image_viewer")
        .pointerInput(bitmap) {
            detectTransformGestures { centroid, change, scale, _ ->
                val next = (zoom * scale).coerceIn(1f, 5f)
                val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
                pan = clamp(pivot - (pivot - pan) * (next / zoom) + change, next)
                zoom = next
            }
        }) {
        Image(bitmap.asImageBitmap(), "大图", Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
        }, contentScale = ContentScale.Fit)
        TextButton(onClose, Modifier.align(Alignment.TopEnd).padding(8.dp).background(
            Color(0xAA222222), RoundedCornerShape(20.dp))) { Text("关闭大图", color = Color.White) }
    }
}

@Composable
internal fun ReaderImagePage(
    source: PageSource,
    targetWidth: Int,
    paged: Boolean,
    foreground: Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    markColor: Color? = null,
    modifier: Modifier = Modifier,
    description: String = "漫画或 PDF 页面",
    speechOffset: Int? = null, speechText: String = "", einkMode: Boolean = false,
    pdfCrop: PdfCrop = PdfCrop(), onPageTap: ((Float, Float) -> Unit)? = null,
    onImagePreview: ((ReaderImagePreview) -> Unit)? = null,
    preparedBitmap: Result<Bitmap>? = null,
    managedBitmap: Boolean = false
) {
    val context = LocalContext.current
    val crop = pdfCrop.normalized()
    val cacheKey = "${source.path}:${source.pdfPage}:${targetWidth.coerceAtMost(1600)}:$crop"
    var decoded by remember(cacheKey) { mutableStateOf<Result<Bitmap>?>(null) }
    val bitmap = (preparedBitmap ?: decoded)?.getOrNull()
    val failure = (preparedBitmap ?: decoded)?.exceptionOrNull()
    var speechGeometry by remember(cacheKey) { mutableStateOf<PdfSpeechGeometry?>(null) }
    val latestTap by rememberUpdatedState(onTap)
    val latestPageTap by rememberUpdatedState(onPageTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestPreview by rememberUpdatedState(onImagePreview)
    LaunchedEffect(cacheKey, speechText, speechOffset != null) {
        if (source.pdfPage >= 0 && speechOffset != null && speechText.isNotBlank() && speechGeometry == null) {
            speechGeometry = withContext(Dispatchers.IO) {
                readerDecodePermits.withPermit { runCatching { loadPdfSpeechGeometry(context, source) }
                    .getOrNull()?.takeIf { it.text == speechText } }
            }
        }
    }
    LaunchedEffect(cacheKey, managedBitmap) {
        if (!managedBitmap && decoded == null) decoded = prepareReaderPageBitmap(context, source, targetWidth, crop)
    }
    fun preview(point: Offset, size: IntSize, scale: Float) {
        val image = bitmap ?: return
        val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        if (fit <= 0f) return
        val focus = Offset(((point.x - size.width / 2f) / (image.width * fit)).coerceIn(-.5f, .5f),
            ((point.y - size.height / 2f) / (image.height * fit)).coerceIn(-.5f, .5f))
        latestPreview?.invoke(ReaderImagePreview(image, scale, focus))
    }
    val layout = if (paged) Modifier.fillMaxSize() else {
        bitmap?.let { Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height) }
            ?: Modifier.fillMaxWidth().heightIn(min = 280.dp)
    }
    Box(
        modifier.then(layout).clipToBounds()
            .then(if (markColor != null) Modifier.border(3.dp, markColor) else Modifier)
            .pointerInput(cacheKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var scale = 1f
                    var opened = false
                    do {
                        val event = awaitPointerEvent()
                        if (latestPreview != null && event.changes.count { it.pressed } >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val center = event.calculateCentroid()
                            if (center.isSpecified && (opened || scale > 1.05f)) {
                                opened = true
                                preview(center, size, scale)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(cacheKey) {
                detectTapGestures(
                    onTap = { point ->
                        latestPageTap?.invoke(point.x / size.width.coerceAtLeast(1), point.y / size.height.coerceAtLeast(1)) ?: latestTap()
                    },
                    onLongPress = { latestLongPress() },
                    onDoubleTap = { preview(it, size, 2f) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            failure != null -> Text("这一页读取失败：${failure.message ?: "文件损坏或已移除"}",
                color = foreground, modifier = Modifier.padding(24.dp))
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = description,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
            )
            else -> BookLoadingAnimation(animate = !einkMode)
        }
        if (bitmap != null && speechOffset != null && speechGeometry != null) {
            val highlights = speechGeometry!!.paragraph(speechOffset)
            Canvas(Modifier.fillMaxSize().testTag("reader_pdf_speech_highlight")) {
                val image = bitmap
                val fit = minOf(size.width / image.width, size.height / image.height)
                val width = image.width * fit
                val height = image.height * fit
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                clipRect(left, top, left + width, top + height) { highlights.mapNotNull { cropPdfSpeechRect(it, crop) }.forEach { rect ->
                    drawRect(Color(0xFFFFA66B).copy(alpha = .35f), Offset(left + rect.left * width, top + rect.top * height),
                        Size(rect.width * width, rect.height * height))
                } }
            }
        }
        if (markColor != null) Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(16.dp)
            .background(markColor, RoundedCornerShape(4.dp)).testTag("reader_image_mark")
            .semantics { contentDescription = "本页彩色标记" })
    }
}

internal fun decodeReaderPage(context: Context, source: PageSource, targetWidth: Int, pdfCrop: PdfCrop = PdfCrop()): Bitmap {
    val crop = pdfCrop.normalized()
    if (source.pdfPage >= 0) {
        return withDocumentDescriptor(context, source.path) { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(source.pdfPage).use { page ->
                    val visibleWidth = page.width * (1f - crop.left - crop.right)
                    val visibleHeight = page.height * (1f - crop.top - crop.bottom)
                    val scale = minOf(targetWidth.toFloat() / visibleWidth, 3200f / visibleHeight,
                        sqrt(2_560_000f / (visibleWidth * visibleHeight)))
                    val width = (visibleWidth * scale).toInt().coerceAtLeast(1)
                    val height = (visibleHeight * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        val matrix = Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(-page.width * crop.left * scale, -page.height * crop.top * scale)
                        }
                        page.render(it, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
    return withLocalDocument(context, source.path) { file ->
    val decoded = requireNotNull(BitmapLoader.decodeBounded(file, targetWidth, 3200, 2_560_000)) {
        "无法解码图片"
    }
    val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> Unit
    }
    val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
    val left = (oriented.width * crop.left).roundToInt().coerceIn(0, oriented.width - 1)
    val top = (oriented.height * crop.top).roundToInt().coerceIn(0, oriented.height - 1)
    val right = (oriented.width * (1f - crop.right)).roundToInt().coerceIn(left + 1, oriented.width)
    val bottom = (oriented.height * (1f - crop.bottom)).roundToInt().coerceIn(top + 1, oriented.height)
    Bitmap.createBitmap(oriented, left, top, right - left, bottom - top).also {
        if (it !== oriented) oriented.recycle()
    }
    }
}
