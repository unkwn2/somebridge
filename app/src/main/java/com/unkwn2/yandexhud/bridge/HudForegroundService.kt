package com.unkwn2.yandexhud.bridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.unkwn2.yandexhud.R
import com.unkwn2.yandexhud.util.Logger

class HudForegroundService : Service() {

    companion object {
        private const val TAG = "FGS"
        private const val CH_ID = "yandexhud_fg"
        private const val NOTIF_ID = 1
        private const val PREFS = "yandexhud_prefs"
        private const val KEY_GAODE = "useGaodeEnum"

        const val DEBUG_ARROW_SCAN = false      // перебор стрелок f27 (только для отладки)

        @Volatile var instance: HudForegroundService? = null
        val bridge: SomeIpBridge? get() = instance?._bridge
        val loopRunner: LoopRunner? get() = instance?._loopRunner
        val isReady: Boolean get() { val i = instance; return i?._bridge != null && i._bound }

        // Field scanner for small arrow (ICON_SIMPLE_NAVI) — 0 = OFF
        @Volatile var iconFieldNum: Int = 0
        val ICON_CANDIDATES = intArrayOf(27, 11, 13, 17, 18, 21, 22, 23, 24, 25)  // 27 приоритет (ICON_SIMPLE_NAVI); f12-f15 исключены как ядовитые

        // f8 PNG from RemoteViews — true = ON (по умолчанию)
        @Volatile var sendPngIcon: Boolean = true

        // nextNextManeuver field number — 0 = OFF (экспериментально)
        @Volatile var nextManeuverFieldNum: Int = 0

        // RV dump in log — false = OFF (засоряет логи)
        @Volatile var probeRv: Boolean = false

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, HudForegroundService::class.java))
            } catch (_: Throwable) {
                val pi = PendingIntent.getForegroundService(ctx, 0, Intent(ctx, HudForegroundService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pi)
            }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HudForegroundService::class.java))
        }

        fun saveSettings(ctx: Context, gaode: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_GAODE, gaode)
                .apply()
        }

        fun loadGaode(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GAODE, true)
    }

    private var _bridge: SomeIpBridge? = null
    private var _loopRunner: LoopRunner? = null
    @Volatile private var _bound = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH_ID, getString(R.string.hud_fg_channel), NotificationManager.IMPORTANCE_LOW)
        ch.description = getString(R.string.hud_fg_notif_text)
        nm.createNotificationChannel(ch)
        startForeground(NOTIF_ID, Notification.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.hud_fg_notif_title))
            .setContentText(getString(R.string.hud_fg_notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build())

        _bridge = SomeIpBridge(this)
        _bridge?.bind { ok ->
            if (ok) {
                _bound = true
                val rc = _bridge?.startService(SomeIpBridge.SERVICE_ID_NAVI) ?: -1
                Logger.i(TAG, "startService rc=$rc")
                val savedGaode = loadGaode(this)
                _loopRunner = LoopRunner(_bridge!!)
                _loopRunner?.useGaodeEnum = savedGaode
                _loopRunner?.start()
            } else {
                Logger.e(TAG, "bind failed")
            }
        }
        Logger.i(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("RESTART", false) == true) {
            Logger.i(TAG, "restarted after death")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _loopRunner?.stop()
        _loopRunner?.joinWorker(1000)
        _loopRunner = null
        _bridge?.stopService(SomeIpBridge.SERVICE_ID_NAVI)
        _bridge?.unbind()
        _bridge = null
        _bound = false
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Logger.i(TAG, "destroyed — scheduling restart in 2s")
        scheduleRestart(2000)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w(TAG, "task removed — restarting")
        scheduleRestart(2000)
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestart(delayMs: Long) {
        val intent = Intent(this, HudForegroundService::class.java).putExtra("RESTART", true)
        val pi = PendingIntent.getForegroundService(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
