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
 * (двигать, уменьшать, растягивать). Вне прямоугольника — чёрный,
 * его затем перекрывает слой с баннерами.
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

  @Volatile private var rx = 0f
  @Volatile private var ry = 0f
  @Volatile private var rw = 1f
  @Volatile private var rh = 1f
  @Volatile private var flip = 0f

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
    GLES20.glUniform1i(uSamplerHandle, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
  }

  override fun disableResources() {
    GlUtil.disableResources(aTextureHandle, aPositionHandle)
  }

  override fun release() {
    GLES20.glDeleteProgram(program)
  }

  fun setRect(x: Float, y: Float, w: Float, h: Float, flipped: Boolean) {
    rx = x; ry = y; rw = w; rh = h; flip = if (flipped) 1f else 0f
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
    private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uSampler;
uniform vec4 uRect;
uniform float uFlip;
varying vec2 vTextureCoord;
void main() {
  vec2 t = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 p = (t - uRect.xy) / uRect.zw;
  if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  } else {
    if (uFlip > 0.5) p.x = 1.0 - p.x;
    gl_FragColor = texture2D(uSampler, vec2(p.x, 1.0 - p.y));
  }
}
"""
  }
}
