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
import com.unkwn2.yandexhud.bridge.ProtobufBuilder
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.notif.YandexA11yService
import com.unkwn2.yandexhud.util.LocalAdb
import com.unkwn2.yandexhud.util.Logger

class MainActivity : AppCompatActivity() {
    companion object { private const val TAG = "UI" }
    private lateinit var statusBar: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnYandex: Button
    private lateinit var btnMockGps: Button
    private lateinit var btnSniffer: Button
    private lateinit var btnA11y: Button
    private lateinit var btnToggleSchema: Button
    private lateinit var btnTestLanes: Button
    private lateinit var btnTestNavMap: Button
    private lateinit var btnTestNextNext: Button
    private lateinit var btnTogglePacked: Button
    private lateinit var btnHudMode: Button
    private lateinit var btnGrant: Button
    private lateinit var btnStopAmap: Button

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
        btnTestLanes = findViewById(R.id.btnTestLanes)
        btnTestNavMap = findViewById(R.id.btnTestNavMap)
        btnTestNextNext = findViewById(R.id.btnTestNextNext)
        btnTogglePacked = findViewById(R.id.btnTogglePacked)
        btnHudMode = findViewById(R.id.btnHudMode)
        btnGrant = findViewById(R.id.btnGrant)
        btnStopAmap = findViewById(R.id.btnStopAmap)

        btnYandex.setOnClickListener { toggleYandex() }
        btnMockGps.setOnClickListener { toggleMockGps() }
        btnSniffer.setOnClickListener { toggleSniffer() }
        btnA11y.setOnClickListener { enableA11y() }
        btnToggleSchema.setOnClickListener { toggleSchema() }
        btnTestLanes.setOnClickListener { testLanes() }
        btnTestNavMap.setOnClickListener { testNavMap() }
        btnTestNextNext.setOnClickListener { testNextNext() }
        btnTogglePacked.setOnClickListener { togglePacked() }
        btnHudMode.setOnClickListener { tryHudMode() }
        btnGrant.setOnClickListener { grantPermissions() }
        btnStopAmap.setOnClickListener { stopAmap() }
        findViewById<Button>(R.id.btnSaveLog).setOnClickListener { saveLog() }
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

    private fun toggleMockGps() {
        if (!mockOn) {
            toast("Mock GPS: Shenzhen 22.54, 114.06")
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

    private fun forceActiveForTest(maneuver: Int = ManeuverMapper.M_RIGHT) {
        HudState.update {
            it.copy(active = true, maneuver = maneuver, distanceMeters = 500,
                road = "Test", etaSeconds = 300, lastUpdateMs = System.currentTimeMillis())
        }
        HudState.setTestLatch(30000L)
    }

    private fun testLanes() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val current = HudState.snapshot().testLanes
        val newVal = !current
        HudState.update { it.copy(testLanes = newVal) }
        if (!HudState.snapshot().active) forceActiveForTest()
        btnTestLanes.text = if (newVal) "LANES ON" else "LANES"
        toast("Test lanes: ${if (newVal) "ON" else "OFF"}")
        Logger.i("TEST", "testLanes=$newVal")
    }

    private fun testNavMap() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val s = HudState.snapshot()
        if (!s.active) forceActiveForTest()
        val fresh = HudState.snapshot()
        val maneuverVal = if (useGaodeEnum) toGaodeDisplay(fresh.maneuver) else fresh.maneuver
        val nextVal = if (maneuverVal == 2) 1 else 2
        val packed = fresh.usePacked
        val navmap = ProtobufBuilder.buildNavMap(intArrayOf(maneuverVal, nextVal), packed)
        val rc = HudForegroundService.bridge?.fireEvent(SomeIpBridge.TOPIC_NAVMAP, navmap) ?: -1
        Logger.i("TEST", "navmap(0x8002) fire rc=$rc maneuvers=[$maneuverVal, $nextVal] packed=$packed")
        toast("NAVMAP 0x8002 rc=$rc [$maneuverVal,$nextVal]")
    }

    private fun testNextNext() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val s = HudState.snapshot()
        if (!s.active) forceActiveForTest()
        val fresh = HudState.snapshot()
        val cur = fresh.nextNextManeuver
        val rev = if (fresh.maneuver == ManeuverMapper.M_LEFT) ManeuverMapper.M_RIGHT else ManeuverMapper.M_LEFT
        val newVal = if (cur > 0) 0 else rev
        HudState.update { it.copy(nextNextManeuver = newVal) }
        btnTestNextNext.text = if (newVal > 0) "NXT:${ManeuverMapper.maneuverName(newVal)}" else "NEXT+NEXT"
        toast("Next-next: ${if (newVal > 0) ManeuverMapper.maneuverName(newVal) else "OFF"}")
        Logger.i("TEST", "nextNextManeuver=$newVal (M_${ManeuverMapper.maneuverName(newVal)})")
    }

    private fun togglePacked() {
        val current = HudState.snapshot().usePacked
        HudState.update { it.copy(usePacked = !current) }
        btnTogglePacked.text = if (!current) "PACKED" else "NON-PK"
        toast("Encoding: ${if (!current) "packed" else "non-packed"}")
        Logger.i("UI", "usePacked=${!current}")
    }

    private fun tryHudMode() {
        var tried = 0
        var succeeded = 0
        val keys = listOf(
            "navi_screen_status", "byd_navi_screen_status",
            "hud_navi_status", "byd_hud_navi_mode"
        )
        for (key in keys) {
            try {
                Settings.System.putInt(contentResolver, key, 3)
                succeeded++
                Logger.i("HUD", "Settings.System.putInt($key, 3) OK")
            } catch (t: Throwable) {
                Logger.i("HUD", "Settings.System.putInt($key, 3) FAIL: ${t.message}")
            }
            try {
                Settings.Global.putInt(contentResolver, key, 3)
                succeeded++
                Logger.i("HUD", "Settings.Global.putInt($key, 3) OK")
            } catch (t: Throwable) {
                Logger.i("HUD", "Settings.Global.putInt($key, 3) FAIL: ${t.message}")
            }
            tried += 2
        }
        val adbCmds = listOf(
            "adb shell settings put system navi_screen_status 3",
            "adb shell settings put global navi_screen_status 3",
            "adb shell settings put system byd_navi_screen_status 3",
            "adb shell am broadcast -a com.byd.amapservice.ACTION_START_NAVI"
        )
        copyAdbCmd(adbCmds.joinToString("\n"))
        toast("HUD mode: $succeeded/$tried OK. ADB cmds copied")
        Logger.i("HUD", "tried=$tried succeeded=$succeeded — ADB cmds copied")
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

    private fun toGaodeDisplay(m: Int): Int = ManeuverMapper.toGaode(m)

    private fun stopAmap() {
        Logger.i(TAG, "=== STOP AMAP (broadcast) ===")
        sendBroadcast(Intent("com.byd.amapservice.ACTION_STOP_NAVI"))
        toast("Sent STOP_NAVI broadcast")
    }

    private fun saveLog() {
        Thread {
            Logger.i(TAG, "=== SAVE LOG: connecting ADB ===")
            runOnUiThread { toast("Saving logs via ADB...") }
            val ok = LocalAdb.init(applicationContext)
            if (!ok) {
                runOnUiThread { toast("ADB init failed") }
                return@Thread
            }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val name = "/sdcard/hud_$ts.log"
            val r = LocalAdb.exec("logcat -d -s \"YandexHUD\" \"YA11Y\" \"YandexNotif\" \"LOOP\" \"FGS\" \"SIPBR\" \"someip::refer-plugin\" \"!YNDX\" > $name")
            LocalAdb.disconnect()
            if (r.success) {
                Logger.i(TAG, "SAVE LOG: written to $name (${r.output.take(100)})")
                runOnUiThread { toast("Log saved: $name") }
            } else {
                Logger.e(TAG, "SAVE LOG failed: ${r.error}")
                runOnUiThread { toast("Log failed: ${r.error}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun grantPermissions() {
        Thread {
            Logger.i(TAG, "=== GRANT: connecting to local ADB ===")
            runOnUiThread { toast("Connecting ADB...") }
            val initOk = LocalAdb.init(applicationContext)
            if (!initOk) {
                Logger.e(TAG, "GRANT: ADB init failed")
                runOnUiThread { toast("ADB init failed") }
                return@Thread
            }
            Logger.i(TAG, "GRANT: ADB connected, granting permissions...")
            runOnUiThread { toast("ADB OK, granting...") }
            val results = LocalAdb.grantAll()
            val labels = arrayOf("NOTIF", "A11Y", "MOCK", "NAVI_SET", "BG_RUN", "BATTRY")
            val sb = StringBuilder()
            for ((i, r) in results.withIndex()) {
                val label = labels.getOrElse(i) { "CMD#$i" }
                Logger.i(TAG, "GRANT $label: success=${r.success} out='${r.output.take(80)}' err='${r.error}'")
                sb.append("$label:${if (r.success) "OK" else "FAIL"} ")
            }
            LocalAdb.disconnect()
            val msg = sb.toString().trim()
            runOnUiThread { toast(msg) }
            Logger.i(TAG, "=== GRANT done: $msg ===")
        }.apply { isDaemon = true }.start()
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
        val yStatus = if (yandexOn) "ON" else "OFF"
        val fgsReady = HudForegroundService.isReady
        val navStatus = if (s.active) "$maneuverStr ${s.distanceMeters}m" else "idle"
        val a11yStatus = if (isA11yEnabled()) "ON" else "OFF"
        val packLabel = if (s.usePacked) "pk" else "np"
        val lanesLabel = if (s.testLanes) "L" else ""
        val nnLabel = if (s.nextNextManeuver > 0) "N${s.nextNextManeuver}" else ""

        runOnUiThread {
            statusBar.text = "Y:$yStatus FGS:$fgsReady | $navStatus | TAG:${tagLabels[maneuverTagIdx]} $packLabel${lanesLabel}${nnLabel} | A11y:$a11yStatus"
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
