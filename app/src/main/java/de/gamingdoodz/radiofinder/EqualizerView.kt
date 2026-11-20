package de.gamingdoodz.radiofinder

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Animierte Equalizer-Balken, die tanzen, solange Musik läuft. */
class EqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bars = 5
    var playing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val speeds = doubleArrayOf(170.0, 230.0, 140.0, 260.0, 200.0, 155.0, 215.0, 185.0, 245.0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(0f, h.toFloat(), 0f, 0f,
            0xFF2EE6A8.toInt(), 0xFF19B6D2.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val n = bars.coerceIn(1, speeds.size)
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / n
        val barW = slot * 0.62f
        val radius = barW / 2
        val t = SystemClock.uptimeMillis().toDouble()
        for (i in 0 until n) {
            val level = if (playing) {
                (0.18 + 0.82 * abs(sin(t / speeds[i] + i * 1.7) * sin(t / (speeds[(i + 3) % speeds.size] * 1.9) + i))).toFloat()
            } else 0.15f
            val bh = (h * level).coerceAtLeast(barW)
            val left = i * slot + (slot - barW) / 2
            rect.set(left, h - bh, left + barW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (playing) postInvalidateOnAnimation()
    }
}
