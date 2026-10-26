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
import android.graphics.PorterDuff
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import com.eyue.reader.data.PageTurnStyle
import kotlin.math.PI
import kotlin.math.sin

/** Two bounded viewport snapshots; hand back to Chromium only after its target frame has drawn. */
internal open class EbookPageTurnWebView(context: Context) : WebView(context) {
    private enum class TurnPhase { IDLE, WAITING_TARGET, ANIMATING, SETTLING, SETTLING_FRAME }
    private var snapshot: Bitmap? = null
    private var targetSnapshot: Bitmap? = null
    private var drawingPage = false
    private var turn: ValueAnimator? = null
    private var fraction = 1f
    private var phase = TurnPhase.IDLE
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
        val old = capturePage()
        snapshot = old; direction = if (forward < 0) -1 else 1; style = animation; fraction = 0f; phase = TurnPhase.WAITING_TARGET
        evaluateJavascript("ERead.command({type:'page',page:$page})") {
            if (snapshot !== old) return@evaluateJavascript
            // JS completion precedes Chromium's display-list commit. Starting the
            // animation there draws the old page twice for its first few frames.
            postVisualStateCallback(0, object : VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (snapshot !== old) return
                    targetSnapshot = capturePage()
                    phase = TurnPhase.ANIMATING
                    turn = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = if (style == PageTurnStyle.CURL) 280 else 180
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (snapshot === old && phase == TurnPhase.ANIMATING) { phase = TurnPhase.SETTLING; invalidate() }
                            }
                        })
                        start()
                    }
                }
            })
        }
        invalidate()
    }

    fun holdPage() {
        finishTurn()
        if (width <= 0 || height <= 0) return
        val old = capturePage()
        snapshot = old
        phase = TurnPhase.WAITING_TARGET
        invalidate()
    }

    fun finishTurn() { phase = TurnPhase.IDLE; turn?.cancel(); turn = null; releaseSnapshot() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) finishTurn()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    private fun releaseSnapshot() {
        snapshot?.recycle(); snapshot = null; targetSnapshot?.recycle(); targetSnapshot = null; fraction = 1f; phase = TurnPhase.IDLE; invalidate()
    }

    protected open fun drawWebContent(canvas: Canvas) {
        drawingPage = true
        try { super.draw(canvas) } finally { drawingPage = false }
    }

    private fun capturePage(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        drawWebContent(Canvas(it).apply { translate(-scrollX.toFloat(), -scrollY.toFloat()) })
    }

    override fun onDraw(canvas: Canvas) {
        // View.draw supplies the WebView's background and clipping; omit only our transition overlay during capture.
        if (drawingPage) { super.onDraw(canvas); return }
        val old = snapshot
        if ((phase == TurnPhase.WAITING_TARGET || phase == TurnPhase.SETTLING || phase == TurnPhase.SETTLING_FRAME) && old != null) {
            // Chromium must still draw to acknowledge postVisualStateCallback.
            // Keep that draw offscreen, otherwise waiting on it while returning
            // early from onDraw can leave the old snapshot on screen indefinitely.
            val layer = canvas.saveLayer(null, null)
            drawWebContent(canvas)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.save(); canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            canvas.drawBitmap(if (phase == TurnPhase.WAITING_TARGET) old else targetSnapshot ?: old, 0f, 0f, bitmapPaint); canvas.restore()
            canvas.restoreToCount(layer)
            if (phase == TurnPhase.SETTLING) {
                phase = TurnPhase.SETTLING_FRAME
                postVisualStateCallback(0, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) { postOnAnimation {
                        if (snapshot === old && phase == TurnPhase.SETTLING_FRAME) releaseSnapshot()
                    } }
                })
            }
            return
        }
        val target = targetSnapshot
        if (old == null || target == null) { drawWebContent(canvas); return }
        val distance = width.toFloat()
        fun oldAt(x: Float, left: Float = 0f, right: Float = distance) {
            canvas.save(); canvas.clipRect(scrollX + left, scrollY.toFloat(), scrollX + right, scrollY + height.toFloat())
            canvas.translate(scrollX + x, scrollY.toFloat()); canvas.drawBitmap(old, 0f, 0f, bitmapPaint); canvas.restore()
        }
        fun currentAt(x: Float, left: Float, right: Float) {
            canvas.save(); canvas.clipRect(scrollX + left, scrollY.toFloat(), scrollX + right, scrollY + height.toFloat())
            canvas.translate(scrollX + x, scrollY.toFloat()); canvas.drawBitmap(target, 0f, 0f, bitmapPaint); canvas.restore()
        }
        when (style) {
            PageTurnStyle.SLIDE -> {
                val cut = if (direction > 0) distance * (1f - fraction) else distance * fraction
                oldAt(-direction * distance * fraction, if (direction > 0) 0f else cut, if (direction > 0) cut else distance)
                currentAt(direction * distance * (1f - fraction), if (direction > 0) cut else 0f, if (direction > 0) distance else cut)
            }
            PageTurnStyle.COVER -> if (direction > 0) {
                val cut = distance * (1f - fraction)
                currentAt(0f, cut, distance); oldAt(-distance * fraction, 0f, cut)
            } else {
                val cut = distance * fraction
                oldAt(0f, cut, distance); currentAt(-distance * (1f - fraction), 0f, cut)
            }
            PageTurnStyle.CURL -> {
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
                canvas.save(); canvas.clipOutPath(front)
                if (direction < 0) { canvas.scale(-1f, 1f); canvas.translate(-distance, 0f) }
                canvas.drawBitmap(target, 0f, 0f, bitmapPaint); canvas.restore()
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
