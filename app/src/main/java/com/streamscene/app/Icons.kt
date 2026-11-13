package com.streamscene.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Собственный набор иконок приложения (рисуются линиями в сетке 24×24). */
enum class Ic { EYE, MIC, MIC_OFF, SOUND, SOUND_OFF, STOP, APP, UP, BROADCAST, CLOCK }

object Icons {
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  fun draw(c: Canvas, ic: Ic, x: Float, y: Float, size: Float, color: Int) {
    stroke.color = color; fill.color = color
    c.save()
    c.translate(x, y)
    c.scale(size / 24f, size / 24f)
    when (ic) {
      Ic.EYE -> {
        val p = Path().apply { moveTo(2f, 12f); quadTo(12f, 2.5f, 22f, 12f); quadTo(12f, 21.5f, 2f, 12f); close() }
        c.drawPath(p, stroke); c.drawCircle(12f, 12f, 3f, fill)
      }
      Ic.MIC, Ic.MIC_OFF -> {
        c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, stroke)
        c.drawArc(RectF(5.5f, 5f, 18.5f, 18f), 0f, 180f, false, stroke)
        c.drawLine(12f, 18f, 12f, 21f, stroke); c.drawLine(9f, 21f, 15f, 21f, stroke)
        if (ic == Ic.MIC_OFF) c.drawLine(4f, 3f, 20f, 21f, stroke)
      }
      Ic.SOUND, Ic.SOUND_OFF -> {
        val p = Path().apply { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close() }
        c.drawPath(p, stroke)
        if (ic == Ic.SOUND) { c.drawArc(RectF(12f, 8f, 18f, 16f), -60f, 120f, false, stroke); c.drawArc(RectF(12f, 5f, 21f, 19f), -60f, 120f, false, stroke) }
        else { c.drawLine(16f, 9f, 21f, 15f, stroke); c.drawLine(21f, 9f, 16f, 15f, stroke) }
      }
      Ic.STOP -> c.drawRoundRect(RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, fill)
      Ic.APP -> {
        c.drawRoundRect(RectF(3f, 4f, 21f, 20f), 3f, 3f, stroke)
        c.drawLine(3f, 9f, 21f, 9f, stroke); c.drawCircle(6f, 6.5f, 0.6f, fill); c.drawCircle(8.5f, 6.5f, 0.6f, fill)
      }
      Ic.UP -> {
        c.drawLine(12f, 20f, 12f, 5f, stroke)
        val p = Path().apply { moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
        c.drawPath(p, stroke)
      }
      Ic.BROADCAST -> {
        c.drawCircle(12f, 12f, 2.2f, fill)
        c.drawArc(RectF(7f, 7f, 17f, 17f), 135f, 90f, false, stroke)
        c.drawArc(RectF(7f, 7f, 17f, 17f), -45f, 90f, false, stroke)
        c.drawArc(RectF(3f, 3f, 21f, 21f), 140f, 80f, false, stroke)
        c.drawArc(RectF(3f, 3f, 21f, 21f), -40f, 80f, false, stroke)
      }
      Ic.CLOCK -> {
        c.drawCircle(12f, 12f, 9f, stroke)
        val p = Path().apply { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
        c.drawPath(p, stroke)
      }
    }
    c.restore()
  }
}

class IconView(ctx: Context, var ic: Ic, var color: Int = Color.WHITE) : View(ctx) {
  override fun onDraw(canvas: Canvas) {
    val s = minOf(width, height).toFloat()
    Icons.draw(canvas, ic, (width - s) / 2f, (height - s) / 2f, s, color)
  }
}

/** Значок качества соединения: 4 столбика. */
class SignalView(ctx: Context) : View(ctx) {
  var level = 0
    set(v) { field = v; invalidate() }
  var color = Color.GREEN
    set(v) { field = v; invalidate() }
  private val p = Paint(Paint.ANTI_ALIAS_FLAG)

  override fun onDraw(canvas: Canvas) {
    val gap = width * 0.1f
    val bw = (width - gap * 3) / 4f
    for (i in 0 until 4) {
      val h = height * (0.35f + 0.65f * i / 3f)
      p.color = if (i < level) color else Color.argb(70, 255, 255, 255)
      val l = i * (bw + gap)
      canvas.drawRoundRect(RectF(l, height - h, l + bw, height.toFloat()), bw / 3f, bw / 3f, p)
    }
  }
}

/** Плавающий кружок: кольцо статуса + зрители. */
class BubbleView(ctx: Context) : View(ctx) {
  var ringColor = Color.parseColor("#ffb31a")
  var viewers: Int? = null
  var signalColor = Color.GRAY
  private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE18181B") }
  private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
  }

  override fun onDraw(canvas: Canvas) {
    val s = minOf(width, height).toFloat()
    val cx = width / 2f; val cy = height / 2f
    val rw = s * 0.07f
    canvas.drawCircle(cx, cy, s / 2f - rw / 2f, bg)
    ring.color = ringColor; ring.strokeWidth = rw
    canvas.drawCircle(cx, cy, s / 2f - rw, ring)
    val v = viewers
    if (v != null) {
      val isz = s * 0.30f
      Icons.draw(canvas, Ic.EYE, cx - isz / 2f, cy - s * 0.30f, isz, Color.argb(220, 255, 255, 255))
      text.textSize = s * (if (v >= 1000) 0.22f else 0.28f)
      val label = if (v >= 10000) "${v / 1000}k" else if (v >= 1000) String.format("%.1fk", v / 1000f) else v.toString()
      canvas.drawText(label, cx, cy + s * 0.22f, text)
    } else {
      val isz = s * 0.46f
      Icons.draw(canvas, Ic.BROADCAST, cx - isz / 2f, cy - isz / 2f, isz, Color.WHITE)
    }
    dot.color = signalColor
    canvas.drawCircle(cx + s * 0.32f, cy + s * 0.32f, s * 0.09f, dot)
  }
}
