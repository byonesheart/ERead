package com.eyue.reader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun BookLoadingAnimation(modifier: Modifier = Modifier, animate: Boolean = true) {
    val turn = if (animate) rememberInfiniteTransition(label = "Book loading").animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(950, delayMillis = 150, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "Turning page") else null
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.size(56.dp, 44.dp).testTag("book_loading_animation").semantics { contentDescription = "正在加载" }) {
        val width = size.width
        val height = size.height
        val center = width * .5f
        val top = height * .28f
        val bottom = height * .87f
        val half = width * .38f
        val stroke = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
        val book = Path().apply {
            moveTo(center, top)
            quadraticTo(width * .32f, height * .08f, center - half, height * .21f)
            lineTo(center - half, height * .79f)
            quadraticTo(width * .32f, height * .69f, center, bottom)
            quadraticTo(width * .68f, height * .69f, center + half, height * .79f)
            lineTo(center + half, height * .21f)
            quadraticTo(width * .68f, height * .08f, center, top)
            close()
        }
        drawPath(book, colors.primaryContainer)
        drawPath(book, colors.primary, style = stroke)
        val phase = turn?.value ?: .5f
        val lift = sin(phase * Math.PI).toFloat() * height * .24f
        val reach = half * (1f - 2f * phase)
        val page = Path().apply {
            moveTo(center, top)
            quadraticTo(center + reach * .55f, top - lift, center + reach, top - abs(reach) / half * height * .07f - lift)
            lineTo(center + reach, bottom - abs(reach) / half * height * .08f - lift)
            quadraticTo(center + reach * .5f, bottom - lift, center, bottom)
            close()
        }
        drawPath(page, colors.surface)
        drawPath(page, colors.primary, style = stroke)
    }
}
