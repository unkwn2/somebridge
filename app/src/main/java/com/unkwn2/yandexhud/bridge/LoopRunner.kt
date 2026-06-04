package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0
    @Volatile var useAltSchema = false

    fun start(periodMs: Long = 1000L) {
        if (running) return
        running = true
        counter = 0
        Thread {
            Logger.i(TAG, "started @ ${periodMs}ms alt=$useAltSchema")
            while (running) {
                val s = HudState.snapshot()
                if (s.active) {
                    val cal = java.util.Calendar.getInstance()
                    val etaTotalMin = s.etaSeconds / 60
                    val etaH = (cal.get(java.util.Calendar.HOUR_OF_DAY) + etaTotalMin / 60) % 24
                    val etaM = (cal.get(java.util.Calendar.MINUTE) + etaTotalMin % 60) % 60
                    val etaStr = String.format("%02d:%02d", etaH, etaM)

                    val payload = if (useAltSchema) {
                        ProtobufBuilder.buildAlt(
                            counter = counter++,
                            maneuver = s.maneuver,
                            distance = s.distanceMeters,
                            road = s.road,
                            lat = s.lat, lon = s.lon,
                            etaString = etaStr
                        )
                    } else {
                        ProtobufBuilder.build(
                            counter = counter++,
                            maneuver = s.maneuver,
                            distance = s.distanceMeters,
                            road = s.road,
                            lat = s.lat, lon = s.lon,
                            etaString = etaStr
                        )
                    }
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)
                    if (rc != 0 && counter % 3 == 0) {
                        Logger.w(TAG, "fireEvent rc=$rc")
                    } else if (counter % 10 == 0) {
                        Logger.i(TAG, "tick #$counter rc=$rc m=${s.maneuver} d=${s.distanceMeters} alt=$useAltSchema")
                    }
                } else if (counter % 30 == 0) {
                    Logger.i(TAG, "tick #$counter (idle)")
                }
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
            }
            Logger.i(TAG, "stopped")
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    val isRunning: Boolean get() = running
}
