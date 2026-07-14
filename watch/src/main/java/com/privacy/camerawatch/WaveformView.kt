package com.privacy.camerawatch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Animated amber mic waveform bars. */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val bars = 22
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var anim: ValueAnimator? = null

    private fun ensureAnim() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) ensureAnim() else { anim?.cancel(); anim = null }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#fbbf24"), Color.parseColor("#f59e0b"), Shader.TileMode.CLAMP)
        val gap = w * 0.012f
        val bw = (w - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val amp = 0.22f + 0.78f * abs(sin(t + i * 0.5f))
            val bh = h * amp
            val x = i * (bw + gap)
            rect.set(x, h - bh, x + bw, h)
            canvas.drawRoundRect(rect, bw * 0.4f, bw * 0.4f, p)
        }
    }
}
