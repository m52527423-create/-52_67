package com.streamscene.app

import android.content.Context
import android.content.SharedPreferences
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Вход через Twitch (Device Code Flow — как в приложениях для ТВ и стриминга)
 * и вызовы Twitch API: ключ трансляции, название, категория, зрители.
 */
object TwitchClient {
  const val SCOPES = "channel:read:stream_key channel:manage:broadcast"

  class Resp(val code: Int, val body: String)

  private lateinit var prefs: SharedPreferences
  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
  private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

  @Volatile private var loginCancelled = false

  fun init(ctx: Context) {
    if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("twitch", Context.MODE_PRIVATE)
  }

  var clientId: String
    get() = prefs.getString("clientId", "") ?: ""
    set(v) = prefs.edit().putString("clientId", v.trim()).apply()

  private val access get() = prefs.getString("access", "") ?: ""
  private val refreshToken get() = prefs.getString("refresh", "") ?: ""
  val userId get() = prefs.getString("userId", "") ?: ""
  val loggedIn get() = access.isNotEmpty() && userId.isNotEmpty()

  /** Client ID, встроенный в приложение (res/values/twitch.xml), иначе введённый вручную. */
  fun builtInClientId(ctx: Context): String = ctx.getString(R.string.twitch_client_id).trim()
  fun effectiveClientId(ctx: Context): String = builtInClientId(ctx).ifEmpty { clientId }

  fun info(ctx: Context): JSONObject = JSONObject()
    .put("clientId", clientId)
    .put("builtIn", builtInClientId(ctx).isNotEmpty())
    .put("loggedIn", loggedIn)
    .put("userId", userId)
    .put("login", prefs.getString("login", ""))
    .put("displayName", prefs.getString("displayName", ""))
    .put("avatar", prefs.getString("avatar", ""))

  fun logout() {
    prefs.edit().remove("access").remove("refresh").remove("userId").remove("login")
      .remove("displayName").remove("avatar").apply()
  }

  private fun exec(req: Request): Resp = try {
    http.newCall(req).execute().use { r -> Resp(r.code, r.body?.string() ?: "") }
  } catch (e: Exception) {
    Resp(0, JSONObject().put("message", "Нет связи с Twitch: ${e.message}").toString())
  }

  private fun msgOf(body: String): String = try { JSONObject(body).optString("message", body) } catch (_: Exception) { body }

  /** Шаг 1: получить код, открыть Twitch, ждать подтверждения. Всё в фоне. */
  fun startDeviceLogin(cid: String, onCode: (userCode: String, uri: String) -> Unit, onDone: (ok: Boolean, msg: String) -> Unit) {
    clientId = cid
    loginCancelled = false
    thread(name = "twitch-login") {
      val r = exec(
        Request.Builder().url("https://id.twitch.tv/oauth2/device")
          .post(FormBody.Builder().add("client_id", cid).add("scopes", SCOPES).build()).build()
      )
      if (r.code != 200) {
        onDone(false, if (r.code == 400) "Twitch не принял Client ID — проверь его и что тип приложения «Public»" else msgOf(r.body))
        return@thread
      }
      val o = JSONObject(r.body)
      val deviceCode = o.getString("device_code")
      var interval = o.optInt("interval", 5)
      val deadline = System.currentTimeMillis() + o.optInt("expires_in", 1800) * 1000L
      onCode(o.optString("user_code"), o.optString("verification_uri"))
      while (System.currentTimeMillis() < deadline && !loginCancelled) {
        Thread.sleep(interval * 1000L)
        if (loginCancelled) break
        val t = exec(
          Request.Builder().url("https://id.twitch.tv/oauth2/token").post(
            FormBody.Builder().add("client_id", cid).add("scopes", SCOPES).add("device_code", deviceCode)
              .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
          ).build()
        )
        if (t.code == 200) {
          val tok = JSONObject(t.body)
          prefs.edit().putString("access", tok.getString("access_token"))
            .putString("refresh", tok.optString("refresh_token")).apply()
          if (fetchUser()) onDone(true, "") else onDone(false, "Не удалось получить данные канала")
          return@thread
        }
        when (val m = msgOf(t.body)) {
          "authorization_pending" -> {}
          "slow_down" -> interval += 5
          else -> { onDone(false, if (m == "access_denied") "Вход отменён в Twitch" else m); return@thread }
        }
      }
      onDone(false, if (loginCancelled) "Вход отменён" else "Время на вход истекло, попробуй ещё раз")
    }
  }

  fun cancelLogin() { loginCancelled = true }

  /** Вход через страницу Twitch: проверяем токен и сохраняем канал. Возвращает текст ошибки или null. */
  fun completeImplicitLogin(cid: String, token: String): String? {
    val v = exec(Request.Builder().url("https://id.twitch.tv/oauth2/validate").header("Authorization", "OAuth $token").build())
    if (v.code != 200) return "Twitch не подтвердил вход: ${msgOf(v.body)}"
    clientId = cid
    prefs.edit().putString("access", token).remove("refresh").apply()
    return if (fetchUser()) null else "Не удалось получить данные канала"
  }

  private fun fetchUser(): Boolean {
    val r = rawApi("GET", "users", "", null)
    if (r.code != 200) return false
    val u = JSONObject(r.body).getJSONArray("data").optJSONObject(0) ?: return false
    prefs.edit()
      .putString("userId", u.getString("id"))
      .putString("login", u.optString("login"))
      .putString("displayName", u.optString("display_name"))
      .putString("avatar", u.optString("profile_image_url"))
      .apply()
    return true
  }

  @Synchronized
  private fun refresh(): Boolean {
    if (refreshToken.isEmpty() || clientId.isEmpty()) return false
    val r = exec(
      Request.Builder().url("https://id.twitch.tv/oauth2/token").post(
        FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken)
          .add("client_id", clientId).build()
      ).build()
    )
    if (r.code != 200) return false
    val tok = JSONObject(r.body)
    prefs.edit().putString("access", tok.getString("access_token"))
      .putString("refresh", tok.optString("refresh_token", refreshToken)).apply()
    return true
  }

  private fun rawApi(method: String, path: String, query: String, json: String?): Resp {
    val url = "https://api.twitch.tv/helix/$path" + if (query.isNotEmpty()) "?$query" else ""
    val body = if (method == "GET" || method == "DELETE") null else (json ?: "{}").toRequestBody(JSON_TYPE)
    return exec(
      Request.Builder().url(url)
        .header("Authorization", "Bearer $access")
        .header("Client-Id", clientId)
        .method(method, body).build()
    )
  }

  /** Запрос к Twitch API; при истёкшем токене сам обновляет его и повторяет. */
  fun api(method: String, path: String, query: String = "", json: String? = null): Resp {
    if (!loggedIn) return Resp(401, JSONObject().put("message", "Не выполнен вход в Twitch").toString())
    var r = rawApi(method, path, query, json)
    if (r.code == 401 && refresh()) r = rawApi(method, path, query, json)
    if (r.code == 401) {
      logout()
      r = Resp(401, JSONObject().put("message", "Вход в Twitch истёк — войди заново").toString())
    }
    return r
  }

  /** Число зрителей, или null если Twitch пока не видит эфир. */
  fun viewers(): Int? {
    val r = api("GET", "streams", "user_id=$userId")
    if (r.code != 200) return null
    return try { JSONObject(r.body).getJSONArray("data").optJSONObject(0)?.optInt("viewer_count") } catch (_: Exception) { null }
  }
}
