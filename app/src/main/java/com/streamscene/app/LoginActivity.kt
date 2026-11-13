package com.streamscene.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Вход в Twitch прямо в приложении: обычная страница Twitch
 * (логин, пароль, код), после неё приложение получает доступ к каналу.
 */
class LoginActivity : Activity() {

  companion object {
    const val EXTRA_CLIENT_ID = "clientId"
    const val EXTRA_PROVIDER = "provider"   // twitch | da
    const val REDIRECT = "http://localhost"
  }

  private lateinit var web: WebView
  private lateinit var progress: ProgressBar
  private var state = ""
  private var clientId = ""
  private var finished = false
  private var provider = "twitch"

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    TwitchClient.init(this); DaClient.init(this)
    provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "twitch"
    clientId = intent.getStringExtra(EXTRA_CLIENT_ID)?.trim().orEmpty().ifEmpty {
      if (provider == "da") DaClient.effectiveClientId(this) else TwitchClient.effectiveClientId(this)
    }
    if (clientId.isEmpty()) { done(false, "Нет Client ID"); return }

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.parseColor("#0b0b0d"))
    }
    val bar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), 0, dp(8), 0)
    }
    bar.addView(TextView(this).apply {
      text = if (provider == "da") "Вход в DonationAlerts" else "Вход в Twitch"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    bar.addView(TextView(this).apply {
      text = "Закрыть"; setTextColor(Color.parseColor("#b691ff"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setPadding(dp(12), dp(12), dp(12), dp(12))
      setOnClickListener { done(false, "Вход отменён") }
    })
    root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
    progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = false }
    root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
    web = WebView(this)
    root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    root.setOnApplyWindowInsetsListener { v, insets ->
      @Suppress("DEPRECATION")
      v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
      insets
    }

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    with(web.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      // Twitch лучше работает с обычным браузером, а не со «встроенным»
      userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
    }
    web.webChromeClient = object : android.webkit.WebChromeClient() {
      override fun onProgressChanged(view: WebView?, newProgress: Int) {
        progress.progress = newProgress
        progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
      }
    }
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        handle(request?.url?.toString())

      override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (handle(url)) view?.stopLoading()
      }
    }

    state = java.util.UUID.randomUUID().toString()
    val url = if (provider == "da")
      Uri.parse("https://www.donationalerts.com/oauth/authorize").buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("redirect_uri", REDIRECT)
        .appendQueryParameter("response_type", "token")
        .appendQueryParameter("scope", DaClient.SCOPES)
        .build().toString()
    else Uri.parse("https://id.twitch.tv/oauth2/authorize").buildUpon()
      .appendQueryParameter("response_type", "token")
      .appendQueryParameter("client_id", clientId)
      .appendQueryParameter("redirect_uri", REDIRECT)
      .appendQueryParameter("scope", TwitchClient.SCOPES)
      .appendQueryParameter("state", state)
      .build().toString()
    web.loadUrl(url)
  }

  /** Перехватываем возврат на http://localhost — там лежит токен. */
  private fun handle(url: String?): Boolean {
    if (url == null || !url.startsWith(REDIRECT)) return false
    if (finished) return true
    val u = Uri.parse(url)
    val params = Uri.parse("http://x/?" + (u.fragment ?: u.query ?: ""))
    val err = params.getQueryParameter("error") ?: u.getQueryParameter("error")
    val token = params.getQueryParameter("access_token")
    when {
      err != null -> done(false, if (err == "access_denied") "Вход отменён" else (params.getQueryParameter("error_description") ?: err))
      token == null -> done(false, "Twitch не вернул доступ")
      provider == "twitch" && params.getQueryParameter("state") != state -> done(false, "Ошибка проверки входа, попробуй ещё раз")
      else -> {
        finished = true
        web.loadData("<body style='background:#0b0b0d;color:#ccc;font:16px sans-serif;display:flex;align-items:center;justify-content:center;height:90vh'>Входим…</body>", "text/html; charset=utf-8", "utf-8")
        thread {
          val res = if (provider == "da") DaClient.completeLogin(clientId, token) else TwitchClient.completeImplicitLogin(clientId, token)
          runOnUiThread { done(res == null, res ?: "") }
        }
      }
    }
    return true
  }

  private fun done(ok: Boolean, msg: String) {
    finished = true
    StreamState.send(JSONObject().put("state", if (provider == "da") "da" else "tw").put("phase", "done").put("ok", ok).put("msg", msg))
    finish()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (::web.isInitialized && web.canGoBack()) web.goBack() else done(false, "Вход отменён")
  }

  override fun onDestroy() {
    if (::web.isInitialized) web.destroy()
    super.onDestroy()
  }
}
