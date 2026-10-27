package com.eyue.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶栏图标。设计稿不是单色线性图标：月牙+星、实心书签、浅蓝底加号都是多色形状，
 * ImageVector 上不了双色，所以直接用 Canvas 画。
 */

private const val MOON_PATH = "M18.9 3.9 C13.1 2.6 7.4 6.8 7.4 12.4 C7.4 18 13.1 22.2 18.9 20.9 " +
    "C14.4 18.4 11.9 15.7 11.9 12.4 C11.9 9.1 14.4 6.4 18.9 3.9 Z"
private const val STAR_PATH = "M17.4 2.4 L18.3 4.6 L20.5 5.5 L18.3 6.4 L17.4 8.6 L16.5 6.4 L14.3 5.5 L16.5 4.6 Z"
private const val BOOKMARK_PATH = "M6.6 5 A2.8 2.8 0 0 1 9.4 2.2 H14.6 A2.8 2.8 0 0 1 17.4 5 " +
    "V21.6 L12 17.4 L6.6 21.6 Z"

private val Violet = Color(0xFFA78BFA)
private val Amber = Color(0xFFFFC24B)
private val Coral = Color(0xFFFF8B7B)

/** 把 24 网格的 SVG 路径缩放到当前尺寸后绘制。 */
private fun DrawScope.drawScaled(path: String, color: Color) {
    val scale = size.minDimension / 24f
    val p = PathParser().parsePathString(path).toPath()
    withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { drawPath(p, color) }
}

/** 日夜切换：夜间=紫罗兰月牙 + 琥珀星；日间=琥珀太阳。 */
@Composable internal fun XingcaiNightModeGlyph(night: Boolean, modifier: Modifier = Modifier, glyphSize: Dp = 24.dp) {
    val monochrome = LocalAppEinkMode.current
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(glyphSize)) {
        if (night) {
            drawScaled(MOON_PATH, if (monochrome) ink else Violet)
            drawScaled(STAR_PATH, if (monochrome) ink else Amber)
        } else {
            val c = center
            val r = size.minDimension * .19f
            drawCircle(if (monochrome) ink else Amber, r, c)
            val inner = size.minDimension * .30f
            val outer = size.minDimension * .40f
            repeat(8) { i ->
                val a = Math.toRadians(i * 45.0)
                drawLine(if (monochrome) ink else Amber,
                    Offset(c.x + (inner * Math.cos(a)).toFloat(), c.y + (inner * Math.sin(a)).toFloat()),
                    Offset(c.x + (outer * Math.cos(a)).toFloat(), c.y + (outer * Math.sin(a)).toFloat()),
                    strokeWidth = size.minDimension * .045f)
            }
        }
    }
}

/** 书签与笔记：珊瑚实心书签 + 白色圆点。 */
@Composable internal fun XingcaiNotesGlyph(modifier: Modifier = Modifier, glyphSize: Dp = 24.dp) {
    val monochrome = LocalAppEinkMode.current
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(glyphSize)) {
        drawScaled(BOOKMARK_PATH, if (monochrome) ink else Coral)
        val s = size.minDimension / 24f
        drawCircle(Color.White, 1.5f * s, Offset(12f * s, 8.5f * s))
    }
}

/** 导入：浅蓝圆角方 + 主色加号。 */
@Composable internal fun XingcaiImportGlyph(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tile = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.size(32.dp).clip(RoundedCornerShape(11.dp))
        .background(if (enabled) tile else tile.copy(alpha = .5f)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val s = size.minDimension / 24f
            val color = if (enabled) accent else accent.copy(alpha = .45f)
            val stroke = 2.1f * s
            drawLine(color, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), stroke)
            drawLine(color, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), stroke)
        }
    }
}
