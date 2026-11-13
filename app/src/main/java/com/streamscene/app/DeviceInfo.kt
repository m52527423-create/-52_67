package com.streamscene.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Батарея, нагрев и тест скорости интернета. */
object DeviceInfo {
  fun battery(ctx: Context): Pair<Int, Boolean> {
    val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    return (if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1) to charging
  }

  fun batteryTemp(ctx: Context): Float {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
  }

  /** 0 — норма … 6 — критично (PowerManager.THERMAL_STATUS_*). */
  fun thermal(ctx: Context): Int = try { ctx.getSystemService(PowerManager::class.java).currentThermalStatus } catch (_: Exception) { 0 }

  fun status(ctx: Context): JSONObject {
    val (lvl, ch) = battery(ctx)
    return JSONObject().put("battery", lvl).put("charging", ch).put("temp", batteryTemp(ctx).toDouble()).put("thermal", thermal(ctx))
  }

  /** Скорость отдачи: несколько отправок случайных данных. Мбит/с или ошибка. */
  fun uploadSpeed(): Result<Double> = runCatching {
    val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val type = "application/octet-stream".toMediaType()
    // прогрев соединения
    http.newCall(Request.Builder().url("https://speed.cloudflare.com/__up").post(ByteArray(64 * 1024).toRequestBody(type)).build()).execute().close()
    val results = ArrayList<Double>()
    var size = 1_000_000
    val deadline = System.currentTimeMillis() + 12_000
    while (results.size < 4 && System.currentTimeMillis() < deadline) {
      val data = Random.nextBytes(size)
      val t0 = System.nanoTime()
      http.newCall(Request.Builder().url("https://speed.cloudflare.com/__up").post(data.toRequestBody(type)).build()).execute().use {
        if (!it.isSuccessful) error("сервер проверки ответил ${it.code}")
      }
      val sec = (System.nanoTime() - t0) / 1e9
      results += size * 8 / sec / 1e6
      if (sec < 1.5 && size < 16_000_000) size *= 2
    }
    if (results.isEmpty()) error("нет результата")
    results.sorted()[results.size / 2]
  }
}
