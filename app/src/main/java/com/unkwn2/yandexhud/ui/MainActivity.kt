package com.unkwn2.yandexhud.ui

import com.unkwn2.yandexhud.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.unkwn2.yandexhud.bridge.HudForegroundService
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.bridge.LoopRunner
import com.unkwn2.yandexhud.bridge.SomeIpBridge
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.notif.YandexA11yService
import com.unkwn2.yandexhud.util.Logger

class MainActivity : AppCompatActivity() {
    private lateinit var bridge: SomeIpBridge
    private lateinit var loop: LoopRunner
    private lateinit var statusBar: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnYandex: Button
    private lateinit var btnMockGps: Button
    private lateinit var btnSniffer: Button
    private lateinit var btnA11y: Button
    private lateinit var btnToggleSchema: Button

    private var yandexOn = false
    private var mockOn = false
    private var snifferOn = false
    private var useAltSchema = false
    private var statusRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        setContentView(R.layout.activity_main)

        bridge = SomeIpBridge(this)
        loop = LoopRunner(bridge)

        statusBar = findViewById(R.id.statusBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        btnYandex = findViewById(R.id.btnYandex)
        btnMockGps = findViewById(R.id.btnMockGps)
        btnSniffer = findViewById(R.id.btnSniffer)
        btnA11y = findViewById(R.id.btnA11y)
        btnToggleSchema = findViewById(R.id.btnToggleSchema)

        btnYandex.setOnClickListener { toggleYandex() }
        btnMockGps.setOnClickListener { toggleMockGps() }
        btnSniffer.setOnClickListener { toggleSniffer() }
        btnA11y.setOnClickListener { enableA11y() }
        btnToggleSchema.setOnClickListener { toggleSchema() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                val adbCmd = "adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener"
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("adb", adbCmd))
                toast("Copied ADB command to clipboard")
            }
        }
        findViewById<Button>(R.id.btnTestLeft).setOnClickListener { testManeuver(ManeuverMapper.M_LEFT, "LEFT") }
        findViewById<Button>(R.id.btnTestRight).setOnClickListener { testManeuver(ManeuverMapper.M_RIGHT, "RIGHT") }
        findViewById<Button>(R.id.btnTestStraight).setOnClickListener { testManeuver(ManeuverMapper.M_STRAIGHT, "STRAIGHT") }
        findViewById<Button>(R.id.btnDumpTree).setOnClickListener {
            YandexA11yService.dumpRequested = true
            toast("Tree dump requested — check logs")
        }

        Logger.observe { line ->
            runOnUiThread {
                logText.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        HudState.observe { updateStatusBar() }
        startStatusRefresh()
    }

    override fun onDestroy() {
        statusRefreshThread?.interrupt()
        if (yandexOn) { loop.stop(); HudForegroundService.stop(this); bridge.unbind() }
        if (mockOn) MockGpsService.stop()
        if (snifferOn) bridge.snifferStop()
        super.onDestroy()
    }

    private fun toggleYandex() {
        if (!yandexOn) {
            if (!isNotifAccessGranted()) {
                val adbCmd = "adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener"
                toast("Need notification access! ADB cmd copied")
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("adb", adbCmd))
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Throwable) {}
                return
            }
            Logger.i("!YNDX", "=== YANDEX NAVI ON ===")
            bridge.bind { ready ->
                if (!ready) { Logger.e("!YNDX", "bind failed"); return@bind }
                val rc = bridge.startService(SomeIpBridge.SERVICE_ID_NAVI)
                if (rc != 0 && rc != 13) {
                    Logger.e("!YNDX", "startService rc=$rc (0=OK, 13=already running)")
                }
                loop.useAltSchema = useAltSchema
                loop.start(1000L)
                HudForegroundService.start(this)
                yandexOn = true
                runOnUiThread { btnYandex.text = "STOP YANDEX" }
                updateStatusBar()
            }
        } else {
            Logger.i("!YNDX", "=== YANDEX NAVI OFF ===")
            loop.stop()
            HudForegroundService.stop(this)
            bridge.unbind()
            yandexOn = false
            runOnUiThread { btnYandex.text = "YANDEX NAVI" }
            updateStatusBar()
        }
    }

    private fun toggleSchema() {
        useAltSchema = !useAltSchema
        loop.useAltSchema = useAltSchema
        val label = if (useAltSchema) "SCHEMA:f5" else "SCHEMA:f28"
        btnToggleSchema.text = label
        Logger.i("UI", "schema toggled: maneuver field = ${if (useAltSchema) "5 (alt)" else "28 (v33)"}")
        toast("Maneuver field: ${if (useAltSchema) "f5 (alt)" else "f28 (v33)"}")
    }

    private fun toggleMockGps() {
        if (!mockOn) {
            MockGpsService.start(this, 55.7558, 37.6173)
            mockOn = true
            runOnUiThread { btnMockGps.text = "STOP MOCK" }
        } else {
            MockGpsService.stop()
            mockOn = false
            runOnUiThread { btnMockGps.text = "MOCK GPS" }
        }
        updateStatusBar()
    }

    private fun toggleSniffer() {
        if (!snifferOn) {
            bridge.snifferStart()
            snifferOn = true
            runOnUiThread { btnSniffer.text = "STOP SNIFF" }
        } else {
            bridge.snifferStop()
            snifferOn = false
            runOnUiThread { btnSniffer.text = "SNIFFER" }
        }
        updateStatusBar()
    }

    private fun enableA11y() {
        val a11yCmd = "adb shell settings put secure enabled_accessibility_services com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService"
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("adb", a11yCmd))
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable YandexHUD in Accessibility. ADB cmd copied")
        } catch (_: Throwable) {
            toast("ADB command copied to clipboard")
        }
    }

    private fun testManeuver(maneuver: Int, name: String) {
        if (!yandexOn) {
            toast("Start YANDEX NAVI first")
            return
        }
        Logger.i("TEST", "firing test maneuver=$name code=$maneuver schema=${if (useAltSchema) "f5" else "f28"}")
        HudState.update {
            it.copy(
                active = true,
                maneuver = maneuver,
                distanceMeters = 500,
                road = "Test Road",
                etaSeconds = 300,
                lastUpdateMs = System.currentTimeMillis()
            )
        }
        updateStatusBar()
    }

    private fun updateStatusBar() {
        val s = HudState.snapshot()
        val maneuverStr = when (s.maneuver) {
            0 -> "NONE"; 2 -> "LEFT"; 3 -> "RIGHT"; 4 -> "HARD_L"; 5 -> "HARD_R"
            6 -> "SLIGHT_L"; 7 -> "SLIGHT_R"; 9 -> "STRAIGHT"
            10 -> "UTURN_L"; 11 -> "UTURN_R"; 12 -> "ARRIVE"
            else -> "${s.maneuver}"
        }
        val yandexStatus = if (yandexOn) "ON" else "OFF"
        val loopStatus = if (loop.isRunning) "${if (s.active) s.distanceMeters else "idle"}" else "idle"
        val mockStatus = if (mockOn) "ON" else "OFF"
        val snifferStatus = if (snifferOn) "ON" else "OFF"
        val a11yStatus = if (isA11yEnabled()) "ON" else "OFF"
        val navStatus = if (s.active) "NAV:$maneuverStr ${s.distanceMeters}m" else "no nav"
        val schemaStr = if (useAltSchema) "f5" else "f28"

        runOnUiThread {
            statusBar.text = "Yandex: $yandexStatus | Loop: $loopStatus | $navStatus | Schema: $schemaStr | A11y: $a11yStatus"
        }
    }

    private fun isNotifAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains("com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexA11yService")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startStatusRefresh() {
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                updateStatusBar()
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        t.isDaemon = true
        t.start()
        statusRefreshThread = t
    }
}
