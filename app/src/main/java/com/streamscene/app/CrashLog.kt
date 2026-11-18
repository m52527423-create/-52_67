package com.streamscene.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Записывает причину падения в файл, чтобы показать её при следующем запуске. */
object CrashLog {
  @Volatile private var installed = false

  fun install(ctx: Context) {
    if (installed) return
    installed = true
    val app = ctx.applicationContext
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        val sb = StringBuilder()
        sb.append("Glowcast упал ").append(SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date()))
          .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
          .append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
          .append(" · поток ").append(t.name).append("\n\n")
        sb.append(android.util.Log.getStackTraceString(e))
        sb.append("\n--- журнал ---\n").append(recentLog(200))
        File(app.filesDir, "last_crash.txt").writeText(sb.toString())
      } catch (_: Throwable) {}
      prev?.uncaughtException(t, e)
    }
  }

  fun take(ctx: Context): String {
    val f = File(ctx.filesDir, "last_crash.txt")
    if (!f.exists()) return ""
    val s = try { f.readText() } catch (_: Exception) { "" }
    f.delete()
    return s
  }

  /** Последние строки журнала только этого приложения. */
  fun recentLog(lines: Int): String = try {
    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString(), "--pid=" + android.os.Process.myPid()))
    p.inputStream.bufferedReader().readText().takeLast(60000)
  } catch (e: Exception) { "нет доступа к журналу: ${e.message}" }
}
