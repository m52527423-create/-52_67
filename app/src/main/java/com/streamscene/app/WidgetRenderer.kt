package com.streamscene.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import kotlin.math.max
import kotlin.math.min

/** Шрифты из assets/fonts — те же, что в редакторе. */
object Fonts {
  private val spec = mapOf(
    "montserrat" to ("montserrat.ttf" to 800), "rubik" to ("rubik.ttf" to 700),
    "oswald" to ("oswald.ttf" to 600), "unbounded" to ("unbounded.ttf" to 700),
    "russo" to ("russo.ttf" to 400), "pixel" to ("pixel.ttf" to 400),
    "comfortaa" to ("comfortaa.ttf" to 700), "caveat" to ("caveat.ttf" to 700)
  )
  private val cache = HashMap<String, Typeface>()

  @Synchronized
  fun get(ctx: Context, id: String): Typeface = cache.getOrPut(id) {
    val s = spec[id] ?: return@getOrPut Typeface.create("sans-serif-black", Typeface.NORMAL)
    try {
      Typeface.Builder(ctx.assets, "fonts/${s.first}")
        .setFontVariationSettings("'wght' ${s.second}")
        .build() ?: Typeface.DEFAULT_BOLD
    } catch (_: Exception) { Typeface.DEFAULT_BOLD }
  }
}

class ChatLine(val name: String, val color: Int, val text: String)

class AlertEvent(val kind: String, val name: String, val title: String, val message: String = "") {
  var amount: Double = 0.0
}

/** Рисует живые слои (тексты, чат, бегущая строка, алерт, заставка приватности) в картинки. */
class WidgetRenderer(private val ctx: Context) {
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

  private fun alphaOf(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

  private fun cornerR(w: Widget, r: RectF): Float {
    val m = min(r.width(), r.height())
    return min(w.radius / 100f * m, m / 2f)
  }

  /** Текстовый слой: таймер, часы, зрители. Повторяет отрисовку текста в редакторе. */
  fun drawTextBox(c: Canvas, w: Widget, r: RectF, text: String, icon: Ic? = null) {
    val op = w.opacity
    if (w.bg != null) {
      fill.color = alphaOf(w.bg, op)
      val rr = cornerR(w, r)
      c.drawRoundRect(r, rr, rr, fill)
    }
    tp.typeface = Fonts.get(ctx, w.font)
    var fs = r.height() * 0.6f
    tp.textSize = fs
    fun total() = tp.measureText(text) + (if (icon != null) fs * 1.15f else 0f)
    var tw = total()
    val maxW = r.width() * 0.92f
    if (tw > maxW) { fs *= maxW / tw; tp.textSize = fs; tw = total() }
    var x = r.centerX() - tw / 2f
    val base = r.centerY() - (tp.descent() + tp.ascent()) / 2f
    if (icon != null) {
      Icons.draw(c, icon, x, r.centerY() - fs * 0.45f, fs * 0.9f, alphaOf(w.color, op))
      x += fs * 1.15f
    }
    if (w.stroke > 0f) {
      sp.typeface = tp.typeface; sp.textSize = fs; sp.strokeWidth = fs * w.stroke * 2f
      sp.color = alphaOf(w.strokeColor, op)
      c.drawText(text, x, base, sp)
    }
    tp.color = alphaOf(w.color, op)
    if (w.bg == null && w.stroke <= 0f) tp.setShadowLayer(fs * 0.15f, 0f, fs * 0.05f, Color.argb((180 * op).toInt(), 0, 0, 0))
    else tp.clearShadowLayer()
    c.drawText(text, x, base, tp)
    tp.clearShadowLayer()
  }

  /** Чат: последние сообщения снизу вверх. */
  fun drawChat(c: Canvas, w: Widget, r: RectF, frameH: Int, lines: List<ChatLine>) {
    val bgc = w.bg ?: Color.BLACK
    fill.color = alphaOf(bgc, w.bgAlpha * w.opacity)
    val rr = cornerR(w, r)
    if (w.bgAlpha > 0f) c.drawRoundRect(r, rr, rr, fill)
    val fs = frameH * when (w.chatSize) { "s" -> 0.026f; "l" -> 0.046f; else -> 0.034f }
    val pad = fs * 0.6f
    tp.typeface = Fonts.get(ctx, w.font); tp.textSize = fs; tp.color = alphaOf(w.color, w.opacity)
    tp.setShadowLayer(fs * 0.12f, 0f, fs * 0.04f, Color.argb(160, 0, 0, 0))
    val width = (r.width() - pad * 2).toInt().coerceAtLeast(10)
    c.save()
    c.clipRect(r)
    var bottom = r.bottom - pad
    for (m in lines.asReversed()) {
      val sb = SpannableStringBuilder()
      sb.append(m.name)
      sb.setSpan(ForegroundColorSpan(alphaOf(m.color, w.opacity)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.append(": ").append(m.text)
      val lay = StaticLayout.Builder.obtain(sb, 0, sb.length, tp, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).setMaxLines(4)
        .setEllipsize(TextUtils.TruncateAt.END).build()
      val top = bottom - lay.height
      if (top < r.top + pad * 0.5f) break
      c.save(); c.translate(r.left + pad, top); lay.draw(c); c.restore()
      bottom = top - fs * 0.35f
    }
    c.restore()
    tp.clearShadowLayer()
    if (lines.isEmpty()) {
      tp.color = alphaOf(Color.WHITE, 0.5f * w.opacity); tp.textSize = fs
      c.drawText("Чат появится здесь", r.left + pad, r.bottom - pad - tp.descent(), tp)
    }
  }

  /** Бегущая строка: offset — сдвиг текста в пикселях. Возвращает ширину одного повтора. */
  fun drawTicker(c: Canvas, w: Widget, r: RectF, offset: Float): Float {
    if (w.bg != null) {
      fill.color = alphaOf(w.bg, w.opacity)
      val rr = cornerR(w, r)
      c.drawRoundRect(r, rr, rr, fill)
    }
    val fs = r.height() * 0.58f
    tp.typeface = Fonts.get(ctx, w.font); tp.textSize = fs; tp.color = alphaOf(w.color, w.opacity)
    val text = w.text.ifEmpty { "Бегущая строка" } + "     •     "
    val tw = tp.measureText(text)
    val base = r.centerY() - (tp.descent() + tp.ascent()) / 2f
    c.save(); c.clipRect(r)
    var x = r.left - (offset % tw)
    while (x < r.right) {
      if (w.stroke > 0f) {
        sp.typeface = tp.typeface; sp.textSize = fs; sp.strokeWidth = fs * w.stroke * 2f; sp.color = alphaOf(w.strokeColor, w.opacity)
        c.drawText(text, x, base, sp)
      }
      c.drawText(text, x, base, tp)
      x += tw
    }
    c.restore()
    return tw
  }

  /** Карточка алерта. */
  fun alertBitmap(w: Widget, pw: Int, ph: Int, a: AlertEvent): Bitmap {
    val bmp = Bitmap.createBitmap(pw.coerceAtLeast(8), ph.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = RectF(0f, 0f, pw.toFloat(), ph.toFloat())
    val acc = when (a.kind) {
      "follow" -> Color.parseColor("#9147ff"); "sub" -> Color.parseColor("#f5a524"); "gift" -> Color.parseColor("#30a46c")
      "cheer" -> Color.parseColor("#3e9bff"); "raid" -> Color.parseColor("#e5484d"); "donate" -> Color.parseColor("#00c896"); else -> Color.parseColor("#9147ff")
    }
    val rr = min(r.height() * 0.22f, cornerR(w, r).coerceAtLeast(r.height() * 0.12f))
    fill.color = alphaOf(w.bg ?: Color.parseColor("#141418"), 0.94f)
    c.drawRoundRect(r, rr, rr, fill)
    fill.shader = LinearGradient(0f, 0f, r.width(), 0f, alphaOf(acc, 0.35f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    c.drawRoundRect(r, rr, rr, fill); fill.shader = null
    fill.color = acc
    c.drawRoundRect(RectF(0f, 0f, r.height() * 0.06f, r.height()), rr, rr, fill)
    // значок
    val ic = when (a.kind) { "follow" -> Ic.HEART; "sub" -> Ic.STAR; "gift" -> Ic.GIFT; "cheer" -> Ic.GEM; "raid" -> Ic.RAID; "donate" -> Ic.COIN; else -> Ic.STAR }
    val isz = r.height() * 0.5f
    val icx = r.height() * 0.2f
    fill.color = alphaOf(acc, 0.22f)
    c.drawCircle(icx + isz / 2f, r.centerY(), isz * 0.75f, fill)
    Icons.draw(c, ic, icx, r.centerY() - isz / 2f, isz, acc)
    // текст
    val left = icx + isz + r.height() * 0.28f
    val avail = r.right - left - r.height() * 0.2f
    tp.typeface = Fonts.get(ctx, w.font)
    tp.color = w.color
    var fs = r.height() * (if (a.message.isEmpty()) 0.32f else 0.27f)
    tp.textSize = fs
    val name = TextUtils.ellipsize(a.name, tp, avail, TextUtils.TruncateAt.END).toString()
    val sub = TextPaint(tp).apply { typeface = Typeface.DEFAULT; textSize = fs * 0.62f; color = alphaOf(Color.WHITE, 0.78f) }
    val y1 = if (a.message.isEmpty()) r.centerY() - fs * 0.05f else r.height() * 0.38f
    c.drawText(name, left, y1, tp)
    c.drawText(TextUtils.ellipsize(a.title, sub, avail, TextUtils.TruncateAt.END).toString(), left, y1 + fs * 0.85f, sub)
    if (a.message.isNotEmpty()) {
      val msg = TextPaint(sub).apply { color = alphaOf(Color.WHITE, 0.92f); textSize = fs * 0.58f; textSkewX = -0.15f }
      c.drawText(TextUtils.ellipsize("«${a.message}»", msg, avail, TextUtils.TruncateAt.END).toString(), left, y1 + fs * 1.75f, msg)
    }
    return bmp
  }

  /** Где поместится картинка алерта (верхняя часть окна, с сохранением пропорций). */
  fun mediaRect(pw: Int, ph: Int, iw: Int, ih: Int): RectF {
    val area = RectF(0f, 0f, pw.toFloat(), ph * 0.68f)
    if (iw <= 0 || ih <= 0) return area
    val k = min(area.width() / iw, area.height() / ih)
    val w = iw * k; val h = ih * k
    return RectF((pw - w) / 2f, area.bottom - h, (pw + w) / 2f, area.bottom)
  }

  /** Алерт со своей картинкой: картинка сверху, крупный текст снизу (как в DonationAlerts). */
  fun mediaAlertBitmap(w: Widget, pw: Int, ph: Int, a: AlertEvent, img: Bitmap?): Bitmap {
    val bmp = Bitmap.createBitmap(pw.coerceAtLeast(8), ph.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    if (img != null) c.drawBitmap(img, null, mediaRect(pw, ph, img.width, img.height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    val acc = when (a.kind) { "donate" -> Color.parseColor("#00e0a4"); "follow" -> Color.parseColor("#b58cff"); "sub", "gift" -> Color.parseColor("#ffc04d"); "cheer" -> Color.parseColor("#6fb5ff"); else -> Color.parseColor("#ff7a7a") }
    val top = ph * 0.70f
    val fs = ph * 0.11f
    tp.typeface = Fonts.get(ctx, w.font); tp.textSize = fs; tp.textAlign = Paint.Align.CENTER
    sp.typeface = tp.typeface; sp.textSize = fs; sp.strokeWidth = fs * 0.22f; sp.color = Color.argb(220, 0, 0, 0); sp.textAlign = Paint.Align.CENTER
    val line1 = TextUtils.ellipsize("${a.name} — ${a.title}", tp, pw * 0.96f, TextUtils.TruncateAt.END).toString()
    // имя белым, сумма/событие цветом
    val y1 = top + fs
    c.drawText(line1, pw / 2f, y1, sp)
    val nameW = tp.measureText("${a.name} — ")
    val totalW = tp.measureText(line1)
    val startX = pw / 2f - totalW / 2f
    tp.textAlign = Paint.Align.LEFT
    tp.color = w.color
    c.drawText(line1.take(min(line1.length, a.name.length + 3)), startX, y1, tp)
    if (line1.length > a.name.length + 3) { tp.color = acc; c.drawText(line1.substring(a.name.length + 3), startX + nameW, y1, tp) }
    if (a.message.isNotEmpty()) {
      val mp = TextPaint(tp).apply { textSize = fs * 0.62f; color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
      mp.setShadowLayer(fs * 0.12f, 0f, 0f, Color.BLACK)
      val lay = StaticLayout.Builder.obtain(a.message, 0, a.message.length, mp, (pw * 0.94f).toInt())
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
      c.save(); c.translate(pw * 0.03f, y1 + fs * 0.35f); lay.draw(c); c.restore()
    }
    tp.textAlign = Paint.Align.LEFT; sp.textAlign = Paint.Align.LEFT
    return bmp
  }

  /** Заставка «Секунду…» для режима приватности. */
  fun privacyBitmap(pw: Int, ph: Int): Bitmap {
    val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    fill.shader = LinearGradient(0f, 0f, pw.toFloat(), ph.toFloat(), Color.parseColor("#0b0b0d"), Color.parseColor("#22113f"), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, pw.toFloat(), ph.toFloat(), fill); fill.shader = null
    val s = min(pw, ph).toFloat()
    val cx = pw / 2f; val cy = ph * 0.42f
    fill.color = Color.WHITE
    val bw = s * 0.035f; val bh = s * 0.13f
    c.drawRoundRect(RectF(cx - bw * 1.6f, cy - bh / 2, cx - bw * 0.6f, cy + bh / 2), bw * 0.3f, bw * 0.3f, fill)
    c.drawRoundRect(RectF(cx + bw * 0.6f, cy - bh / 2, cx + bw * 1.6f, cy + bh / 2), bw * 0.3f, bw * 0.3f, fill)
    tp.typeface = Fonts.get(ctx, "montserrat"); tp.color = Color.WHITE; tp.textSize = s * 0.085f; tp.textAlign = Paint.Align.CENTER
    c.drawText("Секунду…", cx, cy + bh / 2 + s * 0.13f, tp)
    tp.typeface = Typeface.DEFAULT; tp.textSize = s * 0.04f; tp.color = alphaOf(Color.WHITE, 0.7f)
    c.drawText("скоро вернусь", cx, cy + bh / 2 + s * 0.2f, tp)
    tp.textAlign = Paint.Align.LEFT
    return bmp
  }

  fun rectOf(w: Widget, fw: Int, fh: Int) = RectF(w.x * fw, w.y * fh, (w.x + w.w) * fw, (w.y + w.h) * fh)
  fun px(v: Float) = max(1, v.toInt())
}
