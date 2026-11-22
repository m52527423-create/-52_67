package com.streamscene.app

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pedro.encoder.input.gl.render.BaseRenderOffScreen
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Цвет-«ключ»: им заливается фон веб-страницы, а в кадре он становится прозрачным. */
const val WEB_KEY_COLOR = 0xFFFF00FF.toInt()

/**
 * Веб-страница (например, виджет DonationAlerts) в кадре.
 * Страница рисуется на скрытом виртуальном экране, а этот фильтр кладёт её в нужное место.
 */
class WebFilterRender(private val onSurface: (SurfaceTexture) -> Unit) : BaseFilterRender() {
  private val verts = floatArrayOf(-1f, -1f, 0f, 0f, 0f, 1f, -1f, 0f, 1f, 0f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, 1f, 1f)
  private var program = -1
  private var aPos = -1; private var aTex = -1; private var uMVP = -1; private var uST = -1
  private var uSampler = -1; private var uWeb = -1; private var uWebST = -1; private var uRect = -1; private var uKey = -1; private var uAlpha = -1
  private val tex = IntArray(1)
  private val webST = FloatArray(16)
  @Volatile var surfaceTexture: SurfaceTexture? = null; private set

  @Volatile var visible = false
  @Volatile var rx = 0f; @Volatile var ry = 0f; @Volatile var rw = .3f; @Volatile var rh = .3f
  @Volatile var key = true
  @Volatile var alpha = 1f

  init {
    squareVertex = ByteBuffer.allocateDirect(verts.size * BaseRenderOffScreen.FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    squareVertex.put(verts).position(0)
    Matrix.setIdentityM(MVPMatrix, 0); Matrix.setIdentityM(STMatrix, 0); Matrix.setIdentityM(webST, 0)
  }

  override fun initGlFilter(context: Context) {
    program = GlUtil.createProgram(VERTEX, FRAGMENT)
    aPos = GLES20.glGetAttribLocation(program, "aPosition"); aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
    uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix"); uST = GLES20.glGetUniformLocation(program, "uSTMatrix")
    uSampler = GLES20.glGetUniformLocation(program, "uSampler"); uWeb = GLES20.glGetUniformLocation(program, "uWeb")
    uWebST = GLES20.glGetUniformLocation(program, "uWebST"); uRect = GLES20.glGetUniformLocation(program, "uRect")
    uKey = GLES20.glGetUniformLocation(program, "uKey"); uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    GlUtil.createExternalTextures(1, tex, 0)
    val st = SurfaceTexture(tex[0])
    surfaceTexture = st
    Handler(Looper.getMainLooper()).post { onSurface(st) }
  }

  override fun drawFilter() {
    try { surfaceTexture?.updateTexImage(); surfaceTexture?.getTransformMatrix(webST) } catch (_: Exception) {}
    GLES20.glUseProgram(program)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aPos)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aTex)
    GLES20.glUniformMatrix4fv(uMVP, 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(uST, 1, false, STMatrix, 0)
    GLES20.glUniformMatrix4fv(uWebST, 1, false, webST, 0)
    GLES20.glUniform4f(uRect, rx, ry, rw.coerceAtLeast(0.0001f), rh.coerceAtLeast(0.0001f))
    GLES20.glUniform1f(uKey, if (key) 1f else 0f)
    GLES20.glUniform1f(uAlpha, if (visible) alpha else 0f)
    GLES20.glUniform1i(uSampler, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    GLES20.glUniform1i(uWeb, 1)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
  }

  override fun disableResources() { GlUtil.disableResources(aTex, aPos) }

  override fun release() {
    GLES20.glDeleteProgram(program)
    GLES20.glDeleteTextures(1, tex, 0)
    surfaceTexture?.release(); surfaceTexture = null
  }

  companion object {
    private const val VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""
    private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform sampler2D uSampler;
uniform samplerExternalOES uWeb;
uniform mat4 uWebST;
uniform vec4 uRect;
uniform float uKey;
uniform float uAlpha;
varying vec2 vTextureCoord;
void main() {
  vec4 base = texture2D(uSampler, vTextureCoord);
  vec2 t = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 p = (t - uRect.xy) / uRect.zw;
  if (uAlpha <= 0.0 || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) { gl_FragColor = base; return; }
  vec2 tc = (uWebST * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy;
  vec4 col = texture2D(uWeb, tc);
  float a = 1.0;
  vec3 f = col.rgb;
  if (uKey > 0.5) {
    // страница нарисована поверх розового (1,0,1): пиксель = a*цвет + (1-a)*розовый.
    // «Розовость» пикселя показывает, сколько фона просвечивает, — восстанавливаем и прозрачность, и настоящий цвет.
    float m = clamp(min(col.r, col.b) - col.g, 0.0, 1.0);
    a = clamp((1.0 - m - 0.03) / 0.94, 0.0, 1.0);
    f = a > 0.02 ? clamp((col.rgb - (1.0 - a) * vec3(1.0, 0.0, 1.0)) / a, 0.0, 1.0) : vec3(0.0);
  }
  gl_FragColor = vec4(mix(base.rgb, f, a * uAlpha), 1.0);
}
"""
  }
}

/** Скрытый экран с WebView, картинка которого уходит в WebFilterRender. */
class WebWidgetHost(private val ctx: Context) {
  private var display: VirtualDisplay? = null
  private var presentation: Presentation? = null
  private var web: WebView? = null
  private var surface: Surface? = null
  var url: String = ""; private set

  @SuppressLint("SetJavaScriptEnabled")
  fun attach(st: SurfaceTexture, width: Int, height: Int, pageUrl: String, key: Boolean) {
    release()
    try {
      val w = width.coerceIn(64, 1920); val h = height.coerceIn(64, 1920)
      st.setDefaultBufferSize(w, h)
      val sf = Surface(st); surface = sf
      val dm = ctx.getSystemService(DisplayManager::class.java)
      val vd = dm.createVirtualDisplay("streamscene-web", w, h, 160, sf, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
      display = vd
      val p = Presentation(ctx, vd.display)
      val bg = if (key) WEB_KEY_COLOR else Color.TRANSPARENT
      p.window?.setBackgroundDrawable(ColorDrawable(bg))
      val wv = WebView(p.context)
      wv.setBackgroundColor(bg)
      with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = false
        loadWithOverviewMode = false
      }
      wv.webViewClient = WebViewClient()
      wv.webChromeClient = WebChromeClient()
      p.setContentView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      p.show()
      web = wv; presentation = p
      load(pageUrl)
    } catch (e: Exception) {
      Log.w("StreamScene", "web widget", e)
    }
  }

  fun load(pageUrl: String) {
    url = pageUrl
    web?.loadUrl(if (pageUrl.startsWith("http")) pageUrl else "https://$pageUrl")
  }

  fun release() {
    try { web?.destroy() } catch (_: Exception) {}
    try { presentation?.dismiss() } catch (_: Exception) {}
    try { display?.release() } catch (_: Exception) {}
    try { surface?.release() } catch (_: Exception) {}
    web = null; presentation = null; display = null; surface = null
  }
}
