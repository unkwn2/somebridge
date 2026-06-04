package com.unkwn2.yandexhud.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.unkwn2.yandexhud.util.Logger

object MockGpsService {
    private const val TAG = "MOCKGPS"
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun start(ctx: Context, lat: Double, lon: Double) {
        if (running) return
        running = true
        val t = Thread {
            Logger.i(TAG, "started lat=$lat lon=$lon")
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
                Logger.i(TAG, "removed existing test provider")
            } catch (_: Throwable) {}

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
                    this.latitude = lat + counter * 0.000001
                    this.longitude = lon + counter * 0.000001
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
        Logger.i(TAG, "stop requested")
    }

    val isRunning: Boolean get() = running
}
