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
  val micLabel: String,
  val privacy: Boolean = false,
  val warn: String = "",
  val chat: List<ChatLine> = emptyList(),
  val events: List<AlertEvent> = emptyList(),
  val recording: Boolean = false
)

/**
 * Плавающий кружок поверх игры. Нажатие открывает панель:
 * соединение, время в эфире, зрители, битрейт, сцены, микрофон, стоп.
 */
class FloatingControls(private val ctx: Context, private val actions: Actions) {

  interface Actions {
    fun onScene(index: Int)
    fun onToggleMic()
    fun onTogglePrivacy()
    fun onHighlight(line: ChatLine)
    fun onOpenApp()
    fun onToggleRecord()
    fun onQuickEdit()
    fun onStop()
  }

  private val wm = ctx.getSystemService(WindowManager::class.java)
  private val prefs = ctx.getSharedPreferences("bubble", Context.MODE_PRIVATE)
  private val main = Handler(Looper.getMainLooper())
  private val density = ctx.resources.displayMetrics.density
  private fun dp(v: Int) = (v * density).toInt()
  private fun dpf(v: Float) = v * density

  // неоновая палитра (как в приложении)
  private val ACCENT = Color.parseColor("#FF3DA8")
  private val CYAN = Color.parseColor("#3DF2FF")
  private val ON_ACC = Color.parseColor("#140F2B")
  private val RED = Color.parseColor("#FF6B8A")
  private val YELLOW = Color.parseColor("#FFB13D")
  private val GREEN = Color.parseColor("#4BE39A")
  private val MUTE = Color.parseColor("#A79FD0")
  private val TEXT = Color.parseColor("#F1EEFF")
  private val CARD = Color.parseColor("#1C1540")
  private val CARD2 = Color.parseColor("#221A47")
  private val LINE = Color.parseColor("#2E2558")
  private val disp: Typeface by lazy { try { Typeface.create(Typeface.createFromAsset(ctx.assets, "fonts/unbounded.ttf"), 700, false) } catch (_: Exception) { Typeface.DEFAULT_BOLD } }

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
  private var panel: View? = null
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
  private lateinit var recBox: LinearLayout
  private lateinit var recIcon: IconView
  private lateinit var recText: TextView
  private lateinit var privIcon: IconView
  private lateinit var privText: TextView
  private lateinit var privBox: LinearLayout
  private lateinit var warnRow: LinearLayout
  private lateinit var warnText: TextView
  private lateinit var chatBox: LinearLayout
  private lateinit var chatHead: TextView
  private lateinit var evHead: TextView
  private lateinit var evBox: LinearLayout

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
    bubble.ringColor = when { i.privacy -> MUTE; i.state == "live" || i.state == "rec" -> ACCENT; i.state == "reconnecting" -> RED; else -> YELLOW }
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
  private fun head(t: String) = text(13f, TEXT).apply { text = t; typeface = disp }
  private fun text(size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
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

  private fun statCard(parent: LinearLayout, valueColor: Int, label: String, last: Boolean = false): TextView {
    val box = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(CARD, 14f, LINE); setPadding(dp(10), dp(9), dp(10), dp(9))
    }
    val v = text(19f, valueColor).apply { typeface = disp; text = "—"; maxLines = 1 }
    box.addView(v)
    box.addView(text(11f, MUTE).apply { text = label }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
    parent.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!last) rightMargin = dp(8) })
    return v
  }

  private fun openPanel() {
    val p = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = rounded(Color.parseColor("#F5140F2B"), 22f, LINE)
      setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    // 1. состояние + время
    val r1 = row()
    signal = SignalView(ctx)
    r1.addView(signal, LinearLayout.LayoutParams(dp(22), dp(16)).apply { rightMargin = dp(10) })
    tState = text(15f).apply { typeface = disp }
    r1.addView(tState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    tUptime = text(15f, CYAN).apply { typeface = disp; fontFeatureSettings = "tnum" }
    r1.addView(tUptime)
    p.addView(r1)

    // 2. цифры
    val r2 = row()
    tViewers = statCard(r2, ACCENT, "зрителей")
    tBitrate = statCard(r2, CYAN, "Мбит/с", last = true)
    p.addView(r2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

    // предупреждение (батарея, нагрев)
    warnRow = row().apply {
      background = rounded(Color.parseColor("#3A2A20"), 12f); setPadding(dp(10), dp(8), dp(10), dp(8)); visibility = View.GONE
    }
    warnRow.addView(IconView(ctx, Ic.WARN, YELLOW), LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
    warnText = text(12f, YELLOW)
    warnRow.addView(warnText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    p.addView(warnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    // события
    evHead = head("События").apply { visibility = View.GONE }
    p.addView(evHead, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) })
    evBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    p.addView(evBox)

    // чат
    chatHead = head("Чат").apply { visibility = View.GONE }
    p.addView(chatHead, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) })
    chatBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    p.addView(chatBox)

    // 3. сцены
    p.addView(head("Сцены"),
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) })
    val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
    scenesRow = row()
    hs.addView(scenesRow)
    p.addView(hs)

    // 4. кнопки: два ряда по три
    val r4 = row()
    val r5 = row()
    var target = r4
    fun action(ic: Ic, label: String, bg: Int, onClick: () -> Unit): Triple<LinearLayout, IconView, TextView> {
      val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = rounded(bg, 14f, LINE); setPadding(0, dp(11), 0, dp(10)); minimumHeight = dp(60)
        setOnClickListener { onClick() }
      }
      val iv = IconView(ctx, ic, TEXT)
      box.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
      val tv = text(12f).apply { text = label; gravity = Gravity.CENTER }
      box.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
      target.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
      return Triple(box, iv, tv)
    }
    val (_, mi, mt) = action(Ic.MIC, "Микрофон", CARD2) { actions.onToggleMic() }
    micIcon = mi; micText = mt
    val (pb, pi, pt) = action(Ic.EYE_OFF, "Скрыть", CARD2) { actions.onTogglePrivacy() }
    privBox = pb; privIcon = pi; privText = pt
    val (rb, ri, rt) = action(Ic.REC, "Запись", CARD2) { actions.onToggleRecord() }
    recBox = rb; recIcon = ri; recText = rt
    target = r5
    action(Ic.LAYERS, "Правка", CARD2) { closePanel(); actions.onQuickEdit() }
    action(Ic.APP, "Приложение", CARD2) { closePanel(); actions.onOpenApp() }
    val (sb, si, st) = action(Ic.STOP, "Стоп", Color.parseColor("#2A1433")) {
      if (stopArmed) { closePanel(); actions.onStop() }
      else {
        stopArmed = true
        stopText.text = "Точно?"
        stopBox.background = rounded(RED, 14f); stopText.setTextColor(ON_ACC)
        main.postDelayed({ disarmStop() }, 3000)
      }
    }
    stopBox = sb; stopText = st; si.color = RED; st.setTextColor(RED)
    sb.background = rounded(Color.parseColor("#2A1433"), 14f, RED)
    p.addView(r4, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    p.addView(r5, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

    // положение рядом с кружком; в горизонтальной игре — прокрутка, ничего не обрезается
    val s = screenSize()
    val pw = minOf(dp(310), s.x - dp(16))
    val maxH = s.y - dp(16)
    val estH = minOf(dp(460), maxH)
    val content = p
    val scroll = object : android.widget.ScrollView(ctx) {
      override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; background = content.background; content.background = null; clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
    scroll.addView(content)
    val lp = WindowManager.LayoutParams(
      pw, WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      val onLeft = bubbleLp.x + bubbleSize / 2 < s.x / 2
      x = (if (onLeft) bubbleLp.x else bubbleLp.x + bubbleSize - pw).coerceIn(dp(8), (s.x - pw - dp(8)).coerceAtLeast(dp(8)))
      y = (if (bubbleLp.y + bubbleSize + dp(8) + estH < s.y) bubbleLp.y + bubbleSize + dp(8)
          else bubbleLp.y - estH - dp(8)).coerceIn(dp(8), (s.y - estH - dp(8)).coerceAtLeast(dp(8)))
    }
    scroll.setOnTouchListener { _, e ->
      if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false
    }
    try {
      wm.addView(scroll, lp)
      panel = scroll; panelLp = lp
      info?.let { val (lvl, col) = quality(it); fillPanel(it, lvl, col) }
    } catch (_: Exception) {}
  }

  private fun disarmStop() {
    if (!stopArmed || panel == null) { stopArmed = false; return }
    stopArmed = false
    stopText.text = "Стоп"
    stopBox.background = rounded(Color.parseColor("#2A1433"), 14f, RED); stopText.setTextColor(RED)
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


  private fun fillPanel(i: LiveInfo, lvl: Int, col: Int) {
    signal.level = lvl; signal.color = col
    tState.text = when (i.state) { "live" -> if (i.recording) "В эфире · запись" else "В эфире"; "rec" -> "Запись"; "reconnecting" -> "Переподключение"; else -> "Подключение…" }
    tState.setTextColor(if (i.state == "live" || i.state == "rec") TEXT else YELLOW)
    recIcon.color = if (i.recording) RED else TEXT; recIcon.invalidate()
    recText.text = if (i.recording) "Стоп запись" else "Запись"
    recBox.background = rounded(if (i.recording) Color.parseColor("#3A1430") else CARD2, 14f, if (i.recording) RED else LINE)
    tUptime.text = if (i.uptimeMs > 0) fmtUptime(i.uptimeMs) else "--:--"
    tViewers.text = i.viewers?.toString() ?: "—"
    tBitrate.text = if (i.kbps > 0) String.format("%.1f", i.kbps / 1000f) else "—"
    micIcon.ic = if (i.micMuted) (if (i.micLabel == "Звук") Ic.SOUND_OFF else Ic.MIC_OFF) else (if (i.micLabel == "Звук") Ic.SOUND else Ic.MIC)
    micIcon.color = if (i.micMuted) RED else TEXT
    micIcon.invalidate()
    micText.text = if (i.micMuted) "${i.micLabel} выкл" else i.micLabel
    privIcon.ic = if (i.privacy) Ic.EYE else Ic.EYE_OFF; privIcon.invalidate()
    privText.text = if (i.privacy) "Показать" else "Скрыть"
    privBox.background = rounded(if (i.privacy) ACCENT else CARD2, 14f, if (i.privacy) ACCENT else LINE)
    privIcon.color = if (i.privacy) ON_ACC else TEXT; privText.setTextColor(if (i.privacy) ON_ACC else TEXT)
    warnRow.visibility = if (i.warn.isEmpty()) View.GONE else View.VISIBLE
    warnText.text = i.warn
    if (i.privacy && i.warn.isEmpty()) { warnRow.visibility = View.VISIBLE; warnText.text = "Экран скрыт — зрители видят заставку" }
    val showChat = i.chat.isNotEmpty()
    chatHead.visibility = if (showChat) View.VISIBLE else View.GONE
    chatBox.visibility = if (showChat) View.VISIBLE else View.GONE
    chatBox.removeAllViews()
    i.chat.forEach { m ->
      val sb = android.text.SpannableStringBuilder(m.name)
      sb.setSpan(android.text.style.ForegroundColorSpan(m.color), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.append(": ").append(m.text)
      chatBox.addView(text(14f, TEXT).apply {
        text = sb; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), dp(4), dp(6), dp(4))
        background = rounded(Color.TRANSPARENT, 8f)
        setOnClickListener { actions.onHighlight(m) }
      })
    }
    evHead.visibility = if (i.events.isEmpty()) View.GONE else View.VISIBLE
    evBox.visibility = evHead.visibility
    evBox.removeAllViews()
    i.events.takeLast(3).asReversed().forEach { e ->
      val r = row().apply { setPadding(0, dp(3), 0, dp(3)) }
      val ic = when (e.kind) { "follow" -> Ic.HEART; "sub", "gift" -> Ic.STAR; "cheer" -> Ic.GEM; "raid" -> Ic.RAID; "donate" -> Ic.COIN; else -> Ic.STAR }
      val ec = when (e.kind) { "donate", "raid" -> YELLOW; "follow", "cheer" -> ACCENT; else -> CYAN }
      r.addView(IconView(ctx, ic, ec), LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(9) })
      r.addView(text(14f).apply { text = "${e.name} ${e.title}"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      evBox.addView(r)
    }

    if (scenesRow.childCount != i.scenes.size || scenesRow.tag != i.scenes.joinToString("|") + "#" + i.current) {
      scenesRow.removeAllViews()
      scenesRow.tag = i.scenes.joinToString("|") + "#" + i.current
      i.scenes.forEachIndexed { idx, name ->
        val on = idx == i.current
        val b = text(13f, if (on) ON_ACC else TEXT, bold = on).apply {
          text = name
          background = rounded(if (on) ACCENT else CARD2, 999f)
          setPadding(dp(15), dp(11), dp(15), dp(11))
          setOnClickListener { actions.onScene(idx) }
        }
        scenesRow.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
      }
    }
  }

  @Suppress("unused")
  private fun near(a: Int, b: Int) = abs(a - b) < dp(4)
}
