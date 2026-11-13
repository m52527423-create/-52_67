package com.streamscene.app

import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Положение экрана игры в кадре (доли 0..1 от левого верхнего угла) + обрезка краёв (доли). */
data class ScreenRect(
  val x: Float, val y: Float, val w: Float, val h: Float, val flip: Boolean,
  val cl: Float = 0f, val ct: Float = 0f, val cr: Float = 0f, val cb: Float = 0f
)

/** Своё оформление алерта: картинка или GIF и звук. */
class AlertMedia(val img: ByteArray?, val isGif: Boolean, val sound: ByteArray?)

/** Живой слой, который рисуется на телефоне во время эфира (камера, чат, алерты, таймер…). */
class Widget(
  val type: String,          // camera | chat | alerts | timer | clock | viewers | ticker | gif
  val x: Float, val y: Float, val w: Float, val h: Float,
  val opacity: Float,
  val radius: Float,         // 0..50 (% от меньшей стороны)
  val font: String,
  val color: Int,
  val bg: Int?,
  val stroke: Float,         // 0..0.3 от размера шрифта
  val strokeColor: Int,
  val text: String,
  val shape: String,         // camera: rect | circle
  val facing: String,        // camera: front | back
  val border: Int?,
  val rot: Int,              // camera: доп. поворот 0/90/180/270
  val mirror: Boolean,
  val chatSize: String,      // s | m | l
  val bgAlpha: Float,
  val alertKinds: Set<String>,
  val alertSound: Boolean,
  val alertSeconds: Int,
  val speed: Float,          // бегущая строка
  val gif: ByteArray?,
  val media: Map<String, AlertMedia> = emptyMap(),
  val tts: Boolean = false,
  val ttsMin: Double = 0.0,
  val url: String = "",
  val pageWidth: Int = 800,
  val key: Boolean = true
)

/** Готовая сцена: экран игры + картинка всех обычных слоёв (с «окном» под экран) + живые слои. */
class SceneOut(val name: String, val screen: ScreenRect?, val overlayPng: ByteArray, val widgets: List<Widget>)

class StreamConfig(
  val url: String,
  val width: Int,
  val height: Int,
  val fps: Int,
  val bitrate: Int,        // бит/с, максимум
  val audio: String,       // mix | game | mic
  val gameLandscape: Boolean,
  val bubble: Boolean,
  val testMode: Boolean,
  val autoBitrate: Boolean,
  var gameVolume: Float,
  var micVolume: Float,
  val noiseSuppress: Boolean,
  val hideOnLock: Boolean,
  val hideInApp: Boolean,
  val chatChannel: String,
  val privacyPng: ByteArray?,
  val delaySec: Int,
  var scenes: List<SceneOut>,
  var current: Int
)

/** Общее состояние между экраном приложения и сервисом стрима. */
object StreamState {
  @Volatile var config: StreamConfig? = null
  @Volatile var listener: ((JSONObject) -> Unit)? = null
  @Volatile var state: String = "stopped"
  @Volatile var message: String = ""

  /** Отправить событие в редактор, не меняя статус эфира. */
  fun send(o: JSONObject) {
    listener?.invoke(o)
  }

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
      bubble = o.optBoolean("bubble", true),
      testMode = o.optBoolean("testMode", false),
      autoBitrate = o.optBoolean("autoBitrate", true),
      gameVolume = o.optDouble("gameVolume", 1.0).toFloat(),
      micVolume = o.optDouble("micVolume", 1.0).toFloat(),
      noiseSuppress = o.optBoolean("noise", true),
      hideOnLock = o.optBoolean("hideOnLock", true),
      hideInApp = o.optBoolean("hideInApp", true),
      chatChannel = o.optString("chatChannel", ""),
      delaySec = o.optInt("delay", 0).coerceIn(0, 300),
      privacyPng = o.optString("privacy", "").takeIf { it.isNotEmpty() }?.let { decodeData(it) },
      scenes = parseScenes(o.getJSONArray("scenes")),
      current = o.optInt("current", 0)
    )
  }

  fun decodeData(s: String): ByteArray? = try { Base64.decode(s.substringAfter(","), Base64.DEFAULT) } catch (_: Exception) { null }

  private fun media(o: JSONObject?): Map<String, AlertMedia> {
    if (o == null) return emptyMap()
    val m = HashMap<String, AlertMedia>()
    o.keys().forEach { k ->
      val e = o.optJSONObject(k) ?: return@forEach
      val img = e.optString("img", "").takeIf { it.isNotEmpty() }
      val snd = e.optString("snd", "").takeIf { it.isNotEmpty() }
      if (img != null || snd != null)
        m[k] = AlertMedia(img?.let { decodeData(it) }, img?.startsWith("data:image/gif") == true, snd?.let { decodeData(it) })
    }
    return m
  }

  private fun color(s: String?, def: Int): Int = try {
    if (s.isNullOrEmpty() || s == "null") def else Color.parseColor(s)
  } catch (_: Exception) { def }

  private fun f(o: JSONObject, k: String, d: Double = 0.0) = o.optDouble(k, d).toFloat()

  fun parseScenes(arr: JSONArray): List<SceneOut> = (0 until arr.length()).map { i ->
    val s = arr.getJSONObject(i)
    val r = s.optJSONObject("screen")
    val ws = s.optJSONArray("widgets") ?: JSONArray()
    SceneOut(
      name = s.optString("name", "Сцена ${i + 1}"),
      screen = r?.let {
        ScreenRect(
          f(it, "x"), f(it, "y"), f(it, "w", 1.0), f(it, "h", 1.0), it.optBoolean("flip", false),
          f(it, "cl"), f(it, "ct"), f(it, "cr"), f(it, "cb")
        )
      },
      overlayPng = Base64.decode(s.getString("overlay").substringAfter(","), Base64.DEFAULT),
      widgets = (0 until ws.length()).map { j ->
        val w = ws.getJSONObject(j)
        val kinds = w.optJSONArray("kinds")
        Widget(
          type = w.optString("type"),
          x = f(w, "x"), y = f(w, "y"), w = f(w, "w", .2), h = f(w, "h", .1),
          opacity = f(w, "opacity", 1.0),
          radius = f(w, "radius"),
          font = w.optString("font", "system"),
          color = color(w.optString("color"), Color.WHITE),
          bg = if (w.isNull("bg") || w.optString("bg").isEmpty()) null else color(w.optString("bg"), Color.BLACK),
          stroke = f(w, "stroke"),
          strokeColor = color(w.optString("strokeColor"), Color.BLACK),
          text = w.optString("text", ""),
          shape = w.optString("shape", "rect"),
          facing = w.optString("facing", "front"),
          border = if (w.isNull("border") || w.optString("border").isEmpty()) null else color(w.optString("border"), Color.WHITE),
          rot = w.optInt("rot", 0),
          mirror = w.optBoolean("mirror", true),
          chatSize = w.optString("chatSize", "m"),
          bgAlpha = f(w, "bgAlpha", .55),
          alertKinds = if (kinds == null) setOf("follow", "sub", "gift", "cheer", "raid", "donate")
                       else (0 until kinds.length()).map { kinds.getString(it) }.toSet(),
          alertSound = w.optBoolean("sound", true),
          alertSeconds = w.optInt("seconds", 6),
          speed = f(w, "speed", 1.0),
          gif = w.optString("gif", "").takeIf { it.isNotEmpty() }?.let { Base64.decode(it.substringAfter(","), Base64.DEFAULT) },
          media = media(w.optJSONObject("media")),
          tts = w.optBoolean("tts", false),
          ttsMin = w.optDouble("ttsMin", 0.0),
          url = w.optString("url", ""),
          pageWidth = w.optInt("pageWidth", 800),
          key = w.optBoolean("key", true)
        )
      }
    )
  }
}
