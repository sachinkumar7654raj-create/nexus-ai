package com.nexus.ai.ui.avatar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Futuristic start-up intro: expanding rings, scanning arcs, typed title and boot messages. Plays once on launch. */
class BootView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null, def: Int = 0) : View(ctx, attrs, def) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var p = 0f
    private val total = 3200L
    private val messages = listOf("INITIALISING NEURAL CORE", "LOADING VOICE ENGINE", "LINKING PHONE CONTROLS", "NEXUS ONLINE")
    private var finished = false

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = total
        interpolator = LinearInterpolator()
        addUpdateListener { p = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Tapping skips the intro; it also never blocks the app for more than ~3 seconds.
        setOnClickListener { finish() }
        anim.start()
        postDelayed({ finish() }, total + 100)
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun finish() {
        if (finished) return
        finished = true
        anim.cancel()
        animate().alpha(0f).setDuration(350).withEndAction { visibility = GONE }.start()
    }

    override fun onDraw(c: Canvas) {
        if (finished && alpha == 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h * 0.40f
        val s = min(w, h)
        c.drawColor(Color.parseColor("#05070D"))

        // glow
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, s * 0.6f, intArrayOf(Color.argb(120, 34, 228, 255), Color.argb(40, 45, 125, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.6f, paint)
        paint.shader = null

        // rings
        paint.style = Paint.Style.STROKE
        for (i in 0 until 4) {
            val local = ((p * 1.3f) - i * 0.12f).coerceIn(0f, 1f)
            val r = s * (0.10f + 0.30f * local) + i * s * 0.02f
            paint.strokeWidth = s * 0.004f
            paint.color = Color.argb(((1f - local * 0.6f) * 200 * local.coerceAtMost(1f)).toInt().coerceIn(0, 255), 34, 228, 255)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val a = p * 720f * (if (i % 2 == 0) 1 else -1) + i * 60f
            c.drawArc(rect, a, 80f, false, paint)
            c.drawArc(rect, a + 140f, 50f, false, paint)
            c.drawArc(rect, a + 230f, 100f, false, paint)
        }
        // core
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((255 * min(1f, p * 2f)).toInt(), 34, 228, 255)
        c.drawCircle(cx, cy, s * 0.035f * (0.6f + 0.4f * min(1f, p * 3f)), paint)

        // title (typed)
        val title = "NEXUS AI"
        val shown = (title.length * ((p - 0.15f) / 0.45f).coerceIn(0f, 1f)).toInt()
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = s * 0.095f
        paint.letterSpacing = 0.25f
        paint.color = Color.parseColor("#E6F1FF")
        paint.setShadowLayer(s * 0.03f, 0f, 0f, Color.parseColor("#22E4FF"))
        c.drawText(title.take(shown), cx, h * 0.68f, paint)
        paint.clearShadowLayer()

        // tagline
        val ta = ((p - 0.6f) / 0.2f).coerceIn(0f, 1f)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = s * 0.034f
        paint.letterSpacing = 0.08f
        paint.color = Color.argb((255 * ta).toInt(), 143, 166, 201)
        c.drawText("Your Personal AI. Your Digital World.", cx, h * 0.73f, paint)

        // boot status + progress bar
        val idx = ((p * messages.size).toInt()).coerceIn(0, messages.size - 1)
        paint.textSize = s * 0.028f
        paint.letterSpacing = 0.15f
        paint.color = Color.parseColor("#22E4FF")
        c.drawText("> ${messages[idx]}", cx, h * 0.86f, paint)
        val bw = s * 0.6f; val by = h * 0.89f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(60, 34, 228, 255)
        c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + s * 0.008f, 8f, 8f, paint)
        paint.shader = LinearGradient(cx - bw / 2, 0f, cx + bw / 2, 0f, Color.parseColor("#2D7DFF"), Color.parseColor("#22E4FF"), Shader.TileMode.CLAMP)
        c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + bw * p, by + s * 0.008f, 8f, 8f, paint)
    }
}
