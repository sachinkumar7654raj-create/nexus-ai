package com.nexus.ai.ui.avatar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.nexus.ai.core.AvatarState
import com.nexus.ai.core.Mood
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Stylised holographic female avatar drawn with Canvas (no image files needed).
 * Reacts to [state] (idle / listening / thinking / speaking), [mood] and microphone [level].
 */
class AvatarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null, def: Int = 0) : View(ctx, attrs, def) {

    var state: AvatarState = AvatarState.IDLE
        set(v) { field = v; invalidate() }
    var mood: Mood = Mood.NEUTRAL
        set(v) { field = v; invalidate() }
    var level: Float = 0f

    private val cyan = Color.parseColor("#22E4FF")
    private val blue = Color.parseColor("#2D7DFF")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var t = 0f
    private var blink = 0f          // 0 = open, 1 = closed
    private var nextBlinkAt = 2.5f
    private var mouthOpen = 0f
    private var lookX = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            step(dt)
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun step(dt: Float) {
        t += dt
        // blinking
        if (t > nextBlinkAt && blink == 0f) { blink = 0.01f }
        if (blink > 0f) {
            blink += dt * 9f
            if (blink >= 2f) { blink = 0f; nextBlinkAt = t + 2f + (abs(sin(t * 12.9898f)) * 3.5f) }
        }
        // mouth
        val target = when (state) {
            AvatarState.SPEAKING -> 0.25f + 0.75f * abs(sin(t * 11f) * cos(t * 4.3f))
            else -> 0f
        }
        mouthOpen += (target - mouthOpen) * min(1f, dt * 16f)
        // eyes wander gently, look "up" when thinking
        val lx = if (state == AvatarState.THINKING) 0.8f * sin(t * 1.4f) else 0.25f * sin(t * 0.5f)
        lookX += (lx - lookX) * min(1f, dt * 6f)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = min(w, h)
        val cx = w / 2f
        val cy = h / 2f

        drawAura(c, cx, cy, s)
        drawRings(c, cx, cy, s)

        val bob = sin(t * 1.2f) * s * 0.006f
        c.save()
        c.translate(0f, bob)
        drawHairBack(c, cx, cy, s)
        drawNeckAndShoulders(c, cx, cy, s)
        drawFace(c, cx, cy, s)
        drawHairFront(c, cx, cy, s)
        drawEyes(c, cx, cy, s)
        drawBrows(c, cx, cy, s)
        drawNose(c, cx, cy, s)
        drawMouth(c, cx, cy, s)
        c.restore()

        drawScanlines(c, w, h, cx, cy, s)
    }

    private fun drawAura(c: Canvas, cx: Float, cy: Float, s: Float) {
        val pulse = when (state) {
            AvatarState.LISTENING -> 0.55f + 0.45f * max(level, 0.5f + 0.5f * sin(t * 4f))
            AvatarState.SPEAKING -> 0.6f + 0.25f * sin(t * 6f)
            AvatarState.THINKING -> 0.5f + 0.2f * sin(t * 8f)
            else -> 0.45f + 0.1f * sin(t * 1.5f)
        }
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, s * 0.62f,
            intArrayOf(Color.argb((110 * pulse).toInt(), 34, 228, 255), Color.argb((50 * pulse).toInt(), 45, 125, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.62f, paint)
        paint.shader = null
    }

    private fun drawRings(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
        val base = s * 0.46f
        for (i in 0 until 3) {
            val r = base + i * s * 0.025f
            paint.strokeWidth = s * 0.004f
            paint.color = Color.argb(if (i == 0) 150 else 80, 34, 228, 255)
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val dir = if (i % 2 == 0) 1f else -1f
            val speed = if (state == AvatarState.THINKING) 160f else 40f
            val start = (t * speed * dir + i * 70f)
            c.drawArc(rect, start, 70f, false, paint)
            c.drawArc(rect, start + 150f, 40f, false, paint)
            c.drawArc(rect, start + 240f, 90f, false, paint)
        }
        if (state == AvatarState.LISTENING) {
            val k = (t * 1.2f) % 1f
            paint.strokeWidth = s * 0.006f
            paint.color = Color.argb(((1f - k) * 160).toInt(), 34, 228, 255)
            c.drawCircle(cx, cy, s * (0.36f + 0.2f * k + 0.1f * level), paint)
        }
    }

    private fun drawHairBack(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx, cy - s * 0.4f, cx, cy + s * 0.45f, Color.parseColor("#1B2F73"), Color.parseColor("#0B1233"), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(cx, cy - s * 0.37f)
        path.cubicTo(cx - s * 0.34f, cy - s * 0.37f, cx - s * 0.40f, cy - s * 0.02f, cx - s * 0.36f, cy + s * 0.22f)
        path.cubicTo(cx - s * 0.34f, cy + s * 0.40f, cx - s * 0.2f, cy + s * 0.44f, cx - s * 0.14f, cy + s * 0.40f)
        path.lineTo(cx + s * 0.14f, cy + s * 0.40f)
        path.cubicTo(cx + s * 0.2f, cy + s * 0.44f, cx + s * 0.34f, cy + s * 0.40f, cx + s * 0.36f, cy + s * 0.22f)
        path.cubicTo(cx + s * 0.40f, cy - s * 0.02f, cx + s * 0.34f, cy - s * 0.37f, cx, cy - s * 0.37f)
        path.close()
        c.drawPath(path, paint)
        paint.shader = null
    }

    private fun drawNeckAndShoulders(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx, cy + s * 0.15f, cx, cy + s * 0.45f, Color.parseColor("#C9A4B8"), Color.parseColor("#8E6E92"), Shader.TileMode.CLAMP)
        val nw = s * 0.06f
        c.drawRect(cx - nw, cy + s * 0.14f, cx + nw, cy + s * 0.34f, paint)
        paint.shader = LinearGradient(cx, cy + s * 0.3f, cx, cy + s * 0.5f, Color.parseColor("#1A3A8A"), Color.parseColor("#0A1840"), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(cx - s * 0.36f, cy + s * 0.5f)
        path.cubicTo(cx - s * 0.34f, cy + s * 0.36f, cx - s * 0.16f, cy + s * 0.32f, cx - s * 0.07f, cy + s * 0.3f)
        path.lineTo(cx + s * 0.07f, cy + s * 0.3f)
        path.cubicTo(cx + s * 0.16f, cy + s * 0.32f, cx + s * 0.34f, cy + s * 0.36f, cx + s * 0.36f, cy + s * 0.5f)
        path.close()
        c.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.004f; paint.color = Color.argb(180, 34, 228, 255)
        c.drawPath(path, paint)
    }

    private fun faceRect(cx: Float, cy: Float, s: Float) = RectF(cx - s * 0.2f, cy - s * 0.29f, cx + s * 0.2f, cy + s * 0.2f)

    private fun drawFace(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = faceRect(cx, cy, s)
        paint.reset(); paint.isAntiAlias = true
        path.reset()
        path.moveTo(cx, r.top)
        path.cubicTo(r.right + s * 0.02f, r.top, r.right + s * 0.01f, cy - s * 0.02f, r.right - s * 0.03f, cy + s * 0.08f)
        path.cubicTo(r.right - s * 0.07f, r.bottom - s * 0.01f, cx + s * 0.07f, r.bottom, cx, r.bottom)
        path.cubicTo(cx - s * 0.07f, r.bottom, r.left + s * 0.07f, r.bottom - s * 0.01f, r.left + s * 0.03f, cy + s * 0.08f)
        path.cubicTo(r.left - s * 0.01f, cy - s * 0.02f, r.left - s * 0.02f, r.top, cx, r.top)
        path.close()
        paint.shader = RadialGradient(cx, cy - s * 0.08f, s * 0.32f, Color.parseColor("#FFE3D6"), Color.parseColor("#E7B9A8"), Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null
        // cheeks
        val blush = if (mood == Mood.HAPPY) 90 else 45
        paint.color = Color.argb(blush, 255, 120, 140)
        c.drawCircle(cx - s * 0.115f, cy + s * 0.06f, s * 0.04f, paint)
        c.drawCircle(cx + s * 0.115f, cy + s * 0.06f, s * 0.04f, paint)
        // holographic outline
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.003f; paint.color = Color.argb(140, 34, 228, 255)
        c.drawPath(path, paint)
    }

    private fun drawHairFront(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(cx - s * 0.2f, cy - s * 0.35f, cx + s * 0.2f, cy - s * 0.1f, Color.parseColor("#2B4AA8"), Color.parseColor("#14235F"), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(cx - s * 0.23f, cy - s * 0.04f)
        path.cubicTo(cx - s * 0.26f, cy - s * 0.30f, cx - s * 0.10f, cy - s * 0.37f, cx, cy - s * 0.35f)
        path.cubicTo(cx + s * 0.10f, cy - s * 0.37f, cx + s * 0.26f, cy - s * 0.30f, cx + s * 0.23f, cy - s * 0.04f)
        path.cubicTo(cx + s * 0.20f, cy - s * 0.16f, cx + s * 0.14f, cy - s * 0.22f, cx + s * 0.06f, cy - s * 0.25f)
        path.cubicTo(cx - s * 0.02f, cy - s * 0.18f, cx - s * 0.12f, cy - s * 0.16f, cx - s * 0.18f, cy - s * 0.10f)
        path.cubicTo(cx - s * 0.20f, cy - s * 0.08f, cx - s * 0.22f, cy - s * 0.06f, cx - s * 0.23f, cy - s * 0.04f)
        path.close()
        c.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.004f; paint.color = Color.argb(150, 34, 228, 255)
        path.reset()
        path.moveTo(cx - s * 0.17f, cy - s * 0.28f); path.quadTo(cx - s * 0.05f, cy - s * 0.34f, cx + s * 0.08f, cy - s * 0.31f)
        c.drawPath(path, paint)
    }

    private fun eyeCenters(cx: Float, cy: Float, s: Float) = floatArrayOf(cx - s * 0.085f, cx + s * 0.085f, cy - s * 0.045f)

    private fun drawEyes(c: Canvas, cx: Float, cy: Float, s: Float) {
        val e = eyeCenters(cx, cy, s)
        val closed = if (blink <= 0f) 0f else if (blink < 1f) blink else 2f - blink
        val happySquint = if (mood == Mood.HAPPY || mood == Mood.NEUTRAL && state == AvatarState.SPEAKING) 0.18f else 0f
        val surprised = if (mood == Mood.SURPRISED) 1.18f else 1f
        val sad = if (mood == Mood.SAD) 0.85f else 1f
        for (k in 0..1) {
            val ex = e[k]
            val ey = e[2]
            val ew = s * 0.058f
            val eh = s * 0.036f * surprised * sad * (1f - max(closed, happySquint))
            // sclera
            paint.reset(); paint.isAntiAlias = true; paint.color = Color.parseColor("#F8FBFF")
            c.drawOval(RectF(ex - ew, ey - max(eh, s * 0.004f), ex + ew, ey + max(eh, s * 0.004f)), paint)
            if (eh > s * 0.012f) {
                // iris
                val ix = ex + lookX * ew * 0.35f
                val ir = min(eh * 0.95f, ew * 0.62f)
                paint.shader = RadialGradient(ix, ey, ir, intArrayOf(Color.parseColor("#9FF3FF"), Color.parseColor("#22A8E8"), Color.parseColor("#0B3A8C")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.save(); c.clipRect(ex - ew, ey - eh, ex + ew, ey + eh)
                c.drawCircle(ix, ey, ir, paint)
                paint.shader = null
                paint.color = Color.parseColor("#04122E"); c.drawCircle(ix, ey, ir * 0.42f, paint)
                paint.color = Color.WHITE; c.drawCircle(ix - ir * 0.3f, ey - ir * 0.35f, ir * 0.2f, paint)
                c.restore()
            }
            // upper lash line
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.0065f; paint.color = Color.parseColor("#0B1233"); paint.strokeCap = Paint.Cap.ROUND
            path.reset()
            val dir = if (k == 0) -1f else 1f
            path.moveTo(ex - ew * 1.05f, ey + eh * 0.15f)
            path.quadTo(ex, ey - max(eh, s * 0.003f) * 1.5f, ex + ew * 1.05f, ey + eh * 0.15f)
            c.drawPath(path, paint)
            // lash flick on outer corner
            c.drawLine(ex + dir * ew * 1.05f, ey + eh * 0.15f, ex + dir * ew * 1.35f, ey - s * 0.012f, paint)
        }
    }

    private fun drawBrows(c: Canvas, cx: Float, cy: Float, s: Float) {
        var lift = when (mood) {
            Mood.SURPRISED -> -s * 0.018f
            Mood.SAD -> s * 0.004f
            else -> 0f
        }
        if (state == AvatarState.THINKING) lift -= s * 0.008f
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.008f; paint.strokeCap = Paint.Cap.ROUND; paint.color = Color.parseColor("#14235F")
        val y = cy - s * 0.1f + lift
        val tilt = if (mood == Mood.SAD) s * 0.012f else 0f
        path.reset(); path.moveTo(cx - s * 0.14f, y + s * 0.008f + tilt); path.quadTo(cx - s * 0.09f, y - s * 0.012f, cx - s * 0.035f, y + s * 0.004f - tilt); c.drawPath(path, paint)
        path.reset(); path.moveTo(cx + s * 0.14f, y + s * 0.008f + tilt); path.quadTo(cx + s * 0.09f, y - s * 0.012f, cx + s * 0.035f, y + s * 0.004f - tilt); c.drawPath(path, paint)
    }

    private fun drawNose(c: Canvas, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.003f; paint.color = Color.argb(110, 160, 100, 110); paint.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(cx - s * 0.008f, cy + s * 0.005f); path.quadTo(cx - s * 0.018f, cy + s * 0.04f, cx, cy + s * 0.045f); path.quadTo(cx + s * 0.018f, cy + s * 0.04f, cx + s * 0.012f, cy + s * 0.038f)
        c.drawPath(path, paint)
    }

    private fun drawMouth(c: Canvas, cx: Float, cy: Float, s: Float) {
        val my = cy + s * 0.12f
        val mw = s * 0.062f
        val smile = when (mood) { Mood.HAPPY -> 1f; Mood.SAD -> -0.7f; Mood.SURPRISED -> 0.1f; else -> 0.45f }
        val open = if (state == AvatarState.SPEAKING) mouthOpen else if (mood == Mood.SURPRISED) 0.5f else if (state == AvatarState.LISTENING) 0.06f else 0f
        paint.reset(); paint.isAntiAlias = true
        val curve = s * 0.018f * smile
        val openPx = s * 0.045f * open
        if (openPx > s * 0.004f) {
            path.reset()
            path.moveTo(cx - mw, my - curve * 0.3f)
            path.quadTo(cx, my - s * 0.012f - curve * 0.2f, cx + mw, my - curve * 0.3f)
            path.quadTo(cx, my + openPx * 1.6f + curve, cx - mw, my - curve * 0.3f)
            path.close()
            paint.color = Color.parseColor("#7A1F3D"); c.drawPath(path, paint)
            paint.color = Color.parseColor("#F4F7FF")
            c.save(); c.clipPath(path)
            c.drawRect(cx - mw * 0.75f, my - s * 0.012f, cx + mw * 0.75f, my + openPx * 0.25f, paint)
            c.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.006f; paint.color = Color.parseColor("#D9566F"); paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.0085f; paint.strokeCap = Paint.Cap.ROUND; paint.color = Color.parseColor("#D9566F")
            path.reset(); path.moveTo(cx - mw, my - curve * 0.2f); path.quadTo(cx, my + curve * 1.6f, cx + mw, my - curve * 0.2f)
            c.drawPath(path, paint)
        }
    }

    private fun drawScanlines(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, s: Float) {
        paint.reset(); paint.style = Paint.Style.FILL
        val sweep = ((t * 0.35f) % 1f) * h
        paint.shader = LinearGradient(0f, sweep - s * 0.08f, 0f, sweep + s * 0.08f,
            intArrayOf(Color.TRANSPARENT, Color.argb(55, 34, 228, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(cx - s * 0.5f, sweep - s * 0.08f, cx + s * 0.5f, sweep + s * 0.08f, paint)
        paint.shader = null
        paint.color = Color.argb(14, 34, 228, 255)
        var y = cy - s * 0.5f
        while (y < cy + s * 0.5f) { c.drawRect(cx - s * 0.5f, y, cx + s * 0.5f, y + 1.2f, paint); y += s * 0.012f }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val hMode = MeasureSpec.getMode(hSpec)
        val h = if (hMode == MeasureSpec.UNSPECIFIED) w else MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
    }

}
