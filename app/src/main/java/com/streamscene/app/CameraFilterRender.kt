package com.streamscene.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.BaseRenderOffScreen
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Картинка с камеры поверх кадра: прямоугольник со скруглением или круг,
 * рамка, поворот, зеркало, обрезка «по заполнению».
 */
class CameraFilterRender(private val onSurface: (SurfaceTexture) -> Unit) : BaseFilterRender() {

  private val verts = floatArrayOf(
    -1f, -1f, 0f, 0f, 0f,
    1f, -1f, 0f, 1f, 0f,
    -1f, 1f, 0f, 0f, 1f,
    1f, 1f, 0f, 1f, 1f
  )
  private var program = -1
  private var aPos = -1; private var aTex = -1
  private var uMVP = -1; private var uST = -1; private var uSampler = -1; private var uCam = -1
  private var uRect = -1; private var uScale = -1; private var uRot = -1; private var uMirror = -1
  private var uShape = -1; private var uRadius = -1; private var uAspect = -1; private var uBorder = -1
  private var uAlpha = -1; private var uCamST = -1

  private val camTex = IntArray(1)
  @Volatile var surfaceTexture: SurfaceTexture? = null
    private set
  private val camST = FloatArray(16)

  // параметры (меняются с основного потока)
  @Volatile var visible = false
  @Volatile var rx = 0f; @Volatile var ry = 0f; @Volatile var rw = .25f; @Volatile var rh = .25f
  @Volatile var scaleX = 1f; @Volatile var scaleY = 1f
  @Volatile var rotQuarter = 0
  @Volatile var mirror = true
  @Volatile var circle = false
  @Volatile var radius = 0f          // доля от меньшей стороны (0..0.5)
  @Volatile var aspect = 1f          // ширина/высота окна в пикселях
  @Volatile var borderR = 1f; @Volatile var borderG = 1f; @Volatile var borderB = 1f
  @Volatile var borderW = 0f         // доля от меньшей стороны
  @Volatile var alpha = 1f

  init {
    squareVertex = ByteBuffer.allocateDirect(verts.size * BaseRenderOffScreen.FLOAT_SIZE_BYTES)
      .order(ByteOrder.nativeOrder()).asFloatBuffer()
    squareVertex.put(verts).position(0)
    Matrix.setIdentityM(MVPMatrix, 0)
    Matrix.setIdentityM(STMatrix, 0)
    Matrix.setIdentityM(camST, 0)
  }

  override fun initGlFilter(context: Context) {
    program = GlUtil.createProgram(VERTEX, FRAGMENT)
    aPos = GLES20.glGetAttribLocation(program, "aPosition")
    aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
    uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    uST = GLES20.glGetUniformLocation(program, "uSTMatrix")
    uSampler = GLES20.glGetUniformLocation(program, "uSampler")
    uCam = GLES20.glGetUniformLocation(program, "uCam")
    uRect = GLES20.glGetUniformLocation(program, "uRect")
    uScale = GLES20.glGetUniformLocation(program, "uScale")
    uRot = GLES20.glGetUniformLocation(program, "uRot")
    uMirror = GLES20.glGetUniformLocation(program, "uMirror")
    uShape = GLES20.glGetUniformLocation(program, "uShape")
    uRadius = GLES20.glGetUniformLocation(program, "uRadius")
    uAspect = GLES20.glGetUniformLocation(program, "uAspect")
    uBorder = GLES20.glGetUniformLocation(program, "uBorder")
    uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    uCamST = GLES20.glGetUniformLocation(program, "uCamST")

    GlUtil.createExternalTextures(1, camTex, 0)
    val st = SurfaceTexture(camTex[0])
    surfaceTexture = st
    Handler(Looper.getMainLooper()).post { onSurface(st) }
  }

  override fun drawFilter() {
    try {
      surfaceTexture?.updateTexImage()
      surfaceTexture?.getTransformMatrix(camST)
    } catch (_: Exception) {}
    GLES20.glUseProgram(program)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aPos)
    squareVertex.position(BaseRenderOffScreen.SQUARE_VERTEX_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, BaseRenderOffScreen.SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
    GLES20.glEnableVertexAttribArray(aTex)
    GLES20.glUniformMatrix4fv(uMVP, 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(uST, 1, false, STMatrix, 0)
    GLES20.glUniformMatrix4fv(uCamST, 1, false, camST, 0)
    GLES20.glUniform4f(uRect, rx, ry, rw.coerceAtLeast(0.0001f), rh.coerceAtLeast(0.0001f))
    GLES20.glUniform2f(uScale, scaleX, scaleY)
    GLES20.glUniform1f(uRot, rotQuarter.toFloat())
    GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
    GLES20.glUniform1f(uShape, if (circle) 1f else 0f)
    GLES20.glUniform1f(uRadius, radius)
    GLES20.glUniform1f(uAspect, aspect)
    GLES20.glUniform4f(uBorder, borderR, borderG, borderB, borderW)
    GLES20.glUniform1f(uAlpha, if (visible) alpha else 0f)
    GLES20.glUniform1i(uSampler, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    GLES20.glUniform1i(uCam, 1)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex[0])
  }

  override fun disableResources() {
    GlUtil.disableResources(aTex, aPos)
  }

  override fun release() {
    GLES20.glDeleteProgram(program)
    GLES20.glDeleteTextures(1, camTex, 0)
    surfaceTexture?.release()
    surfaceTexture = null
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
uniform samplerExternalOES uCam;
uniform mat4 uCamST;
uniform vec4 uRect;
uniform vec2 uScale;
uniform float uRot;
uniform float uMirror;
uniform float uShape;
uniform float uRadius;
uniform float uAspect;
uniform vec4 uBorder;
uniform float uAlpha;
varying vec2 vTextureCoord;
void main() {
  vec4 base = texture2D(uSampler, vTextureCoord);
  vec2 t = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 p = (t - uRect.xy) / uRect.zw;
  if (uAlpha <= 0.0 || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) { gl_FragColor = base; return; }
  vec2 c = p - 0.5;
  vec2 q; vec2 hs;
  if (uAspect >= 1.0) { q = vec2(c.x * uAspect, c.y); hs = vec2(0.5 * uAspect, 0.5); }
  else { q = vec2(c.x, c.y / uAspect); hs = vec2(0.5, 0.5 / uAspect); }
  float d;
  if (uShape > 0.5) { d = length(q) - 0.5; }
  else { vec2 r = abs(q) - (hs - vec2(uRadius)); d = length(max(r, 0.0)) + min(max(r.x, r.y), 0.0) - uRadius; }
  if (d > 0.0) { gl_FragColor = base; return; }
  vec2 uv = (p - 0.5) * uScale + 0.5;
  if (uMirror > 0.5) uv.x = 1.0 - uv.x;
  if (uRot > 0.5 && uRot < 1.5) uv = vec2(uv.y, 1.0 - uv.x);
  else if (uRot > 1.5 && uRot < 2.5) uv = vec2(1.0 - uv.x, 1.0 - uv.y);
  else if (uRot > 2.5) uv = vec2(1.0 - uv.y, uv.x);
  vec2 tc = (uCamST * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
  vec4 col = texture2D(uCam, tc);
  if (uBorder.a > 0.0 && d > -uBorder.a) col = vec4(uBorder.rgb, 1.0);
  float edge = clamp(-d * 400.0, 0.0, 1.0);
  gl_FragColor = mix(base, col, uAlpha * edge);
}
"""
  }
}
