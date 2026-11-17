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
enum class Ic { EYE, EYE_OFF, MIC, MIC_OFF, SOUND, SOUND_OFF, STOP, APP, UP, BROADCAST, CLOCK, HEART, STAR, GIFT, GEM, RAID, WARN, CHAT, COIN, REC, LAYERS }

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
      Ic.REC -> { c.drawCircle(12f, 12f, 8.5f, stroke); c.drawCircle(12f, 12f, 4.5f, fill) }
      Ic.LAYERS -> {
        val p = Path().apply { moveTo(12f, 4f); lineTo(21f, 9f); lineTo(12f, 14f); lineTo(3f, 9f); close() }
        c.drawPath(p, stroke)
        val q = Path().apply { moveTo(3f, 14f); lineTo(12f, 19f); lineTo(21f, 14f) }
        c.drawPath(q, stroke)
      }
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
      Ic.EYE_OFF -> {
        val p = Path().apply { moveTo(2f, 12f); quadTo(12f, 2.5f, 22f, 12f); quadTo(12f, 21.5f, 2f, 12f); close() }
        c.drawPath(p, stroke); c.drawLine(3f, 3f, 21f, 21f, stroke)
      }
      Ic.HEART -> {
        val p = Path().apply {
          moveTo(12f, 20.5f); cubicTo(3f, 14.5f, 2f, 9f, 5f, 6.2f); cubicTo(7.5f, 4f, 10.5f, 4.8f, 12f, 7.5f)
          cubicTo(13.5f, 4.8f, 16.5f, 4f, 19f, 6.2f); cubicTo(22f, 9f, 21f, 14.5f, 12f, 20.5f); close()
        }
        c.drawPath(p, fill)
      }
      Ic.STAR -> {
        val p = Path()
        for (i in 0 until 10) {
          val r = if (i % 2 == 0) 9.5f else 4f
          val a = Math.toRadians((-90 + i * 36).toDouble())
          val px = 12f + r * Math.cos(a).toFloat(); val py = 12.5f + r * Math.sin(a).toFloat()
          if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close(); c.drawPath(p, fill)
      }
      Ic.GIFT -> {
        c.drawRoundRect(RectF(4f, 11f, 20f, 21f), 1.5f, 1.5f, fill)
        c.drawRoundRect(RectF(3f, 7.5f, 21f, 11f), 1.2f, 1.2f, fill)
        c.drawArc(RectF(6.5f, 3f, 12f, 8f), 180f, 270f, false, stroke)
        c.drawArc(RectF(12f, 3f, 17.5f, 8f), 90f, 270f, false, stroke)
      }
      Ic.GEM -> {
        val p = Path().apply { moveTo(7f, 4f); lineTo(17f, 4f); lineTo(21.5f, 9.5f); lineTo(12f, 21f); lineTo(2.5f, 9.5f); close() }
        c.drawPath(p, fill)
      }
      Ic.RAID -> {
        val p = Path().apply { moveTo(12f, 2.5f); cubicTo(16.5f, 6f, 17f, 11f, 15.5f, 16f); lineTo(8.5f, 16f); cubicTo(7f, 11f, 7.5f, 6f, 12f, 2.5f); close() }
        c.drawPath(p, fill)
        val f2 = Path().apply { moveTo(9f, 17.5f); lineTo(12f, 22f); lineTo(15f, 17.5f); close() }
        c.drawPath(f2, fill)
      }
      Ic.WARN -> {
        val p = Path().apply { moveTo(12f, 3f); lineTo(22f, 21f); lineTo(2f, 21f); close() }
        c.drawPath(p, stroke); c.drawLine(12f, 10f, 12f, 15f, stroke); c.drawCircle(12f, 18f, 0.8f, fill)
      }
      Ic.COIN -> {
        c.drawCircle(12f, 12f, 9.5f, fill)
        val old = fill.color
        fill.color = android.graphics.Color.argb(150, 0, 0, 0)
        c.drawCircle(12f, 12f, 6.5f, fill)
        fill.color = old
        c.drawRect(11f, 8f, 13f, 16f, fill)
      }
      Ic.CHAT -> {
        val p = Path().apply { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); lineTo(4f, 16f); close() }
        c.drawPath(p, stroke)
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
  private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0140F2B") }
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
