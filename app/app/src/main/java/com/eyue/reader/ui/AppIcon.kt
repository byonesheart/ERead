package com.eyue.reader.ui

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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle
import kotlin.math.roundToInt

/** Pixelate the glyph, rather than only recoloring the ordinary Material icon. */
@Composable
internal fun AppIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
                     tint: Color = LocalContentColor.current) {
    if (LocalAppThemeStyle.current != AppThemeStyle.PIXEL) {
        MaterialIcon(imageVector, contentDescription, modifier, tint)
        return
    }
    val painter = rememberVectorPainter(imageVector)
    val filter by rememberUpdatedState(remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) })
    val pixels = remember(imageVector, painter) {
        Modifier.drawWithCache {
            // One small bitmap per visible icon. Cache observes vector invalidations; tint and
            // animation frames only redraw the cached pixels, with no decoding or rasterization.
            val grid = 16
            val bitmap = ImageBitmap(grid, grid)
            CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), Size(grid.toFloat(), grid.toFloat())) {
                with(painter) { draw(size = size) }
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
