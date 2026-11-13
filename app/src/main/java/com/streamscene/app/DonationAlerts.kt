package com.streamscene.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

/** Вход в DonationAlerts и запросы к их API. */
object DaClient {
  const val SCOPES = "oauth-user-show oauth-donation-subscribe"
  private const val API = "https://www.donationalerts.com/api/v1"
  private lateinit var prefs: SharedPreferences
  val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

  fun init(ctx: Context) {
    if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("da", Context.MODE_PRIVATE)
  }

  fun builtInClientId(ctx: Context) = ctx.getString(R.string.da_client_id).trim()
  var clientId: String
    get() = prefs.getString("clientId", "") ?: ""
    set(v) = prefs.edit().putString("clientId", v.trim()).apply()
  fun effectiveClientId(ctx: Context) = builtInClientId(ctx).ifEmpty { clientId }

  val token get() = prefs.getString("token", "") ?: ""
  val loggedIn get() = token.isNotEmpty()

  fun info(ctx: Context): JSONObject = JSONObject()
    .put("loggedIn", loggedIn)
    .put("builtIn", builtInClientId(ctx).isNotEmpty())
    .put("clientId", clientId)
    .put("name", prefs.getString("name", ""))
    .put("avatar", prefs.getString("avatar", ""))

  fun logout() = prefs.edit().remove("token").remove("name").remove("avatar").remove("userId").apply()

  /** Данные пользователя: id, имя, socket_connection_token. null — ошибка. */
  fun user(): JSONObject? = try {
    http.newCall(Request.Builder().url("$API/user/oauth").header("Authorization", "Bearer $token").build()).execute().use { r ->
      if (r.code == 401) { logout(); null }
      else if (!r.isSuccessful) null
      else JSONObject(r.body?.string() ?: "{}").optJSONObject("data")
    }
  } catch (e: Exception) { Log.w("StreamScene", "da user", e); null }

  /** После страницы входа: сохраняем токен и профиль. Возвращает текст ошибки или null. */
  fun completeLogin(cid: String, tok: String): String? {
    clientId = cid
    prefs.edit().putString("token", tok).apply()
    val u = user() ?: return "DonationAlerts не подтвердил вход"
    prefs.edit().putString("name", u.optString("name")).putString("avatar", u.optString("avatar"))
      .putString("userId", u.optString("id")).apply()
    return null
  }

  fun subscribeChannel(userId: String, client: String): String? = try {
    val body = JSONObject().put("channels", JSONArray().put("\$alerts:donation_$userId")).put("client", client)
    http.newCall(
      Request.Builder().url("$API/centrifuge/subscribe").header("Authorization", "Bearer $token")
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    ).execute().use { r ->
      if (!r.isSuccessful) null
      else JSONObject(r.body?.string() ?: "{}").optJSONArray("channels")?.optJSONObject(0)?.optString("token")
    }
  } catch (e: Exception) { Log.w("StreamScene", "da subscribe", e); null }

  private val money = DecimalFormat("#,##0.##")
  fun formatAmount(amount: Double, currency: String): String {
    val sym = when (currency.uppercase()) { "RUB" -> "₽"; "USD" -> "$"; "EUR" -> "€"; "UAH" -> "₴"; "KZT" -> "₸"; "BYN" -> "Br"; "BRL" -> "R$"; "TRY" -> "₺"; else -> currency }
    return if (sym == "$" || sym == "€") "$sym${money.format(amount)}" else "${money.format(amount)} $sym"
  }
}

/** Донаты DonationAlerts в реальном времени (Centrifugo). */
class DaAlertsClient(private val onAlert: (AlertEvent) -> Unit, private val onStatus: (String) -> Unit) {
  private val main = Handler(Looper.getMainLooper())
  private val wsHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
  private var ws: WebSocket? = null
  @Volatile private var closed = false
  private var retry = 0
  private var msgId = 10
  private val seen = LinkedHashSet<String>()
  private val ping = object : Runnable {
    override fun run() {
      try { ws?.send(JSONObject().put("method", 7).put("id", ++msgId).toString()) } catch (_: Exception) {}
      main.postDelayed(this, 25000)
    }
  }

  fun start() { thread { connect() } }

  fun stop() {
    closed = true
    main.removeCallbacksAndMessages(null)
    try { ws?.close(1000, null) } catch (_: Exception) {}
  }

  private fun connect() {
    if (closed) return
    val u = DaClient.user()
    if (u == null) { main.post { onStatus("DonationAlerts: войди заново во вкладке «Эфир»") }; if (DaClient.loggedIn) again(); return }
    val userId = u.optString("id"); val sct = u.optString("socket_connection_token")
    ws = wsHttp.newWebSocket(Request.Builder().url("wss://centrifugo.donationalerts.com/connection/websocket").build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(JSONObject().put("params", JSONObject().put("token", sct)).put("id", 1).toString())
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        // сервер может присылать несколько JSON подряд, по одному в строке
        text.split('\n').filter { it.isNotBlank() }.forEach { handle(webSocket, it, userId) }
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (webSocket == ws) again() }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (webSocket == ws) again() }
    })
  }

  private fun handle(webSocket: WebSocket, raw: String, userId: String) {
    val m = try { JSONObject(raw) } catch (_: Exception) { return }
    if (m.optInt("id") == 1) {
      val client = m.optJSONObject("result")?.optString("client") ?: return
      thread {
        val tok = DaClient.subscribeChannel(userId, client)
        if (tok == null) { main.post { onStatus("DonationAlerts: не удалось подписаться на донаты") }; return@thread }
        webSocket.send(JSONObject().put("params", JSONObject().put("channel", "\$alerts:donation_$userId").put("token", tok))
          .put("method", 1).put("id", 2).toString())
        retry = 0
        main.post { onStatus("DonationAlerts подключён"); main.removeCallbacks(ping); main.postDelayed(ping, 25000) }
      }
      return
    }
    val d = m.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("data") ?: return
    val id = d.optString("id")
    if (id.isNotEmpty() && !seen.add(id)) return
    if (seen.size > 200) seen.remove(seen.first())
    val name = d.optString("username").takeIf { it.isNotBlank() && it != "null" } ?: "Аноним"
    val amount = d.optDouble("amount", 0.0)
    val cur = d.optString("currency", "RUB")
    val msg = d.optString("message").takeIf { it != "null" }.orEmpty()
    val ev = AlertEvent("donate", name, DaClient.formatAmount(amount, cur), if (d.optString("message_type") == "audio") "" else msg)
    ev.amount = amount
    main.post { onAlert(ev) }
  }

  private fun again() {
    if (closed) return
    retry++
    main.removeCallbacks(ping)
    main.postDelayed({ thread { connect() } }, min(60000L, 3000L * retry))
  }
}
