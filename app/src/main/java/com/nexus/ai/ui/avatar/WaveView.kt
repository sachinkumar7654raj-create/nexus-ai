package com.nexus.ai.ui.avatar

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
import com.nexus.ai.core.AvatarState
import kotlin.math.abs
import kotlin.math.sin

/** Sound-wave bars. While listening they follow the real microphone level; while speaking they show a gentle wave. */
class WaveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null, def: Int = 0) : View(ctx, attrs, def) {

    var state: AvatarState = AvatarState.IDLE
    var level: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private val bars = 33
    private val smooth = FloatArray(bars)

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t += 0.05f; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        paint.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#2D7DFF"), Color.parseColor("#22E4FF"), Shader.TileMode.CLAMP)
        val slot = w / bars
        val bw = slot * 0.55f
        for (i in 0 until bars) {
            val centerWeight = 1f - abs(i - bars / 2f) / (bars / 2f)
            val target = when (state) {
                AvatarState.LISTENING -> (0.12f + level * 0.9f * (0.5f + 0.5f * abs(sin(t * 2f + i * 0.7f)))) * (0.4f + 0.6f * centerWeight)
                AvatarState.SPEAKING -> (0.18f + 0.6f * abs(sin(t * 1.7f + i * 0.45f) * sin(t * 0.9f + i * 0.2f))) * (0.35f + 0.65f * centerWeight)
                AvatarState.THINKING -> 0.1f + 0.25f * abs(sin(t * 3f - i * 0.5f))
                AvatarState.IDLE -> 0.05f + 0.02f * sin(t + i)
            }
            smooth[i] += (target - smooth[i]) * 0.3f
            val bh = (smooth[i] * h).coerceAtLeast(3f)
            val x = i * slot + (slot - bw) / 2f
            rect.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }
}
