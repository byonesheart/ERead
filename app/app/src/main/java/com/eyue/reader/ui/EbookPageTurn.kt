package com.eyue.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import com.eyue.reader.data.PageTurnStyle
import kotlin.math.PI
import kotlin.math.sin

/** One bounded viewport snapshot; publisher DOM stays mounted throughout a page turn. */
internal open class EbookPageTurnWebView(context: Context) : WebView(context) {
    private var snapshot: Bitmap? = null
    private var turn: ValueAnimator? = null
    private var fraction = 1f
    private var direction = 1
    private var style = PageTurnStyle.SLIDE
    var paperColor: Int = android.graphics.Color.WHITE
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val front = Path()
    private val flap = Path()
    private val foldMatrix = Matrix()
    private val foldShade = LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0x34000000, 0x20ffffff, 0x42000000), null, Shader.TileMode.CLAMP)

    fun turnPage(page: Int, forward: Int, animation: PageTurnStyle) {
        finishTurn()
        if (width <= 0 || height <= 0) { evaluateJavascript("ERead.command({type:'page',page:$page})", null); return }
        val old = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val capture = Canvas(old).apply { translate(-scrollX.toFloat(), -scrollY.toFloat()) }
        draw(capture)
        snapshot = old; direction = if (forward < 0) -1 else 1; style = animation; fraction = 0f
        evaluateJavascript("ERead.command({type:'page',page:$page})") {
            if (snapshot !== old) return@evaluateJavascript
            turn = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (style == PageTurnStyle.CURL) 280 else 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (snapshot === old) releaseSnapshot() }
                })
                start()
            }
        }
        invalidate()
    }

    fun finishTurn() { turn?.cancel(); turn = null; releaseSnapshot() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) finishTurn()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun releaseSnapshot() {
        snapshot?.recycle(); snapshot = null; fraction = 1f; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val old = snapshot
        if (old == null || fraction >= 1f) { super.onDraw(canvas); return }
        val distance = width.toFloat()
        fun oldAt(x: Float) {
            canvas.save(); canvas.translate(scrollX + x, scrollY.toFloat()); canvas.drawBitmap(old, 0f, 0f, bitmapPaint); canvas.restore()
        }
        fun currentAt(x: Float) {
            canvas.save(); canvas.translate(x, 0f); super.onDraw(canvas); canvas.restore()
        }
        when (style) {
            PageTurnStyle.SLIDE -> {
                oldAt(-direction * distance * fraction)
                currentAt(direction * distance * (1f - fraction))
            }
            PageTurnStyle.COVER -> if (direction > 0) {
                super.onDraw(canvas); oldAt(-distance * fraction)
            } else {
                oldAt(0f); currentAt(-distance * (1f - fraction))
            }
            PageTurnStyle.CURL -> {
                super.onDraw(canvas)
                canvas.save()
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                if (direction < 0) { canvas.translate(distance, 0f); canvas.scale(-1f, 1f) }
                val cut = distance * (1f - fraction)
                val curve = sin(fraction * PI).toFloat() * distance * .11f
                val bottom = height.toFloat()
                front.rewind()
                front.apply {
                    moveTo(0f, 0f); lineTo(cut + curve * .5f, 0f); lineTo(cut - curve * .5f, bottom); lineTo(0f, bottom); close()
                }
                fun printOld() {
                    canvas.save()
                    if (direction < 0) { canvas.translate(distance, 0f); canvas.scale(-1f, 1f) }
                    canvas.drawBitmap(old, 0f, 0f, bitmapPaint); canvas.restore()
                }
                canvas.save(); canvas.clipPath(front); printOld(); canvas.restore()
                flap.rewind()
                flap.apply {
                    moveTo(cut + curve * .5f, 0f); lineTo(cut - curve * .5f, bottom)
                    quadTo(cut - curve * 1.25f, bottom * .98f, cut - curve * 1.8f, bottom * .93f)
                    lineTo(cut - curve * .8f, bottom * .04f)
                    quadTo(cut - curve * .25f, -bottom * .01f, cut + curve * .5f, 0f); close()
                }
                shade.shader = null; shade.color = paperColor; canvas.drawPath(flap, shade)
                canvas.save(); canvas.clipPath(flap); canvas.translate(cut * 2f, 0f); canvas.scale(-1f, 1f)
                bitmapPaint.alpha = 55; printOld(); bitmapPaint.alpha = 255; canvas.restore()
                foldMatrix.setScale((curve * 2.2f).coerceAtLeast(.01f), 1f)
                foldMatrix.postTranslate(cut - curve * 1.7f, 0f)
                foldShade.setLocalMatrix(foldMatrix)
                shade.shader = foldShade
                canvas.drawPath(flap, shade); shade.shader = null; canvas.restore()
            }
        }
    }
}
