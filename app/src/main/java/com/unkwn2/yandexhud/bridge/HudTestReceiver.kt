package com.unkwn2.yandexhud.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unkwn2.yandexhud.mock.MockGpsService
import com.unkwn2.yandexhud.util.Logger

class HudTestReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HUDTEST"
        const val ACTION = "com.unkwn2.HUD_TEST"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val mode = intent.getStringExtra("mode")

        when (mode) {
            "sniff" -> {
                Logger.i(TAG, "triggering sniffer...")
                val bridge = HudForegroundService.bridge ?: run {
                    Logger.e(TAG, "bridge not ready — start HUD service first")
                    return
                }
                bridge.snifferStart()
            }
            "sniffstop" -> {
                val bridge = HudForegroundService.bridge
                bridge?.snifferStop()
                Logger.i(TAG, "sniffer stopped")
            }
            "mock" -> {
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: 39.9042
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: 116.4074
                MockGpsService.start(app, lat, lon)
                Logger.i(TAG, "mock started lat=$lat lon=$lon")
            }
            "mockstop" -> {
                MockGpsService.stop()
                Logger.i(TAG, "mock stopped")
            }
            "fire" -> {
                val topicStr = intent.getStringExtra("topic") ?: "4010a00018001"
                val topic = try { topicStr.toLong(16) } catch (_: NumberFormatException) {
                    Logger.e(TAG, "invalid topic hex: $topicStr"); return
                }
                val payloadHex = (intent.getStringExtra("payload") ?: "").replace(" ", "")
                val payload = try {
                    payloadHex.chunked(2).filter { it.length == 2 }
                        .map { it.toInt(16).toByte() }.toByteArray()
                } catch (_: NumberFormatException) {
                    Logger.e(TAG, "invalid payload hex"); return
                }
                val bridge = HudForegroundService.bridge ?: run {
                    Logger.e(TAG, "bridge not ready")
                    return
                }
                val rc = bridge.fireEvent(topic, payload)
                Logger.i(TAG, "fire topic=0x$topicStr len=${payload.size} rc=$rc")
            }
            else -> Logger.w(TAG, "unknown mode=$mode (sniff|sniffstop|mock|mockstop|fire)")
        }
    }
}
