package com.streamscene.app

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.encoder.input.gl.render.filters.`object`.GifFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Фоновый сервис: захват экрана + звук + живые слои → Twitch (RTMP). */
class StreamService : Service(), ConnectChecker, FloatingControls.Actions {

  companion object {
    const val ACTION_START = "com.streamscene.START"
    const val ACTION_STOP = "com.streamscene.STOP"
    const val ACTION_NEXT = "com.streamscene.NEXT"
    const val ACTION_PREV = "com.streamscene.PREV"
    const val ACTION_PRIVACY = "com.streamscene.PRIVACY"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"
    private const val CHANNEL = "stream"
    private const val NOTIF_ID = 42
    private const val TAG = "StreamScene"
    private const val AUDIO_BITRATE = 160 * 1000

    @Volatile var instance: StreamService? = null
  }

  private val main = Handler(Looper.getMainLooper())
  private var stream: GenericStream? = null
  private var projection: MediaProjection? = null
  private var screenSource: ScreenSource? = null
  private var audioSrc: AudioSource? = null
  private lateinit var renderer: WidgetRenderer

  // фильтры (порядок = порядок слоёв в кадре)
  private val place = PlaceFilterRender()
  private val overlay = ImageObjectFilterRender()
  private var camFilter: CameraFilterRender? = null
  private var camCtl: CameraController? = null
  private var camFacing = ""
  private var dynFilter: ImageObjectFilterRender? = null
  private val tickerFilters = ArrayList<ImageObjectFilterRender>()
  private val gifFilters = ArrayList<GifFilterRender>()
  private var alertFilter: ImageObjectFilterRender? = null
  private val privacyFilter = ImageObjectFilterRender()

  private var current = 0
  private var running = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var controls: FloatingControls? = null
  private var uiState = "connecting"
  private var liveSince = 0L
  private var viewers: Int? = null
  private var kbps = 0L
  private var micMuted = false
  private var fw = 1280
  private var fh = 720

  // приватность
  private var privManual = false
  private var screenLocked = false
  private var appForeground = true
  private var privacyOn = false

  // адаптивный битрейт, батарея, нагрев
  private var adapter: QueueAwareBitrateAdapter? = null
  private var maxVideoBitrate = 0
  private var videoBitrate = 0
  private var thermalCapped = false
  private var warn = ""
  private var batteryWarned = 100
  private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

  // чат и алерты
  private var chat: ChatClient? = null
  private var alerts: AlertsClient? = null
  private val chatLines = ArrayDeque<ChatLine>()
  private val alertQueue = ArrayDeque<AlertEvent>()
  private var alertBusy = false
  private var dynSig = ""
  private var lastDynDraw = 0L
  private var dynPending = false
  private var tickerOffset = FloatArray(4)
  private val tickerBufs = HashMap<Int, ArrayDeque<Bitmap>>()

  private val ticker = object : Runnable {
    override fun run() {
      pushUi()
      redrawDyn(false)
      checkCameraRotation()
      main.postDelayed(this, 1000)
    }
  }
  private val viewerPoll = object : Runnable {
    override fun run() {
      if (running && TwitchClient.loggedIn && uiState == "live") {
        thread {
          val v = TwitchClient.viewers()
          main.post {
            viewers = v
            if (v != null) StreamState.send(JSONObject().put("state", "viewers").put("count", v))
            pushUi(); redrawDyn(true)
          }
        }
      }
      main.postDelayed(this, 20000)
    }
  }
  private val tickerLoop = object : Runnable {
    override fun run() {
      drawTickers()
      main.postDelayed(this, 50)
    }
  }

  private val sysReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      when (i.action) {
        Intent.ACTION_SCREEN_OFF -> { screenLocked = true; updatePrivacy() }
        Intent.ACTION_USER_PRESENT -> { screenLocked = false; updatePrivacy() }
        Intent.ACTION_SCREEN_ON -> {
          val kg = getSystemService(KeyguardManager::class.java)
          if (!kg.isKeyguardLocked) { screenLocked = false; updatePrivacy() }
        }
        Intent.ACTION_BATTERY_CHANGED -> checkBattery(i)
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    TwitchClient.init(this)
    renderer = WidgetRenderer(this)
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Стрим", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel("warn", "Предупреждения эфира", NotificationManager.IMPORTANCE_HIGH))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        startForegroundTyped()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 34) {
          intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
          @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) stopAll("Нет доступа к экрану") else startStreaming(code, data)
      }
      ACTION_STOP -> stopAll()
      ACTION_NEXT -> if (running) switchScene(current + 1) else stopSelf()
      ACTION_PREV -> if (running) switchScene(current - 1) else stopSelf()
      ACTION_PRIVACY -> if (running) onTogglePrivacy() else stopSelf()
      else -> if (!running) stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
  private fun needCamera() = StreamState.config?.scenes?.any { s -> s.widgets.any { it.type == "camera" } } == true

  private fun startForegroundTyped() {
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    if (needCamera() && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    startForeground(NOTIF_ID, buildNotification("Подключение…"), type)
  }

  private fun startStreaming(code: Int, data: Intent) {
    val cfg = StreamState.config ?: return stopAll("Нет настроек стрима")
    try {
      val mpm = getSystemService(MediaProjectionManager::class.java)
      val mp = mpm.getMediaProjection(code, data) ?: return stopAll("Не удалось получить доступ к экрану")
      projection = mp
      mp.registerCallback(object : MediaProjection.Callback() {
        override fun onStop() {
          main.post { if (running) stopAll("Захват экрана остановлен системой") }
        }
      }, main)

      if (!granted(Manifest.permission.RECORD_AUDIO)) return stopAll("Нужно разрешение на запись звука (оно нужно и для звука игры)")
      val audio = when (cfg.audio) {
        "mic" -> MicrophoneSource()
        "game" -> InternalAudioSource(mp)
        else -> MixAudioSource(mp)
      }
      audioSrc = audio
      val s = GenericStream(applicationContext, this, NoVideoSource(), audio)
      s.getGlInterface().setForceRender(true, cfg.fps)

      fw = cfg.width; fh = cfg.height
      val vertical = cfg.height > cfg.width
      val rotation = if (vertical) 90 else 0
      maxVideoBitrate = cfg.bitrate; videoBitrate = cfg.bitrate
      val ok = s.prepareVideo(max(fw, fh), min(fw, fh), cfg.bitrate, cfg.fps, 2, rotation) &&
        s.prepareAudio(44100, true, AUDIO_BITRATE, echoCanceler = false, noiseSuppressor = cfg.noiseSuppress && cfg.audio != "game")
      if (!ok) {
        s.release()
        return stopAll("Телефон не поддерживает выбранное качество — попробуй 720p")
      }
      val screen = ScreenSource(applicationContext, mp)
      s.changeVideoSource(screen)
      screenSource = screen
      s.getStreamClient().setReTries(10)
      if (cfg.autoBitrate) makeAdapter(cfg.bitrate)

      buildFilters(s, cfg)
      stream = s
      running = true
      current = cfg.current.coerceIn(0, max(0, cfg.scenes.size - 1))
      uiState = "connecting"; liveSince = 0L; viewers = null; kbps = 0L; micMuted = false
      privManual = false; screenLocked = false; appForeground = true
      applyScene()
      applyVolumes()
      updatePrivacy()

      StreamState.emit("connecting")
      s.startStream(cfg.url)
      fitVirtualDisplay(cfg)
      main.postDelayed({ fitVirtualDisplay(cfg) }, 800)

      if (cfg.bubble) controls = FloatingControls(this, this).also { it.show() }
      startLiveData(cfg)
      main.post(ticker)
      main.postDelayed(viewerPoll, 15000)
      if (tickerFilters.isNotEmpty()) main.post(tickerLoop)

      val flt = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_BATTERY_CHANGED)
      }
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysReceiver, flt, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(sysReceiver, flt)
      val pm = getSystemService(PowerManager::class.java)
      thermalListener = PowerManager.OnThermalStatusChangedListener { onThermal(it) }.also {
        try { pm.addThermalStatusListener(mainExecutor, it) } catch (_: Exception) {}
      }
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamScene:stream").apply { acquire(8 * 60 * 60 * 1000L) }
    } catch (e: Exception) {
      Log.e(TAG, "start failed", e)
      stopAll("Не удалось запустить: ${e.message}")
    }
  }

  // ---------------- фильтры ----------------
  private fun buildFilters(s: GenericStream, cfg: StreamConfig) {
    val gl = s.getGlInterface()
    gl.addFilter(place)
    gl.addFilter(overlay)
    if (needCamera() && granted(Manifest.permission.CAMERA)) {
      val ctl = CameraController(this).apply { onError = { m -> main.post { warnUser(m) } } }
      camCtl = ctl
      camFilter = CameraFilterRender { st ->
        val w = cfg.scenes.flatMap { it.widgets }.firstOrNull { it.type == "camera" }
        camFacing = w?.facing ?: "front"
        ctl.open(st, camFacing == "front")
      }.also { gl.addFilter(it) }
    }
    val dynTypes = setOf("timer", "clock", "viewers", "chat")
    if (cfg.scenes.any { sc -> sc.widgets.any { it.type in dynTypes } }) {
      dynFilter = ImageObjectFilterRender().also {
        it.setImage(Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)); it.setScale(100f, 100f); it.setPosition(0f, 0f)
        gl.addFilter(it)
      }
    }
    val nTick = min(3, cfg.scenes.maxOfOrNull { sc -> sc.widgets.count { it.type == "ticker" } } ?: 0)
    repeat(nTick) { tickerFilters += ImageObjectFilterRender().also { f -> f.alpha = 0f; gl.addFilter(f) } }
    val allGifs = cfg.scenes.flatMap { sc -> sc.widgets.filter { it.type == "gif" && it.gif != null } }
    val nGif = min(3, cfg.scenes.maxOfOrNull { sc -> sc.widgets.count { it.type == "gif" && it.gif != null } } ?: 0)
    repeat(nGif) {
      gifFilters += GifFilterRender().also { f ->
        try { f.setGif(ByteArrayInputStream(allGifs.first().gif)) } catch (e: Exception) { Log.w(TAG, "gif", e) }
        f.alpha = 0f; gl.addFilter(f)
      }
    }
    if (cfg.scenes.any { sc -> sc.widgets.any { it.type == "alerts" } }) {
      alertFilter = ImageObjectFilterRender().also { f ->
        f.setImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)); f.alpha = 0f; gl.addFilter(f)
      }
    }
    privacyFilter.setImage(renderer.privacyBitmap(fw, fh))
    privacyFilter.setScale(100f, 100f); privacyFilter.setPosition(0f, 0f); privacyFilter.alpha = 0f
    gl.addFilter(privacyFilter)
  }

  private fun sceneWidgets(type: String) = StreamState.config?.scenes?.getOrNull(current)?.widgets?.filter { it.type == type }.orEmpty()

  private fun applyScene() {
    val cfg = StreamState.config ?: return
    if (cfg.scenes.isEmpty()) return
    current = current.coerceIn(0, cfg.scenes.size - 1)
    cfg.current = current
    val sc = cfg.scenes[current]
    val r = sc.screen
    if (r != null) { place.setRect(r.x, r.y, r.w, r.h, r.flip); place.setCrop(r.cl, r.ct, r.cr, r.cb) }
    else { place.setRect(0f, 0f, 1f, 1f, false); place.setCrop(0f, 0f, 0f, 0f) }
    BitmapFactory.decodeByteArray(sc.overlayPng, 0, sc.overlayPng.size)?.let {
      overlay.setImage(it); overlay.setScale(100f, 100f); overlay.setPosition(0f, 0f)
    }
    // камера
    camFilter?.let { cf ->
      val w = sceneWidgets("camera").firstOrNull()
      if (w == null) cf.visible = false
      else {
        if (w.facing != camFacing) { camFacing = w.facing; cf.surfaceTexture?.let { camCtl?.open(it, w.facing == "front") } }
        cf.rx = w.x; cf.ry = w.y; cf.rw = w.w; cf.rh = w.h
        cf.circle = w.shape == "circle"; cf.radius = (w.radius / 100f).coerceIn(0f, .5f); cf.alpha = w.opacity
        cf.aspect = (w.w * fw) / (w.h * fh).coerceAtLeast(1f)
        val b = w.border
        cf.borderW = if (b != null) 0.04f else 0f
        if (b != null) { cf.borderR = Color.red(b) / 255f; cf.borderG = Color.green(b) / 255f; cf.borderB = Color.blue(b) / 255f }
        cf.visible = true
        checkCameraRotation()
      }
    }
    // бегущие строки
    val tks = sceneWidgets("ticker")
    tickerFilters.forEachIndexed { i, f ->
      val w = tks.getOrNull(i)
      if (w == null) f.alpha = 0f else { f.setScale(w.w * 100f, w.h * 100f); f.setPosition(w.x * 100f, w.y * 100f); f.alpha = 1f }
    }
    // GIF
    val gifs = sceneWidgets("gif").filter { it.gif != null }
    gifFilters.forEachIndexed { i, f ->
      val w = gifs.getOrNull(i)
      if (w == null) f.alpha = 0f
      else {
        try { f.setGif(ByteArrayInputStream(w.gif)) } catch (e: Exception) { Log.w(TAG, "gif", e) }
        f.setScale(w.w * 100f, w.h * 100f); f.setPosition(w.x * 100f, w.y * 100f); f.alpha = w.opacity
      }
    }
    redrawDyn(true)
    updateNotification()
    pushUi()
    StreamState.emit("scene", sc.name) { put("index", current) }
  }

  fun switchScene(index: Int) {
    val n = StreamState.config?.scenes?.size ?: return
    if (n == 0) return
    current = ((index % n) + n) % n
    applyScene()
  }

  // ---------------- живые слои ----------------
  private fun fmtUp(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) }

  private fun redrawDyn(force: Boolean) {
    val f = dynFilter ?: return
    val ws = StreamState.config?.scenes?.getOrNull(current)?.widgets?.filter { it.type in setOf("timer", "clock", "viewers", "chat") }.orEmpty()
    val now = System.currentTimeMillis()
    val up = if (liveSince > 0) fmtUp(now - liveSince) else "0:00:00"
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val vtxt = viewers?.toString() ?: "—"
    val sig = "$current|$up|$clock|$vtxt|${chatLines.size}|${chatLines.lastOrNull()?.text}|${ws.size}"
    if (!force && sig == dynSig) return
    if (now - lastDynDraw < 400) {
      if (!dynPending) { dynPending = true; main.postDelayed({ dynPending = false; redrawDyn(true) }, 450) }
      return
    }
    dynSig = sig; lastDynDraw = now
    val bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    for (w in ws) {
      val r = renderer.rectOf(w, fw, fh)
      when (w.type) {
        "timer" -> renderer.drawTextBox(c, w, r, up)
        "clock" -> renderer.drawTextBox(c, w, r, clock)
        "viewers" -> renderer.drawTextBox(c, w, r, vtxt, Ic.EYE)
        "chat" -> renderer.drawChat(c, w, r, fh, chatLines.toList())
      }
    }
    f.setImage(bmp); f.setScale(100f, 100f); f.setPosition(0f, 0f)
  }

  private fun drawTickers() {
    val tks = sceneWidgets("ticker")
    tickerFilters.forEachIndexed { i, f ->
      val w = tks.getOrNull(i) ?: return@forEachIndexed
      val pw = max(8, (w.w * fw).toInt()); val ph = max(8, (w.h * fh).toInt())
      val pool = tickerBufs.getOrPut(i) { ArrayDeque() }
      var bmp = if (pool.size >= 3) pool.removeFirst() else null
      if (bmp == null || bmp.width != pw || bmp.height != ph) bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
      bmp!!.eraseColor(Color.TRANSPARENT)
      tickerOffset[i] += ph * 0.09f * w.speed
      renderer.drawTicker(Canvas(bmp), w, android.graphics.RectF(0f, 0f, pw.toFloat(), ph.toFloat()), tickerOffset[i])
      pool.addLast(bmp)
      f.setImage(bmp)
      f.setScale(w.w * 100f, w.h * 100f); f.setPosition(w.x * 100f, w.y * 100f)
    }
  }

  private fun checkCameraRotation() {
    val cf = camFilter ?: return
    val ctl = camCtl ?: return
    val w = sceneWidgets("camera").firstOrNull() ?: return
    val disp = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val deg = (disp?.rotation ?: 0) * 90
    val base = if (ctl.isFront) (ctl.sensorOrientation + deg) % 360 else (ctl.sensorOrientation - deg + 360) % 360
    val rot = ((base + w.rot) % 360) / 90
    cf.rotQuarter = rot
    cf.mirror = w.mirror
    val camAR = if (rot % 2 == 1) ctl.size.height.toFloat() / ctl.size.width else ctl.size.width.toFloat() / ctl.size.height
    val rectAR = cf.aspect
    if (camAR > rectAR) { cf.scaleX = rectAR / camAR; cf.scaleY = 1f } else { cf.scaleX = 1f; cf.scaleY = camAR / rectAR }
  }

  private fun startLiveData(cfg: StreamConfig) {
    val channel = cfg.chatChannel.ifEmpty { if (TwitchClient.loggedIn) TwitchClient.info(this).optString("login") else "" }
    if (channel.isNotEmpty()) {
      chat = ChatClient(channel) { line ->
        chatLines.addLast(line)
        while (chatLines.size > 30) chatLines.removeFirst()
        redrawDyn(false); pushUi()
      }.also { it.start() }
    }
    if (alertFilter != null && TwitchClient.loggedIn) {
      alerts = AlertsClient(TwitchClient.userId, { ev -> queueAlert(ev) }, { msg ->
        StreamState.send(JSONObject().put("state", "info").put("msg", msg))
      }).also { it.start() }
    }
  }

  private fun queueAlert(ev: AlertEvent) {
    StreamState.send(JSONObject().put("state", "alert").put("name", ev.name).put("title", ev.title))
    alertQueue.addLast(ev)
    if (!alertBusy) showNextAlert()
  }

  fun testAlert() {
    val samples = listOf(
      AlertEvent("follow", "Pelmen_TV", "новый фолловер"),
      AlertEvent("sub", "NightOwl", "подписка · 3 месяца", "Спасибо за стримы!"),
      AlertEvent("cheer", "KoshkaGamer", "500 битов", "Держи на чай"),
      AlertEvent("raid", "ZvezdaTV", "рейд · 42 зрителя")
    )
    queueAlert(samples.random())
  }

  private fun showNextAlert() {
    val f = alertFilter
    val ev = alertQueue.removeFirstOrNull()
    if (ev == null || f == null || !running) { alertBusy = false; return }
    val w = sceneWidgets("alerts").firstOrNull { ev.kind in it.alertKinds || (ev.kind == "gift" && "sub" in it.alertKinds) }
    if (w == null) { alertBusy = false; showNextAlert(); return }
    alertBusy = true
    val pw = max(8, (w.w * fw).toInt()); val ph = max(8, (w.h * fh).toInt())
    f.setImage(renderer.alertBitmap(w, pw, ph, ev))
    f.setScale(w.w * 100f, w.h * 100f)
    if (w.alertSound) AlertSound.play(ev.kind)
    val steps = 10; val slide = 0.04f
    fun frame(i: Int, show: Boolean) {
      val k = i / steps.toFloat()
      val a = if (show) k else 1f - k
      f.alpha = a * w.opacity
      f.setPosition(w.x * 100f, (w.y + slide * (1f - a)) * 100f)
    }
    for (i in 0..steps) main.postDelayed({ frame(i, true) }, i * 30L)
    val hold = w.alertSeconds.coerceIn(2, 20) * 1000L
    for (i in 0..steps) main.postDelayed({ frame(i, false) }, 330L + hold + i * 30L)
    main.postDelayed({ f.alpha = 0f; showNextAlert() }, 330L + hold + 400L)
  }

  // ---------------- приватность и звук ----------------
  fun setAppForeground(fg: Boolean) {
    appForeground = fg
    if (running) updatePrivacy()
  }

  private fun updatePrivacy() {
    val cfg = StreamState.config ?: return
    val on = privManual || (cfg.hideOnLock && screenLocked) || (cfg.hideInApp && appForeground)
    if (on == privacyOn) return
    privacyOn = on
    privacyFilter.alpha = if (on) 1f else 0f
    applyVolumes()
    pushUi()
    StreamState.send(JSONObject().put("state", "privacy").put("on", on).put("manual", privManual))
  }

  fun setVolumes(game: Float, mic: Float) {
    StreamState.config?.let { it.gameVolume = game; it.micVolume = mic }
    applyVolumes()
  }

  private fun applyVolumes() {
    val cfg = StreamState.config ?: return
    when (val a = audioSrc) {
      is MixAudioSource -> {
        a.microphoneVolume = if (micMuted) 0f else cfg.micVolume
        a.internalVolume = if (privacyOn) 0f else cfg.gameVolume
      }
      is MicrophoneSource -> if (micMuted) a.mute() else a.unMute()
      is InternalAudioSource -> if (micMuted || privacyOn) a.mute() else a.unMute()
      else -> {}
    }
  }

  // ---------------- автокачество, батарея, нагрев ----------------
  private fun makeAdapter(maxVideo: Int) {
    adapter = QueueAwareBitrateAdapter(maxVideo + AUDIO_BITRATE, max(400_000, maxVideo / 5) + AUDIO_BITRATE) { b ->
      val v = (b - AUDIO_BITRATE).coerceAtLeast(300_000)
      videoBitrate = v
      try { stream?.setVideoBitrateOnFly(v) } catch (_: Exception) {}
    }
  }

  private fun onThermal(status: Int) {
    if (!running) return
    if (status >= PowerManager.THERMAL_STATUS_SEVERE && !thermalCapped) {
      thermalCapped = true
      val capped = (maxVideoBitrate * 0.6f).toInt()
      if (adapter != null) makeAdapter(capped)
      try { stream?.setVideoBitrateOnFly(capped) } catch (_: Exception) {}
      videoBitrate = capped
      warnUser("Телефон сильно нагрелся — снизил качество эфира")
    } else if (status >= PowerManager.THERMAL_STATUS_MODERATE && status < PowerManager.THERMAL_STATUS_SEVERE) {
      warnUser("Телефон греется. Сними чехол или убавь яркость")
    } else if (status < PowerManager.THERMAL_STATUS_MODERATE && thermalCapped) {
      thermalCapped = false
      if (adapter != null) makeAdapter(maxVideoBitrate) else try { stream?.setVideoBitrateOnFly(maxVideoBitrate) } catch (_: Exception) {}
      warnUser("")
    }
  }

  private fun checkBattery(i: Intent) {
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    if (level < 0) return
    if (charging) { batteryWarned = 100; if (warn.startsWith("Батарея")) warnUser(""); return }
    if (level <= 5 && batteryWarned > 5) { batteryWarned = 5; warnUser("Батарея $level% — эфир скоро прервётся, подключи зарядку") }
    else if (level <= 15 && batteryWarned > 15) { batteryWarned = 15; warnUser("Батарея $level% — подключи зарядку") }
  }

  private fun warnUser(msg: String) {
    warn = msg
    pushUi()
    updateNotification()
    if (msg.isNotEmpty()) {
      StreamState.send(JSONObject().put("state", "warn").put("msg", msg))
      try {
        getSystemService(NotificationManager::class.java).notify(43,
          Notification.Builder(this, "warn").setSmallIcon(R.drawable.ic_notif).setContentTitle("СтримСцена").setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg)).setAutoCancel(true).build())
      } catch (_: Exception) {}
    }
  }

  /** Делаем виртуальный экран с пропорциями телефона, чтобы не было чёрных полос. */
  private fun fitVirtualDisplay(cfg: StreamConfig) {
    try {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
      val l = max(dm.widthPixels, dm.heightPixels).toFloat()
      val sh = min(dm.widthPixels, dm.heightPixels).toFloat()
      val base = max(cfg.width, cfg.height)
      val other = ((base * sh / l) / 2f).roundToInt() * 2
      if (cfg.gameLandscape) screenSource?.resize(base, other) else screenSource?.resize(other, base)
    } catch (e: Exception) {
      Log.w(TAG, "resize failed", e)
    }
  }

  // ---------------- уведомление ----------------
  private fun buildNotification(text: String): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    fun action(title: String, act: String, req: Int): Notification.Action {
      val pi = PendingIntent.getService(
        this, req, Intent(this, StreamService::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), title, pi).build()
    }
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notif)
      .setContentTitle("СтримСцена")
      .setContentText(text)
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(action("След. сцена", ACTION_NEXT, 2))
      .addAction(action(if (privacyOn) "Показать" else "Скрыть", ACTION_PRIVACY, 4))
      .addAction(action("Стоп", ACTION_STOP, 3))
      .build()
  }

  private var statusText = "Подключение…"
  private fun updateNotification() {
    if (!running) return
    val name = StreamState.config?.scenes?.getOrNull(current)?.name ?: ""
    val extra = if (privacyOn) " · экран скрыт" else ""
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("$statusText · $name$extra" + if (warn.isNotEmpty()) " · $warn" else ""))
  }

  private fun stopAll(error: String = "") {
    running = false
    main.removeCallbacksAndMessages(null)
    controls?.hide(); controls = null
    chat?.stop(); chat = null
    alerts?.stop(); alerts = null
    camCtl?.close(); camCtl = null
    try { unregisterReceiver(sysReceiver) } catch (_: Exception) {}
    thermalListener?.let { try { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) } catch (_: Exception) {} }
    thermalListener = null
    try {
      stream?.let {
        if (it.isStreaming) it.stopStream()
        it.release()
      }
    } catch (e: Exception) {
      Log.w(TAG, "stop failed", e)
    }
    stream = null
    screenSource = null
    try { projection?.stop() } catch (_: Exception) {}
    projection = null
    try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    wakeLock = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    StreamState.emit(if (error.isEmpty()) "stopped" else "error", error)
  }

  override fun onDestroy() {
    if (running) stopAll()
    instance = null
    super.onDestroy()
  }

  // ---------- ConnectChecker ----------
  override fun onConnectionStarted(url: String) {
    StreamState.emit("connecting")
  }

  override fun onConnectionSuccess() {
    main.post {
      statusText = "В эфире"
      if (liveSince == 0L) liveSince = System.currentTimeMillis()
      uiState = "live"
      updateNotification(); pushUi(); redrawDyn(true)
      StreamState.emit("live")
    }
  }

  override fun onConnectionFailed(reason: String) {
    main.post {
      val s = stream ?: return@post
      if (running && s.getStreamClient().reTry(5000, reason, null)) {
        statusText = "Переподключение…"
        uiState = "reconnecting"
        updateNotification(); pushUi()
        StreamState.emit("reconnecting", reason)
      } else {
        stopAll("Ошибка соединения: $reason")
      }
    }
  }

  override fun onNewBitrate(bitrate: Long) {
    kbps = bitrate / 1000
    StreamState.emit("bitrate") { put("kbps", kbps); put("target", videoBitrate / 1000); put("max", maxVideoBitrate / 1000) }
  }

  override fun onStreamingStats(report: StreamingStatsReport) {
    adapter?.onStreamingStats(report)
  }

  override fun onDisconnect() {
    if (running) main.post { uiState = "reconnecting"; pushUi(); StreamState.emit("reconnecting", "Связь потеряна") }
  }

  override fun onAuthError() {
    main.post { stopAll("Twitch отклонил ключ трансляции — проверь ключ") }
  }

  override fun onAuthSuccess() {}

  // ---------- плавающий кружок ----------
  private fun micLabel() = if (StreamState.config?.audio == "game") "Звук" else "Микрофон"

  private fun pushUi() {
    val c = controls ?: return
    val cfg = StreamState.config ?: return
    c.update(
      LiveInfo(
        state = uiState, viewers = viewers, kbps = kbps, targetKbps = maxVideoBitrate / 1000,
        uptimeMs = if (liveSince > 0) System.currentTimeMillis() - liveSince else 0L,
        scenes = cfg.scenes.map { it.name }, current = current, micMuted = micMuted, micLabel = micLabel(),
        privacy = privacyOn, warn = warn, chat = chatLines.toList().takeLast(4)
      )
    )
  }

  override fun onScene(index: Int) = switchScene(index)

  override fun onToggleMic() {
    micMuted = !micMuted
    applyVolumes()
    pushUi()
    StreamState.send(JSONObject().put("state", "mic").put("muted", micMuted))
  }

  override fun onTogglePrivacy() {
    privManual = !privacyOn
    if (!privManual) { screenLocked = false; appForeground = false }
    updatePrivacy()
    updateNotification()
  }

  fun setPrivacy(on: Boolean) {
    privManual = on
    if (!on) appForeground = false
    updatePrivacy(); updateNotification()
  }

  override fun onOpenApp() {
    try {
      startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } catch (_: Exception) {}
  }

  override fun onStop() = stopAll()
}
