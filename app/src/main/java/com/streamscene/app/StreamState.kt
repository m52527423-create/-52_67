package com.streamscene.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Положение экрана игры в кадре, доли от 0 до 1, отсчёт от левого верхнего угла. */
data class ScreenRect(val x: Float, val y: Float, val w: Float, val h: Float, val flip: Boolean)

/** Готовая сцена: где экран игры + картинка всех слоёв поверх (с «дыркой» под экран). */
class SceneOut(val name: String, val screen: ScreenRect?, val overlayPng: ByteArray)

class StreamConfig(
  val url: String,
  val width: Int,          // итоговая ширина кадра
  val height: Int,         // итоговая высота кадра
  val fps: Int,
  val bitrate: Int,        // бит/с
  val audio: String,       // mix | game | mic
  val gameLandscape: Boolean,
  var scenes: List<SceneOut>,
  var current: Int
)

/** Общее состояние между экраном приложения и сервисом стрима. */
object StreamState {
  @Volatile var config: StreamConfig? = null
  @Volatile var listener: ((JSONObject) -> Unit)? = null
  @Volatile var state: String = "stopped"
  @Volatile var message: String = ""

  fun emit(state: String, message: String = "", extra: JSONObject.() -> Unit = {}) {
    if (state != "bitrate" && state != "scene") {
      this.state = state
      this.message = message
    }
    val o = JSONObject().put("state", state).put("msg", message)
    o.extra()
    listener?.invoke(o)
  }

  fun parseConfig(json: String): StreamConfig {
    val o = JSONObject(json)
    return StreamConfig(
      url = o.getString("url"),
      width = o.getInt("width"),
      height = o.getInt("height"),
      fps = o.optInt("fps", 30),
      bitrate = o.optInt("bitrate", 3000) * 1000,
      audio = o.optString("audio", "mix"),
      gameLandscape = o.optBoolean("gameLandscape", true),
      scenes = parseScenes(o.getJSONArray("scenes")),
      current = o.optInt("current", 0)
    )
  }

  fun parseScenes(arr: JSONArray): List<SceneOut> = (0 until arr.length()).map { i ->
    val s = arr.getJSONObject(i)
    val r = s.optJSONObject("screen")
    SceneOut(
      name = s.optString("name", "Сцена ${i + 1}"),
      screen = r?.let {
        ScreenRect(
          it.getDouble("x").toFloat(), it.getDouble("y").toFloat(),
          it.getDouble("w").toFloat(), it.getDouble("h").toFloat(),
          it.optBoolean("flip", false)
        )
      },
      overlayPng = Base64.decode(s.getString("overlay").substringAfter(","), Base64.DEFAULT)
    )
  }
}
