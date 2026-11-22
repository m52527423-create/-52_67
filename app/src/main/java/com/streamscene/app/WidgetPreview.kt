package com.streamscene.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Живое превью веб-виджетов (алерты, сбор DonationAlerts…) поверх превью сцены в редакторе.
 * Это настоящие страницы виджетов с прозрачным фоном — видно, как сбор выглядит и где он стоит.
 * Касания проходят насквозь в редактор, поэтому слой по-прежнему можно двигать пальцем.
 */
class WidgetPreview(private val ctx: Context) {

  /** Рамка размером с превью сцены — всё, что за её краем, обрезается. */
  val box: FrameLayout = object : FrameLayout(ctx) {
    override fun dispatchTouchEvent(ev: MotionEvent?) = false
  }.apply { clipChildren = true; clipToPadding = true; visibility = View.GONE }

  private class PassWeb(c: Context) : WebView(c) {
    var url0 = ""
    override fun dispatchTouchEvent(ev: MotionEvent?) = false
  }
  private val views = HashMap<String, PassWeb>()

  @SuppressLint("SetJavaScriptEnabled")
  private fun make(id: String): PassWeb = PassWeb(ctx).apply {
    setBackgroundColor(Color.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    isFocusable = false; isClickable = false
    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
    pivotX = 0f; pivotY = 0f
    // превью без звука: звук алертов и так идёт в эфир из самого эфира, а здесь он задвоился бы
    webViewClient = object : android.webkit.WebViewClient() {
      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { view.evaluateJavascript(MUTE, null) }
      override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(MUTE, null) }
    }
    box.addView(this, FrameLayout.LayoutParams(10, 10))
    views[id] = this
  }

  /**
   * json: { show, stage:{x,y,w,h}, items:[{id,url,x,y,w,h,pw}] } — в физических пикселях относительно WebView редактора.
   * pw — ширина страницы виджета в CSS-пикселях (как «Ширина страницы» в свойствах слоя).
   */
  fun apply(json: String) {
    val o = try { JSONObject(json) } catch (_: Exception) { return }
    val show = o.optBoolean("show", false)
    val st = o.optJSONObject("stage")
    val items = o.optJSONArray("items")
    if (!show || st == null || items == null || items.length() == 0) {
      box.visibility = View.GONE
      if (items == null || items.length() == 0) clear()
      return
    }
    val lp = (box.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
    lp.leftMargin = st.optInt("x"); lp.topMargin = st.optInt("y"); lp.width = st.optInt("w"); lp.height = st.optInt("h")
    box.layoutParams = lp
    box.visibility = View.VISIBLE
    val density = ctx.resources.displayMetrics.density
    val keep = HashSet<String>()
    for (i in 0 until items.length()) {
      val it = items.getJSONObject(i)
      val id = it.optString("id"); val url = it.optString("url")
      if (id.isEmpty() || url.isEmpty()) continue
      keep += id
      val v = views[id] ?: make(id)
      val w = it.optDouble("w").toFloat().coerceAtLeast(1f); val h = it.optDouble("h").toFloat().coerceAtLeast(1f)
      val pw = it.optDouble("pw", 800.0).toFloat().coerceIn(100f, 3000f)
      // страница шириной pw CSS-пикселей, уменьшенная до размера слоя
      val pagePxW = (pw * density).toInt().coerceAtLeast(1)
      val pagePxH = (pagePxW * h / w).toInt().coerceAtLeast(1)
      val vlp = v.layoutParams as FrameLayout.LayoutParams
      if (vlp.width != pagePxW || vlp.height != pagePxH) { vlp.width = pagePxW; vlp.height = pagePxH; v.layoutParams = vlp }
      val k = w / pagePxW
      v.scaleX = k; v.scaleY = k
      v.translationX = it.optDouble("x").toFloat() - lp.leftMargin
      v.translationY = it.optDouble("y").toFloat() - lp.topMargin
      if (v.url0 != url) { v.url0 = url; v.loadUrl(url) }
    }
    views.keys.filter { it !in keep }.forEach { id -> views.remove(id)?.let { box.removeView(it); it.destroy() } }
  }

  fun pause() = views.values.forEach { it.onPause() }
  fun resume() = views.values.forEach { it.onResume() }

  fun clear() {
    views.values.forEach { box.removeView(it); it.destroy() }
    views.clear()
    box.visibility = View.GONE
  }

  companion object {
    private const val MUTE = """(function(){try{
      if(window.__gcMuted)return;window.__gcMuted=true;
      var P=HTMLMediaElement.prototype,op=P.play;P.play=function(){this.muted=true;this.volume=0;return op.apply(this,arguments);};
      setInterval(function(){document.querySelectorAll('audio,video').forEach(function(m){m.muted=true;m.volume=0;});},400);
      ['AudioContext','webkitAudioContext'].forEach(function(n){var A=window[n];if(!A)return;window[n]=function(){var c=new A();try{var g=c.createGain();g.gain.value=0;g.connect(c.destination);Object.defineProperty(c,'destination',{get:function(){return g;}});}catch(e){}return c;};window[n].prototype=A.prototype;});
      if(window.speechSynthesis){window.speechSynthesis.speak=function(){};}
    }catch(e){}})();"""
  }
}
