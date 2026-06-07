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

        private const val KEY_TURN_ICON = "nextTurnIcon"
        private const val KEY_NEXT_TURN_ICON = "nextNextTurnIcon"
        private const val KEY_MANEUVER_ID = "maneuverID"
        private const val KEY_SEG_REMAIN_DIST = "segRemainDis"
        private const val KEY_ROUTE_REMAIN_DIST = "routeRemainDisAuto"
        private const val KEY_ROUTE_REMAIN_TIME = "routeRemainTimeAuto"
        private const val KEY_NEXT_ROAD_NAME = "nextRoadName"
        private const val KEY_ETA_TEXT = "etaText"
        private const val KEY_NAVI_STATUS = "naviStatus"

        @Volatile var lastNextNextGaode: Int = 0
        @Volatile var lastManeuverGaode: Int = 0
        @Volatile var lastSegDist: Int = 0
        @Volatile var lastLaneBack: IntArray = IntArray(0)
        @Volatile var lastLaneFront: IntArray = IntArray(0)
        @Volatile var lastRecommendLane: IntArray = IntArray(0)
        @Volatile var lastLaneDist: Int = -1
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_NAVI && action != ACTION_START_NAVI && action != ACTION_STOP_NAVI) return

        Logger.i(TAG, "onReceive action=$action extras=${dumpExtras(intent)}")

        if (action == ACTION_STOP_NAVI) {
            lastNextNextGaode = 0
            lastManeuverGaode = 0
            return
        }

        val turnIcon = intent.getIntExtra(KEY_TURN_ICON, -1)
        val nextTurnIcon = intent.getIntExtra(KEY_NEXT_TURN_ICON, -1)
        val maneuverId = intent.getIntExtra(KEY_MANEUVER_ID, -1)
        val segDist = intent.getIntExtra(KEY_SEG_REMAIN_DIST, 0)
        val routeRemainDist = intent.getIntExtra(KEY_ROUTE_REMAIN_DIST, 0)
        val routeRemainTime = intent.getIntExtra(KEY_ROUTE_REMAIN_TIME, 0)
        val nextRoad = intent.getStringExtra(KEY_NEXT_ROAD_NAME) ?: ""

        if (turnIcon >= 0) lastManeuverGaode = turnIcon
        if (nextTurnIcon >= 0) lastNextNextGaode = nextTurnIcon
        if (segDist > 0) lastSegDist = segDist

        Logger.i(TAG, "turnIcon=$turnIcon nextNext=$nextTurnIcon manId=$maneuverId segDist=$segDist routeDist=$routeRemainDist routeTime=$routeRemainTime road='$nextRoad'")

        if (turnIcon >= 0 && !HudState.isTestLatched()) {
            HudState.update { prev ->
                val mergeNextNext = if (nextTurnIcon > 0) nextTurnIcon else prev.nextNextManeuver
                prev.copy(
                    active = true,
                    maneuver = gaodeToInternal(turnIcon),
                    distanceMeters = if (segDist > 0) segDist else prev.distanceMeters,
                    road = if (nextRoad.isNotEmpty() && nextRoad != "???") nextRoad else prev.road,
                    totalDistMeters = if (routeRemainDist > 0) routeRemainDist else prev.totalDistMeters,
                    totalTimeSeconds = if (routeRemainTime > 0) routeRemainTime * 60 else prev.totalTimeSeconds,
                    nextNextManeuver = mergeNextNext,
                    lastUpdateMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun gaodeToInternal(gaode: Int): Int = when (gaode) {
        1 -> ManeuverMapper.M_LEFT
        2 -> ManeuverMapper.M_RIGHT
        3 -> ManeuverMapper.M_SLIGHT_LEFT
        4 -> ManeuverMapper.M_SLIGHT_RIGHT
        7 -> ManeuverMapper.M_HARD_LEFT
        8 -> ManeuverMapper.M_HARD_RIGHT
        9 -> ManeuverMapper.M_UTURN_LEFT
        10 -> ManeuverMapper.M_UTURN_RIGHT
        11 -> ManeuverMapper.M_STRAIGHT
        13 -> ManeuverMapper.M_ROUNDABOUT_ENTER
        48 -> ManeuverMapper.M_ARRIVE
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
