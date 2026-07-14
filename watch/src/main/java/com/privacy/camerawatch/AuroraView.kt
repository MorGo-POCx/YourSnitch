package com.privacy.camerawatch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Slowly drifting aurora glow blobs. Shaders are cached and translated (not rebuilt) per frame. */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val aur1 = Color.parseColor("#8C3b56f6")
    private val aur2 = Color.parseColor("#802563eb")
    private val aur3 = Color.parseColor("#527c5cf6")

    private var sh1: RadialGradient? = null
    private var sh2: RadialGradient? = null
    private var sh3: RadialGradient? = null
    private var r1 = 0f
    private var r2 = 0f
    private var r3 = 0f

    private var t = 0f
    private var anim: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        r1 = fw * 0.62f; sh1 = RadialGradient(0f, 0f, r1, aur1, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        r2 = fw * 0.66f; sh2 = RadialGradient(0f, 0f, r2, aur2, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        r3 = fw * 0.5f;  sh3 = RadialGradient(0f, 0f, r3, aur3, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 26000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    private fun blob(canvas: Canvas, sh: RadialGradient?, r: Float, cx: Float, cy: Float) {
        if (sh == null) return
        m.setTranslate(cx, cy)
        sh.setLocalMatrix(m)
        p.shader = sh
        canvas.drawCircle(cx, cy, r, p)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        blob(canvas, sh1, r1, w * (0.18f + 0.12f * sin(t)), h * (0.12f + 0.06f * cos(t * 0.8f)))
        blob(canvas, sh2, r2, w * (0.86f + 0.10f * cos(t * 0.9f)), h * (0.9f + 0.05f * sin(t)))
        blob(canvas, sh3, r3, w * (0.5f + 0.14f * sin(t * 0.7f)), h * (0.42f + 0.08f * cos(t)))
    }
}
