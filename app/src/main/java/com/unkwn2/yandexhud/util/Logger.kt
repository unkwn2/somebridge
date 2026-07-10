package com.unkwn2.yandexhud.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object Logger {
    private const val APP_TAG = "YandexHUD"
    private const val LOG_DIR = "yandexhud/logs"
    private const val MAX_LOG_AGE_MS = 7 * 24 * 3600 * 1000L
    private const val KEY_LOGS = "logsEnabled"
    private const val PREFS = "yandexhud_prefs"
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile private var logWriter: PrintWriter? = null
    @Volatile var enabled: Boolean = false
        private set

    fun init(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOGS, false)
        try {
            val dir = File(ctx.getExternalFilesDir(null)?.parentFile, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()

            deleteOldLogs(dir)

            val logFile = File(dir, "yandexhud_${dateFmt.get().format(Date())}.log")
            logWriter = PrintWriter(FileWriter(logFile, true), true)
            Log.println(Log.INFO, APP_TAG, "[Logger] log file: ${logFile.absolutePath}")
        } catch (t: Throwable) {
            Log.println(Log.ERROR, APP_TAG, "[Logger] init failed: ${t.message}")
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOGS, on).apply()
        enabled = on
    }

    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Log.ERROR, tag, msg)

    private fun log(level: Int, tag: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.get().format(Date())}\t${levelChar(level)}\t$tag\t$msg"
        Log.println(level, APP_TAG, "[$tag] $msg")
        listeners.forEach { it(line) }
        try { logWriter?.println(line) } catch (_: Throwable) {}
    }

    fun observe(cb: (String) -> Unit) { listeners += cb }

    private fun levelChar(l: Int) = when (l) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        else -> "I"
    }

    private fun deleteOldLogs(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".log") && now - f.lastModified() > MAX_LOG_AGE_MS) {
                f.delete()
            }
        }
    }
}
