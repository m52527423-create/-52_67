package com.streamscene.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Данные для кружка и панели. */
class LiveInfo(
  val state: String,          // connecting | live | reconnecting
  val viewers: Int?,
  val kbps: Long,
  val targetKbps: Int,
  val uptimeMs: Long,
  val scenes: List<String>,
  val current: Int,
  val micMuted: Boolean,
  val micLabel: String
)

/**
 * Плавающий кружок поверх игры. Нажатие открывает панель:
 * соединение, время в эфире, зрители, битрейт, сцены, микрофон, стоп.
 */
class FloatingControls(private val ctx: Context, private val actions: Actions) {

  interface Actions {
    fun onScene(index: Int)
    fun onToggleMic()
    fun onOpenApp()
    fun onStop()
  }

  private val wm = ctx.getSystemService(WindowManager::class.java)
  private val prefs = ctx.getSharedPreferences("bubble", Context.MODE_PRIVATE)
  private val main = Handler(Looper.getMainLooper())
  private val density = ctx.resources.displayMetrics.density
  private fun dp(v: Int) = (v * density).toInt()
  private fun dpf(v: Float) = v * density

  private val ACCENT = Color.parseColor("#9147ff")
  private val RED = Color.parseColor("#e91916")
  private val YELLOW = Color.parseColor("#ffb31a")
  private val GREEN = Color.parseColor("#00c896")
  private val MUTE = Color.parseColor("#a0a0ad")
  private val CARD2 = Color.parseColor("#26262c")

  private val bubbleSize = dp(58)
  private val bubble = BubbleView(ctx)
  private val bubbleLp = WindowManager.LayoutParams(
    bubbleSize, bubbleSize,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
  ).apply {
    gravity = Gravity.TOP or Gravity.START
    x = prefs.getInt("x", 0)
    y = prefs.getInt("y", dp(140))
  }

  private var shown = false
  private var panel: LinearLayout? = null
  private var panelLp: WindowManager.LayoutParams? = null
  private var panelClosedAt = 0L
  private var stopArmed = false
  private var info: LiveInfo? = null

  // элементы панели
  private lateinit var signal: SignalView
  private lateinit var tState: TextView
  private lateinit var tUptime: TextView
  private lateinit var tViewers: TextView
  private lateinit var tBitrate: TextView
  private lateinit var scenesRow: LinearLayout
  private lateinit var micIcon: IconView
  private lateinit var micText: TextView
  private lateinit var stopBox: LinearLayout
  private lateinit var stopText: TextView

  fun canShow() = Settings.canDrawOverlays(ctx)

  fun show() {
    if (shown || !canShow()) return
    try {
      wm.addView(bubble, bubbleLp)
      shown = true
      bubble.setOnTouchListener(BubbleTouch())
    } catch (_: Exception) {}
  }

  fun hide() {
    closePanel()
    if (shown) try { wm.removeView(bubble) } catch (_: Exception) {}
    shown = false
  }

  fun update(i: LiveInfo) {
    info = i
    val (lvl, col) = quality(i)
    bubble.ringColor = when (i.state) { "live" -> RED; "reconnecting" -> Color.parseColor("#ff7a1a"); else -> YELLOW }
    bubble.viewers = i.viewers
    bubble.signalColor = col
    bubble.invalidate()
    if (panel != null) fillPanel(i, lvl, col)
  }

  private fun quality(i: LiveInfo): Pair<Int, Int> {
    if (i.state == "reconnecting") return 0 to RED
    if (i.state != "live") return 1 to YELLOW
    if (i.kbps <= 0) return 3 to GREEN
    val r = i.kbps.toFloat() / i.targetKbps.coerceAtLeast(1)
    return when {
      r >= 0.8f -> 4 to GREEN
      r >= 0.6f -> 3 to GREEN
      r >= 0.35f -> 2 to YELLOW
      else -> 1 to RED
    }
  }

  private fun screenSize(): Point {
    return if (Build.VERSION.SDK_INT >= 30) {
      val b = wm.currentWindowMetrics.bounds
      Point(b.width(), b.height())
    } else {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
      Point(dm.widthPixels, dm.heightPixels)
    }
  }

  private inner class BubbleTouch : View.OnTouchListener {
    private var sx = 0f; private var sy = 0f; private var px = 0; private var py = 0
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onTouch(v: View, e: MotionEvent): Boolean {
      when (e.action) {
        MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = bubbleLp.x; py = bubbleLp.y; dragging = false }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - sx; val dy = e.rawY - sy
          if (!dragging && hypot(dx, dy) > slop) { dragging = true; closePanel() }
          if (dragging) {
            bubbleLp.x = (px + dx).toInt(); bubbleLp.y = (py + dy).toInt()
            try { wm.updateViewLayout(bubble, bubbleLp) } catch (_: Exception) {}
          }
        }
        MotionEvent.ACTION_UP -> if (dragging) snapToEdge() else togglePanel()
      }
      return true
    }
  }

  private fun snapToEdge() {
    val s = screenSize()
    bubbleLp.x = if (bubbleLp.x + bubbleSize / 2 < s.x / 2) 0 else s.x - bubbleSize
    bubbleLp.y = bubbleLp.y.coerceIn(0, (s.y - bubbleSize).coerceAtLeast(0))
    try { wm.updateViewLayout(bubble, bubbleLp) } catch (_: Exception) {}
    prefs.edit().putInt("x", bubbleLp.x).putInt("y", bubbleLp.y).apply()
  }

  private fun togglePanel() {
    if (panel != null) { closePanel(); return }
    if (System.currentTimeMillis() - panelClosedAt < 350) return // панель только что закрылась касанием по кружку
    openPanel()
  }

  // ---------------- панель ----------------
  private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(ctx).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
  }

  private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = dpf(radiusDp)
    if (strokeColor != null) setStroke(dp(1), strokeColor)
  }

  private fun row(): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
  }

  private fun openPanel() {
    val p = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(Color.parseColor("#F218181B"), 18f, Color.parseColor("#2f2f37"))
      setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    // 1. соединение + время
    val r1 = row()
    signal = SignalView(ctx)
    r1.addView(signal, LinearLayout.LayoutParams(dp(22), dp(16)).apply { rightMargin = dp(10) })
    tState = text(15f, bold = true)
    r1.addView(tState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    r1.addView(IconView(ctx, Ic.CLOCK, MUTE), LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(5) })
    tUptime = text(14f, MUTE).apply { typeface = Typeface.MONOSPACE }
    r1.addView(tUptime)
    p.addView(r1)

    // 2. зрители + битрейт
    val r2 = row()
    r2.addView(IconView(ctx, Ic.EYE, Color.WHITE), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(7) })
    tViewers = text(14f)
    r2.addView(tViewers, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    r2.addView(IconView(ctx, Ic.UP, MUTE), LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(4) })
    tBitrate = text(14f, MUTE)
    r2.addView(tBitrate)
    p.addView(r2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    // 3. сцены
    p.addView(text(12f, MUTE).apply { text = "СЦЕНЫ"; letterSpacing = 0.08f },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) })
    val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
    scenesRow = row()
    hs.addView(scenesRow)
    p.addView(hs)

    // 4. кнопки
    val r4 = row()
    fun action(ic: Ic, label: String, bg: Int, onClick: () -> Unit): Triple<LinearLayout, IconView, TextView> {
      val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = rounded(bg, 12f); setPadding(0, dp(9), 0, dp(8))
        setOnClickListener { onClick() }
      }
      val iv = IconView(ctx, ic, Color.WHITE)
      box.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
      val tv = text(11f).apply { text = label; gravity = Gravity.CENTER }
      box.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
      r4.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
      return Triple(box, iv, tv)
    }
    val (_, mi, mt) = action(Ic.MIC, "Микрофон", CARD2) { actions.onToggleMic() }
    micIcon = mi; micText = mt
    action(Ic.APP, "Редактор", CARD2) { closePanel(); actions.onOpenApp() }
    val (sb, _, st) = action(Ic.STOP, "Стоп", Color.parseColor("#5a1b1b")) {
      if (stopArmed) { closePanel(); actions.onStop() }
      else {
        stopArmed = true
        stopText.text = "Точно?"
        stopBox.background = rounded(RED, 12f)
        main.postDelayed({ disarmStop() }, 3000)
      }
    }
    stopBox = sb; stopText = st
    p.addView(r4, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

    // положение рядом с кружком
    val s = screenSize()
    val pw = minOf(dp(290), s.x - dp(16))
    val estH = dp(250)
    val lp = WindowManager.LayoutParams(
      pw, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      val onLeft = bubbleLp.x + bubbleSize / 2 < s.x / 2
      x = (if (onLeft) bubbleLp.x else bubbleLp.x + bubbleSize - pw).coerceIn(dp(8), (s.x - pw - dp(8)).coerceAtLeast(dp(8)))
      y = if (bubbleLp.y + bubbleSize + dp(8) + estH < s.y) bubbleLp.y + bubbleSize + dp(8)
          else (bubbleLp.y - estH - dp(8)).coerceAtLeast(dp(8))
    }
    p.setOnTouchListener { _, e ->
      if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
    }
    try {
      wm.addView(p, lp)
      panel = p; panelLp = lp
      info?.let { val (lvl, col) = quality(it); fillPanel(it, lvl, col) }
    } catch (_: Exception) {}
  }

  private fun disarmStop() {
    if (!stopArmed || panel == null) { stopArmed = false; return }
    stopArmed = false
    stopText.text = "Стоп"
    stopBox.background = rounded(Color.parseColor("#5a1b1b"), 12f)
  }

  private fun closePanel() {
    val p = panel ?: return
    try { wm.removeView(p) } catch (_: Exception) {}
    panel = null
    stopArmed = false
    panelClosedAt = System.currentTimeMillis()
  }

  private fun fmtUptime(ms: Long): String {
    val s = ms / 1000
    return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
  }

  private fun viewersText(v: Int?): String {
    if (v == null) return if (TwitchClient.loggedIn) "зрители — ждём Twitch" else "войди через Twitch"
    val n = v % 100; val m = v % 10
    val w = if (n in 11..14) "зрителей" else if (m == 1) "зритель" else if (m in 2..4) "зрителя" else "зрителей"
    return "$v $w"
  }

  private fun fillPanel(i: LiveInfo, lvl: Int, col: Int) {
    signal.level = lvl; signal.color = col
    tState.text = when (i.state) { "live" -> "В эфире"; "reconnecting" -> "Переподключение"; else -> "Подключение…" }
    tState.setTextColor(if (i.state == "live") Color.WHITE else YELLOW)
    tUptime.text = if (i.uptimeMs > 0) fmtUptime(i.uptimeMs) else "--:--"
    tViewers.text = viewersText(i.viewers)
    tBitrate.text = if (i.kbps > 0) String.format("%.1f Мбит/с", i.kbps / 1000f) else "—"
    micIcon.ic = if (i.micMuted) (if (i.micLabel == "Звук") Ic.SOUND_OFF else Ic.MIC_OFF) else (if (i.micLabel == "Звук") Ic.SOUND else Ic.MIC)
    micIcon.color = if (i.micMuted) RED else Color.WHITE
    micIcon.invalidate()
    micText.text = if (i.micMuted) "${i.micLabel} выкл" else i.micLabel

    if (scenesRow.childCount != i.scenes.size || scenesRow.tag != i.scenes.joinToString("|") + "#" + i.current) {
      scenesRow.removeAllViews()
      scenesRow.tag = i.scenes.joinToString("|") + "#" + i.current
      i.scenes.forEachIndexed { idx, name ->
        val on = idx == i.current
        val b = text(13f, if (on) Color.WHITE else Color.parseColor("#d6d6de"), bold = on).apply {
          text = name
          background = rounded(if (on) ACCENT else CARD2, 999f)
          setPadding(dp(13), dp(8), dp(13), dp(8))
          setOnClickListener { actions.onScene(idx) }
        }
        scenesRow.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
      }
    }
  }

  @Suppress("unused")
  private fun near(a: Int, b: Int) = abs(a - b) < dp(4)
}
