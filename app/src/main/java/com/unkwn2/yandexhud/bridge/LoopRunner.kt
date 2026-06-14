package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0
    @Volatile var useGaodeEnum: Boolean = true
    @Volatile private var wasActive = false
    @Volatile private var worker: Thread? = null

    fun start(periodMs: Long = 300L) {
        if (running) return
        worker?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        running = true
        counter = 0
        wasActive = false
        val t = Thread {
            Logger.i(TAG, "started @ ${periodMs}ms gaode=$useGaodeEnum")
            while (running) {
                val s = HudState.snapshot()
                if (HudForegroundService.DEBUG_ARROW_SCAN && s.arrowScanActive) {
                    wasActive = true
                    val payload = ProtobufBuilder.build(
                        counter = counter++,
                        maneuver = 0,
                        distance = 0,
                        road = "",
                        lat = 0.0, lon = 0.0,
                        etaString = "",
                        statusIcon = 2,
                        simpleNaviIndex = s.arrowScanIndex,
                        suppressF28 = true,
                        iconFieldNum = 0,
                        maneuverIcon = 0,
                        iconPng = null,
                        testLanes = false,
                        laneLayout = "",
                        usePacked = s.usePacked
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
                    val etaStr = String.format("%02d:%02d", etaH, etaM)

                    val a11yFresh = s.maneuverGaode > 0 && (System.currentTimeMillis() - s.maneuverGaodeMs) < 5000
                    val maneuverVal = if (a11yFresh) s.maneuverGaode
                        else if (useGaodeEnum) toGaodeEnum(s.maneuver) else s.maneuver
                    val statusIconVal = 2
                    val arriveText = if (maneuverVal == 48) s.arriveText.ifEmpty { "Прибытие" } else ""

                    // HudRoadInfoNotifyStruct protobuf — все поля
                    val laneLayout = if (s.testLanes) "1,2,2,1" else ""
                    val payload = ProtobufBuilder.build(
                        counter = counter++,
                        maneuver = maneuverVal,
                        distance = s.distanceMeters,
                        road = s.road,
                        lat = s.lat, lon = s.lon,
                        etaString = etaStr,
                        totalDistMeters = s.totalDistMeters,
                        totalTimeSeconds = s.totalTimeSeconds,
                        statusIcon = statusIconVal,
                        speedLimit = s.speedLimit,
                        arriveText = arriveText,
                        testLanes = s.testLanes,
                        usePacked = s.usePacked,
                        laneLayout = laneLayout,
                        iconFieldNum = if (HudForegroundService.DEBUG_ARROW_SCAN) HudForegroundService.iconFieldNum else 0,
                        maneuverIcon = if (HudForegroundService.DEBUG_ARROW_SCAN && HudForegroundService.iconFieldNum > 0) maneuverVal else 0,
                        iconPng = if (HudForegroundService.sendPngIcon) s.iconPng else null,
                        nextManeuverFieldNum = if (HudForegroundService.DEBUG_ARROW_SCAN) HudForegroundService.nextManeuverFieldNum else 0,
                        nextManeuverValue = if (HudForegroundService.DEBUG_ARROW_SCAN && HudForegroundService.nextManeuverFieldNum > 0) toGaodeEnum(s.nextNextManeuver) else 0,
                        suppressF28 = HudForegroundService.DEBUG_ARROW_SCAN && HudForegroundService.iconFieldNum > 0
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)

                    if (counter % 30 == 0) {
                        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
                        val packLabel = if (s.usePacked) "pk" else "np"
                        val scanLabel = if (HudForegroundService.DEBUG_ARROW_SCAN && HudForegroundService.iconFieldNum > 0) " SCAN=f${HudForegroundService.iconFieldNum}=$maneuverVal NOF28" else ""
                        Logger.i(TAG, "tick #$counter rc=$rc m=$maneuverVal($enumLabel) $packLabel d=${s.distanceMeters} road='${s.road}' iconIdx=$statusIconVal lanes=${if (s.testLanes) laneLayout else "-"}$scanLabel")
                    }
                } else {
                    if (wasActive) {
                        val clearPayload = ProtobufBuilder.build(counter = counter++, maneuver = 0,
                            distance = 0, road = "", lat = 0.0, lon = 0.0, etaString = "",
                            statusIcon = 1, iconPng = null, testLanes = false, laneLayout = "",
                            usePacked = s.usePacked)
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
    val isRunning: Boolean get() = running

    private fun toGaodeEnum(m: Int): Int = ManeuverMapper.toGaode(m)
}
