package com.streamscene.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.BaseRenderOffScreen
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ставит картинку экрана телефона в заданный прямоугольник кадра
 * (двигать, уменьшать, растягивать). Вне прямоугольника — чёрный
 * или размытая игра (режим «Размытие»), сверху — слой с баннерами.
 * Размытие дешёвое: игра сначала ужимается в маленькую картинку (усреднением),
 * потом растягивается обратно с мягким сглаживанием.
 */
class PlaceFilterRender : BaseFilterRender() {

  private val squareVertexDataFilter = floatArrayOf(
    // X, Y, Z, U, V
    -1f, -1f, 0f, 0f, 0f,
    1f, -1f, 0f, 1f, 0f,
    -1f, 1f, 0f, 0f, 1f,
    1f, 1f, 0f, 1f, 1f
  )

  private var program = -1
  private var aPositionHandle = -1
  private var aTextureHandle = -1
  private var uMVPMatrixHandle = -1
  private var uSTMatrixHandle = -1
  private var uSamplerHandle = -1
  private var uRectHandle = -1
  private var uFlipHandle = -1
  private var uCropHandle = -1

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
  @Volatile private var coverX = 1f
  @Volatile private var coverY = 1f

  // маленький кадр для размытия
  private var downProgram = -1
  private var dPos = -1; private var dTex = -1; private var dSampler = -1; private var dCover = -1; private var dStep = -1; private var dCrop = -1
  private var uBgHandle = -1; private var uBlurHandle = -1; private var uBgStepHandle = -1
  private val smallFbo = IntArray(1)
  private val smallTex = IntArray(1)
  private var smallOk = false
  private var uModeHandle = -1
  private var uCoverHandle = -1
  private var sw = 96
  private var sh = 54

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
    uBgHandle = GLES20.glGetUniformLocation(program, "uBg")
    uBlurHandle = GLES20.glGetUniformLocation(program, "uBlur")
    uBgStepHandle = GLES20.glGetUniformLocation(program, "uBgStep")
    uModeHandle = GLES20.glGetUniformLocation(program, "uMode")
    uCoverHandle = GLES20.glGetUniformLocation(program, "uCover")

    downProgram = GlUtil.createProgram(VERTEX, DOWN)
    dPos = GLES20.glGetAttribLocation(downProgram, "aPosition")
    dTex = GLES20.glGetAttribLocation(downProgram, "aTextureCoord")
    dSampler = GLES20.glGetUniformLocation(downProgram, "uSampler")
    dCover = GLES20.glGetUniformLocation(downProgram, "uCover")
    dStep = GLES20.glGetUniformLocation(downProgram, "uStep")
    dCrop = GLES20.glGetUniformLocation(downProgram, "uCrop")
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
  }

  /** Сначала (если включено размытие) ужимаем игру в маленький кадр, потом обычный проход. */
  override fun draw() {
    if (blur && smallOk) {
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
      GLES20.glUniform2f(dCover, coverX, coverY)
      GLES20.glUniform2f(dStep, 1f / sw, 1f / sh)
      GLES20.glUniform4f(dCrop, cl, ct, cr, cb)
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

  override fun drawFilter() {
    GLES20.glUseProgram(program)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(
      aPositionHandle, 3, GLES20.GL_FLOAT, false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
    )
    GLES20.glEnableVertexAttribArray(aPositionHandle)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(
      aTextureHandle, 2, GLES20.GL_FLOAT, false,
      BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
    )
    GLES20.glEnableVertexAttribArray(aTextureHandle)
    GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, STMatrix, 0)
    GLES20.glUniform4f(uRectHandle, rx, ry, rw.coerceAtLeast(0.0001f), rh.coerceAtLeast(0.0001f))
    GLES20.glUniform1f(uFlipHandle, flip)
    GLES20.glUniform4f(uCropHandle, cl, ct, cr, cb)
    GLES20.glUniform1f(uBlurHandle, if (blur) 1f else 0f)
    GLES20.glUniform1f(uModeHandle, if (smallOk) 1f else 0f)
    GLES20.glUniform2f(uCoverHandle, coverX, coverY)
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
    GLES20.glDeleteTextures(1, smallTex, 0)
  }

  /** Режим «Размытие»: края кадра заполняет размытая игра. screenAR — пропорции окна игры, frameAR — кадра. */
  fun setBlur(on: Boolean, screenAR: Float, frameAR: Float) {
    blur = on
    // «обрезать по кадру»: какую часть игры видно, если растянуть её на весь кадр без искажений
    if (screenAR > frameAR) { coverX = frameAR / screenAR; coverY = 1f } else { coverX = 1f; coverY = screenAR / frameAR }
  }

  fun setRect(x: Float, y: Float, w: Float, h: Float, flipped: Boolean) {
    rx = x; ry = y; rw = w; rh = h; flip = if (flipped) 1f else 0f
  }

  /** Обрезка краёв экрана игры (доли от 0 до ~0.45). */
  fun setCrop(l: Float, t: Float, r: Float, b: Float) {
    cl = l; ct = t; cr = r; cb = b
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
    // В цепочке фильтров верх кадра — это v = 1, поэтому переворачиваем Y,
    // чтобы прямоугольник задавался от левого верхнего угла, как в редакторе.
    // уменьшенная копия игры «по кадру» (16 выборок на пиксель — уже мягкая)
    private const val DOWN = """
precision mediump float;
uniform sampler2D uSampler;
uniform vec2 uCover;
uniform vec2 uStep;
uniform vec4 uCrop;
varying vec2 vTextureCoord;
vec4 at(vec2 q) {
  vec2 t = vec2(0.5) + (q - vec2(0.5)) * uCover;
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
    private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uSampler;
uniform sampler2D uBg;
uniform float uBlur;
uniform float uMode;
uniform vec2 uCover;
uniform vec2 uBgStep;
uniform vec4 uRect;
uniform float uFlip;
uniform vec4 uCrop;
varying vec2 vTextureCoord;
void main() {
  vec2 t = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 p = (t - uRect.xy) / uRect.zw;
  if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
    if (uBlur > 0.5 && uMode < 0.5) {
      // запасной вариант без маленькой текстуры: размытие прямо по игре
      vec2 q = vec2(0.5) + (t - vec2(0.5)) * uCover;
      vec4 c = vec4(0.0);
      for (int i = 0; i < 5; i++) {
        for (int j = 0; j < 5; j++) {
          vec2 o = (vec2(float(i), float(j)) - 2.0) * 0.012;
          vec2 sq = q + o;
          vec2 s2 = vec2(uCrop.x + sq.x * (1.0 - uCrop.x - uCrop.z), uCrop.y + sq.y * (1.0 - uCrop.y - uCrop.w));
          c += texture2D(uSampler, vec2(s2.x, 1.0 - s2.y));
        }
      }
      gl_FragColor = vec4(c.rgb / 25.0 * 0.8, 1.0);
    } else if (uBlur > 0.5) {
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
      gl_FragColor = vec4(c.rgb * 0.8, 1.0);
    } else {
      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    }
  } else {
    if (uFlip > 0.5) p.x = 1.0 - p.x;
    vec2 s = vec2(uCrop.x + p.x * (1.0 - uCrop.x - uCrop.z), uCrop.y + p.y * (1.0 - uCrop.y - uCrop.w));
    gl_FragColor = texture2D(uSampler, vec2(s.x, 1.0 - s.y));
  }
}
"""
  }
}
