package com.privacy.camerawatch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Radar shield-eye logo: gradient shield, dark inset, two pulsing radar rings, an eye. */
class SnitchMarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val accent = Color.parseColor("#5b7cfa")
    private val accent2 = Color.parseColor("#8ea6ff")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shieldPath = Path()
    private val innerPath = Path()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun ensureAnim() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedFraction; invalidate() }
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

    private fun poly(path: Path, l: Float, t: Float, w: Float, h: Float) {
        path.reset()
        path.moveTo(l + 0.5f * w, t)
        path.lineTo(l + w, t + 0.15f * h)
        path.lineTo(l + w, t + 0.52f * h)
        path.lineTo(l + 0.5f * w, t + h)
        path.lineTo(l, t + 0.52f * h)
        path.lineTo(l, t + 0.15f * h)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val pad = vw * 0.06f
        val w = vw - 2 * pad
        val h = vh - 2 * pad
        val l = pad
        val t = pad
        val cx = l + w / 2f
        val cy = t + h * 0.5f

        poly(shieldPath, l, t, w, h)
        fill.shader = LinearGradient(l, t, l + w, t + h, accent2, accent, Shader.TileMode.CLAMP)
        canvas.drawPath(shieldPath, fill)

        val inset = w * 0.06f
        poly(innerPath, l + inset, t + inset, w - 2 * inset, h - 2 * inset)
        fill.shader = LinearGradient(l, t, l + w, t + h, Color.parseColor("#0c1626"), Color.parseColor("#0a1120"), Shader.TileMode.CLAMP)
        canvas.drawPath(innerPath, fill)
        fill.shader = null

        val baseR = w * 0.227f
        ring.strokeWidth = w * 0.022f
        drawRing(canvas, cx, cy, phase, baseR)
        drawRing(canvas, cx, cy, (phase + 0.5f) % 1f, baseR)

        val eyeR = w * 0.19f
        fill.shader = RadialGradient(cx - eyeR * 0.25f, cy - eyeR * 0.3f, eyeR * 1.5f, Color.WHITE, accent2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, eyeR, fill)
        fill.shader = null
        fill.color = Color.parseColor("#0a0f1a")
        canvas.drawCircle(cx, cy, eyeR * 0.52f, fill)
        fill.color = Color.parseColor("#E6FFFFFF")
        canvas.drawCircle(cx - eyeR * 0.28f, cy - eyeR * 0.28f, eyeR * 0.14f, fill)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, p: Float, baseR: Float) {
        val r = baseR * (0.5f + 1.55f * p)
        ring.color = accent2
        ring.alpha = (0.85f * (1f - p) * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, ring)
    }
}
