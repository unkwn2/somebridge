package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0
    @Volatile var maneuverTagIdx: Int = 0
    @Volatile var useGaodeEnum: Boolean = true

    fun start(periodMs: Long = 1000L) {
        if (running) return
        running = true
        counter = 0
        Thread {
            Logger.i(TAG, "started @ ${periodMs}ms tagIdx=$maneuverTagIdx gaode=$useGaodeEnum")
            while (running) {
                val s = HudState.snapshot()
                if (s.active) {
                    val cal = java.util.Calendar.getInstance()
                    val nowTotalMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                    val etaTotalMin = s.etaSeconds / 60
                    val arrTotal = nowTotalMin + etaTotalMin
                    val etaH = (arrTotal / 60) % 24
                    val etaM = arrTotal % 60
                    val etaStr = String.format("%02d:%02d", etaH, etaM)

                    val maneuverVal = if (useGaodeEnum) toGaodeEnum(s.maneuver) else s.maneuver
                    val arriveText = if (maneuverVal == 48) s.arriveText.ifEmpty { "Прибытие" } else ""
                    val payload = ProtobufBuilder.build(
                        counter = counter++,
                        maneuver = maneuverVal,
                        maneuverTagIdx = maneuverTagIdx,
                        distance = s.distanceMeters,
                        road = s.road,
                        lat = s.lat, lon = s.lon,
                        etaString = etaStr,
                        totalDistMeters = s.totalDistMeters,
                        totalTimeSeconds = s.totalTimeSeconds,
                        speedLimit = s.speedLimit,
                        arriveText = arriveText,
                        testLanes = s.testLanes,
                        nextNextManeuver = s.nextNextManeuver,
                        usePacked = s.usePacked
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)
                    if (rc != 0 && counter % 3 == 0) {
                        Logger.w(TAG, "fireEvent rc=$rc")
                    } else if (counter % 10 == 0) {
                        val tagLabel = arrayOf("f28", "f5", "f6")[maneuverTagIdx]
                        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
                        val packLabel = if (s.usePacked) "pk" else "np"
                        Logger.i(TAG, "tick #$counter rc=$rc m=$maneuverVal($enumLabel) tag=$tagLabel $packLabel d=${s.distanceMeters}")
                    }
                } else if (counter % 30 == 0) {
                    Logger.i(TAG, "tick #$counter (idle)")
                }
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
            }
            Logger.i(TAG, "stopped")
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false }
    val isRunning: Boolean get() = running

    private fun toGaodeEnum(m: Int): Int = ManeuverMapper.toGaode(m)
}
