package com.privacy.camerawatch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val wordmark = findViewById<TextView>(R.id.wordmark)
        wordmark.post {
            val w = wordmark.paint.measureText(wordmark.text.toString())
            wordmark.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.WHITE, Color.parseColor("#8ea6ff")),
                null, Shader.TileMode.CLAMP
            )
            wordmark.invalidate()
        }

        findViewById<View>(R.id.loadFill).animate()
            .scaleX(1f).setDuration(2600).setInterpolator(DecelerateInterpolator()).start()

        pulseRing(findViewById(R.id.ring1), 0)
        pulseRing(findViewById(R.id.ring2), 1300)

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2850)
    }

    private fun pulseRing(v: View, startDelay: Long) {
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.55f, 2.0f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.55f, 2.0f),
                ObjectAnimator.ofFloat(v, View.ALPHA, 0.85f, 0f)
            )
            duration = 2800
            this.startDelay = startDelay
            interpolator = LinearInterpolator()
        }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!isFinishing) { set.startDelay = 0; set.start() }
            }
        })
        set.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
