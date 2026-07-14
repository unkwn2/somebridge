package com.unkwn2.yandexhud.ui

import com.unkwn2.yandexhud.R
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.unkwn2.yandexhud.bridge.HudForegroundService
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.notif.YandexA11yService
import com.unkwn2.yandexhud.notif.YandexNaviNotificationListener
import com.unkwn2.yandexhud.util.LocalAdb
import com.unkwn2.yandexhud.util.Logger
import android.service.notification.NotificationListenerService

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "UI"
        const val APP_PASSWORD = "сделано @rbgboost"
    }
    private lateinit var statusBar: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnYandex: Button
    private lateinit var btnMockGps: Button
    private lateinit var btnSniffer: Button
    private lateinit var btnA11y: Button
    private lateinit var btnGrant: Button
    private lateinit var btnRvDump: Button
    private lateinit var btnSpeedSign: Button
    private lateinit var btnLogs: Button
    private lateinit var hiddenPanel: LinearLayout
    private var menuVisible = false
    private var yandexOn = false
    private var mockOn = false
    private var snifferOn = false
    private var statusRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)

        checkPasswordAndInit()
    }

    private fun checkPasswordAndInit() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("YandexHUD")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == APP_PASSWORD) {
                    initUI()
                } else {
                    toast("Неверный пароль")
                    checkPasswordAndInit()
                }
            }
            .show()
    }

    private fun initUI() {
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        btnYandex = findViewById(R.id.btnYandex)
        btnMockGps = findViewById(R.id.btnMockGps)
        btnSniffer = findViewById(R.id.btnSniffer)
        btnA11y = findViewById(R.id.btnA11y)
        btnGrant = findViewById(R.id.btnGrant)
        btnRvDump = findViewById(R.id.btnRvDump)
        btnSpeedSign = findViewById(R.id.btnSpeedSign)
        btnLogs = findViewById(R.id.btnLogs)
        hiddenPanel = findViewById(R.id.hiddenPanel)

        btnYandex.setOnClickListener { toggleYandex() }
        btnMockGps.setOnClickListener { toggleMockGps() }
        btnSniffer.setOnClickListener { toggleSniffer() }
        btnA11y.setOnClickListener { enableA11y() }
        btnGrant.setOnClickListener { grantPermissions() }
        btnRvDump.setOnClickListener { cycleRvDump() }
        btnSpeedSign.setOnClickListener { toggleSpeedSign() }
        btnLogs.setOnClickListener { toggleLogs() }
        findViewById<Button>(R.id.btnSaveLog).setOnClickListener { saveLog() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                copyAdbCmd("adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")
                toast("Copied ADB command to clipboard")
            }
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { toggleMenu() }
        findViewById<Button>(R.id.btnDonation).setOnClickListener { showDonationDialog() }

        // Initial button states
        btnSpeedSign.text = if (HudForegroundService.speedSignEnabled) "ЗНАК СК: ВКЛ" else "ЗНАК СК: ВЫКЛ"
        btnLogs.text = if (Logger.enabled) "LOGS: ON" else "LOGS: OFF"

        Logger.observe { line ->
            runOnUiThread {
                logText.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        HudState.observe { updateStatusBar() }
        startStatusRefresh()
        ensurePermissions()
    }

    private fun toggleMenu() {
        menuVisible = !menuVisible
        hiddenPanel.visibility = if (menuVisible) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<Button>(R.id.btnMenu).text = if (menuVisible) "HIDE" else "MENU"
    }

    private fun showDonationDialog() {
        val ru = """<b>РУССКИЙ</b><br/>
Bybit Pay: <a href="https://i.bybit.com/1ZabXMjn">https://i.bybit.com/1ZabXMjn</a><br/>
Сбербанк: <a href="https://messenger.online.sberbank.ru/sl/8mN4lmACrTZ8F568r">https://messenger.online.sberbank.ru/sl/8mN4lmACrTZ8F568r</a><br/>
USDT TRC20: TYcEkN1x2UU6BUssBxwLBAuKsbJHy3SUtR<br/><br/>
<b>ENGLISH</b><br/>
Bybit Pay: <a href="https://i.bybit.com/1ZabXMjn">https://i.bybit.com/1ZabXMjn</a><br/>
Sberbank: <a href="https://messenger.online.sberbank.ru/sl/8mN4lmACrTZ8F568r">https://messenger.online.sberbank.ru/sl/8mN4lmACrTZ8F568r</a><br/>
USDT TRC20: TYcEkN1x2UU6BUssBxwLBAuKsbJHy3SUtR"""
        val tv = TextView(this).apply {
            setTextColor(0xFFC9D1D9.toInt())
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(24, 24, 24, 24)
            textSize = 13f
            text = Html.fromHtml(ru, Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val donationPlain = """Bybit Pay: https://i.bybit.com/1ZabXMjn
Сбербанк: https://messenger.online.sberbank.ru/sl/8mN4lmACrTZ8F568r
USDT TRC20: TYcEkN1x2UU6BUssBxwLBAuKsbJHy3SUtR"""
        AlertDialog.Builder(this)
            .setTitle("Помощь проекту / Support")
            .setView(tv)
            .setPositiveButton("Копировать / Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("donation", donationPlain))
                toast("Реквизиты скопированы / Copied")
            }
            .setNegativeButton("Закрыть / Close", null)
            .show()
    }

    override fun onDestroy() {
        statusRefreshThread?.interrupt()
        if (mockOn) MockGpsService.stop()
        if (snifferOn) HudForegroundService.bridge?.snifferStop()
        super.onDestroy()
    }

    private fun toggleYandex() {
        if (!yandexOn) {
            if (!isNotifAccessGranted()) {
                Thread {
                    val ok = LocalAdb.ensurePermissions(applicationContext, force = true)
                    runOnUiThread {
                        if (ok && isNotifAccessGranted()) {
                            rebindNotifListener()
                            startYandex()
                        } else {
                            toast("Нужен доступ к уведомлениям — команда скопирована")
                            copyAdbCmd("adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")
                            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Throwable) {}
                        }
                    }
                }.apply { isDaemon = true; name = "Yandex-grant" }.start()
                return
            }
            rebindNotifListener()
            startYandex()
        } else {
            Logger.i("!YNDX", "=== YANDEX NAVI OFF ===")
            HudForegroundService.stop(this)
            yandexOn = false
            runOnUiThread {
                btnYandex.text = "YANDEX NAVI"
                updateStatusBar()
            }
        }
    }

    private fun startYandex() {
        Logger.i("!YNDX", "=== YANDEX NAVI ON ===")
        HudForegroundService.start(this)
        yandexOn = true
        runOnUiThread {
            btnYandex.text = "STOP YANDEX"
            updateStatusBar()
        }
    }

    private fun toggleMockGps() {
        if (!mockOn) {
            toast("Mock GPS: Beijing 39.90, 116.41")
            copyAdbCmd("adb shell appops set com.unkwn2.yandexhud android:mock_location allow")
            MockGpsService.start(this)
            mockOn = true
            btnMockGps.text = "STOP"
        } else {
            MockGpsService.stop()
            mockOn = false
            btnMockGps.text = "MOCK"
        }
    }

    private fun toggleSniffer() {
        val b = HudForegroundService.bridge
        if (!snifferOn) {
            HudForegroundService.loopRunner?.stop()
            b?.snifferStart()
            snifferOn = true
            btnSniffer.text = "STOP SNIFF"
            toast("Sniffer ON — LOOP stopped, listening for native Amap frames")
            Logger.i("UI", "sniffer on, loop stopped")
        } else {
            b?.snifferStop()
            HudForegroundService.loopRunner?.start()
            snifferOn = false
            btnSniffer.text = "SNIFFER"
            toast("Sniffer OFF — LOOP resumed")
            Logger.i("UI", "sniffer off, loop resumed")
        }
    }

    private fun enableA11y() {
        copyAdbCmd("adb shell settings put secure enabled_accessibility_services com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable YandexHUD in Accessibility. ADB cmd copied")
        } catch (_: Throwable) {
            toast("ADB command copied")
        }
    }

    private fun toggleSpeedSign() {
        val on = !HudForegroundService.speedSignEnabled
        HudForegroundService.setSpeedSign(this, on)
        btnSpeedSign.text = if (on) "ЗНАК СК: ВКЛ" else "ЗНАК СК: ВЫКЛ"
        toast("Знак скорости в f7: ${if (on) "ВКЛ" else "ВЫКЛ"}")
    }

    private fun toggleLogs() {
        val on = !Logger.enabled
        Logger.setEnabled(applicationContext, on)
        btnLogs.text = if (on) "LOGS: ON" else "LOGS: OFF"
        if (!on) logText.text = ""
        toast("Логи: ${if (on) "ВКЛ" else "ВЫКЛ"}")
    }

    private fun cycleRvDump() {
        HudForegroundService.probeRv = !HudForegroundService.probeRv
        val label = if (HudForegroundService.probeRv) "RV: ON" else "RV: OFF"
        runOnUiThread { btnRvDump.text = label }
        Logger.i("TEST", "probeRv=${HudForegroundService.probeRv}")
        toast("RV dump: ${if (HudForegroundService.probeRv) "ON" else "OFF"}")
    }

    private fun saveLog() {
        Thread {
            Logger.i(TAG, "=== SAVE LOG: ensureConnected ===")
            runOnUiThread { toast("Saving logs via ADB...") }
            val ok = LocalAdb.ensureConnected(applicationContext)
            if (!ok) {
                runOnUiThread { toast("ADB unavailable") }
                return@Thread
            }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val name = "/sdcard/hud_$ts.log"
            val r = LocalAdb.exec("logcat -d -s YandexHUD YA11Y YandexNotif LOOP FGS SIPBR UI TEST !YNDX > $name")
            if (r.success) {
                Logger.i(TAG, "SAVE LOG: written to $name (${r.output.take(100)})")
                runOnUiThread { toast("Log saved: $name") }
            } else {
                Logger.e(TAG, "SAVE LOG failed: ${r.error}")
                runOnUiThread { toast("Log failed: ${r.error}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun grantPermissions() = Thread {
        Logger.i(TAG, "=== GRANT: ensurePermissions(force=true) ===")
        runOnUiThread { toast("Выдача прав...") }
        val ok = LocalAdb.ensurePermissions(applicationContext, force = true)
        if (ok && isNotifAccessGranted()) {
            rebindNotifListener()
        }
        runOnUiThread { toast(if (ok) "Все права выданы ✓" else "ADB недоступен") }
    }.apply { isDaemon = true; name = "GRANT" }.start()

    private fun ensurePermissions() {
        Thread {
            val ok = LocalAdb.ensurePermissions(applicationContext)
            if (ok && isNotifAccessGranted()) {
                rebindNotifListener()
            }
        }.apply { isDaemon = true; name = "ensurePerms" }.start()
    }

    private fun updateStatusBar() {
        val s = HudState.snapshot()
        val maneuverStr = when (s.maneuver) {
            0 -> "NONE"; 2 -> "LEFT"; 3 -> "RIGHT"; 4 -> "HARD_L"; 5 -> "HARD_R"
            6 -> "SLIGHT_L"; 7 -> "SLIGHT_R"; 9 -> "STRAIGHT"
            10 -> "UTURN_L"; 11 -> "UTURN_R"; 12 -> "ARRIVE"
            else -> "${s.maneuver}"
        }
        val yStatus = if (yandexOn) "ON" else "OFF"
        val fgsReady = HudForegroundService.isReady
        val navStatus = if (s.active) "$maneuverStr ${s.distanceMeters}m" else "idle"
        val a11yStatus = if (isA11yEnabled()) "ON" else "OFF"

        runOnUiThread {
            statusBar.text = "Y:$yStatus FGS:$fgsReady | $navStatus | NEW | A11y:$a11yStatus"
        }
    }

    private fun isNotifAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun rebindNotifListener() {
        if (!isNotifAccessGranted()) {
            Logger.i(TAG, "skip rebind — notif permission not granted")
            return
        }
        try {
            val cn = android.content.ComponentName(this, YandexNaviNotificationListener::class.java)
            NotificationListenerService.requestRebind(cn)
            Logger.i(TAG, "requestRebind called for $cn")
        } catch (t: Throwable) {
            Logger.w(TAG, "requestRebind failed: ${t.message}")
        }
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains("com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService")
    }

    private fun copyAdbCmd(cmd: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("adb", cmd))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startStatusRefresh() {
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                updateStatusBar()
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        t.isDaemon = true; t.start(); statusRefreshThread = t
    }
}
