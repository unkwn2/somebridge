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
import com.unkwn2.yandexhud.notif.YandexNaviNotificationListener
import com.unkwn2.yandexhud.util.LocalAdb
import com.unkwn2.yandexhud.util.Logger

class HudForegroundService : Service() {

    companion object {
        private const val TAG = "FGS"
        private const val CH_ID = "yandexhud_fg"
        private const val NOTIF_ID = 1
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val PREFS = "yandexhud_prefs"
        private const val KEY_SPEED_SIGN = "speedSignEnabled"

        @Volatile var instance: HudForegroundService? = null
        val bridge: SomeIpBridge? get() = instance?._bridge
        val loopRunner: LoopRunner? get() = instance?._loopRunner
        val isReady: Boolean get() { val i = instance; return i?._bridge != null && i._bound }

        // f8 PNG from RemoteViews — true = ON (по умолчанию)
        @Volatile var sendPngIcon: Boolean = true

        // RV dump in log — false = OFF (засоряет логи)
        @Volatile var probeRv: Boolean = false

        // Speed sign in f7 toggle (red circle with number instead of lane strip)
        @Volatile var speedSignEnabled: Boolean = true
        fun setSpeedSign(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEED_SIGN, on).apply()
            speedSignEnabled = on
        }
        fun getSpeedSign(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEED_SIGN, true)

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, HudForegroundService::class.java))
            } catch (_: Throwable) {
                Logger.w(TAG, "startForegroundService blocked; retry on next foreground")
            }
        }
        fun stop(ctx: Context) {
            val i = instance
            if (i != null) i.intentionalStop = true
            ctx.stopService(Intent(ctx, HudForegroundService::class.java))
        }
    }

    private var _bridge: SomeIpBridge? = null
    private var _loopRunner: LoopRunner? = null
    @Volatile private var _bound = false
    @Volatile private var intentionalStop = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        speedSignEnabled = getSpeedSign(this)
        NaviIconLoader.init(this)
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
                _loopRunner = LoopRunner(_bridge!!)
                _loopRunner?.start()
            } else {
                Logger.e(TAG, "bind failed")
            }
        }
        // Принудительный rebind NotificationListenerService (disable→enable заставляет систему пересоздать биндинг)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val cn = android.content.ComponentName(this, YandexNaviNotificationListener::class.java)
                packageManager.setComponentEnabledSetting(cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                packageManager.setComponentEnabledSetting(cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                android.service.notification.NotificationListenerService.requestRebind(cn)
                Logger.i(TAG, "listener rebind forced (disable→enable cycle)")
            } catch (t: Throwable) { Logger.w(TAG, "rebind failed: ${t.message}") }
        }, 2000L)
        Logger.i(TAG, "created — cold start (pid=${android.os.Process.myPid()}, elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}ms)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("RESTART", false) == true) {
            Logger.i(TAG, "restarted after death")
            restartAttempt = 0
            intentionalStop = false
        }
        if (intent?.getBooleanExtra("HEARTBEAT", false) == true) {
            Logger.i(TAG, "heartbeat tick — alive (pid=${android.os.Process.myPid()})")
        }
        scheduleHeartbeat()
        return START_STICKY
    }

    private var restartAttempt = 0
    private val MAX_RESTART_DELAY = 60_000L

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
        if (intentionalStop) {
            cancelHeartbeat()
            Logger.i(TAG, "destroyed — intentional stop, no restart")
        } else {
            Logger.i(TAG, "destroyed — scheduling restart in 2s")
            scheduleRestart(2000)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w(TAG, "task removed — restarting")
        scheduleRestart(2000)
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestart(delayMs: Long) {
        val actualDelay = delayMs.coerceAtMost(MAX_RESTART_DELAY)
        val attempt = restartAttempt++
        val backoff = if (attempt > 0) (actualDelay * (1L shl attempt.coerceAtMost(5))).coerceAtMost(MAX_RESTART_DELAY) else actualDelay
        Logger.i(TAG, "scheduling restart in ${backoff}ms (attempt=$attempt)")
        try {
            val intent = Intent(this, HudForegroundService::class.java).putExtra("RESTART", true)
            val pi = PendingIntent.getForegroundService(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + backoff, pi)
        } catch (t: Throwable) { Logger.w(TAG, "FGS restart blocked: ${t.message}") }
    }

    private fun scheduleHeartbeat() {
        try {
            val intent = Intent(this, HudForegroundService::class.java).putExtra("HEARTBEAT", true)
            val pi = PendingIntent.getForegroundService(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS, pi)
        } catch (t: Throwable) { Logger.w(TAG, "heartbeat schedule failed: ${t.message}") }
    }

    private fun cancelHeartbeat() {
        try {
            val intent = Intent(this, HudForegroundService::class.java).putExtra("HEARTBEAT", true)
            val pi = PendingIntent.getForegroundService(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
