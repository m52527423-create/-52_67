package com.streamscene.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.concurrent.thread

/** Фоновый сервис: захват экрана + звук → сцена → Twitch (RTMP). */
class StreamService : Service(), ConnectChecker, FloatingControls.Actions {

  companion object {
    const val ACTION_START = "com.streamscene.START"
    const val ACTION_STOP = "com.streamscene.STOP"
    const val ACTION_NEXT = "com.streamscene.NEXT"
    const val ACTION_PREV = "com.streamscene.PREV"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"
    private const val CHANNEL = "stream"
    private const val NOTIF_ID = 42
    private const val TAG = "StreamScene"

    @Volatile var instance: StreamService? = null
  }

  private val main = Handler(Looper.getMainLooper())
  private var stream: GenericStream? = null
  private var projection: MediaProjection? = null
  private var screenSource: ScreenSource? = null
  private val place = PlaceFilterRender()
  private val overlay = ImageObjectFilterRender()
  private var current = 0
  private var running = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var audioSrc: AudioSource? = null
  private var controls: FloatingControls? = null
  private var uiState = "connecting"
  private var liveSince = 0L
  private var viewers: Int? = null
  private var kbps = 0L
  private var micMuted = false

  private val ticker = object : Runnable {
    override fun run() {
      pushUi()
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
            if (v != null) StreamState.send(org.json.JSONObject().put("state", "viewers").put("count", v))
            pushUi()
          }
        }
      }
      main.postDelayed(this, 20000)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    TwitchClient.init(this)
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Стрим", NotificationManager.IMPORTANCE_LOW))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        startForegroundTyped()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        // типизированный вариант надёжен только с Android 14 (на 13 бывают сбои)
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
      else -> if (!running) stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun startForegroundTyped() {
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    if (hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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

      if (!hasMic()) return stopAll("Нужно разрешение на запись звука (оно нужно и для звука игры)")
      val audio = when {
        cfg.audio == "mic" -> MicrophoneSource()
        cfg.audio == "game" -> InternalAudioSource(mp)
        else -> MixAudioSource(mp)
      }
      audioSrc = audio
      val s = GenericStream(applicationContext, this, NoVideoSource(), audio)
      s.getGlInterface().setForceRender(true, cfg.fps)

      val vertical = cfg.height > cfg.width
      val longSide = max(cfg.width, cfg.height)
      val shortSide = min(cfg.width, cfg.height)
      val rotation = if (vertical) 90 else 0
      val ok = s.prepareVideo(longSide, shortSide, cfg.bitrate, cfg.fps, 2, rotation) &&
        s.prepareAudio(44100, true, 160 * 1000)
      if (!ok) {
        s.release()
        return stopAll("Телефон не поддерживает выбранное качество — попробуй 720p")
      }
      val screen = ScreenSource(applicationContext, mp)
      s.changeVideoSource(screen)
      screenSource = screen
      s.getStreamClient().setReTries(10)

      s.getGlInterface().addFilter(place)
      s.getGlInterface().addFilter(overlay)
      stream = s
      running = true
      current = cfg.current.coerceIn(0, max(0, cfg.scenes.size - 1))
      applyScene()

      StreamState.emit("connecting")
      s.startStream(cfg.url)
      fitVirtualDisplay(cfg)
      main.postDelayed({ fitVirtualDisplay(cfg) }, 800)

      uiState = "connecting"; liveSince = 0L; viewers = null; kbps = 0L; micMuted = false
      if (cfg.bubble) {
        controls = FloatingControls(this, this).also { it.show() }
      }
      main.post(ticker)
      main.postDelayed(viewerPoll, 15000)

      val pm = getSystemService(PowerManager::class.java)
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamScene:stream").apply { acquire(6 * 60 * 60 * 1000L) }
    } catch (e: Exception) {
      Log.e(TAG, "start failed", e)
      stopAll("Не удалось запустить: ${e.message}")
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

  private fun applyScene() {
    val cfg = StreamState.config ?: return
    if (cfg.scenes.isEmpty()) return
    current = current.coerceIn(0, cfg.scenes.size - 1)
    cfg.current = current
    val sc = cfg.scenes[current]
    val r = sc.screen
    if (r != null) place.setRect(r.x, r.y, r.w, r.h, r.flip) else place.setRect(0f, 0f, 1f, 1f, false)
    val bmp = BitmapFactory.decodeByteArray(sc.overlayPng, 0, sc.overlayPng.size)
    if (bmp != null) {
      overlay.setImage(bmp)
      overlay.setScale(100f, 100f)
      overlay.setPosition(0f, 0f)
    }
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

  /** Сцены поменяли в редакторе во время эфира. */
  fun reloadScenes() {
    if (running) applyScene()
  }

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
      .addAction(action("Пред. сцена", ACTION_PREV, 1))
      .addAction(action("След. сцена", ACTION_NEXT, 2))
      .addAction(action("Стоп", ACTION_STOP, 3))
      .build()
  }

  private var statusText = "Подключение…"
  private fun updateNotification() {
    if (!running) return
    val name = StreamState.config?.scenes?.getOrNull(current)?.name ?: ""
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("$statusText · сцена: $name"))
  }

  private fun stopAll(error: String = "") {
    running = false
    main.removeCallbacksAndMessages(null)
    controls?.hide()
    controls = null
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

  // ---------- ConnectChecker (вызывается из фоновых потоков) ----------
  override fun onConnectionStarted(url: String) {
    StreamState.emit("connecting")
  }

  override fun onConnectionSuccess() {
    main.post {
      statusText = "В эфире"
      if (liveSince == 0L) liveSince = System.currentTimeMillis()
      uiState = "live"
      updateNotification()
      pushUi()
      StreamState.emit("live")
    }
  }

  override fun onConnectionFailed(reason: String) {
    main.post {
      val s = stream ?: return@post
      if (running && s.getStreamClient().reTry(5000, reason, null)) {
        statusText = "Переподключение…"
        uiState = "reconnecting"
        updateNotification()
        pushUi()
        StreamState.emit("reconnecting", reason)
      } else {
        stopAll("Ошибка соединения: $reason")
      }
    }
  }

  override fun onNewBitrate(bitrate: Long) {
    kbps = bitrate / 1000
    StreamState.emit("bitrate") { put("kbps", kbps) }
  }

  override fun onDisconnect() {
    if (running) main.post { uiState = "reconnecting"; pushUi(); StreamState.emit("reconnecting", "Связь потеряна") }
  }

  // ---------- плавающий кружок ----------
  private fun micLabel() = if (StreamState.config?.audio == "game") "Звук" else "Микрофон"

  private fun pushUi() {
    val c = controls ?: return
    val cfg = StreamState.config ?: return
    c.update(
      LiveInfo(
        state = uiState, viewers = viewers, kbps = kbps, targetKbps = cfg.bitrate / 1000,
        uptimeMs = if (liveSince > 0) System.currentTimeMillis() - liveSince else 0L,
        scenes = cfg.scenes.map { it.name }, current = current, micMuted = micMuted, micLabel = micLabel()
      )
    )
  }

  override fun onScene(index: Int) = switchScene(index)

  override fun onToggleMic() {
    micMuted = !micMuted
    when (val a = audioSrc) {
      is MixAudioSource -> a.microphoneVolume = if (micMuted) 0f else 1f
      is MicrophoneSource -> if (micMuted) a.mute() else a.unMute()
      is InternalAudioSource -> if (micMuted) a.mute() else a.unMute()
      else -> {}
    }
    pushUi()
    StreamState.send(org.json.JSONObject().put("state", "mic").put("muted", micMuted))
  }

  override fun onOpenApp() {
    try {
      startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } catch (_: Exception) {}
  }

  override fun onStop() = stopAll()

  override fun onAuthError() {
    main.post { stopAll("Twitch отклонил ключ трансляции — проверь ключ") }
  }

  override fun onAuthSuccess() {}
}
