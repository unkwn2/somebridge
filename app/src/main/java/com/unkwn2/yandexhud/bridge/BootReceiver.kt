package com.unkwn2.yandexhud.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unkwn2.yandexhud.util.LocalAdb
import com.unkwn2.yandexhud.util.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Logger.i(TAG, "onReceive action=$action")
        Thread {
            Logger.i(TAG, "ensurePermissions start")
            LocalAdb.ensurePermissions(ctx)
            HudForegroundService.start(ctx)
            Logger.i(TAG, "BootReceiver done")
        }.apply { isDaemon = true; name = "BootInit" }.start()
    }

    companion object {
        private const val TAG = "BOOT_RX"
    }
}
