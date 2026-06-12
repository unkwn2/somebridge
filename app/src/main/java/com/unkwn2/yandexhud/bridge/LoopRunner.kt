package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0
    @Volatile var maneuverTagIdx: Int = 0
    @Volatile var useGaodeEnum: Boolean = true

    fun start(periodMs: Long = 300L) {
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
                    val statusIconVal = if (s.active) 2 else 1  // navigatingStatus: 2=draw, 1=clear
                    val arriveText = if (maneuverVal == 48) s.arriveText.ifEmpty { "Прибытие" } else ""

                    // HudRoadInfoNotifyStruct protobuf — все поля
                    val laneLayout = if (s.testLanes) "1,2,2,1" else ""
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
                        statusIcon = statusIconVal,
                        speedLimit = s.speedLimit,
                        arriveText = arriveText,
                        testLanes = s.testLanes,
                        usePacked = s.usePacked,
                        laneLayout = laneLayout,
                        iconFieldNum = HudForegroundService.iconFieldNum,
                        maneuverIcon = if (HudForegroundService.iconFieldNum > 0) maneuverVal else 0
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)

                    if (counter % 30 == 0) {
                        val enumLabel = if (useGaodeEnum) "GAODE" else "v33"
                        val packLabel = if (s.usePacked) "pk" else "np"
                        val iconLabel = if (HudForegroundService.iconFieldNum > 0) " ICON=f${HudForegroundService.iconFieldNum}=$maneuverVal" else ""
                        Logger.i(TAG, "tick #$counter rc=$rc m=$maneuverVal($enumLabel) $packLabel d=${s.distanceMeters} road='${s.road}' iconIdx=$statusIconVal lanes=${if (s.testLanes) laneLayout else "-"}$iconLabel")
                    }
                } else if (counter % 100 == 0) {
                    Logger.i(TAG, "tick #$counter (idle)")
                }
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
            }
            Logger.i(TAG, "stopped")
        }.apply { name = "HudLoop"; isDaemon = false; priority = Thread.MAX_PRIORITY }.start()
    }

    fun stop() { running = false }
    val isRunning: Boolean get() = running

    private fun toGaodeEnum(m: Int): Int = ManeuverMapper.toGaode(m)
}
