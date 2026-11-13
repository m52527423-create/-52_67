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

/**
 * Главный экран: редактор сцен (HTML в WebView) + управление стримом.
 */
class MainActivity : Activity() {

  companion object {
    private const val REQ_PROJECTION = 1
    private const val REQ_FILE = 2
    private const val REQ_PERMS = 3
  }

  private lateinit var web: WebView
  private var fileCallback: ValueCallback<Array<Uri>>? = null
  private var pageReady = false

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    web = WebView(this)
    web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    web.setBackgroundColor(0xFF0E0E10.toInt())
    setContentView(web)
    // отступы под системные панели (Android 15 рисует под ними)
    web.setOnApplyWindowInsetsListener { v, insets ->
      @Suppress("DEPRECATION")
      v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
      insets
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
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
    if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS) else requestProjection()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQ_PERMS) return
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      StreamState.emit("error", "Без разрешения на звук стрим не запустится. Разреши «Микрофон» в настройках приложения.")
    } else requestProjection()
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
      runOnUiThread { StreamService.instance?.switchScene(index) }
    }

    @JavascriptInterface
    fun goHome() {
      runOnUiThread {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
  }
}
