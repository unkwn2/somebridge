package com.unkwn2.yandexhud.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.i("BOOT", "boot completed — auto-granting permissions")
        Thread {
            for (attempt in 1..5) {
                try { Thread.sleep(attempt * 2000L) } catch (_: InterruptedException) { break }
                val ok = LocalAdb.init(ctx)
                if (!ok) { Logger.w("BOOT", "ADB init attempt $attempt failed"); continue }
                val results = LocalAdb.grantAll()
                val allOk = results.all { it.success }
                if (allOk) {
                    Logger.i("BOOT", "auto-grant success on attempt $attempt")
                    LocalAdb.disconnect()
                    break
                } else {
                    Logger.w("BOOT", "auto-grant attempt $attempt partial fail, retrying...")
                }
                LocalAdb.disconnect()
            }
        }.apply { isDaemon = true }.start()
    }
}
