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
        val iconPng: ByteArray? = null,
        val trafficLightColor: String = "",
        val trafficLightSeconds: Int = 0,
        val cameraAlert: String = "",
        val cameraDistanceMeters: Int = 0,   // дистанция до камеры/POI от Яндекса (f18). 0 = нет камеры
        val cameraIconPng: ByteArray? = null, // PNG-значок камеры из Яндекса (флаг в f8 при камере)
        val maneuverGaode: Int = 0,
        val maneuverGaodeMs: Long = 0L,
        val arrowScanActive: Boolean = false,
        val arrowScanIndex: Int = 0
    )

    @Volatile private var current = Snapshot()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    private const val ACTIVE_TIMEOUT_MS = 60_000L

    fun snapshot(): Snapshot {
        val s = current
        if (s.active && !isTestLatched()) {
            val age = System.currentTimeMillis() - s.lastUpdateMs
            if (age > ACTIVE_TIMEOUT_MS) {
                update { it.copy(active = false) }
                return current
            }
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

    fun softClearManeuver() = update {
        it.copy(
            maneuver = ManeuverMapper.M_UNKNOWN,
            maneuverGaode = 0, maneuverGaodeMs = 0L,
            distanceMeters = 0, road = "", arriveText = "",
            cameraAlert = "", cameraDistanceMeters = 0, cameraIconPng = null,
            lastUpdateMs = System.currentTimeMillis())
    }

    fun deactivate() {
        val now = System.currentTimeMillis()
        val s = current
        // Если данные свежие (< 10с) — не дезактивируем (a11y/нотиф могли обновиться недавно)
        if (s.active && (now - s.lastUpdateMs) < 60_000L) return
        update {
            it.copy(active = false, maneuver = ManeuverMapper.M_UNKNOWN,
                maneuverGaode = 0, maneuverGaodeMs = 0L,
                distanceMeters = 0, road = "", arriveText = "",
                cameraAlert = "", cameraDistanceMeters = 0, cameraIconPng = null,
                lastUpdateMs = now)
        }
    }

    fun clearManeuver() = deactivate()
}
