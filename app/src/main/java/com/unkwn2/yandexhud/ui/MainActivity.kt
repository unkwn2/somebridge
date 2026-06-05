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
import com.unkwn2.yandexhud.bridge.SomeIpBridge
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.notif.YandexA11yService
import com.unkwn2.yandexhud.util.Logger

class MainActivity : AppCompatActivity() {
    private lateinit var statusBar: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnYandex: Button
    private lateinit var btnMockGps: Button
    private lateinit var btnSniffer: Button
    private lateinit var btnA11y: Button
    private lateinit var btnToggleSchema: Button
    private lateinit var btnToggleEnum: Button
    private lateinit var btnTestLanes: Button
    private lateinit var btnTestNavMap: Button
    private lateinit var btnTestNextNext: Button

    private var yandexOn = false
    private var mockOn = false
    private var snifferOn = false
    private var maneuverTagIdx = 0
    private var useGaodeEnum = true
    private var statusRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        maneuverTagIdx = HudForegroundService.loadTagIdx(this)
        useGaodeEnum = HudForegroundService.loadGaode(this)
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        btnYandex = findViewById(R.id.btnYandex)
        btnMockGps = findViewById(R.id.btnMockGps)
        btnSniffer = findViewById(R.id.btnSniffer)
        btnA11y = findViewById(R.id.btnA11y)
        btnToggleSchema = findViewById(R.id.btnToggleSchema)
        btnToggleEnum = findViewById(R.id.btnToggleEnum)
        btnTestLanes = findViewById(R.id.btnTestLanes)
        btnTestNavMap = findViewById(R.id.btnTestNavMap)
        btnTestNextNext = findViewById(R.id.btnTestNextNext)

        btnYandex.setOnClickListener { toggleYandex() }
        btnMockGps.setOnClickListener { toggleMockGps() }
        btnSniffer.setOnClickListener { toggleSniffer() }
        btnA11y.setOnClickListener { enableA11y() }
        btnToggleSchema.setOnClickListener { toggleSchema() }
        btnToggleEnum.setOnClickListener { toggleEnum() }
        btnTestLanes.setOnClickListener { testLanes() }
        btnTestNavMap.setOnClickListener { testNavMap() }
        btnTestNextNext.setOnClickListener { testNextNext() }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                copyAdbCmd("adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")
                toast("Copied ADB command to clipboard")
            }
        }
        findViewById<Button>(R.id.btnTestLeft).setOnClickListener { testManeuver(ManeuverMapper.M_LEFT, "LEFT") }
        findViewById<Button>(R.id.btnTestRight).setOnClickListener { testManeuver(ManeuverMapper.M_RIGHT, "RIGHT") }
        findViewById<Button>(R.id.btnTestStraight).setOnClickListener { testManeuver(ManeuverMapper.M_STRAIGHT, "STRAIGHT") }
        findViewById<Button>(R.id.btnDumpTree).setOnClickListener {
            YandexA11yService.dumpRequested = true
            toast("Tree dump requested")
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
        if (mockOn) MockGpsService.stop()
        if (snifferOn) HudForegroundService.bridge?.snifferStop()
        super.onDestroy()
    }

    private fun toggleYandex() {
        if (!yandexOn) {
            if (!isNotifAccessGranted()) {
                copyAdbCmd("adb shell cmd notification allow_listener com.unkwn2.yandexhud/com.unkwn2.yandexhud.notif.YandexNaviNotificationListener")
                toast("Need notification access! ADB cmd copied")
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Throwable) {}
                return
            }
            Logger.i("!YNDX", "=== YANDEX NAVI ON ===")
            HudForegroundService.start(this)
            yandexOn = true
            runOnUiThread {
                btnYandex.text = "STOP YANDEX"
                updateStatusBar()
            }
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

    private fun toggleSchema() {
        maneuverTagIdx = (maneuverTagIdx + 1) % 3
        HudForegroundService.loopRunner?.maneuverTagIdx = maneuverTagIdx
        HudForegroundService.saveSettings(this, maneuverTagIdx, useGaodeEnum)
        val labels = arrayOf("f28", "f5", "f6")
        btnToggleSchema.text = "TAG:${labels[maneuverTagIdx]}"
        Logger.i("UI", "maneuver tag = ${labels[maneuverTagIdx]}")
        toast("Maneuver field: ${labels[maneuverTagIdx]}")
    }

    private fun toggleEnum() {
        useGaodeEnum = !useGaodeEnum
        HudForegroundService.loopRunner?.useGaodeEnum = useGaodeEnum
        HudForegroundService.saveSettings(this, maneuverTagIdx, useGaodeEnum)
        val label = if (useGaodeEnum) "GAODE" else "v33"
        btnToggleEnum.text = "ENUM:$label"
        Logger.i("UI", "enum mode = $label")
        toast("Enum: $label")
    }

    private fun toggleMockGps() {
        if (!mockOn) {
            MockGpsService.start(this, 55.7558, 37.6173)
            mockOn = true
            btnMockGps.text = "STOP MOCK"
        } else {
            MockGpsService.stop()
            mockOn = false
            btnMockGps.text = "MOCK GPS"
        }
    }

    private fun toggleSniffer() {
        val b = HudForegroundService.bridge
        if (!snifferOn) {
            b?.snifferStart()
            snifferOn = true
            btnSniffer.text = "STOP SNIFF"
        } else {
            b?.snifferStop()
            snifferOn = false
            btnSniffer.text = "SNIFFER"
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

    private fun testLanes() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val current = HudState.snapshot().testLanes
        HudState.update { it.copy(testLanes = !current) }
        btnTestLanes.text = if (!current) "LANES ON" else "LANES"
        toast("Test lanes: ${if (!current) "ON" else "OFF"}")
        Logger.i("TEST", "testLanes=${!current}")
    }

    private fun testNavMap() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val s = HudState.snapshot()
        val maneuverVal = if (useGaodeEnum) toGaodeDisplay(s.maneuver) else s.maneuver
        val nextVal = if (maneuverVal == 2) 1 else 2
        val navmap = com.unkwn2.yandexhud.bridge.ProtobufBuilder.buildNavMap(intArrayOf(maneuverVal, nextVal))
        val rc = HudForegroundService.bridge?.fireEvent(SomeIpBridge.TOPIC_NAVMAP, navmap) ?: -1
        Logger.i("TEST", "navmap fire rc=$rc maneuvers=[$maneuverVal, $nextVal]")
        toast("NAVMAP sent rc=$rc")
    }

    private fun testNextNext() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val s = HudState.snapshot()
        val currentNextNext = s.nextNextManeuver
        val newNextNext = if (currentNextNext > 0) 0 else if (useGaodeEnum) toGaodeDisplay(s.maneuver).let { if (it == 2) 1 else 2 } else 0
        HudState.update { it.copy(nextNextManeuver = newNextNext) }
        btnTestNextNext.text = if (newNextNext > 0) "NEXT:$newNextNext" else "NEXT+NEXT"
        toast("Next-next: ${if (newNextNext > 0) "GAODE $newNextNext" else "OFF"}")
        Logger.i("TEST", "nextNextManeuver=$newNextNext")
    }

    private fun testManeuver(maneuver: Int, name: String) {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val tagLabels = arrayOf("f28", "f5", "f6")
        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
        val gaodeVal = if (useGaodeEnum) toGaodeDisplay(maneuver) else maneuver
        Logger.i("TEST", "maneuver=$name code=$maneuver gaode=$gaodeVal tag=${tagLabels[maneuverTagIdx]} enum=$enumLabel")
        HudState.update {
            it.copy(active = true, maneuver = maneuver, distanceMeters = 500,
                road = "Test $name", etaSeconds = 300, lastUpdateMs = System.currentTimeMillis())
        }
        HudState.setTestLatch(10000L)
        toast("TEST $name → HUD $gaodeVal (latch 10s)")
    }

    private fun toGaodeDisplay(m: Int): Int = when (m) {
        ManeuverMapper.M_LEFT -> 1; ManeuverMapper.M_RIGHT -> 2
        ManeuverMapper.M_SLIGHT_LEFT -> 3; ManeuverMapper.M_SLIGHT_RIGHT -> 4
        ManeuverMapper.M_FORK_LEFT -> 3; ManeuverMapper.M_FORK_RIGHT -> 4
        ManeuverMapper.M_HARD_LEFT -> 7; ManeuverMapper.M_HARD_RIGHT -> 8
        ManeuverMapper.M_EXIT_LEFT -> 7; ManeuverMapper.M_EXIT_RIGHT -> 8
        ManeuverMapper.M_UTURN_LEFT -> 9; ManeuverMapper.M_UTURN_RIGHT -> 10
        ManeuverMapper.M_STRAIGHT -> 11; ManeuverMapper.M_ARRIVE -> 48
        ManeuverMapper.M_ROUNDABOUT_ENTER -> 13; ManeuverMapper.M_ROUNDABOUT_EXIT -> 24
        ManeuverMapper.M_FERRY -> 46; ManeuverMapper.M_TUNNEL -> 49; ManeuverMapper.M_TOLL -> 47
        else -> 0
    }

    private fun updateStatusBar() {
        val s = HudState.snapshot()
        val maneuverStr = when (s.maneuver) {
            0 -> "NONE"; 2 -> "LEFT"; 3 -> "RIGHT"; 4 -> "HARD_L"; 5 -> "HARD_R"
            6 -> "SLIGHT_L"; 7 -> "SLIGHT_R"; 9 -> "STRAIGHT"
            10 -> "UTURN_L"; 11 -> "UTURN_R"; 12 -> "ARRIVE"
            else -> "${s.maneuver}"
        }
        val tagLabels = arrayOf("f28", "f5", "f6")
        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
        val yStatus = if (yandexOn) "ON" else "OFF"
        val fgsReady = HudForegroundService.isReady
        val navStatus = if (s.active) "$maneuverStr ${s.distanceMeters}m" else "idle"
        val a11yStatus = if (isA11yEnabled()) "ON" else "OFF"

        runOnUiThread {
            statusBar.text = "Yandex:$yStatus FGS:$fgsReady | $navStatus | TAG:${tagLabels[maneuverTagIdx]} ENUM:$enumLabel | A11y:$a11yStatus"
        }
    }

    private fun isNotifAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

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
