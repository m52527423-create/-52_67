package com.streamscene.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.pedro.encoder.input.gl.render.BaseRenderOffScreen
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Ставит картинку экрана телефона в заданный прямоугольник кадра.
 *
 * Что делает сверх простого «вставить картинку»:
 *  1. Сам находит чёрные полосы, которыми Android обрамляет экран телефона внутри кадра,
 *     и берёт только саму картинку (поведение Android разное на разных телефонах,
 *     поэтому не угадываем, а смотрим на пиксели раз в ~полсекунды).
 *  2. Если картинка телефона повернута иначе, чем окно игры (например, рабочий стол
 *     вертикальный, а окно горизонтальное), — вписывает её без искажений.
 *  3. Режим «Размытие»: всё, что вокруг картинки, — та же игра, растянутая на кадр и размытая.
 */
class PlaceFilterRender : BaseFilterRender() {

  private val squareVertexDataFilter = floatArrayOf(
    // X, Y, Z, U, V
    -1f, -1f, 0f, 0f, 0f,
    1f, -1f, 0f, 1f, 0f,
    -1f, 1f, 0f, 0f, 1f,
    1f, 1f, 0f, 1f, 1f
  )

  // основной проход
  private var program = -1
  private var aPositionHandle = -1
  private var aTextureHandle = -1
  private var uMVPMatrixHandle = -1
  private var uSTMatrixHandle = -1
  private var uSamplerHandle = -1
  private var uRectHandle = -1
  private var uFlipHandle = -1
  private var uCropHandle = -1
  private var uFitHandle = -1
  private var uBgHandle = -1
  private var uBlurHandle = -1
  private var uBgStepHandle = -1

  // уменьшенная копия для размытия
  private var downProgram = -1
  private var dPos = -1; private var dTex = -1; private var dSampler = -1
  private var dCover = -1; private var dStep = -1; private var dCrop = -1
  private var dMvp = -1; private var dSt = -1; private var dFlip = -1
  private val smallFbo = IntArray(1)
  private val smallTex = IntArray(1)
  private var smallOk = false
  private var sw = 96
  private var sh = 54

  // поиск чёрных полос
  private val probeFbo = IntArray(1)
  private var lastProbe = 0L
  private var useBlur = false
  private var colBuf: ByteBuffer? = null
  private var rowBuf: ByteBuffer? = null
  private var lastCand = floatArrayOf(-1f, -1f, -1f, -1f)
  private var auto = floatArrayOf(0f, 0f, 0f, 0f)   // l t r b — полосы Android

  @Volatile private var rx = 0f
  @Volatile private var ry = 0f
  @Volatile private var rw = 1f
  @Volatile private var rh = 1f
  @Volatile private var flip = 0f
  @Volatile private var cl = 0f
  @Volatile private var ct = 0f
  @Volatile private var cr = 0f
  @Volatile private var cb = 0f
  @Volatile private var blur = false

  // итоговая обрезка (полосы Android + обрезка пользователя) и вписывание — считаются на каждом кадре
  private val eff = FloatArray(4)
  private var sx = 0f; private var sy = 0f; private var sW = 1f; private var sH = 1f; private var sFlip = 0f
  private var uc0 = 0f; private var uc1 = 0f; private var uc2 = 0f; private var uc3 = 0f
  private val fit = floatArrayOf(0f, 0f, 1f, 1f)
  private var coverX = 1f
  private var coverY = 1f

  init {
    squareVertex = ByteBuffer.allocateDirect(squareVertexDataFilter.size * BaseRenderOffScreen.FLOAT_SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
    squareVertex.put(squareVertexDataFilter).position(0)
    Matrix.setIdentityM(MVPMatrix, 0)
    Matrix.setIdentityM(STMatrix, 0)
  }

  override fun initGlFilter(context: Context) {
    program = GlUtil.createProgram(VERTEX, FRAGMENT)
    aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
    uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
    uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
    uRectHandle = GLES20.glGetUniformLocation(program, "uRect")
    uFlipHandle = GLES20.glGetUniformLocation(program, "uFlip")
    uCropHandle = GLES20.glGetUniformLocation(program, "uCrop")
    uFitHandle = GLES20.glGetUniformLocation(program, "uFit")
    uBgHandle = GLES20.glGetUniformLocation(program, "uBg")
    uBlurHandle = GLES20.glGetUniformLocation(program, "uBlur")
    uBgStepHandle = GLES20.glGetUniformLocation(program, "uBgStep")

    downProgram = GlUtil.createProgram(VERTEX, DOWN)
    dPos = GLES20.glGetAttribLocation(downProgram, "aPosition")
    dTex = GLES20.glGetAttribLocation(downProgram, "aTextureCoord")
    dSampler = GLES20.glGetUniformLocation(downProgram, "uSampler")
    dCover = GLES20.glGetUniformLocation(downProgram, "uCover")
    dStep = GLES20.glGetUniformLocation(downProgram, "uStep")
    dCrop = GLES20.glGetUniformLocation(downProgram, "uCrop")
    dMvp = GLES20.glGetUniformLocation(downProgram, "uMVPMatrix")
    dSt = GLES20.glGetUniformLocation(downProgram, "uSTMatrix")
    dFlip = GLES20.glGetUniformLocation(downProgram, "uFlip")

    val w = getWidth().coerceAtLeast(1); val h = getHeight().coerceAtLeast(1)
    if (w >= h) { sw = 96; sh = (96f * h / w).toInt().coerceAtLeast(8) } else { sh = 96; sw = (96f * w / h).toInt().coerceAtLeast(8) }
    GLES20.glGenTextures(1, smallTex, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTex[0])
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sw, sh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glGenFramebuffers(1, smallFbo, 0)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo[0])
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smallTex[0], 0)
    smallOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE && downProgram > 0
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    GLES20.glGenFramebuffers(1, probeFbo, 0)
    colBuf = ByteBuffer.allocateDirect(h * 4).order(ByteOrder.nativeOrder())
    rowBuf = ByteBuffer.allocateDirect(w * 4).order(ByteOrder.nativeOrder())
    Log.i(TAG, "place init ${w}x$h blurBuf ${sw}x$sh ok=$smallOk")
  }

  override fun draw() {
    val w = getWidth().coerceAtLeast(1); val h = getHeight().coerceAtLeast(1)
    val now = System.nanoTime()
    if (now - lastProbe > 700_000_000L) { lastProbe = now; try { probeBars(w, h) } catch (e: Exception) { Log.w(TAG, "probe", e) } }
    // снимок настроек на весь кадр, чтобы они не поменялись между проходами
    sx = rx; sy = ry; sW = rw; sH = rh; sFlip = flip; uc0 = cl; uc1 = ct; uc2 = cr; uc3 = cb
    useBlur = blur && smallOk
    computeLayout(w, h)

    if (useBlur) {
      // маленькая текстура не должна быть привязана ни к одному слоту, пока в неё рисуем
      GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo[0])
      GLES20.glViewport(0, 0, sw, sh)
      GLES20.glUseProgram(downProgram)
      squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
      GLES20.glVertexAttribPointer(dPos, 3, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
      GLES20.glEnableVertexAttribArray(dPos)
      squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
      GLES20.glVertexAttribPointer(dTex, 2, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
      GLES20.glEnableVertexAttribArray(dTex)
      GLES20.glUniformMatrix4fv(dMvp, 1, false, MVPMatrix, 0)
      GLES20.glUniformMatrix4fv(dSt, 1, false, STMatrix, 0)
      GLES20.glUniform2f(dCover, coverX, coverY)
      GLES20.glUniform2f(dStep, 1f / sw, 1f / sh)
      GLES20.glUniform4f(dCrop, eff[0], eff[1], eff[2], eff[3])
      GLES20.glUniform1f(dFlip, sFlip)
      GLES20.glUniform1i(dSampler, 0)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.disableResources(dTex, dPos)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
    super.draw()
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
  }

  /** Считаем итоговую обрезку, вписывание и область «по кадру» для размытия. */
  private fun computeLayout(w: Int, h: Int) {
    val a = auto
    val iw = 1f - a[0] - a[2]; val ih = 1f - a[1] - a[3]
    eff[0] = a[0] + uc0 * iw; eff[1] = a[1] + uc1 * ih; eff[2] = a[2] + uc2 * iw; eff[3] = a[3] + uc3 * ih
    // пропорции самой картинки телефона (в пикселях) и окна игры
    val cAR = (w.toFloat() * (1f - eff[0] - eff[2])) / (h.toFloat() * (1f - eff[1] - eff[3])).coerceAtLeast(1f)
    val rAR = (sW * w) / (sH * h).coerceAtLeast(1f)
    // если картинка повернута иначе, чем окно (вертикальный рабочий стол в горизонтальном окне), — вписываем без искажений
    if ((cAR > 1f) != (rAR > 1f) && cAR > 0f && rAR > 0f) {
      if (cAR > rAR) { val s = rAR / cAR; fit[0] = 0f; fit[1] = (1f - s) / 2f; fit[2] = 1f; fit[3] = s }
      else { val s = cAR / rAR; fit[0] = (1f - s) / 2f; fit[1] = 0f; fit[2] = s; fit[3] = 1f }
    } else { fit[0] = 0f; fit[1] = 0f; fit[2] = 1f; fit[3] = 1f }
    // фон «по кадру»: какую часть картинки видно, если растянуть её на весь кадр без искажений
    val fAR = w.toFloat() / h
    if (cAR > fAR) { coverX = fAR / cAR; coverY = 1f } else { coverX = 1f; coverY = cAR / fAR }
  }

  /**
   * Смотрим на пиксели исходной картинки по нескольким линиям и находим сплошные чёрные полосы по краям.
   * Полосы принимаем, только если они симметричные (Android ставит картинку по центру)
   * и повторились две проверки подряд — чтобы тёмная игра не выглядела как «полоса».
   */
  private fun probeBars(w: Int, h: Int) {
    val col = colBuf ?: return; val row = rowBuf ?: return
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo[0])
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, previousTexId, 0)
    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); return
    }
    fun black(b: ByteBuffer, i: Int): Boolean {
      val r = b.get(i * 4).toInt() and 0xff; val g = b.get(i * 4 + 1).toInt() and 0xff; val bl = b.get(i * 4 + 2).toInt() and 0xff
      return r < 14 && g < 14 && bl < 14
    }
    // сначала слева/справа (по 3 строкам)
    var left = w; var right = w
    for (fy in floatArrayOf(.25f, .5f, .75f)) {
      row.clear()
      GLES20.glReadPixels(0, (h * fy).toInt(), w, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, row)
      var l = 0; while (l < w && black(row, l)) l++
      var r = 0; while (r < w && black(row, w - 1 - r)) r++
      left = min(left, l); right = min(right, r)
    }
    // потом сверху/снизу — по столбцам внутри картинки, а не в боковых полосах
    var top = h; var bottom = h
    if (left < w) {
      val x0 = left.toFloat() / w; val x1 = 1f - right.toFloat() / w
      for (k in floatArrayOf(.25f, .5f, .75f)) {
        col.clear()
        GLES20.glReadPixels(((x0 + (x1 - x0) * k) * w).toInt().coerceIn(0, w - 1), 0, 1, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, col)
        var bb = 0; while (bb < h && black(col, bb)) bb++              // строка 0 — низ картинки
        var t = 0; while (t < h && black(col, h - 1 - t)) t++
        bottom = min(bottom, bb); top = min(top, t)
      }
    }
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

    // весь кадр чёрный (загрузка, затемнение) — ничего не решаем, оставляем как было
    if (left >= w || top >= h) return
    val ft = top.toFloat() / h; val fb = bottom.toFloat() / h
    val fl = left.toFloat() / w; val fr = right.toFloat() / w
    val cand = floatArrayOf(0f, 0f, 0f, 0f)
    // Android добавляет полосы только с одной стороны: либо по бокам, либо сверху-снизу
    if (fl < .45f && fr < .45f && fl > .012f && fr > .012f && abs(fl - fr) < .025f) { val v = (fl + fr) / 2f + .002f; cand[0] = v; cand[2] = v }
    else if (ft < .45f && fb < .45f && ft > .012f && fb > .012f && abs(ft - fb) < .025f) { val v = (ft + fb) / 2f + .002f; cand[1] = v; cand[3] = v }
    val same = (0..3).all { abs(cand[it] - lastCand[it]) < .006f }
    lastCand = cand
    if (same && (0..3).any { abs(cand[it] - auto[it]) > .004f }) {
      auto = cand
      Log.i(TAG, "bars l=${cand[0]} t=${cand[1]} r=${cand[2]} b=${cand[3]}")
    }
  }

  override fun drawFilter() {
    GLES20.glUseProgram(program)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aPositionHandle)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aTextureHandle)
    GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
    GLES20.glUniform4f(uRectHandle, sx, sy, sW.coerceAtLeast(0.0001f), sH.coerceAtLeast(0.0001f))
    GLES20.glUniform1f(uFlipHandle, sFlip)
    GLES20.glUniform4f(uCropHandle, eff[0], eff[1], eff[2], eff[3])
    GLES20.glUniform4f(uFitHandle, fit[0], fit[1], fit[2].coerceAtLeast(0.0001f), fit[3].coerceAtLeast(0.0001f))
    GLES20.glUniform1f(uBlurHandle, if (useBlur) 1f else 0f)
    GLES20.glUniform2f(uBgStepHandle, 1f / sw, 1f / sh)
    GLES20.glUniform1i(uBgHandle, 1)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallTex[0])
    GLES20.glUniform1i(uSamplerHandle, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
  }

  override fun disableResources() {
    GlUtil.disableResources(aTextureHandle, aPositionHandle)
  }

  override fun release() {
    GLES20.glDeleteProgram(program)
    if (downProgram > 0) GLES20.glDeleteProgram(downProgram)
    GLES20.glDeleteFramebuffers(1, smallFbo, 0)
    GLES20.glDeleteFramebuffers(1, probeFbo, 0)
    GLES20.glDeleteTextures(1, smallTex, 0)
  }

  /** Режим «Размытие»: края кадра заполняет размытая игра. */
  @Suppress("UNUSED_PARAMETER")
  fun setBlur(on: Boolean, screenAR: Float, frameAR: Float) { blur = on }

  fun setRect(x: Float, y: Float, w: Float, h: Float, flipped: Boolean) {
    rx = x; ry = y; rw = w; rh = h; flip = if (flipped) 1f else 0f
  }

  /** Обрезка краёв экрана игры, которую задал пользователь (доли от 0 до ~0.45). */
  fun setCrop(l: Float, t: Float, r: Float, b: Float) {
    cl = l; ct = t; cr = r; cb = b
  }

  companion object {
    private const val TAG = "StreamScene"
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
    // уменьшенная копия картинки «по кадру» (16 выборок на пиксель — уже мягкая)
    private const val DOWN = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSampler;
uniform vec2 uCover;
uniform vec2 uStep;
uniform vec4 uCrop;
uniform float uFlip;
varying vec2 vTextureCoord;
vec4 at(vec2 q) {
  vec2 t = clamp(vec2(0.5) + (q - vec2(0.5)) * uCover, 0.0, 1.0);
  if (uFlip > 0.5) t.x = 1.0 - t.x;
  vec2 s = vec2(uCrop.x + t.x * (1.0 - uCrop.x - uCrop.z), uCrop.y + t.y * (1.0 - uCrop.y - uCrop.w));
  return texture2D(uSampler, vec2(s.x, 1.0 - s.y));
}
void main() {
  vec2 q = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec4 c = vec4(0.0);
  for (int i = 0; i < 4; i++) {
    for (int j = 0; j < 4; j++) {
      c += at(q + (vec2(float(i), float(j)) - 1.5) * uStep * 0.6);
    }
  }
  gl_FragColor = c / 16.0;
}
"""
    // В цепочке фильтров верх кадра — это v = 1, поэтому переворачиваем Y,
    // чтобы прямоугольник задавался от левого верхнего угла, как в редакторе.
    private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSampler;
uniform sampler2D uBg;
uniform float uBlur;
uniform vec2 uBgStep;
uniform vec4 uRect;
uniform float uFlip;
uniform vec4 uCrop;
uniform vec4 uFit;
varying vec2 vTextureCoord;
vec4 bg() {
  if (uBlur < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
  vec2 u = vTextureCoord;
  vec4 c = texture2D(uBg, u) * 0.25;
  c += texture2D(uBg, u + vec2(uBgStep.x, 0.0)) * 0.125;
  c += texture2D(uBg, u - vec2(uBgStep.x, 0.0)) * 0.125;
  c += texture2D(uBg, u + vec2(0.0, uBgStep.y)) * 0.125;
  c += texture2D(uBg, u - vec2(0.0, uBgStep.y)) * 0.125;
  c += texture2D(uBg, u + uBgStep) * 0.0625;
  c += texture2D(uBg, u - uBgStep) * 0.0625;
  c += texture2D(uBg, u + vec2(uBgStep.x, -uBgStep.y)) * 0.0625;
  c += texture2D(uBg, u + vec2(-uBgStep.x, uBgStep.y)) * 0.0625;
  return vec4(c.rgb * 0.8, 1.0);
}
void main() {
  vec2 t = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 p = (t - uRect.xy) / uRect.zw;
  p = (p - uFit.xy) / uFit.zw;
  if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
    gl_FragColor = bg();
  } else {
    if (uFlip > 0.5) p.x = 1.0 - p.x;
    vec2 s = vec2(uCrop.x + p.x * (1.0 - uCrop.x - uCrop.z), uCrop.y + p.y * (1.0 - uCrop.y - uCrop.w));
    gl_FragColor = texture2D(uSampler, vec2(s.x, 1.0 - s.y));
  }
}
"""
  }
}
