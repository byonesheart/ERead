package com.eyue.reader.ui

import android.graphics.Bitmap
import android.content.Context
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.sqrt

private val readerBitmapCache = object : LruCache<String, Bitmap>(minOf(Runtime.getRuntime().maxMemory() / 16, 12L * 1024 * 1024).toInt()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val readerDecodePermits = Semaphore(2)

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
    speechOffset: Int? = null, speechText: String = "", einkMode: Boolean = false
) {
    val context = LocalContext.current
    val cacheKey = "${source.path}:${source.pdfPage}:${targetWidth.coerceAtMost(1600)}"
    var bitmap by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(cacheKey) { mutableStateOf<String?>(null) }
    var zoom by remember(cacheKey) { mutableStateOf(1f) }
    var pan by remember(cacheKey) { mutableStateOf(Offset.Zero) }
    var surfaceSize by remember(cacheKey) { mutableStateOf(IntSize.Zero) }
    var speechGeometry by remember(cacheKey) { mutableStateOf<PdfSpeechGeometry?>(null) }
    val latestTap by rememberUpdatedState(onTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    LaunchedEffect(cacheKey, speechText, speechOffset != null) {
        if (source.pdfPage >= 0 && speechOffset != null && speechText.isNotBlank() && speechGeometry == null) {
            speechGeometry = withContext(Dispatchers.IO) {
                readerDecodePermits.withPermit { runCatching { loadPdfSpeechGeometry(context, source) }
                    .getOrNull()?.takeIf { it.text == speechText } }
            }
        }
    }
    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                readerDecodePermits.withPermit { runCatching {
                    val identity = "${documentSourceIdentity(context, source.path)}:${source.pdfPage}:${targetWidth.coerceAtMost(1600)}"
                    readerBitmapCache.get(identity) ?: decodeReaderPage(context, source, targetWidth.coerceIn(400, 1600)).also { readerBitmapCache.put(identity, it) }
                } }
            }
            decoded.fold(
                onSuccess = { bitmap = it },
                onFailure = { failure = "这一页读取失败：${it.message ?: "文件损坏或已移除"}" }
            )
        }
    }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        val candidate = pan + panChange
        val image = bitmap
        val fit = if (image != null) minOf(surfaceSize.width.toFloat() / image.width, surfaceSize.height.toFloat() / image.height) else 1f
        val contentWidth = (image?.width ?: surfaceSize.width) * fit
        val contentHeight = (image?.height ?: surfaceSize.height) * fit
        val maxX = ((contentWidth * zoom - surfaceSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((contentHeight * zoom - surfaceSize.height) / 2f).coerceAtLeast(0f)
        pan = if (zoom > 1f) Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY)) else Offset.Zero
    }
    val layout = if (paged) Modifier.fillMaxSize() else {
        bitmap?.let { Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height) }
            ?: Modifier.fillMaxWidth().heightIn(min = 280.dp)
    }
    Box(
        modifier.then(layout).onSizeChanged { surfaceSize = it }.clipToBounds()
            .then(if (markColor != null) Modifier.border(3.dp, markColor) else Modifier)
            .transformable(state = transform, canPan = { zoom > 1f })
            .pointerInput(cacheKey) {
                detectTapGestures(
                    onTap = { latestTap() },
                    onLongPress = { latestLongPress() },
                    onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 2f
                        pan = Offset.Zero
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            failure != null -> Text(failure.orEmpty(), color = foreground, modifier = Modifier.padding(24.dp))
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(), contentDescription = description,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                }, contentScale = ContentScale.Fit
            )
            else -> if (einkMode) Text("正在读取页面", color = foreground)
                else CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        if (bitmap != null && speechOffset != null && speechGeometry != null) {
            val highlights = speechGeometry!!.paragraph(speechOffset)
            Canvas(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
            }.testTag("reader_pdf_speech_highlight")) {
                val image = bitmap!!
                val fit = minOf(size.width / image.width, size.height / image.height)
                val width = image.width * fit
                val height = image.height * fit
                val left = (size.width - width) / 2f
                val top = (size.height - height) / 2f
                highlights.forEach { rect -> drawRect(Color(0xFFFFA66B).copy(alpha = .35f),
                    Offset(left + rect.left * width, top + rect.top * height), Size(rect.width * width, rect.height * height)) }
            }
        }
        if (markColor != null) Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(16.dp)
            .background(markColor, RoundedCornerShape(4.dp)).testTag("reader_image_mark")
            .semantics { contentDescription = "本页彩色标记" })
    }
}

private fun decodeReaderPage(context: Context, source: PageSource, targetWidth: Int): Bitmap {
    if (source.pdfPage >= 0) {
        return withDocumentDescriptor(context, source.path) { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(source.pdfPage).use { page ->
                    val scale = minOf(targetWidth.toFloat() / page.width, 3200f / page.height,
                        sqrt(2_560_000f / (page.width.toFloat() * page.height)))
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
        else -> return@withLocalDocument decoded
    }
    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
    }
}
