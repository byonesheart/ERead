package com.eyue.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle
import kotlin.math.roundToInt

private const val PixelIconGrid = 16

/** Solid pixel glyphs: antialiased vector edges must never become enlarged gray blocks. */
@Composable
internal fun AppIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
                     tint: Color = LocalContentColor.current) {
    if (LocalAppThemeStyle.current != AppThemeStyle.PIXEL) {
        MaterialIcon(if (LocalAppThemeStyle.current == AppThemeStyle.XINGCAI) xingcaiIcon(imageVector) else imageVector,
            contentDescription, modifier, tint)
        return
    }
    val painter = rememberVectorPainter(imageVector)
    val filter by rememberUpdatedState(remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) })
    val pixels = remember(imageVector, painter) {
        Modifier.drawWithCache {
            val rows = pixelIconRows(imageVector.name.substringAfterLast('.'))
            val bitmap = if (rows != null) {
                solidPixelBitmap(rows, imageVector.autoMirror && layoutDirection == LayoutDirection.Rtl)
            } else {
                // Unknown icons still get a hard binary mask, rather than gray antialiasing.
                // Only the visible icon owns this tiny bitmap; tint changes only redraw it.
                val source = ImageBitmap(PixelIconGrid, PixelIconGrid)
                CanvasDrawScope().draw(this, layoutDirection, Canvas(source), Size(PixelIconGrid.toFloat(), PixelIconGrid.toFloat())) {
                    with(painter) { draw(size = size) }
                }
                val pixels = IntArray(PixelIconGrid * PixelIconGrid)
                source.readPixels(pixels)
                val mask = IntArray(PixelIconGrid)
                pixels.forEachIndexed { index, color ->
                    if (color ushr 24 >= 128) mask[index / PixelIconGrid] = mask[index / PixelIconGrid] or (1 shl (index % PixelIconGrid))
                }
                solidPixelBitmap(mask)
            }
            onDrawBehind {
                val edge = minOf(size.width, size.height).roundToInt().coerceAtLeast(1)
                drawImage(bitmap, dstOffset = IntOffset(((size.width - edge) / 2).roundToInt(), ((size.height - edge) / 2).roundToInt()),
                    dstSize = IntSize(edge, edge), colorFilter = filter, filterQuality = FilterQuality.None)
            }
        }
    }
    val description = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    }
    Box(modifier.size(24.dp).then(pixels).then(description))
}

private fun solidPixelBitmap(rows: IntArray, mirrored: Boolean = false): ImageBitmap {
    val pixels = IntArray(PixelIconGrid * PixelIconGrid) { index ->
        val x = if (mirrored) PixelIconGrid - 1 - index % PixelIconGrid else index % PixelIconGrid
        if (rows[index / PixelIconGrid] and (1 shl x) != 0) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
    }
    return Bitmap.createBitmap(pixels, PixelIconGrid, PixelIconGrid, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Integer-grid strokes author the app's actual controls, independent of Material's curved edges. */
private fun pixelIconRows(name: String): IntArray? {
    val glyph = PixelGlyph()
    with(glyph) {
        when (name) {
            "Search", "SearchOff" -> {
                disk(6, 6, 5); disk(6, 6, 3, false); line(10, 10, 14, 14, 2)
                if (name == "SearchOff") { line(2, 2, 13, 13, 2) }
            }
            "MenuBook", "AutoStories" -> {
                box(1, 3, 6, 12); box(9, 3, 14, 12); fill(6, 4, 9, 13)
                erase(7, 4, 8, 11); line(3, 6, 4, 6); line(11, 6, 12, 6); line(3, 9, 4, 9); line(11, 9, 12, 9)
                if (name == "AutoStories") { line(3, 1, 5, 1); line(10, 1, 12, 1) }
            }
            "LibraryBooks" -> { box(4, 1, 13, 12, 2); line(1, 4, 1, 14, 2); line(1, 14, 11, 14); line(7, 5, 10, 5); line(7, 8, 10, 8) }
            "BookmarkBorder", "Bookmarks" -> {
                fill(3, 1, 11, 14); erase(5, 3, 9, 14)
                line(4, 13, 7, 10); line(7, 10, 10, 13)
                if (name == "Bookmarks") { line(6, 0, 14, 0); line(14, 0, 14, 12) }
            }
            "Add" -> { line(7, 2, 7, 13, 2); line(2, 7, 13, 7, 2) }
            "Close" -> { line(3, 3, 12, 12, 2); line(3, 12, 12, 3, 2) }
            "Check" -> { line(2, 7, 6, 11, 2); line(6, 11, 13, 3, 2) }
            "DarkMode" -> { disk(7, 7, 6); disk(10, 4, 5, false) }
            "LightMode", "BrightnessAuto" -> {
                disk(7, 7, 3); disk(7, 7, 1, false)
                line(7, 0, 7, 1, 2); line(7, 13, 7, 14, 2); line(0, 7, 1, 7, 2); line(13, 7, 14, 7, 2)
                line(2, 2, 3, 3); line(11, 11, 12, 12); line(2, 12, 3, 11); line(11, 3, 12, 2)
                if (name == "BrightnessAuto") { erase(4, 4, 10, 10); line(5, 10, 7, 4); line(7, 4, 9, 10); line(6, 8, 8, 8) }
            }
            "Settings" -> {
                disk(7, 7, 5); fill(6, 0, 9, 3); fill(6, 12, 9, 15); fill(0, 6, 3, 9); fill(12, 6, 15, 9)
                fill(2, 2, 4, 4); fill(11, 2, 13, 4); fill(2, 11, 4, 13); fill(11, 11, 13, 13); disk(7, 7, 2, false)
            }
            "Tune" -> {
                line(1, 3, 14, 3); line(1, 7, 14, 7); line(1, 11, 14, 11)
                box(4, 1, 6, 5); box(10, 5, 12, 9); box(6, 9, 8, 13)
            }
            "Headphones" -> {
                disk(7, 7, 6); disk(7, 7, 4, false); erase(0, 8, 15, 15); fill(1, 7, 4, 13); fill(10, 7, 13, 13)
            }
            "PlayArrow" -> triangle(4, 3)
            "Pause" -> { fill(4, 3, 6, 12); fill(9, 3, 11, 12) }
            "FastForward", "FastRewind" -> { triangle(1, 3); triangle(8, 3); if (name == "FastRewind") mirror() }
            "SkipNext", "SkipPrevious" -> { triangle(2, 3); fill(12, 3, 13, 12); if (name == "SkipPrevious") mirror() }
            "ArrowForward", "ArrowBack" -> {
                line(2, 7, 12, 7, 2); line(8, 2, 13, 7, 2); line(8, 12, 13, 7, 2)
                if (name == "ArrowBack") mirror()
            }
            "ChevronRight" -> { line(5, 3, 10, 7, 2); line(5, 11, 10, 7, 2) }
            "ExpandMore", "ExpandLess" -> {
                line(3, 5, 7, 9, 2); line(7, 9, 11, 5, 2); if (name == "ExpandLess") flip()
            }
            "UnfoldMore" -> { line(4, 4, 7, 1); line(7, 1, 10, 4); line(4, 11, 7, 14); line(7, 14, 10, 11) }
            "SwapHoriz" -> {
                line(2, 4, 13, 4); line(10, 1, 13, 4); line(10, 7, 13, 4)
                line(2, 11, 13, 11); line(2, 11, 5, 8); line(2, 11, 5, 14)
            }
            "SwapVert" -> {
                line(4, 2, 4, 13); line(1, 5, 4, 2); line(4, 2, 7, 5)
                line(11, 2, 11, 13); line(8, 10, 11, 13); line(11, 13, 14, 10)
            }
            "MoreHoriz" -> { fill(2, 7, 3, 8); fill(7, 7, 8, 8); fill(12, 7, 13, 8) }
            "MoreVert" -> { fill(7, 2, 8, 3); fill(7, 7, 8, 8); fill(7, 12, 8, 13) }
            "Cloud", "CloudDone", "CloudDownload", "CloudUpload", "CloudSync" -> {
                cloud()
                when (name) {
                    "CloudDone" -> { erase(5, 8, 12, 12); line(5, 9, 7, 11); line(7, 11, 12, 6) }
                    "CloudDownload", "CloudUpload" -> {
                        erase(5, 7, 10, 12); line(7, 7, 7, 14, 2)
                        if (name == "CloudDownload") { line(4, 11, 7, 14); line(7, 14, 10, 11) }
                        else { line(4, 10, 7, 7); line(7, 7, 10, 10) }
                    }
                    "CloudSync" -> { erase(4, 6, 11, 11); line(4, 7, 10, 7); line(8, 5, 10, 7); line(5, 10, 11, 10); line(5, 10, 7, 12) }
                }
            }
            "FolderOpen" -> { box(1, 4, 14, 13); line(1, 2, 6, 2); line(1, 2, 1, 4); line(6, 2, 8, 4); line(4, 7, 14, 7); line(4, 7, 2, 13) }
            "DeleteOutline" -> { box(4, 4, 11, 13, 2); line(2, 2, 13, 2, 2); line(6, 0, 9, 0); line(6, 6, 6, 10); line(9, 6, 9, 10) }
            "Description", "Feedback" -> {
                box(3, 1, 12, 14, 2)
                if (name == "Description") { line(6, 5, 9, 5); line(6, 8, 9, 8); line(6, 11, 9, 11) }
                else { line(7, 4, 7, 8, 2); line(7, 10, 8, 10); erase(3, 12, 10, 14); line(3, 11, 3, 14); line(3, 14, 6, 11) }
            }
            "Edit", "BorderColor", "EditNote" -> {
                line(3, 11, 10, 4, 3); line(11, 2, 13, 4, 2); fill(2, 12, 4, 14)
                if (name == "BorderColor") line(1, 15, 14, 15)
                if (name == "EditNote") { line(1, 3, 6, 3); line(1, 6, 4, 6) }
            }
            "FormatUnderlined" -> { line(3, 2, 3, 9, 2); line(10, 2, 10, 9, 2); line(5, 11, 9, 11, 2); line(1, 15, 14, 15) }
            "GridView" -> { box(2, 2, 6, 6); box(9, 2, 13, 6); box(2, 9, 6, 13); box(9, 9, 13, 13) }
            "ViewList", "Checklist" -> {
                for (y in listOf(3, 7, 11)) { fill(2, y, 3, y + 1); line(6, y, 13, y, 2) }
                if (name == "Checklist") { line(0, 3, 1, 4); line(1, 4, 4, 1); line(0, 11, 1, 12); line(1, 12, 4, 9) }
            }
            "Sort" -> { line(2, 3, 13, 3, 2); line(2, 7, 10, 7, 2); line(2, 11, 7, 11, 2) }
            "Info", "ErrorOutline", "CheckCircle" -> {
                disk(7, 7, 6); disk(7, 7, 4, false)
                when (name) {
                    "Info" -> { fill(7, 3, 8, 4); fill(7, 6, 8, 11) }
                    "ErrorOutline" -> { fill(7, 3, 8, 8); fill(7, 10, 8, 11) }
                    else -> { line(3, 7, 6, 10); line(6, 10, 11, 4) }
                }
            }
            "OpenInNew" -> { box(2, 4, 11, 13); erase(8, 4, 11, 7); line(6, 9, 13, 2, 2); line(9, 1, 14, 1); line(14, 1, 14, 6) }
            "Palette" -> { disk(7, 7, 6); disk(4, 5, 1, false); disk(8, 3, 1, false); disk(11, 6, 1, false); disk(4, 9, 1, false); disk(11, 11, 3, false) }
            "History" -> { disk(8, 8, 6); disk(8, 8, 4, false); line(8, 4, 8, 8); line(8, 8, 11, 9); line(1, 1, 1, 5); line(1, 5, 5, 5) }
            "RecordVoiceOver" -> { disk(6, 5, 3); fill(2, 10, 10, 13); line(12, 3, 13, 4); line(13, 4, 13, 7); line(12, 8, 13, 7); line(15, 2, 15, 9) }
            "FavoriteBorder" -> { disk(5, 5, 3); disk(10, 5, 3); for (y in 7..13) line(y - 5, y, 20 - y, y); disk(5, 5, 1, false); disk(10, 5, 1, false); for (y in 7..10) erase(y - 3, y, 18 - y, y) }
            "CleaningServices" -> { line(9, 1, 5, 8, 2); line(5, 7, 1, 14); line(5, 7, 11, 14); line(1, 14, 11, 14); line(5, 10, 4, 13); line(7, 11, 7, 13) }
            else -> return null
        }
    }
    return glyph.rows
}

private class PixelGlyph {
    val rows = IntArray(PixelIconGrid)
    private fun dot(x: Int, y: Int, set: Boolean = true) {
        if (x !in 0 until PixelIconGrid || y !in 0 until PixelIconGrid) return
        rows[y] = if (set) rows[y] or (1 shl x) else rows[y] and (1 shl x).inv()
    }
    fun fill(left: Int, top: Int, right: Int, bottom: Int, set: Boolean = true) {
        for (y in top..bottom) for (x in left..right) dot(x, y, set)
    }
    fun erase(left: Int, top: Int, right: Int, bottom: Int) = fill(left, top, right, bottom, false)
    fun line(fromX: Int, fromY: Int, toX: Int, toY: Int, width: Int = 1) {
        val steps = maxOf(kotlin.math.abs(toX - fromX), kotlin.math.abs(toY - fromY)).coerceAtLeast(1)
        for (step in 0..steps) {
            val x = (fromX + (toX - fromX) * step.toFloat() / steps).roundToInt()
            val y = (fromY + (toY - fromY) * step.toFloat() / steps).roundToInt()
            fill(x, y, x + width - 1, y + width - 1)
        }
    }
    fun box(left: Int, top: Int, right: Int, bottom: Int, width: Int = 1) {
        fill(left, top, right, bottom); erase(left + width, top + width, right - width, bottom - width)
    }
    fun disk(cx: Int, cy: Int, radius: Int, set: Boolean = true) {
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) dot(x, y, set)
    }
    fun triangle(left: Int, top: Int) {
        for (row in 0..9) fill(left, top + row, left + 5 - kotlin.math.abs(4 - row), top + row)
    }
    fun mirror() { for (y in rows.indices) rows[y] = Integer.reverse(rows[y]) ushr (32 - PixelIconGrid) }
    fun flip() { rows.reverse() }
    fun cloud() { disk(7, 5, 4); disk(3, 8, 3); disk(12, 8, 3); fill(3, 8, 12, 11); disk(7, 5, 2, false); erase(3, 7, 12, 9) }
}
