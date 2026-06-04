package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.util.Logger

class LoopRunner(private val bridge: SomeIpBridge) {
    private val TAG = "LOOP"
    @Volatile private var running = false
    @Volatile private var counter = 0

    fun start(periodMs: Long = 200L) {
        if (running) return
        running = true
        counter = 0
        Thread {
            Logger.i(TAG, "started @ ${periodMs}ms")
            while (running) {
                val s = HudState.snapshot()
                if (s.active) {
                    val cal = java.util.Calendar.getInstance()
                    val etaH = (cal.get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24
                    val etaM = cal.get(java.util.Calendar.MINUTE)
                    val etaStr = String.format("%02d:%02d", etaH, etaM)
                    val payload = ProtobufBuilder.build(
                        counter = counter++,
                        maneuver = s.maneuver,
                        distance = s.distanceMeters,
                        road = s.road,
                        lat = s.lat, lon = s.lon,
                        etaString = etaStr,
                        etaMinutes = s.etaSeconds / 60
                    )
                    val rc = bridge.fireEvent(SomeIpBridge.TOPIC_NAVI, payload)
                    if (rc != 0 && counter % 5 == 0) {
                        Logger.w(TAG, "fireEvent rc=$rc")
                    } else if (counter % 25 == 0) {
                        Logger.i(TAG, "tick #$counter rc=$rc m=${s.maneuver} d=${s.distanceMeters}")
                    }
                } else if (counter % 50 == 0) {
                    Logger.i(TAG, "tick #$counter (idle — HudState inactive)")
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
