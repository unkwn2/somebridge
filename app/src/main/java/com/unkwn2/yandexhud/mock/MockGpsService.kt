package com.unkwn2.yandexhud.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.util.Logger

object MockGpsService {
    private const val TAG = "MOCKGPS"

    private const val DEFAULT_LAT = 39.9042
    private const val DEFAULT_LON = 116.4074

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile var currentLat = DEFAULT_LAT
    @Volatile var currentLon = DEFAULT_LON

    fun start(ctx: Context, lat: Double = DEFAULT_LAT, lon: Double = DEFAULT_LON) {
        if (running) return
        currentLat = lat
        currentLon = lon
        running = true
        val t = Thread {
            Logger.i(TAG, "started lat=$lat lon=$lon")
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Throwable) {}

            try {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                Logger.i(TAG, "addTestProvider OK")
            } catch (t: Throwable) {
                Logger.e(TAG, "addTestProvider failed: ${t.message}")
                Logger.e(TAG, "Run: adb shell appops set com.unkwn2.yandexhud android:mock_location allow")
                running = false; return@Thread
            }

            var myLat = lat
            var myLon = lon
            var counter = 0
            while (running) {
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = myLat
                    longitude = myLon
                    accuracy = 5f
                    altitude = 50.0
                    bearing = 0f
                    speed = 15f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    extras = Bundle().apply { putInt("satellites", 12) }
                }
                try {
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
                    if (counter % 10 == 0) Logger.i(TAG, "mock lat=${loc.latitude} lon=${loc.longitude}")
                    HudState.update { it.copy(lat = myLat, lon = myLon, lastUpdateMs = System.currentTimeMillis()) }
                } catch (t: Throwable) {
                    Logger.e(TAG, "setTestProviderLocation: ${t.message}")
                }
                myLat += 0.00013
                myLon += 0.00013
                currentLat = myLat
                currentLon = myLon
                counter++
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }

            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Throwable) {}
            running = false
            Logger.i(TAG, "stopped")
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    val isRunning: Boolean get() = running
}
