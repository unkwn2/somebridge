package com.unkwn2.yandexhud.amap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger

class AmapNaviReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AMAP_RX"
        const val ACTION_NAVI = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val ACTION_START_NAVI = "com.byd.amapservice.ACTION_START_NAVI"
        const val ACTION_STOP_NAVI = "com.byd.amapservice.ACTION_STOP_NAVI"

        private const val KEY_TYPE = "KEY_TYPE"
        private const val KEY_TURN_ICON = "nextTurnIcon"
        private const val KEY_NEXT_TURN = "nextNextTurnIcon"
        private const val KEY_NEXT_ROAD = "nextRouteName"
        private const val KEY_ROUTE_DIST = "routeRemainDist"
        private const val KEY_ROUTE_TIME = "routeRemainTime"
        private const val KEY_SEG_DIST = "curToSegmentDist"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_NAVI && action != ACTION_START_NAVI && action != ACTION_STOP_NAVI) return

        val keyType = intent.getIntExtra(KEY_TYPE, 0)
        Logger.i(TAG, "onReceive action=$action keyType=$keyType extras=${dumpExtras(intent)}")

        if (action == ACTION_STOP_NAVI) return

        if (keyType != 10001 && keyType != 0) return

        val turnIcon = intent.getIntExtra(KEY_TURN_ICON, -1)
        val nextTurnIcon = intent.getIntExtra(KEY_NEXT_TURN, -1)
        val segDist = intent.getIntExtra(KEY_SEG_DIST, 0)
        val routeDist = intent.getIntExtra(KEY_ROUTE_DIST, 0)
        val routeTime = intent.getIntExtra(KEY_ROUTE_TIME, 0)
        val nextRoad = intent.getStringExtra(KEY_NEXT_ROAD) ?: ""

        Logger.i(TAG, "turnIcon=$turnIcon nextNext=$nextTurnIcon segDist=$segDist routeDist=$routeDist routeTime=$routeTime road='$nextRoad'")

        if (turnIcon < 0) return

        val internal = autoNaviToInternal(turnIcon)
        val nextInternal = if (nextTurnIcon > 0) autoNaviToInternal(nextTurnIcon) else 0

        HudState.update { prev ->
            prev.copy(
                active = true,
                maneuver = if (internal != ManeuverMapper.M_UNKNOWN) internal else prev.maneuver,
                distanceMeters = if (segDist > 0) segDist else prev.distanceMeters,
                road = if (nextRoad.isNotEmpty() && nextRoad != "???") nextRoad else prev.road,
                totalDistMeters = if (routeDist > 0) routeDist else prev.totalDistMeters,
                totalTimeSeconds = if (routeTime > 0) routeTime else prev.totalTimeSeconds,
                nextNextManeuver = if (nextInternal > 0) nextInternal else prev.nextNextManeuver,
                lastUpdateMs = System.currentTimeMillis()
            )
        }
    }

    private fun autoNaviToInternal(icon: Int): Int = when (icon) {
        0, 1 -> ManeuverMapper.M_STRAIGHT
        2 -> ManeuverMapper.M_LEFT
        3 -> ManeuverMapper.M_RIGHT
        4 -> ManeuverMapper.M_SLIGHT_LEFT
        5 -> ManeuverMapper.M_SLIGHT_RIGHT
        6 -> ManeuverMapper.M_HARD_LEFT
        7 -> ManeuverMapper.M_HARD_RIGHT
        8 -> ManeuverMapper.M_UTURN_LEFT
        9 -> ManeuverMapper.M_UTURN_RIGHT
        10, 12 -> ManeuverMapper.M_FORK_LEFT
        11, 13 -> ManeuverMapper.M_FORK_RIGHT
        14 -> ManeuverMapper.M_ROUNDABOUT_ENTER
        15 -> ManeuverMapper.M_ARRIVE
        else -> ManeuverMapper.M_UNKNOWN
    }

    private fun dumpExtras(intent: Intent): String {
        val sb = StringBuilder()
        val extras = intent.extras ?: return "(no extras)"
        for (key in extras.keySet()) {
            val v = extras.get(key)
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append("$key=$v")
        }
        return sb.toString()
    }
}
