package com.streamscene.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/** Камера (Camera2) → SurfaceTexture фильтра камеры. */
class CameraController(private val ctx: Context) {
  private val mgr = ctx.getSystemService(CameraManager::class.java)
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var surface: Surface? = null
  var sensorOrientation = 270; private set
  var isFront = true; private set
  var size = Size(1280, 720); private set
  var onError: ((String) -> Unit)? = null

  private fun findCamera(front: Boolean): String? {
    val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    return mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
      ?: mgr.cameraIdList.firstOrNull()
  }

  @SuppressLint("MissingPermission")
  fun open(st: SurfaceTexture, front: Boolean) {
    close()
    try {
      val id = findCamera(front)
      if (id == null) { onError?.invoke("Камера не найдена"); return }
      val ch = mgr.getCameraCharacteristics(id)
      isFront = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
      sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (isFront) 270 else 90
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
      size = sizes.filter { it.width <= 1280 && it.height <= 720 }
        .minByOrNull { abs(it.width - 1280) + abs(it.height - 720) } ?: sizes.firstOrNull() ?: Size(640, 480)
      val fpsRange = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.filter { it.upper <= 30 }?.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
      st.setDefaultBufferSize(size.width, size.height)
      val sf = Surface(st); surface = sf
      val t = HandlerThread("camera").also { it.start() }; thread = t
      val h = Handler(t.looper); handler = h
      mgr.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
          device = d
          try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(sf), object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                  val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(sf)
                    if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fpsRange.lower, fpsRange.upper))
                  }
                  s.setRepeatingRequest(req.build(), null, h)
                } catch (e: Exception) { onError?.invoke("Камера: ${e.message}") }
              }
              override fun onConfigureFailed(s: CameraCaptureSession) { onError?.invoke("Камера не запустилась") }
            }, h)
          } catch (e: Exception) { onError?.invoke("Камера: ${e.message}") }
        }
        override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
        override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; onError?.invoke("Камера занята другим приложением") }
      }, h)
    } catch (e: Exception) {
      Log.w("StreamScene", "camera", e)
      onError?.invoke("Камера: ${e.message}")
    }
  }

  fun close() {
    try { session?.close() } catch (_: Exception) {}
    try { device?.close() } catch (_: Exception) {}
    session = null; device = null
    try { surface?.release() } catch (_: Exception) {}
    surface = null
    thread?.quitSafely(); thread = null; handler = null
  }
}
