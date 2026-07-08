package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0
    @Volatile var useGaodeEnum: Boolean = true
    @Volatile private var wasActive = false
    @Volatile private var lastLoggedManeuver = Int.MIN_VALUE
    @Volatile private var worker: Thread? = null
    private val startLock = Any()

    private val fixedStage = ProtobufBuilder.STAGE_MAX

    fun resetNewStage() {}
    fun stageLabel(): String = ProtobufBuilder.stageName(fixedStage)

    fun start(periodMs: Long = 300L) {
        synchronized(startLock) {
            if (running) return
            running = true
            counter = 0
            wasActive = false
        }
        worker?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        val t = Thread {
            Logger.i(TAG, "started @ ${periodMs}ms gaode=$useGaodeEnum builder=${if (HudForegroundService.builderOld) "OLD" else "NEW"}")
            while (running) {
                val s = HudState.snapshot()
                if (HudForegroundService.DEBUG_ARROW_SCAN && s.arrowScanActive) {
                    wasActive = true
                    val payload = ProtobufBuilder.build(
                        counter++,
                        maneuver = 0,
                        distance = 0,
                        road = "",
                        lat = 0.0, lon = 0.0,
                        etaString = "",
                        statusIcon = 2,
                        iconPngLarge = null,
                        iconPngSmall = null,
                        testLanes = false,
                        laneLayout = ""
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)
                    if (counter % 5 == 0) {
                        HudState.update { it.copy(arrowScanIndex = (it.arrowScanIndex + 1) % 48) }
                    }
                    if (counter % 5 == 0) {
                        Logger.i(TAG, "arrowScan idx=${s.arrowScanIndex} rc=$rc")
                    }
                } else if (s.active) {
                    wasActive = true
                    val cal = java.util.Calendar.getInstance()
                    val nowTotalMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                    val etaTotalMin = s.etaSeconds / 60
                    val arrTotal = nowTotalMin + etaTotalMin
                    val etaH = (arrTotal / 60) % 24
                    val etaM = arrTotal % 60
                    val etaStr = if (s.etaSeconds > 0) String.format("%02d:%02d", etaH, etaM) else ""

                    val a11yFresh = s.maneuverGaode > 0 && (System.currentTimeMillis() - s.maneuverGaodeMs) < 10_000
                    val a11yHold = s.maneuverGaode > 0 && s.distanceMeters > 0 && (System.currentTimeMillis() - s.maneuverGaodeMs) < 20_000
                    val maneuverVal = if (a11yFresh) s.maneuverGaode
                        else if (a11yHold) s.maneuverGaode
                        else toGaodeEnum(s.maneuver)
                    val statusIconVal = 2

                    // ДИАГНОСТИКА: связка вход GAODE -> выход f28 (для эмпирической выверки gaodeToF28).
                    // Логируем при КАЖДОЙ смене манёвра, чтобы поймать какой gaode даёт какой f28.
                    val f28out = ProtobufBuilder.gaodeToF28(maneuverVal)
                    if (maneuverVal != lastLoggedManeuver) {
                        lastLoggedManeuver = maneuverVal
                        Logger.i(TAG, "MANEUVER gaode_in=$maneuverVal(${ManeuverMapper.maneuverName(s.maneuver)}) a11yFresh=$a11yFresh sGaode=${s.maneuverGaode} -> f28_out=$f28out road='${s.road}' d=${s.distanceMeters}")
                    }

                    // Полосы в ПРАВИЛЬНОМ формате "S,H|" (см. конспект). Демо: 3 полосы, активна по направлению манёвра.
                    val laneLayout = if (s.testLanes)
                        ProtobufBuilder.buildLanes(
                            ProtobufBuilder.gaodeToF28(maneuverVal),
                            3,
                            when (ProtobufBuilder.gaodeToF28(maneuverVal)) { 3 -> 0; 2 -> 2; else -> 1 }
                        ) else ""
                    val pngLarge = if (HudForegroundService.sendPngIcon && maneuverVal != 0)
                        NaviIconLoader.loadLarge(maneuverVal) ?: s.iconPng else null
                    val pngSmall = if (HudForegroundService.sendPngIcon && maneuverVal != 0)
                        NaviIconLoader.loadSmall(maneuverVal) ?: pngLarge else null

                    // Камера: при cameraAlert подменяем иконку манёвра на иконку камеры (f8)
                    // Дистанция до камеры идёт в f9 (как в эталоне discope: camera → f8+f9)
                    val isCameraActive = s.cameraAlert.isNotEmpty()
                    val effectivePngSmall = if (isCameraActive) s.cameraIconPng ?: pngSmall else pngSmall
                    val effectiveCameraDist = if (isCameraActive) s.cameraDistanceMeters else 0

                    val payload: ByteArray
                    val modeLabel: String
                    // Дистанция: при камере — дистанция до камеры в f9, иначе — до манёвра
                    val effectiveDist = if (isCameraActive && effectiveCameraDist > 0) effectiveCameraDist else s.distanceMeters

                    if (HudForegroundService.builderOld) {
                        // ── OLD: рабочий метод от 18 июня ──
                        val arriveTxt = if (maneuverVal == 48) s.arriveText.ifEmpty { "Прибытие" } else ""
                        payload = ProtobufBuilder.buildOld(
                            counter = counter++,
                            maneuver = if (isCameraActive) 0 else maneuverVal,
                            distance = effectiveDist,
                            road = s.road,
                            lat = s.lat, lon = s.lon,
                            etaString = etaStr,
                            totalDistMeters = s.totalDistMeters,
                            totalTimeSeconds = s.totalTimeSeconds,
                            statusIcon = statusIconVal,
                            speedLimit = s.speedLimit,
                            arriveText = arriveTxt,
                            testLanes = s.testLanes,
                            laneLayout = laneLayout,
                            iconPng = effectivePngSmall
                        )
                        modeLabel = "OLD"
                    } else {
                        // ── NEW: формат эталона discope (1779 событий) ──
                        val stage = fixedStage
                        payload = ProtobufBuilder.buildNewSafe(
                            stage = stage,
                            counter = counter++,
                            maneuver = if (isCameraActive) 0 else maneuverVal,
                            distance = effectiveDist,
                            road = s.road,
                            lat = s.lat, lon = s.lon,
                            etaString = etaStr,
                            totalDistMeters = s.totalDistMeters,
                            statusIcon = statusIconVal,
                            speedLimit = s.speedLimit,
                            iconPngSmall = effectivePngSmall,
                            testLanes = s.testLanes,
                            laneLayout = laneLayout,
                            speedInF7 = HudForegroundService.speedInLaneSlot
                        )
                        modeLabel = "NEW:${stageLabel()}"
                    }
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)

                    if (counter % 10 == 0) {
                        val pngB = pngSmall?.size ?: 0
                        Logger.i(TAG, "tick #$counter rc=$rc mode=$modeLabel m=$maneuverVal(GAODE) d=${s.distanceMeters} road='${s.road}' iconIdx=$statusIconVal lanes=${if (s.testLanes) laneLayout else "-"} bytes=${payload.size} png=${pngB}B")
                    }
                } else {
                    if (wasActive) {
                        val clearPayload = ProtobufBuilder.buildClearFrame(counter++)
                        bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, clearPayload)
                        wasActive = false
                        Logger.i(TAG, "sent HUD clear frame (statusIcon=1)")
                    } else if (counter % 100 == 0) {
                        Logger.i(TAG, "tick #$counter (idle)")
                    }
                }
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
            }
            Logger.i(TAG, "stopped")
        }
        t.name = "HudLoop"; t.isDaemon = false; t.priority = Thread.MAX_PRIORITY
        worker = t; t.start()
    }

    fun stop() { running = false; worker?.interrupt() }
    fun joinWorker(timeoutMs: Long = 1000) { worker?.let { try { it.join(timeoutMs) } catch (_: InterruptedException) {} } }
    val isRunning: Boolean get() = running

    private fun toGaodeEnum(m: Int): Int = ManeuverMapper.toGaode(m)
}
