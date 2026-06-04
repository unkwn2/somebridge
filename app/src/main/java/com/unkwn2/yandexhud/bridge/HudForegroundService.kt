package com.unkwn2.yandexhud.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.unkwn2.yandexhud.R
import com.unkwn2.yandexhud.util.Logger

class HudForegroundService : Service() {

    companion object {
        private const val TAG = "FGS"
        private const val CH_ID = "yandexhud_fg"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val intent = Intent(ctx, HudForegroundService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HudForegroundService::class.java))
        }
    }

    private var bridge: SomeIpBridge? = null
    private var loopRunner: LoopRunner? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH_ID, getString(R.string.hud_fg_channel), NotificationManager.IMPORTANCE_LOW)
        ch.description = getString(R.string.hud_fg_notif_text)
        nm.createNotificationChannel(ch)

        val notif = Notification.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.hud_fg_notif_title))
            .setContentText(getString(R.string.hud_fg_notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        bridge = SomeIpBridge(this)
        bridge?.bind { ok ->
            if (ok) {
                val startRc = bridge?.startService(SomeIpBridge.TOPIC_NAVI) ?: -1
                Logger.i(TAG, "startService rc=$startRc")
                loopRunner = LoopRunner(bridge!!)
                loopRunner?.start()
            } else {
                Logger.e(TAG, "bind failed")
            }
        }
        Logger.i(TAG, "created")
    }

    override fun onDestroy() {
        loopRunner?.stop()
        loopRunner = null
        bridge?.stopService(SomeIpBridge.TOPIC_NAVI)
        bridge?.unbind()
        bridge = null
        super.onDestroy()
        Logger.i(TAG, "destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
