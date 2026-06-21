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
    private val startLock = Any()

    fun start(periodMs: Long = 300L) {
        synchronized(startLock) {
            if (running) return
            running = true
            counter = 0
            wasActive = false
        }
        worker?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        val t = Thread {
            Logger.i(TAG, "started @ ${periodMs}ms gaode=$useGaodeEnum")
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
                        iconPng = null,
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
                    val etaStr = String.format("%02d:%02d", etaH, etaM)

                    val a11yFresh = s.maneuverGaode > 0 && (System.currentTimeMillis() - s.maneuverGaodeMs) < 5000
                    val maneuverVal = if (a11yFresh) s.maneuverGaode
                        else if (useGaodeEnum) toGaodeEnum(s.maneuver) else s.maneuver
                    val statusIconVal = 2

                    val laneLayout = if (s.testLanes) "1,2,2,1" else ""
                    val payload = ProtobufBuilder.build(
                        counter++,
                        maneuver = maneuverVal,
                        distance = s.distanceMeters,
                        road = s.road,
                        lat = s.lat, lon = s.lon,
                        etaString = etaStr,
                        totalDistMeters = s.totalDistMeters,
                        totalTimeSeconds = s.totalTimeSeconds,
                        statusIcon = statusIconVal,
                        iconPng = if (HudForegroundService.sendPngIcon) s.iconPng else null,
                        testLanes = s.testLanes,
                        laneLayout = laneLayout
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)

                    if (counter % 30 == 0) {
                        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
                        Logger.i(TAG, "tick #$counter rc=$rc m=$maneuverVal($enumLabel) d=${s.distanceMeters} road='${s.road}' iconIdx=$statusIconVal lanes=${if (s.testLanes) laneLayout else "-"}")
                    }
                } else {
                    if (wasActive) {
                        val clearPayload = ProtobufBuilder.build(
                            counter++, maneuver = 0,
                            distance = 0, road = "", lat = 0.0, lon = 0.0, etaString = "",
                            statusIcon = 1, iconPng = null, testLanes = false, laneLayout = ""
                        )
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
