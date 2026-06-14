package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import java.util.concurrent.CopyOnWriteArrayList

object HudState {
    data class Snapshot(
        val active: Boolean = false,
        val maneuver: Int = ManeuverMapper.M_STRAIGHT,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val lastUpdateMs: Long = 0L,
        val testLatchUntilMs: Long = 0L,
        val totalDistMeters: Int = 0,
        val totalTimeSeconds: Int = 0,
        val speedLimit: Int = 0,
        val arriveText: String = "",
        val testLanes: Boolean = false,
        val nextNextManeuver: Int = 0,
        val usePacked: Boolean = true,
        val iconPng: ByteArray? = null,      // PNG иконки манёвра из RemoteViews → f8
        val trafficLightColor: String = "",   // "red"/"green"/"yellow" из RemoteViews
        val trafficLightSeconds: Int = 0,     // секунды до смены светофора
        val cameraAlert: String = "",          // "camera"/"accident"/"roadworks"/"other"
        val maneuverGaode: Int = 0,             // GAODE-код манёвра из A11y contentDescription (0 = не установлен)
        val maneuverGaodeMs: Long = 0L,         // когда был установлен maneuverGaode (ms)
        val arrowScanActive: Boolean = false,   // режим перебора стрелок ICON_SIMPLE_NAVI
        val arrowScanIndex: Int = 0             // текущий индекс текстуры 0..47
    )

    @Volatile private var current = Snapshot()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun snapshot(): Snapshot {
        val s = current
        if (s.active && !isTestLatched() && System.currentTimeMillis() - s.lastUpdateMs > 30_000) {
            update { if (it === s) it.copy(active = false) else it }
            return current
        }
        return s
    }

    @Synchronized
    fun update(transform: (Snapshot) -> Snapshot) {
        current = transform(current)
        listeners.forEach { it(current) }
    }

    fun observe(cb: (Snapshot) -> Unit) { listeners += cb; cb(current) }

    fun isTestLatched(): Boolean = System.currentTimeMillis() < current.testLatchUntilMs

    fun setTestLatch(durationMs: Long = 5000L) {
        update { it.copy(testLatchUntilMs = System.currentTimeMillis() + durationMs) }
    }
}
