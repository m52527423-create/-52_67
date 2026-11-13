package com.streamscene.app

import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

private val wsHttp = OkHttpClient.Builder().pingInterval(40, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

fun ruPlural(n: Int, a: String, b: String, c: String): String {
  val x = n % 100; val m = n % 10
  return if (x in 11..14) c else if (m == 1) a else if (m in 2..4) b else c
}

/** Чат Twitch (только чтение, анонимно). */
class ChatClient(private val channel: String, private val onLine: (ChatLine) -> Unit) {
  private val main = Handler(Looper.getMainLooper())
  private var ws: WebSocket? = null
  @Volatile private var closed = false
  private var retry = 0
  private val palette = intArrayOf(
    Color.parseColor("#ff7a7a"), Color.parseColor("#7ab8ff"), Color.parseColor("#7aff9e"), Color.parseColor("#ffd27a"),
    Color.parseColor("#d17aff"), Color.parseColor("#7affea"), Color.parseColor("#ffa07a"), Color.parseColor("#b5ff7a")
  )

  fun start() = connect()

  fun stop() {
    closed = true
    try { ws?.close(1000, null) } catch (_: Exception) {}
  }

  private fun connect() {
    if (closed || channel.isBlank()) return
    ws = wsHttp.newWebSocket(Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        retry = 0
        webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
        webSocket.send("PASS SCHMOOPIIE")
        webSocket.send("NICK justinfan" + (10000..99999).random())
        webSocket.send("JOIN #" + channel.lowercase().trim().removePrefix("#"))
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        text.split("\r\n").forEach { if (it.isNotEmpty()) handle(webSocket, it) }
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = again()
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = again()
    })
  }

  private fun again() {
    if (closed) return
    retry++
    main.postDelayed({ connect() }, min(30000L, 2000L * retry))
  }

  private fun handle(ws: WebSocket, raw: String) {
    if (raw.startsWith("PING")) { ws.send("PONG :tmi.twitch.tv"); return }
    var line = raw
    val tags = HashMap<String, String>()
    if (line.startsWith("@")) {
      val sp = line.indexOf(' ')
      line.substring(1, sp).split(';').forEach { kv -> val i = kv.indexOf('='); if (i > 0) tags[kv.substring(0, i)] = kv.substring(i + 1) }
      line = line.substring(sp + 1)
    }
    if (!line.startsWith(":")) return
    val sp = line.indexOf(' ')
    val prefix = line.substring(1, sp)
    val rest = line.substring(sp + 1)
    if (!rest.startsWith("PRIVMSG ")) return
    val ti = rest.indexOf(" :")
    if (ti < 0) return
    var msg = rest.substring(ti + 2)
    if (msg.startsWith("\u0001ACTION ")) msg = msg.removePrefix("\u0001ACTION ").trimEnd('\u0001')
    val login = prefix.substringBefore('!')
    val name = tags["display-name"]?.replace("\\s", " ")?.takeIf { it.isNotEmpty() } ?: login
    val color = tags["color"]?.takeIf { it.startsWith("#") }?.let { try { Color.parseColor(it) } catch (_: Exception) { null } }
      ?: palette[(login.hashCode() and 0x7fffffff) % palette.size]
    main.post { onLine(ChatLine(name, lighten(color), msg)) }
  }

  // слишком тёмные ники плохо видно на стриме — осветляем
  private fun lighten(c: Int): Int {
    val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    if (l > 110) return c
    val k = 0.45f
    return Color.rgb((Color.red(c) + (255 - Color.red(c)) * k).toInt(), (Color.green(c) + (255 - Color.green(c)) * k).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * k).toInt())
  }
}

/** События канала (фолловы, сабы, биты, рейды) через Twitch EventSub. */
class AlertsClient(
  private val userId: String,
  private val onAlert: (AlertEvent) -> Unit,
  private val onStatus: (String) -> Unit
) {
  private val main = Handler(Looper.getMainLooper())
  private var ws: WebSocket? = null
  @Volatile private var closed = false
  private var retry = 0
  private val seen = LinkedHashSet<String>()

  fun start() = connect("wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30", false)

  fun stop() {
    closed = true
    try { ws?.close(1000, null) } catch (_: Exception) {}
  }

  private fun connect(url: String, isReconnect: Boolean) {
    if (closed) return
    val old = ws
    ws = wsHttp.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val type = m.optJSONObject("metadata")?.optString("message_type") ?: return
        val p = m.optJSONObject("payload") ?: JSONObject()
        when (type) {
          "session_welcome" -> {
            retry = 0
            if (isReconnect) { try { old?.close(1000, null) } catch (_: Exception) {} }
            else thread { subscribe(p.getJSONObject("session").getString("id")) }
          }
          "session_reconnect" -> connect(p.getJSONObject("session").getString("reconnect_url"), true)
          "notification" -> {
            val id = m.getJSONObject("metadata").optString("message_id")
            if (!seen.add(id)) return
            if (seen.size > 200) seen.remove(seen.first())
            val ev = parse(p.getJSONObject("subscription").getString("type"), p.getJSONObject("event"))
            if (ev != null) main.post { onAlert(ev) }
          }
        }
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (webSocket == ws) again() }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (webSocket == ws) again() }
    })
  }

  private fun again() {
    if (closed) return
    retry++
    main.postDelayed({ connect("wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30", false) }, min(60000L, 3000L * retry))
  }

  private fun subscribe(session: String) {
    val subs = listOf(
      Triple("channel.follow", "2", JSONObject().put("broadcaster_user_id", userId).put("moderator_user_id", userId)),
      Triple("channel.subscribe", "1", JSONObject().put("broadcaster_user_id", userId)),
      Triple("channel.subscription.message", "1", JSONObject().put("broadcaster_user_id", userId)),
      Triple("channel.subscription.gift", "1", JSONObject().put("broadcaster_user_id", userId)),
      Triple("channel.cheer", "1", JSONObject().put("broadcaster_user_id", userId)),
      Triple("channel.raid", "1", JSONObject().put("to_broadcaster_user_id", userId))
    )
    var ok = 0
    val failed = ArrayList<String>()
    for ((type, ver, cond) in subs) {
      val body = JSONObject().put("type", type).put("version", ver).put("condition", cond)
        .put("transport", JSONObject().put("method", "websocket").put("session_id", session))
      val r = TwitchClient.api("POST", "eventsub/subscriptions", "", body.toString())
      if (r.code in 200..299) ok++ else { failed += type; Log.w("StreamScene", "eventsub $type ${r.code} ${r.body}") }
    }
    main.post {
      onStatus(when {
        failed.isEmpty() -> "Алерты включены"
        ok == 0 -> "Алерты недоступны — войди в Twitch заново"
        else -> "Алерты включены частично (сабы и биты — только для компаньонов)"
      })
    }
  }

  private fun parse(type: String, e: JSONObject): AlertEvent? {
    fun name(k: String = "user_name") = if (e.optBoolean("is_anonymous")) "Аноним" else e.optString(k).ifEmpty { "Кто-то" }
    fun tier() = when (e.optString("tier")) { "2000" -> " 2 уровня"; "3000" -> " 3 уровня"; else -> "" }
    return when (type) {
      "channel.follow" -> AlertEvent("follow", name(), "новый фолловер")
      "channel.subscribe" -> if (e.optBoolean("is_gift")) null else AlertEvent("sub", name(), "подписка" + tier())
      "channel.subscription.message" -> {
        val n = e.optInt("cumulative_months", 1)
        AlertEvent("sub", name(), "подписка · $n ${ruPlural(n, "месяц", "месяца", "месяцев")}", e.optJSONObject("message")?.optString("text").orEmpty())
      }
      "channel.subscription.gift" -> { val n = e.optInt("total", 1); AlertEvent("gift", name(), "дарит $n ${ruPlural(n, "подписку", "подписки", "подписок")}") }
      "channel.cheer" -> { val n = e.optInt("bits"); AlertEvent("cheer", name(), "$n ${ruPlural(n, "бит", "бита", "битов")}", e.optString("message")) }
      "channel.raid" -> { val n = e.optInt("viewers"); AlertEvent("raid", e.optString("from_broadcaster_user_name"), "рейд · $n ${ruPlural(n, "зритель", "зрителя", "зрителей")}") }
      else -> null
    }
  }
}

/** Звук алерта. Играет как звук игры, поэтому попадает в эфир (если звук игры включён). */
object AlertSound {
  fun play(kind: String) {
    thread {
      try {
        val notes = when (kind) {
          "sub" -> floatArrayOf(523f, 659f, 784f, 1047f); "gift" -> floatArrayOf(587f, 740f, 880f, 1175f)
          "cheer" -> floatArrayOf(880f, 1175f, 1568f); "raid" -> floatArrayOf(392f, 523f, 659f, 784f, 1047f)
          else -> floatArrayOf(660f, 880f)
        }
        val rate = 44100; val step = (rate * 0.12).toInt(); val tail = (rate * 0.4).toInt()
        val pcm = ShortArray(step * notes.size + tail)
        notes.forEachIndexed { n, f ->
          for (i in 0 until tail) {
            val idx = n * step + i
            if (idx >= pcm.size) break
            val t = i.toDouble() / rate
            val env = (1 - exp(-t * 80)) * exp(-t * 7)
            val v = sin(2 * PI * f * t) * env * 0.22 * Short.MAX_VALUE
            pcm[idx] = (pcm[idx] + v.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
          }
        }
        val track = AudioTrack.Builder()
          .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
          .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep((pcm.size * 1000L / rate) + 200)
        track.release()
      } catch (e: Exception) { Log.w("StreamScene", "alert sound", e) }
    }
  }
}
