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
import android.content.ContentValues
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.speech.tts.TextToSpeech
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.GifFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.util.QueueAwareBitrateAdapter
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
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
    private val DYN_TYPES = setOf("timer", "clock", "viewers", "chat", "goal")
    // порядок слоёв в кадре (снизу вверх)
    private const val R_PLACE = 0; private const val R_OVERLAY = 1; private const val R_CAM = 2; private const val R_DYN = 3
    private const val R_TICK = 4; private const val R_GIF = 5; private const val R_WEB = 6; private const val R_AGIF = 7
    private const val R_ALERT = 8; private const val R_HL = 9; private const val R_PRIV = 10
  }

  // зеркало цепочки фильтров, чтобы добавлять слои на лету в нужное место
  private var glI: GlStreamInterface? = null
  private val chain = ArrayList<BaseFilterRender>()
  private val rankOf = HashMap<BaseFilterRender, Int>()
  private var tickerRunning = false
  private var fitTries = 0
  private var fitW = 0
  private var fitH = 0
  private var camTypeOn = false

  // цель доната
  private var goalAmount = 0.0
  private var goalRev = ""

  // запись
  private var recFile: File? = null
  private var recordOnly = false

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
  private var alertGif: GifFilterRender? = null
  private val webFilters = ArrayList<WebFilterRender>()
  private val webHosts = ArrayList<WebWidgetHost>()
  private val webState = ArrayList<String>()
  private var highlightFilter: ImageObjectFilterRender? = null
  private val events = ArrayDeque<AlertEvent>()
  private var da: DaAlertsClient? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var player: MediaPlayer? = null
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
      updatePhoneAspect()
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
    CrashLog.install(this)
    TwitchClient.init(this)
    DaClient.init(this)
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
    if (needCamera() && granted(Manifest.permission.CAMERA)) { type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA; camTypeOn = true }
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
      if (cfg.delaySec > 0) s.getStreamClient().setDelay(cfg.delaySec * 1000L)
      recordOnly = cfg.recordOnly
      if (cfg.autoBitrate && !recordOnly) makeAdapter(cfg.bitrate)

      buildFilters(s, cfg)
      stream = s
      running = true
      current = cfg.current.coerceIn(0, max(0, cfg.scenes.size - 1))
      uiState = "connecting"; liveSince = 0L; viewers = null; kbps = 0L; micMuted = false; fitTries = 0; fitW = 0; fitH = 0
      privManual = false; screenLocked = false; appForeground = true
      applyScene()
      applyVolumes()
      updatePrivacy()

      if (recordOnly) {
        if (!startRecording()) return stopAll("Не удалось начать запись")
        statusText = "Запись"; uiState = "rec"; liveSince = System.currentTimeMillis()
        StreamState.emit("recording")
      } else {
        StreamState.emit("connecting")
        s.startStream(cfg.url)
        if (cfg.record) startRecording()
      }

      if (cfg.bubble) controls = FloatingControls(this, this).also { it.show() }
      startLiveData(cfg)
      main.post(ticker)
      main.postDelayed(viewerPoll, 15000)

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
  private fun addF(f: BaseFilterRender, rank: Int) {
    val gl = glI ?: return
    var pos = chain.indexOfFirst { (rankOf[it] ?: 0) > rank }
    if (pos < 0) pos = chain.size
    chain.add(pos, f); rankOf[f] = rank
    if (pos == chain.size - 1) gl.addFilter(f) else gl.addFilter(pos, f)
  }

  private fun buildFilters(s: GenericStream, cfg: StreamConfig) {
    glI = s.getGlInterface()
    chain.clear(); rankOf.clear()
    addF(place, R_PLACE)
    addF(overlay, R_OVERLAY)
    highlightFilter = ImageObjectFilterRender().also { f ->
      f.setImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)); f.alpha = 0f; addF(f, R_HL)
    }
    val customPriv = cfg.privacyPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    privacyFilter.setImage(customPriv ?: renderer.privacyBitmap(fw, fh))
    privacyFilter.setScale(100f, 100f); privacyFilter.setPosition(0f, 0f); privacyFilter.alpha = 0f
    addF(privacyFilter, R_PRIV)
    ensureFilters(cfg)
  }

  /** Добавляет слои, которых ещё нет (можно во время эфира — без перезапуска). */
  private fun ensureFilters(cfg: StreamConfig) {
    if (glI == null) return
    val all = cfg.scenes.flatMap { it.widgets }
    if (camFilter == null && needCamera()) {
      if (!granted(Manifest.permission.CAMERA)) warnUser("Разреши камеру для Glowcast, чтобы она появилась в эфире")
      else {
        if (!camTypeOn && running) try { startForegroundTyped() } catch (e: Exception) { Log.w(TAG, "fg camera", e) }
        val ctl = CameraController(this).apply { onError = { m -> main.post { warnUser(m) } } }
        camCtl = ctl
        camFilter = CameraFilterRender { st ->
          val w = sceneWidgets("camera").firstOrNull() ?: StreamState.config?.scenes?.flatMap { it.widgets }?.firstOrNull { it.type == "camera" }
          camFacing = w?.facing ?: "front"
          ctl.open(st, camFacing == "front")
        }.also { it.visible = false; addF(it, R_CAM) }
      }
    }
    if (dynFilter == null && all.any { it.type in DYN_TYPES }) {
      dynFilter = ImageObjectFilterRender().also {
        it.setImage(Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)); it.setScale(100f, 100f); it.setPosition(0f, 0f)
        addF(it, R_DYN)
      }
    }
    val nTick = min(3, cfg.scenes.maxOfOrNull { sc -> sc.widgets.count { it.type == "ticker" } } ?: 0)
    while (tickerFilters.size < nTick) tickerFilters += ImageObjectFilterRender().also { f ->
      f.setImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)); f.alpha = 0f; addF(f, R_TICK)
    }
    if (tickerFilters.isNotEmpty() && !tickerRunning && running) { tickerRunning = true; main.post(tickerLoop) }
    val firstGif = all.firstOrNull { it.type == "gif" && it.gif != null }?.gif
    val nGif = min(3, cfg.scenes.maxOfOrNull { sc -> sc.widgets.count { it.type == "gif" && it.gif != null } } ?: 0)
    while (gifFilters.size < nGif && firstGif != null) gifFilters += GifFilterRender().also { f ->
      try { f.setGif(ByteArrayInputStream(firstGif)) } catch (e: Exception) { Log.w(TAG, "gif", e) }
      f.alpha = 0f; addF(f, R_GIF)
    }
    val nWeb = min(3, cfg.scenes.maxOfOrNull { sc -> sc.widgets.count { it.type == "web" && it.url.isNotBlank() } } ?: 0)
    while (webFilters.size < nWeb) {
      val idx = webFilters.size
      webHosts += WebWidgetHost(this); webState += ""
      webFilters += WebFilterRender { st -> attachWeb(idx, st) }.also { it.visible = false; addF(it, R_WEB) }
    }
    val alertWs = all.filter { it.type == "alerts" }
    val gifMedia = alertWs.flatMap { it.media.values }.firstOrNull { it.isGif && it.img != null }
    if (alertGif == null && gifMedia != null) {
      alertGif = GifFilterRender().also { f ->
        try { f.setGif(ByteArrayInputStream(gifMedia.img)) } catch (e: Exception) { Log.w(TAG, "alert gif", e) }
        f.alpha = 0f; addF(f, R_AGIF)
      }
    }
    if (alertFilter == null && alertWs.isNotEmpty()) {
      alertFilter = ImageObjectFilterRender().also { f ->
        f.setImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)); f.alpha = 0f; addF(f, R_ALERT)
      }
    }
    if (tts == null && alertWs.any { it.tts }) {
      tts = TextToSpeech(this) { st ->
        ttsReady = st == TextToSpeech.SUCCESS
        if (ttsReady) {
          tts?.language = Locale("ru", "RU")
          tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
      }
    }
    syncGoal(cfg)
  }

  private fun sceneWidgets(type: String) = StreamState.config?.scenes?.getOrNull(current)?.widgets?.filter { it.type == type }.orEmpty()

  private fun applyScene() {
    val cfg = StreamState.config ?: return
    if (cfg.scenes.isEmpty()) return
    current = current.coerceIn(0, cfg.scenes.size - 1)
    cfg.current = current
    val sc = cfg.scenes[current]
    ensureFilters(cfg)
    val r = sc.screen
    if (r != null) Log.i(TAG, "screen rect ${r.x},${r.y} ${r.w}x${r.h} crop ${r.cl},${r.ct},${r.cr},${r.cb} blur=${r.blur} frame=${fw}x$fh")
    if (r != null) { place.setRect(r.x, r.y, r.w, r.h, r.flip); userCrop = floatArrayOf(r.cl, r.ct, r.cr, r.cb); applyCrop(); place.setBlur(r.blur, r.w * fw / (r.h * fh).coerceAtLeast(1f), fw.toFloat() / fh) }
    else { place.setRect(0f, 0f, 1f, 1f, false); userCrop = floatArrayOf(0f, 0f, 0f, 0f); applyCrop(); place.setBlur(false, 1f, 1f) }
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
    // веб-виджеты
    val webs = sceneWidgets("web").filter { it.url.isNotBlank() }
    webFilters.forEachIndexed { i, f ->
      val w = webs.getOrNull(i)
      if (w == null) f.visible = false
      else {
        f.rx = w.x; f.ry = w.y; f.rw = w.w; f.rh = w.h; f.key = w.key; f.alpha = w.opacity; f.visible = true
        f.surfaceTexture?.let { st -> attachWeb(i, st) }
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

  // ---------------- веб-виджеты ----------------
  private fun attachWeb(slot: Int, st: android.graphics.SurfaceTexture) {
    val w = sceneWidgets("web").filter { it.url.isNotBlank() }.getOrNull(slot)
      ?: StreamState.config?.scenes?.flatMap { sc -> sc.widgets.filter { it.type == "web" && it.url.isNotBlank() } }?.getOrNull(slot) ?: return
    val pw = w.pageWidth.coerceIn(200, 1920)
    val ph = (pw * (w.h * fh) / (w.w * fw).coerceAtLeast(1f)).toInt().coerceIn(64, 1920)
    val sig = "${w.url}|$pw|$ph|${w.key}"
    if (webState.getOrNull(slot) == sig) return
    if (slot < webState.size) webState[slot] = sig
    webHosts.getOrNull(slot)?.attach(st, pw, ph, w.url, w.key)
  }

  // ---------------- выделенное сообщение ----------------
  override fun onHighlight(line: ChatLine) {
    val f = highlightFilter ?: return
    val w = sceneWidgets("highlight").firstOrNull()
    val vertical = fh > fw
    val x = w?.x ?: if (vertical) .05f else .2f
    val y = w?.y ?: if (vertical) .62f else .7f
    val ww = w?.w ?: if (vertical) .9f else .6f
    val hh = w?.h ?: if (vertical) .12f else .17f
    f.setImage(renderer.highlightBitmap(max(8, (ww * fw).toInt()), max(8, (hh * fh).toInt()), line))
    f.setScale(ww * 100f, hh * 100f); f.setPosition(x * 100f, y * 100f)
    main.removeCallbacks(hideHighlight)
    for (i in 0..8) main.postDelayed({ f.alpha = i / 8f }, i * 30L)
    main.postDelayed(hideHighlight, 10000)
    StreamState.send(JSONObject().put("state", "info").put("msg", "Сообщение показано на стриме"))
  }

  fun highlight(name: String, color: Int, text: String) = onHighlight(ChatLine(name, color, text))

  private val hideHighlight = Runnable {
    val f = highlightFilter ?: return@Runnable
    for (i in 0..8) main.postDelayed({ f.alpha = 1f - i / 8f }, i * 30L)
  }

  // ---------------- живые слои ----------------
  private fun fmtUp(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) }

  private fun redrawDyn(force: Boolean) {
    val f = dynFilter ?: return
    val ws = StreamState.config?.scenes?.getOrNull(current)?.widgets?.filter { it.type in DYN_TYPES }.orEmpty()
    val now = System.currentTimeMillis()
    val up = if (liveSince > 0) fmtUp(now - liveSince) else "0:00:00"
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val vtxt = viewers?.toString() ?: "—"
    val sig = "$current|$up|$clock|$vtxt|${chatLines.size}|${chatLines.lastOrNull()?.text}|${ws.size}|$goalAmount|${ws.hashCode()}"
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
        "goal" -> renderer.drawGoal(c, w, r, goalAmount, w.goalTarget)
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
        StreamState.send(JSONObject().put("state", "chat").put("name", line.name)
          .put("color", String.format("#%06X", 0xFFFFFF and line.color)).put("text", line.text))
        redrawDyn(false); pushUi()
      }.also { it.start() }
    }
    if (DaClient.loggedIn) {
      da = DaAlertsClient({ ev -> queueAlert(ev) }, { msg -> StreamState.send(JSONObject().put("state", "info").put("msg", msg)) }).also { it.start() }
    }
    if (TwitchClient.loggedIn) {
      alerts = AlertsClient(TwitchClient.userId, { ev -> queueAlert(ev) }, { msg ->
        StreamState.send(JSONObject().put("state", "info").put("msg", msg))
      }).also { it.start() }
    }
  }

  // ---------------- цель доната ----------------
  private fun goalWidget(cfg: StreamConfig? = StreamState.config) = cfg?.scenes?.flatMap { it.widgets }?.firstOrNull { it.type == "goal" }

  private fun syncGoal(cfg: StreamConfig) {
    val w = goalWidget(cfg) ?: return
    val prefs = getSharedPreferences("goal", MODE_PRIVATE)
    if (w.goalRev != prefs.getString("rev", null)) {
      goalAmount = w.goalStart
      prefs.edit().putString("rev", w.goalRev).putFloat("amount", goalAmount.toFloat()).apply()
    } else goalAmount = prefs.getFloat("amount", w.goalStart.toFloat()).toDouble()
    goalRev = w.goalRev
  }

  private fun addToGoal(ev: AlertEvent) {
    val w = goalWidget() ?: return
    val add = when (ev.kind) {
      "donate" -> ev.rub
      "cheer" -> ev.amount / 100.0 * w.rateBits
      "sub", "gift" -> ev.amount * w.rateSub
      else -> 0.0
    }
    if (add <= 0.0) return
    goalAmount += add
    getSharedPreferences("goal", MODE_PRIVATE).edit().putString("rev", goalRev).putFloat("amount", goalAmount.toFloat()).apply()
    StreamState.send(JSONObject().put("state", "goal").put("amount", goalAmount))
    redrawDyn(true)
  }

  private fun queueAlert(ev: AlertEvent) {
    addToGoal(ev)
    StreamState.send(JSONObject().put("state", "alert").put("kind", ev.kind).put("name", ev.name).put("title", ev.title).put("message", ev.message))
    events.addLast(ev); while (events.size > 50) events.removeFirst()
    pushUi()
    alertQueue.addLast(ev)
    if (!alertBusy) showNextAlert()
  }

  fun testAlert() {
    val samples = listOf(
      AlertEvent("follow", "Pelmen_TV", "новый фолловер"),
      AlertEvent("sub", "NightOwl", "подписка · 3 месяца", "Спасибо за стримы!"),
      AlertEvent("cheer", "KoshkaGamer", "500 битов", "Держи на чай"),
      AlertEvent("raid", "ZvezdaTV", "рейд · 42 зрителя"),
      AlertEvent("donate", "Ivan", "500 ₽", "Отличный стрим, держи на пиццу!").apply { amount = 500.0 }
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
    val media = w.media[ev.kind] ?: if (ev.kind == "gift") w.media["sub"] else null
    var gifRect: android.graphics.RectF? = null
    val card: Bitmap = if (media?.img != null) {
      if (media.isGif && alertGif != null) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(media.img, 0, media.img.size, o)
        gifRect = renderer.mediaRect(pw, ph, o.outWidth, o.outHeight)
        try { alertGif?.setGif(ByteArrayInputStream(media.img)) } catch (e: Exception) { Log.w(TAG, "alert gif", e) }
        renderer.mediaAlertBitmap(w, pw, ph, ev, null)
      } else renderer.mediaAlertBitmap(w, pw, ph, ev, BitmapFactory.decodeByteArray(media.img, 0, media.img.size))
    } else renderer.alertBitmap(w, pw, ph, ev)
    f.setImage(card)
    f.setScale(w.w * 100f, w.h * 100f)
    val g = alertGif
    val gr = gifRect
    if (g != null && gr != null) g.setScale(gr.width() / fw * 100f, gr.height() / fh * 100f)

    // звук: свой или стандартный, затем озвучка доната
    var soundMs = 0L
    if (media?.sound != null) soundMs = playSound(media.sound)
    else if (w.alertSound) { AlertSound.play(ev.kind); soundMs = 900L }
    var ttsMs = 0L
    if (w.tts && ev.kind == "donate" && ev.message.isNotEmpty() && ev.amount >= w.ttsMin && ttsReady) {
      ttsMs = 1500L + ev.message.length * 75L
      main.postDelayed({ try { tts?.speak(ev.message, TextToSpeech.QUEUE_ADD, null, "a" + System.nanoTime()) } catch (_: Exception) {} }, soundMs + 200)
    }

    val steps = 10; val slide = 0.04f
    fun frame(i: Int, show: Boolean) {
      val k = i / steps.toFloat()
      val a = if (show) k else 1f - k
      val dy = slide * (1f - a)
      f.alpha = a * w.opacity
      f.setPosition(w.x * 100f, (w.y + dy) * 100f)
      if (g != null && gr != null) {
        g.alpha = a * w.opacity
        g.setPosition((w.x + gr.left / fw) * 100f, (w.y + gr.top / fh + dy) * 100f)
      }
    }
    for (i in 0..steps) main.postDelayed({ frame(i, true) }, i * 30L)
    val hold = maxOf(w.alertSeconds.coerceIn(2, 30) * 1000L, soundMs + 300, soundMs + ttsMs)
    for (i in 0..steps) main.postDelayed({ frame(i, false) }, 330L + hold + i * 30L)
    main.postDelayed({ f.alpha = 0f; g?.alpha = 0f; showNextAlert() }, 330L + hold + 400L)
  }

  /** Проигрывает свой звук алерта как звук игры (попадает в эфир). Возвращает длительность, мс. */
  private fun playSound(bytes: ByteArray): Long = try {
    val file = File(cacheDir, "alert_${bytes.contentHashCode()}.snd")
    if (!file.exists()) file.writeBytes(bytes)
    player?.release()
    val mp = MediaPlayer()
    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
    mp.setDataSource(file.absolutePath)
    mp.prepare()
    mp.setOnCompletionListener { it.release(); if (player == it) player = null }
    mp.start()
    player = mp
    mp.duration.toLong().coerceIn(0L, 30000L)
  } catch (e: Exception) {
    Log.w(TAG, "alert sound", e); 0L
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
          Notification.Builder(this, "warn").setSmallIcon(R.drawable.ic_notif).setContentTitle("Glowcast").setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg)).setAutoCancel(true).build())
      } catch (_: Exception) {}
    }
  }

  /** Делаем виртуальный экран с пропорциями телефона, чтобы не было чёрных полос. */
  // чёрные полосы Android и поворот картинки телефона теперь находит сам фильтр экрана (PlaceFilterRender)
  private fun updatePhoneAspect() {
    try {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
      if (dm.heightPixels > 0) place.setPhoneAspect(dm.widthPixels.toFloat() / dm.heightPixels)
    } catch (_: Exception) {}
  }
  private var userCrop = floatArrayOf(0f, 0f, 0f, 0f)
  private fun applyCrop() { val u = userCrop; place.setCrop(u[0], u[1], u[2], u[3]) }

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
      .setContentTitle("Glowcast")
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

  // ---------------- запись в файл ----------------
  private fun startRecording(): Boolean {
    val s = stream ?: return false
    if (s.isRecording) return true
    return try {
      val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
      dir.mkdirs()
      val f = File(dir, "StreamScene_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4")
      recFile = f
      s.startRecord(f.absolutePath, null, RecordController.Listener { st ->
        if (st == RecordController.Status.STARTED || st == RecordController.Status.RECORDING) main.post { pushUi() }
      })
      StreamState.send(JSONObject().put("state", "rec").put("on", true))
      pushUi()
      true
    } catch (e: Exception) {
      Log.w(TAG, "record", e)
      warnUser("Запись не запустилась: ${e.message}")
      false
    }
  }

  private fun stopRecording() {
    val s = stream ?: return
    if (!s.isRecording) return
    try { s.stopRecord() } catch (e: Exception) { Log.w(TAG, "stop record", e) }
    StreamState.send(JSONObject().put("state", "rec").put("on", false))
    recFile?.let { saveToGallery(it) }
    recFile = null
    pushUi()
  }

  /** Переносит файл записи в Галерею: Movies/StreamScene. */
  private fun saveToGallery(f: File) {
    val app = applicationContext
    thread {
      Thread.sleep(600)
      if (!f.exists() || f.length() < 1024) {
        StreamState.send(JSONObject().put("state", "info").put("msg", "Запись пустая — ничего не сохранено")); f.delete(); return@thread
      }
      try {
        val cr = app.contentResolver
        val v = ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StreamScene")
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("нет доступа к Галерее")
        cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
        v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, v, null, null)
        f.delete()
        StreamState.send(JSONObject().put("state", "info").put("msg", "Запись сохранена в Галерею: Movies/StreamScene"))
      } catch (e: Exception) {
        Log.w(TAG, "gallery", e)
        StreamState.send(JSONObject().put("state", "info").put("msg", "Не удалось перенести запись в Галерею. Файл: ${f.absolutePath}"))
      }
    }
  }

  fun toggleRecording() { if (stream?.isRecording == true) stopRecording() else startRecording() }

  private fun stopAll(error: String = "") {
    running = false
    try { stopRecording() } catch (_: Exception) {}
    main.removeCallbacksAndMessages(null)
    controls?.hide(); controls = null
    chat?.stop(); chat = null
    alerts?.stop(); alerts = null
    da?.stop(); da = null
    try { tts?.shutdown() } catch (_: Exception) {}
    tts = null; ttsReady = false
    try { player?.release() } catch (_: Exception) {}
    player = null
    camCtl?.close(); camCtl = null
    webHosts.forEach { it.release() }
    try { unregisterReceiver(sysReceiver) } catch (_: Exception) {}
    thermalListener?.let { try { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) } catch (_: Exception) {} }
    thermalListener = null
    try {
      stream?.let {
        if (it.isStreaming) it.stopStream()
        if (it.isRecording) it.stopRecord()
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
    glI = null; chain.clear(); rankOf.clear()
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
        privacy = privacyOn, warn = warn, chat = chatLines.toList().takeLast(4), events = events.toList(),
        recording = stream?.isRecording == true
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

  override fun onToggleRecord() = toggleRecording()

  override fun onQuickEdit() {
    try {
      startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "layers")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    } catch (_: Exception) {}
  }
}
