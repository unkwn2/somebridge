package com.unkwn2.yandexhud.ui

import com.unkwn2.yandexhud.R
import com.unkwn2.yandexhud.bridge.NaviIconLoader
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.unkwn2.yandexhud.BuildConfig
import com.unkwn2.yandexhud.bridge.HudForegroundService
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.bridge.SomeIpBridge
import com.unkwn2.yandexhud.bridge.ProtobufBuilder
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.notif.YandexA11yService
import com.unkwn2.yandexhud.notif.YandexNaviNotificationListener
import com.unkwn2.yandexhud.util.LocalAdb
import com.unkwn2.yandexhud.util.LicenseManager
import com.unkwn2.yandexhud.util.Logger
import android.service.notification.NotificationListenerService
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "UI"
        private const val PREFS = "yandexhud_prefs"
    }
    private lateinit var statusBar: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnYandex: Button
    private lateinit var btnMockGps: Button
    private lateinit var btnSniffer: Button
    private lateinit var btnA11y: Button
    private lateinit var btnTestLanes: Button
    private lateinit var btnTestNavMap: Button
    private lateinit var btnTestNextNext: Button
    private lateinit var btnTogglePacked: Button
    private lateinit var btnHudMode: Button
    private lateinit var btnBuilderOld: Button
    private lateinit var btnBuilderNew: Button
    private lateinit var btnGrant: Button
    private lateinit var btnIconScan: Button
    private lateinit var btnPngIcon: Button
    private lateinit var btnRvDump: Button
    private lateinit var btnArrowScan: Button
    private lateinit var hiddenPanel: LinearLayout
    private var menuVisible = false
    private var yandexOn = false
    private var mockOn = false
    private var snifferOn = false
    private var useGaodeEnum = true
    private var statusRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        useGaodeEnum = HudForegroundService.loadGaode(this)
        HudForegroundService.builderOld = HudForegroundService.loadBuilderMode(this)

        if (BuildConfig.DEBUG) {
            initUI()
        } else {
            checkPasswordAndInit()
        }
    }

    // ── Password lock (release only) ───────────────────────────────────────────

    private fun checkPasswordAndInit() {
        val prefs = getSharedPreferences("app_lock", MODE_PRIVATE)
        val hash = prefs.getString("password_hash", null)
        if (hash == null) {
            showSetPasswordDialog(prefs)
        } else {
            showEnterPasswordDialog(prefs, hash)
        }
    }

    private fun showSetPasswordDialog(prefs: SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Set Password")
            .setMessage("First launch — create an app password")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Set") { _, _ ->
                val pwd = input.text.toString()
                if (pwd.length < 4) {
                    toast("Password must be at least 4 characters")
                    showSetPasswordDialog(prefs)
                    return@setPositiveButton
                }
                prefs.edit().putString("password_hash", sha256(pwd)).apply()
                toast("Password set")
                initUI()
            }
            .show()
    }

    private fun showEnterPasswordDialog(prefs: SharedPreferences, hash: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Password")
            .setMessage("App is locked")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val pwd = input.text.toString()
                if (sha256(pwd) == hash) {
                    toast("Access granted")
                    initUI()
                } else {
                    toast("Wrong password")
                    showEnterPasswordDialog(prefs, hash)
                }
            }
            .show()
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun initUI() {
        setContentView(R.layout.activity_main)

        statusBar = findViewById(R.id.statusBar)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        btnYandex = findViewById(R.id.btnYandex)
        btnMockGps = findViewById(R.id.btnMockGps)
        btnSniffer = findViewById(R.id.btnSniffer)
        btnA11y = findViewById(R.id.btnA11y)
        btnTestLanes = findViewById(R.id.btnTestLanes)
        btnTestNavMap = findViewById(R.id.btnTestNavMap)
        btnTestNextNext = findViewById(R.id.btnTestNextNext)
        btnTogglePacked = findViewById(R.id.btnTogglePacked)
        btnHudMode = findViewById(R.id.btnHudMode)
        btnBuilderOld = findViewById(R.id.btnBuilderOld)
        btnBuilderNew = findViewById(R.id.btnBuilderNew)
        btnGrant = findViewById(R.id.btnGrant)
        btnIconScan = findViewById(R.id.btnIconScan)
        btnPngIcon = findViewById(R.id.btnPngIcon)
        btnRvDump = findViewById(R.id.btnRvDump)
        btnArrowScan = findViewById(R.id.btnArrowScan)
        hiddenPanel = findViewById(R.id.hiddenPanel)

        btnYandex.setOnClickListener { toggleYandex() }
        btnMockGps.setOnClickListener { toggleMockGps() }
        btnSniffer.setOnClickListener { toggleSniffer() }
        btnA11y.setOnClickListener { enableA11y() }
        btnTestLanes.setOnClickListener { testLanes() }
        btnTestNavMap.setOnClickListener { testNavMap() }
        btnTestNextNext.setOnClickListener { testNextNext() }
        btnTogglePacked.setOnClickListener { togglePacked() }
        btnHudMode.setOnClickListener { toggleHudMode() }
        btnHudMode.text = if (useGaodeEnum) "GAODE" else "v33"
        btnBuilderOld.setOnClickListener { setBuilderOld(true) }
        btnBuilderNew.setOnClickListener { setBuilderOld(false) }
        updateBuilderButtons()
        btnGrant.setOnClickListener { grantPermissions() }
        btnIconScan.setOnClickListener { cycleIconField() }
        btnPngIcon.setOnClickListener { cyclePngIcon() }
        btnRvDump.setOnClickListener { cycleRvDump() }
        btnArrowScan.setOnClickListener { toggleArrowScan() }
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
        findViewById<Button>(R.id.btnDumpTree).setOnClickListener {
            YandexA11yService.dumpRequested = true
            toast("Tree dump requested")
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { toggleMenu() }
        findViewById<Button>(R.id.btnDonation).setOnClickListener { showDonationDialog() }
        findViewById<Button>(R.id.btnSelftest).setOnClickListener { runSelftest() }

        Logger.observe { line ->
            runOnUiThread {
                logText.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        HudState.observe { s ->
            updateStatusBar()
            runOnUiThread {
                if (s.arrowScanActive && ::btnArrowScan.isInitialized) {
                    btnArrowScan.text = "ARROW: ${s.arrowScanIndex}"
                }
            }
        }
        startStatusRefresh()
        checkLicense()
        ensurePermissions()
    }

    private fun checkLicense() {
        if (LicenseManager.isLicenseValid(applicationContext)) return
        val input = EditText(this).apply {
            hint = "Введите ключ лицензии"
            setSingleLine()
            setPadding(48, 24, 48, 24)
            textSize = 16f
        }
        AlertDialog.Builder(this)
            .setTitle("Активация YandexHUD")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Активировать") { _, _ ->
                val key = input.text.toString().trim()
                if (LicenseManager.saveLicense(applicationContext, key)) {
                    toast("Лицензия активирована")
                    Logger.i(TAG, "license activated")
                } else {
                    toast("Неверный ключ")
                    Logger.w(TAG, "license key invalid")
                    checkLicense()
                }
            }
            .show()
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
            if (!LicenseManager.isLicenseValid(applicationContext)) {
                toast("Требуется активация")
                checkLicense()
                return
            }
            // Грантим через ADB если права не выданы
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

    private fun toggleHudMode() {
        useGaodeEnum = !useGaodeEnum
        HudForegroundService.loopRunner?.useGaodeEnum = useGaodeEnum
        HudForegroundService.saveSettings(this, useGaodeEnum)
        val label = if (useGaodeEnum) "GAODE" else "v33"
        runOnUiThread { btnHudMode.text = label }
        toast("Protocol: $label")
        Logger.i("UI", "useGaodeEnum=$useGaodeEnum ($label)")
    }

    private fun testManeuver(maneuver: Int, name: String) {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
        val gaodeVal = if (useGaodeEnum) toGaodeDisplay(maneuver) else maneuver
        Logger.i("TEST", "maneuver=$name code=$maneuver gaode=$gaodeVal enum=$enumLabel")
        HudState.update {
            it.copy(active = true, maneuver = maneuver, distanceMeters = 500,
                road = "Test $name", etaSeconds = 300, lastUpdateMs = System.currentTimeMillis())
        }
        HudState.setTestLatch(10000L)
        toast("TEST $name → HUD $gaodeVal (latch 10s)")
    }

    private fun runSelftest() {
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val bridge = HudForegroundService.bridge
        if (bridge == null) { toast("Bridge not ready"); return }

        Thread {
            Logger.i("SELFTEST", "=== HUD SELFTEST START ===")
            val testCases = listOf(
                "m=0(NONE)" to Triple(0, 0, "нет манёвра"),
                "m=11(STRAIGHT)" to Triple(11, 500, "прямо"),
                "m=1(LEFT)" to Triple(1, 300, "влево"),
                "m=2(RIGHT)" to Triple(2, 400, "вправо"),
                "m=9(UTURN)" to Triple(9, 200, "разворот"),
                "m=5(HARD_RIGHT)" to Triple(5, 350, "шоссе"),
                "CAMERA" to Triple(0, 490, "камера")
            )

            for ((label, triple) in testCases) {
                val (gaode, dist, desc) = triple
                val pngLarge = NaviIconLoader.loadLarge(gaode)
                val pngSmall = NaviIconLoader.loadSmall(gaode) ?: pngLarge
                val cameraDist = if (label == "CAMERA") 300 else 0

                val payload = ProtobufBuilder.buildNewSafe(
                    stage = ProtobufBuilder.STAGE_MAX,
                    counter = 0, maneuver = gaode, distance = dist,
                    road = "Selftest $desc", lat = 39.9, lon = 116.4,
                    etaString = "12:00", totalDistMeters = 10000, totalTimeSeconds = 3600,
                    statusIcon = 2, speedLimit = 0, cameraDistance = cameraDist,
                    iconPngLarge = pngLarge, iconPngSmall = pngSmall
                )
                val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)
                val pngB = pngSmall?.size ?: 0
                Logger.i("SELFTEST", "case=$label rc=$rc bytes=${payload.size} png=${pngB}B guard=${if (payload.size <= ProtobufBuilder.MAX_PAYLOAD_BYTES) "OK" else "OVER"}")
            }

            // SIZEGUARD test: oversized PNG
            val hugePng = ByteArray(4000) { 0x89.toByte() }
            val payloadHuge = ProtobufBuilder.buildNewSafe(
                stage = ProtobufBuilder.STAGE_MAX,
                counter = 0, maneuver = 11, distance = 500,
                road = "Huge PNG test", lat = 39.9, lon = 116.4,
                etaString = "12:00", totalDistMeters = 10000, totalTimeSeconds = 3600,
                statusIcon = 2, iconPngLarge = hugePng, iconPngSmall = hugePng
            )
            val rcHuge = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payloadHuge)
            Logger.i("SELFTEST", "case=SIZEGUARD_HUGE rc=$rcHuge bytes=${payloadHuge.size} guard=${if (payloadHuge.size <= ProtobufBuilder.MAX_PAYLOAD_BYTES) "OK" else "OVER"}")

            Logger.i("SELFTEST", "=== HUD SELFTEST DONE ===")
            runOnUiThread { toast("Selftest done — check logcat SELFTEST") }
        }.apply { isDaemon = true; name = "SELFTEST" }.start()
    }

    private fun toggleArrowScan() {
        if (!HudForegroundService.DEBUG_ARROW_SCAN) { toast("Arrow scan disabled"); return }
        if (!yandexOn) { toast("Start YANDEX NAVI first"); return }
        val s = HudState.snapshot()
        if (!s.arrowScanActive) {
            if (!s.active) forceActiveForTest()
            HudState.update { it.copy(arrowScanActive = true, arrowScanIndex = 0) }
            toast("Arrow scan started: idx=0")
            Logger.i("TEST", "arrowScan ON (f27 texture 0..47)")
        } else {
            HudState.update { it.copy(arrowScanActive = false) }
            runOnUiThread { btnArrowScan.text = "ARROW SCAN" }
            val clearPayload = ProtobufBuilder.buildOld(
                counter = 0, maneuver = 0, distance = 0, road = "",
                lat = 0.0, lon = 0.0, etaString = "",
                totalDistMeters = 0, totalTimeSeconds = 0,
                statusIcon = 1, speedLimit = 0, arriveText = "",
                testLanes = false, laneLayout = "", iconPng = null
            )
            HudForegroundService.bridge?.fireEvent(SomeIpBridge.TOPIC_NAVI, clearPayload)
            toast("Arrow scan stopped")
            Logger.i("TEST", "arrowScan OFF — sent clear f16=1")
        }
    }

    private fun setBuilderOld(old: Boolean) {
        HudForegroundService.builderOld = old
        HudForegroundService.saveBuilderMode(this, old)
        HudForegroundService.loopRunner?.resetNewStage()
        updateBuilderButtons()
        toast(if (old) "Builder: OLD (рабочий метод)" else "Builder: NEW (перебор полей, +1 каждые 5с)")
        Logger.i("UI", "builderOld=$old")
        updateStatusBar()
    }

    private fun updateBuilderButtons() {
        val old = HudForegroundService.builderOld
        runOnUiThread {
            btnBuilderOld.text = if (old) "● OLD" else "OLD"
            btnBuilderNew.text = if (!old) "● NEW" else "NEW"
        }
    }

    private fun toGaodeDisplay(m: Int): Int = ManeuverMapper.toGaode(m)

    private var iconScanIdx = -1

    private fun cycleIconField() {
        val candidates = HudForegroundService.ICON_CANDIDATES
        iconScanIdx = (iconScanIdx + 1) % (candidates.size + 1)
        val fieldNum = if (iconScanIdx < candidates.size) candidates[iconScanIdx] else 0
        HudForegroundService.iconFieldNum = fieldNum
        val label = if (fieldNum > 0) "ICON: f$fieldNum" else "ICON: OFF"
        runOnUiThread { btnIconScan.text = label }
        Logger.i("TEST", "iconFieldNum=$fieldNum (${if (fieldNum > 0) "scanning f$fieldNum" else "OFF"})")
        toast("Small arrow field: ${if (fieldNum > 0) "f$fieldNum" else "OFF"}")
    }

    private fun cyclePngIcon() {
        HudForegroundService.sendPngIcon = !HudForegroundService.sendPngIcon
        val label = if (HudForegroundService.sendPngIcon) "PNG: ON" else "PNG: OFF"
        runOnUiThread { btnPngIcon.text = label }
        Logger.i("TEST", "sendPngIcon=${HudForegroundService.sendPngIcon}")
        toast("f8 PNG: ${if (HudForegroundService.sendPngIcon) "ON" else "OFF"}")
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
        val packLabel = if (s.usePacked) "pk" else "np"
        val lanesLabel = if (s.testLanes) "L" else ""
        val nnLabel = if (s.nextNextManeuver > 0) "N${s.nextNextManeuver}" else ""
        val builderLabel = if (HudForegroundService.builderOld) "OLD"
            else "NEW:${HudForegroundService.loopRunner?.stageLabel() ?: "?"}"

        runOnUiThread {
            statusBar.text = "Y:$yStatus FGS:$fgsReady | $navStatus | $builderLabel | $packLabel${lanesLabel}${nnLabel} | A11y:$a11yStatus"
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