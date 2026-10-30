package com.eyue.reader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReadingMode
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private const val PAGE_TURN_MILLIS = 180
private const val PAGE_CURL_MILLIS = 300

internal data class ReaderPageLocation(val chapter: Int, val page: Int) : Comparable<ReaderPageLocation> {
    override fun compareTo(other: ReaderPageLocation): Int =
        compareValuesBy(this, other, ReaderPageLocation::chapter, ReaderPageLocation::page)
}

/** Direct dragging still works; there is no animated inertia to leave trails on an e-ink panel. */
internal val ReaderEinkFlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = initialVelocity
}

/** A tablet must remain wide even with system bars; a landscape phone never gets two narrow pages. */
internal fun readerUseDualPage(enabled: Boolean, mode: ReadingMode, window: IntSize, density: Density): Boolean =
    enabled && mode != ReadingMode.SCROLL && window.width > window.height &&
        min(window.width, window.height) / density.density >= 600f

internal fun readerPageViewport(bodyViewport: IntSize, dualPage: Boolean, density: Density): IntSize =
    if (dualPage) IntSize(((bodyViewport.width - with(density) { 1.dp.roundToPx() }) / 2).coerceAtLeast(0), bodyViewport.height)
    else bodyViewport

internal fun readerSpreadStart(page: Int, dualPage: Boolean): Int =
    if (dualPage) page.coerceAtLeast(0) / 2 * 2 else page.coerceAtLeast(0)

internal fun readerPageIsVisible(chapter: Int, page: Int, visibleChapter: Int, visiblePage: Int, dualPage: Boolean): Boolean =
    chapter == visibleChapter && readerSpreadStart(page, dualPage) == readerSpreadStart(visiblePage, dualPage)

/** Progress counts the last visible physical page, while the saved focus/character anchor stays unchanged. */
internal fun readerVisiblePageEnd(page: Int, pageCount: Int, dualPage: Boolean): Int =
    (readerSpreadStart(page, dualPage) + if (dualPage) 2 else 1).coerceIn(0, pageCount.coerceAtLeast(0))

/** Chapter starts stay on a fresh spread, so late page counts in earlier chapters cannot move a visible spread. */
internal fun readerAdjacentPage(chapter: Int, page: Int, counts: List<Int>, direction: Int,
                                dualPage: Boolean): ReaderPageLocation? {
    if (chapter !in counts.indices || direction == 0) return null
    val step = if (dualPage) 2 else 1
    val target = readerSpreadStart(page, dualPage) + if (direction > 0) step else -step
    if (target in 0 until counts[chapter]) return ReaderPageLocation(chapter, target)
    return if (direction > 0 && chapter < counts.lastIndex) ReaderPageLocation(chapter + 1, 0)
        else if (direction < 0 && chapter > 0) ReaderPageLocation(chapter - 1,
            readerSpreadStart((counts[chapter - 1] - 1).coerceAtLeast(0), dualPage)) else null
}

/** AnimatedContent retains each outgoing slot's assigned z-index. Every next sheet must
 * therefore go below the previous sheet, including a second forward turn, rather than reusing -1. */
private class ReaderPaperOrder(private var anchor: ReaderPageLocation) {
    var forward = true
        private set
    var targetZ = 0f
        private set

    fun moveTo(target: ReaderPageLocation) {
        if (target == anchor) return
        forward = target > anchor
        targetZ += if (forward) -1f else 1f
        anchor = target
    }
}

@Composable
internal fun ReaderPageTurnFrame(
    anchor: ReaderPageLocation, dualPage: Boolean, chapterPageCounts: List<Int>, style: PageTurnStyle,
    vertical: Boolean, einkMode: Boolean, paper: Color,
    backgroundContent: @Composable () -> Unit = {},
    drag: ReaderPaperDrag? = null,
    content: @Composable (chapter: Int, page: Int, interactive: Boolean) -> Unit
) {
    val spread: @Composable (ReaderPageLocation, Boolean) -> Unit = { location, interactive ->
        Box(Modifier.fillMaxSize().background(paper)) {
        backgroundContent()
        Row(Modifier.fillMaxSize().testTag(if (dualPage) "reader_dual_page" else "reader_single_page")) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content(location.chapter, location.page, interactive) }
            if (dualPage) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(if (einkMode) Color.Black else Color.Gray.copy(alpha = .2f)))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (location.page + 1 < (chapterPageCounts.getOrNull(location.chapter) ?: 0))
                        content(location.chapter, location.page + 1, interactive)
                }
            }
        }
        }
    }
    if (drag != null) {
        val forward = drag.direction > 0
        val visibility = remember(drag) { derivedStateOf { if (forward) 1f - drag.fraction else drag.fraction } }
        Box(Modifier.fillMaxSize().clipToBounds().testTag("reader_paper_drag")) {
            if (style == PageTurnStyle.SLIDE) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = -drag.direction * drag.fraction * size.width }) { spread(drag.origin, false) }
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = drag.direction * (1f - drag.fraction) * size.width }) { spread(drag.target, false) }
            } else {
                spread(if (forward) drag.target else drag.origin, false)
                Box(Modifier.fillMaxSize().then(if (style == PageTurnStyle.CURL) Modifier.readerPaperFold(visibility, false, paper)
                    else Modifier.graphicsLayer { translationX = -visibility.let { 1f - it.value } * size.width })) {
                    spread(if (forward) drag.origin else drag.target, false)
                }
            }
        }
        return
    }
    if (einkMode || vertical) {
        spread(anchor, true)
        return
    }
    key(style, vertical, dualPage) {
    val turn = updateTransition(anchor, label = "reader page turn")
    val paperOrder = remember { ReaderPaperOrder(anchor) }.apply { moveTo(anchor) }
    val forward = paperOrder.forward
    turn.AnimatedContent(Modifier.fillMaxSize().clipToBounds().testTag("reader_animation_${style.name.lowercase()}"),
        transitionSpec = {
            val sign = if (paperOrder.forward) 1 else -1
            when (style) {
                PageTurnStyle.SLIDE -> if (vertical)
                    slideInVertically(tween(PAGE_TURN_MILLIS)) { sign * it } togetherWith slideOutVertically(tween(PAGE_TURN_MILLIS)) { -sign * it }
                else slideInHorizontally(tween(PAGE_TURN_MILLIS)) { sign * it } togetherWith slideOutHorizontally(tween(PAGE_TURN_MILLIS)) { -sign * it }
                PageTurnStyle.COVER -> if (sign > 0) {
                    (fadeIn(tween(PAGE_TURN_MILLIS), initialAlpha = 1f) togetherWith
                        if (vertical) slideOutVertically(tween(PAGE_TURN_MILLIS)) { -it } else slideOutHorizontally(tween(PAGE_TURN_MILLIS)) { -it })
                        .apply { targetContentZIndex = paperOrder.targetZ }
                } else {
                    ((if (vertical) slideInVertically(tween(PAGE_TURN_MILLIS)) { -it } else slideInHorizontally(tween(PAGE_TURN_MILLIS)) { -it })
                        togetherWith fadeOut(tween(PAGE_TURN_MILLIS), targetAlpha = 1f)).apply { targetContentZIndex = paperOrder.targetZ }
                }
                // Keep both live pages during the fold; the custom drawing clips the front,
                // mirrors its printing on the curved back and shades the crease instead of fading it.
                PageTurnStyle.CURL -> (fadeIn(tween(PAGE_CURL_MILLIS), initialAlpha = 1f) togetherWith
                    fadeOut(tween(PAGE_CURL_MILLIS), targetAlpha = 1f)).apply { targetContentZIndex = paperOrder.targetZ }
            }
        }) { location ->
        val visibility = transition.animateFloat(transitionSpec = { tween(if (style == PageTurnStyle.CURL) PAGE_CURL_MILLIS else PAGE_TURN_MILLIS) },
            label = "paper visibility") { state -> if (state == EnterExitState.Visible) 1f else 0f }
        val turningPaper = if (forward) location != anchor else location == anchor
        Box(Modifier.fillMaxSize().then(if (style == PageTurnStyle.CURL && turningPaper)
            Modifier.zIndex(1f).readerPaperFold(visibility, vertical, paper) else Modifier)) {
            spread(location, location == anchor && !turn.isRunning)
        }
    }
    }
}

/** A diagonal, curved fold with mirrored print and a shaded paper back, in either paging direction. */
private fun Modifier.readerPaperFold(visibility: State<Float>, vertical: Boolean, paper: Color): Modifier = drawWithContent {
    val amount = (1f - visibility.value).coerceIn(0f, 1f)
    if (amount <= .001f) { drawContent(); return@drawWithContent }
    if (amount >= .999f) return@drawWithContent
    val axis = if (vertical) size.height else size.width
    val cross = if (vertical) size.width else size.height
    val cut = axis * (1f - amount)
    val curve = sin(amount * PI).toFloat() * axis * .11f
    fun point(u: Float, v: Float) = if (vertical) Offset(v, u) else Offset(u, v)
    fun Path.to(u: Float, v: Float) { val p = point(u, v); lineTo(p.x, p.y) }
    val front = Path().apply {
        moveTo(0f, 0f); to(cut + curve * .5f, 0f); to(cut - curve * .5f, cross)
        to(0f, cross); close()
    }
    clipPath(front) { this@drawWithContent.drawContent() }
    val flap = Path().apply {
        val first = point(cut + curve * .5f, 0f)
        moveTo(first.x, first.y); to(cut - curve * .5f, cross)
        val c1 = point(cut - curve * 1.25f, cross * .98f)
        val end1 = point(cut - curve * 1.8f, cross * .93f)
        quadraticBezierTo(c1.x, c1.y, end1.x, end1.y)
        to(cut - curve * .8f, cross * .04f)
        val c2 = point(cut - curve * .25f, -cross * .01f)
        quadraticBezierTo(c2.x, c2.y, first.x, first.y); close()
    }
    val shadowStart = point(cut - curve * 2.2f, cross * .5f)
    val shadowEnd = point(cut + curve * .4f, cross * .5f)
    clipPath(front) {
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .23f)), shadowStart, shadowEnd))
    }
    drawPath(flap, paper)
    clipPath(flap) {
        withTransform({
            if (vertical) { translate(top = cut * 2f); scale(1f, -1f, Offset.Zero) }
            else { translate(left = cut * 2f); scale(-1f, 1f, Offset.Zero) }
        }) { this@drawWithContent.drawContent() }
        drawPath(flap, paper.copy(alpha = .88f))
    }
    drawPath(flap, Brush.linearGradient(listOf(Color.Black.copy(alpha = .18f), Color.White.copy(alpha = .12f),
        Color.Black.copy(alpha = .26f)), point(cut - curve * 1.7f, cross * .5f), point(cut + curve * .5f, cross * .5f)))
}
