package com.streamscene.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.concurrent.thread

/**
 * Главный экран: редактор сцен (HTML в WebView) + управление стримом.
 */
class MainActivity : Activity() {

  companion object {
    private const val REQ_PROJECTION = 1
    private const val REQ_FILE = 2
    private const val REQ_PERMS = 3
    private const val REQ_CAM_LIVE = 4
  }

  private lateinit var web: WebView
  private var fileCallback: ValueCallback<Array<Uri>>? = null
  private var pageReady = false

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    TwitchClient.init(this)
    DaClient.init(this)
    web = WebView(this)
    web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    web.setBackgroundColor(0xFF140F2B.toInt())
    // WebView не учитывает свои отступы, поэтому кладём её в рамку и отодвигаем от системных панелей и клавиатуры
    val root = FrameLayout(this)
    root.setBackgroundColor(0xFF140F2B.toInt())
    root.addView(web)
    setContentView(root)
    root.setOnApplyWindowInsetsListener { v, insets ->
      val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
      val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
      v.setPadding(sys.left, sys.top, sys.right, max(sys.bottom, ime.bottom))
      android.view.WindowInsets.CONSUMED
    }

    with(web.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      allowFileAccess = false
      allowContentAccess = true
    }
    web.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
      ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
          startActivityForResult(params!!.createIntent(), REQ_FILE)
          true
        } catch (e: Exception) {
          fileCallback = null
          false
        }
      }
    }
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val u = request?.url ?: return false
        if (u.scheme == "http" || u.scheme == "https") {
          try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
          return true
        }
        return false
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        pageReady = true
      }
    }
    web.addJavascriptInterface(Bridge(), "Native")
    StreamState.listener = { o -> runOnUiThread { if (pageReady) web.evaluateJavascript("window.onNative && window.onNative($o)", null) } }
    web.loadUrl("file:///android_asset/editor.html")
    openTabFromIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    openTabFromIntent(intent)
  }

  private fun openTabFromIntent(i: Intent?) {
    val tab = i?.getStringExtra("tab") ?: return
    i.removeExtra("tab")
    if (pageReady) web.evaluateJavascript("window.openTab && window.openTab('$tab')", null)
    else web.postDelayed({ openTabFromIntent(Intent().putExtra("tab", tab)) }, 400)
  }

  override fun onPause() {
    super.onPause()
    StreamService.instance?.setAppForeground(false)
  }

  override fun onResume() {
    super.onResume()
    StreamService.instance?.setAppForeground(true)
    if (pageReady) StreamState.send(JSONObject().put("state", "resume"))
  }

  override fun onDestroy() {
    StreamState.listener = null
    web.destroy()
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    web.evaluateJavascript("window.onBack && window.onBack()") { handled ->
      if (handled != "true") {
        @Suppress("DEPRECATION")
        super.onBackPressed()
      }
    }
  }

  // ---------- запуск стрима ----------
  private fun beginStart() {
    val need = mutableListOf<String>()
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
    val wantsCamera = StreamState.config?.scenes?.any { s -> s.widgets.any { it.type == "camera" } } == true
    if (wantsCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.CAMERA
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
    if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS) else requestProjection()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQ_CAM_LIVE) {
      if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) StreamService.instance?.let { it.switchScene(StreamState.config?.current ?: 0) }
      return
    }
    if (requestCode != REQ_PERMS) return
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      StreamState.emit("error", "Без разрешения на звук стрим не запустится. Разреши «Микрофон» в настройках приложения.")
    } else {
      val wantsCamera = StreamState.config?.scenes?.any { s -> s.widgets.any { it.type == "camera" } } == true
      if (wantsCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        StreamState.send(JSONObject().put("state", "warn").put("msg", "Без доступа к камере эфир пойдёт без камеры"))
      requestProjection()
    }
  }

  private fun requestProjection() {
    val mpm = getSystemService(MediaProjectionManager::class.java)
    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    @Suppress("DEPRECATION")
    super.onActivityResult(requestCode, resultCode, data)
    when (requestCode) {
      REQ_FILE -> {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
      }
      REQ_PROJECTION -> {
        if (resultCode == RESULT_OK && data != null) {
          val i = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_CODE, resultCode)
            .putExtra(StreamService.EXTRA_DATA, data)
          startForegroundService(i)
          StreamState.emit("connecting")
        } else {
          StreamState.emit("error", "Доступ к экрану не дан — стрим не запущен")
        }
      }
    }
  }

  private fun bringToFront() {
    try {
      startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } catch (_: Exception) {}
  }

  private fun kvFile(key: String) = File(filesDir, "kv_" + key.replace(Regex("[^a-zA-Z0-9_]"), "_") + ".txt")

  /** Методы, которые вызывает редактор (JavaScript → Kotlin). */
  inner class Bridge {
    @JavascriptInterface
    fun info(): String {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
      return JSONObject()
        .put("screenLong", max(dm.widthPixels, dm.heightPixels))
        .put("screenShort", min(dm.widthPixels, dm.heightPixels))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("state", StreamState.state)
        .put("msg", StreamState.message)
        .put("current", StreamState.config?.current ?: 0)
        .toString()
    }

    @JavascriptInterface
    fun kvGet(key: String): String? = kvFile(key).takeIf { it.exists() }?.readText()

    @JavascriptInterface
    fun kvSet(key: String, value: String) {
      kvFile(key).writeText(value)
    }

    @JavascriptInterface
    fun start(json: String) {
      try {
        StreamState.config = StreamState.parseConfig(json)
      } catch (e: Exception) {
        StreamState.emit("error", "Ошибка настроек: ${e.message}")
        return
      }
      runOnUiThread { beginStart() }
    }

    @JavascriptInterface
    fun stop() {
      runOnUiThread {
        if (StreamService.instance != null) {
          startService(Intent(this@MainActivity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        } else StreamState.emit("stopped")
      }
    }

    @JavascriptInterface
    fun setScene(index: Int) {
      runOnUiThread { StreamService.instance?.switchScene(index) }
    }

    @JavascriptInterface
    fun updateScenes(json: String, index: Int) {
      val cfg = StreamState.config ?: return
      try {
        cfg.scenes = StreamState.parseScenes(JSONArray(json))
        cfg.current = index
      } catch (e: Exception) {
        return
      }
      runOnUiThread {
        val wantsCamera = cfg.scenes.any { s -> s.widgets.any { it.type == "camera" } }
        if (wantsCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
          requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_LIVE)
        StreamService.instance?.switchScene(index)
      }
    }

    @JavascriptInterface
    fun toggleRecord() {
      runOnUiThread { StreamService.instance?.toggleRecording() }
    }

    // ---------- Twitch ----------
    @JavascriptInterface
    fun twInfo(): String = TwitchClient.info(this@MainActivity).toString()

    /** Вход через страницу Twitch внутри приложения (логин, пароль, код). */
    @JavascriptInterface
    fun twWebLogin(clientId: String) {
      runOnUiThread {
        startActivity(Intent(this@MainActivity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_CLIENT_ID, clientId))
      }
    }

    @JavascriptInterface
    fun twLogin(clientId: String) {
      TwitchClient.startDeviceLogin(clientId.trim(),
        onCode = { code, uri ->
          StreamState.send(JSONObject().put("state", "tw").put("phase", "code").put("code", code).put("uri", uri))
          runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (_: Exception) {} }
        },
        onDone = { ok, msg ->
          StreamState.send(JSONObject().put("state", "tw").put("phase", "done").put("ok", ok).put("msg", msg))
          if (ok) runOnUiThread { bringToFront() }
        })
    }

    // ---------- DonationAlerts ----------
    @JavascriptInterface
    fun daInfo(): String = DaClient.info(this@MainActivity).toString()

    @JavascriptInterface
    fun daLogin(clientId: String) {
      runOnUiThread {
        startActivity(Intent(this@MainActivity, LoginActivity::class.java)
          .putExtra(LoginActivity.EXTRA_PROVIDER, "da").putExtra(LoginActivity.EXTRA_CLIENT_ID, clientId))
      }
    }

    @JavascriptInterface
    fun daLogout() = DaClient.logout()

    @JavascriptInterface
    fun twCancel() = TwitchClient.cancelLogin()

    @JavascriptInterface
    fun twLogout() = TwitchClient.logout()

    /** Асинхронный запрос к Twitch API; ответ придёт в onNative({state:'api', id, code, body}). */
    @JavascriptInterface
    fun twApi(id: Int, method: String, path: String, query: String, body: String) {
      thread {
        val r = TwitchClient.api(method, path, query, body.ifEmpty { null })
        StreamState.send(JSONObject().put("state", "api").put("id", id).put("code", r.code).put("body", r.body))
      }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
      runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
    }

    // ---------- плавающий кружок ----------
    @JavascriptInterface
    fun canOverlay(): Boolean = Settings.canDrawOverlays(this@MainActivity)

    @JavascriptInterface
    fun requestOverlay() {
      runOnUiThread {
        try {
          startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
      }
    }

    /** «О приложении» — там в меню ⋮ есть «Разрешить ограниченные настройки» (Android 13+). */
    @JavascriptInterface
    fun openAppSettings() {
      runOnUiThread {
        try {
          startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
      }
    }

    // ---------- проверка перед эфиром и управление в эфире ----------
    @JavascriptInterface
    fun deviceStatus(): String = DeviceInfo.status(this@MainActivity).toString()

    @JavascriptInterface
    fun speedTest() {
      thread {
        val r = DeviceInfo.uploadSpeed()
        StreamState.send(JSONObject().put("state", "speed").put("ok", r.isSuccess)
          .put("mbps", r.getOrDefault(0.0)).put("msg", r.exceptionOrNull()?.message ?: ""))
      }
    }

    @JavascriptInterface
    fun setVolumes(game: Double, mic: Double) {
      runOnUiThread { StreamService.instance?.setVolumes(game.toFloat(), mic.toFloat()) }
    }

    @JavascriptInterface
    fun setPrivacy(on: Boolean) {
      runOnUiThread { StreamService.instance?.setPrivacy(on) }
    }

    @JavascriptInterface
    fun highlight(name: String, color: String, text: String) {
      val c = try { android.graphics.Color.parseColor(color) } catch (_: Exception) { android.graphics.Color.WHITE }
      runOnUiThread { StreamService.instance?.highlight(name, c, text) }
    }

    @JavascriptInterface
    fun testAlert() {
      runOnUiThread { StreamService.instance?.testAlert() }
    }

    @JavascriptInterface
    fun goHome() {
      runOnUiThread {
        // назад в игру: прячем приложение, система вернёт предыдущее окно
        if (!moveTaskToBack(true)) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
  }
}
