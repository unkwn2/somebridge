package com.unkwn2.yandexhud.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.unkwn2.yandexhud.util.Logger

object MockGpsService {
    private const val TAG = "MOCKGPS"

    val CITIES = linkedMapOf(
        "Moscow" to Pair(55.7558, 37.6173),
        "Shenzhen" to Pair(22.5431, 114.0579),
        "Guangzhou" to Pair(23.1291, 113.2644),
        "Shanghai" to Pair(31.2304, 121.4737)
    )

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile var currentCity: String = ""

    fun start(ctx: Context, city: String) {
        val coords = CITIES[city] ?: return
        stop()
        currentCity = city
        startInternal(ctx, coords.first, coords.second)
    }

    fun start(ctx: Context, lat: Double, lon: Double) {
        stop()
        currentCity = "${lat},${lon}"
        startInternal(ctx, lat, lon)
    }

    private fun startInternal(ctx: Context, lat: Double, lon: Double) {
        if (running) return
        running = true
        val t = Thread {
            Logger.i(TAG, "started city=$currentCity lat=$lat lon=$lon")
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
                running = false; return@Thread
            }

            var counter = 0
            while (running) {
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat + counter * 0.000001
                    longitude = lon + counter * 0.000001
                    accuracy = 5f
                    bearing = 45f
                    speed = 10f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    extras = Bundle().apply { putInt("satellites", 12) }
                }
                try {
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
                    if (counter % 50 == 0) Logger.i(TAG, "mock lat=${loc.latitude} lon=${loc.longitude}")
                } catch (t: Throwable) {
                    Logger.e(TAG, "setTestProviderLocation: ${t.message}")
                }
                counter++
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }

            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Throwable) {}
            running = false
            currentCity = ""
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
