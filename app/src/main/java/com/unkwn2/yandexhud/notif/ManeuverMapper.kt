package com.unkwn2.yandexhud.notif

import com.unkwn2.yandexhud.bridge.HudState

object ManeuverMapper {
    const val M_UNKNOWN = 0
    const val M_STRAIGHT = 9
    const val M_LEFT = 2
    const val M_RIGHT = 3
    const val M_HARD_LEFT = 4
    const val M_HARD_RIGHT = 5
    const val M_SLIGHT_LEFT = 6
    const val M_SLIGHT_RIGHT = 7
    const val M_UTURN_LEFT = 10
    const val M_UTURN_RIGHT = 11
    const val M_ARRIVE = 12
    const val M_FORK_LEFT = 13
    const val M_FORK_RIGHT = 14
    const val M_EXIT_LEFT = 15
    const val M_EXIT_RIGHT = 16
    const val M_ROUNDABOUT_ENTER = 17
    const val M_ROUNDABOUT_EXIT = 18
    const val M_FERRY = 19
    const val M_TUNNEL = 20
    const val M_TOLL = 21

    private val RU_PHRASES = linkedMapOf(
        "развернитесь направо" to M_UTURN_RIGHT,
        "разворот направо" to M_UTURN_RIGHT,
        "развернитесь налево" to M_UTURN_LEFT,
        "развернитесь" to M_UTURN_LEFT,
        "разворот" to M_UTURN_LEFT,
        "u-turn" to M_UTURN_LEFT,
        "резкий поворот налево" to M_HARD_LEFT,
        "резко налево" to M_HARD_LEFT,
        "резкий поворот направо" to M_HARD_RIGHT,
        "резко направо" to M_HARD_RIGHT,
        "плавный поворот налево" to M_SLIGHT_LEFT,
        "плавно налево" to M_SLIGHT_LEFT,
        "держитесь левее" to M_SLIGHT_LEFT,
        "плавный поворот направо" to M_SLIGHT_RIGHT,
        "плавно направо" to M_SLIGHT_RIGHT,
        "держитесь правее" to M_SLIGHT_RIGHT,
        "поверните налево" to M_LEFT,
        "поворот налево" to M_LEFT,
        "налево" to M_LEFT,
        "левый" to M_LEFT,
        "левее" to M_SLIGHT_LEFT,
        "правее" to M_SLIGHT_RIGHT,
        "поверните направо" to M_RIGHT,
        "поворот направо" to M_RIGHT,
        "направо" to M_RIGHT,
        "правый" to M_RIGHT,
        "въезжайте на кольцо" to M_ROUNDABOUT_ENTER,
        "войдите в кольцо" to M_ROUNDABOUT_ENTER,
        "круговое" to M_ROUNDABOUT_ENTER,
        "кольцо" to M_ROUNDABOUT_ENTER,
        "съезжайте с кольца" to M_ROUNDABOUT_EXIT,
        "выезжайте из кольца" to M_ROUNDABOUT_EXIT,
        "съезд" to M_EXIT_RIGHT,
        "въезд на паром" to M_FERRY,
        "паром" to M_FERRY,
        "тоннель" to M_TUNNEL,
        "туннель" to M_TUNNEL,
        "платный" to M_TOLL,
        "пошлина" to M_TOLL,
        "вы прибыли" to M_ARRIVE,
        "маршрут завершён" to M_ARRIVE,
        "до конца маршрута" to M_ARRIVE,
        "конец маршрута" to M_ARRIVE,
        "конечная" to M_ARRIVE,
        "достигнут" to M_ARRIVE,
        "прибытие" to M_ARRIVE,
        "прямо" to M_STRAIGHT,
        "продолжайте прямо" to M_STRAIGHT,
        "продолжить" to M_STRAIGHT,
        "двигайтесь прямо" to M_STRAIGHT
    )

    private val EN_ICON_NAMES = mapOf(
        "notification_straight_sdl" to M_STRAIGHT,
        "notification_go_ahead_sdl" to M_STRAIGHT,
        "notification_left_sdl" to M_LEFT,
        "notification_right_sdl" to M_RIGHT,
        "notification_hard_left_sdl" to M_HARD_LEFT,
        "notification_hard_right_sdl" to M_HARD_RIGHT,
        "notification_slight_left_sdl" to M_SLIGHT_LEFT,
        "notification_slight_right_sdl" to M_SLIGHT_RIGHT,
        "notification_uturn_left_sdl" to M_UTURN_LEFT,
        "notification_uturn_right_sdl" to M_UTURN_RIGHT,
        "notification_uturn_sdl" to M_UTURN_LEFT,
        "notification_fork_left_sdl" to M_FORK_LEFT,
        "notification_fork_right_sdl" to M_FORK_RIGHT,
        "notification_exit_left_sdl" to M_EXIT_LEFT,
        "notification_exit_right_sdl" to M_EXIT_RIGHT,
        "notification_enter_roundabout_sdl" to M_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to M_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to M_ARRIVE,
        "notification_arrive_sdl" to M_ARRIVE,
        "notification_board_ferry_sdl" to M_FERRY,
        "notification_leave_ferry_sdl" to M_FERRY,
        "notification_ferry_sdl" to M_FERRY,
        "direction_straight" to M_STRAIGHT,
        "direction_left" to M_LEFT,
        "direction_right" to M_RIGHT,
        "direction_slight_left" to M_SLIGHT_LEFT,
        "direction_slight_right" to M_SLIGHT_RIGHT,
        "direction_hard_left" to M_HARD_LEFT,
        "direction_hard_right" to M_HARD_RIGHT,
        "direction_uturn" to M_UTURN_LEFT,
        "direction_roundabout" to M_ROUNDABOUT_ENTER,
        "direction_arrive" to M_ARRIVE,
        "direction_ferry" to M_FERRY,
        "navigation_straight" to M_STRAIGHT,
        "navigation_left" to M_LEFT,
        "navigation_right" to M_RIGHT,
        "navigation_slight_left" to M_SLIGHT_LEFT,
        "navigation_slight_right" to M_SLIGHT_RIGHT,
        "navigation_hard_left" to M_HARD_LEFT,
        "navigation_hard_right" to M_HARD_RIGHT,
        "navigation_uturn" to M_UTURN_LEFT,
        "navigation_roundabout" to M_ROUNDABOUT_ENTER,
        "navigation_arrive" to M_ARRIVE,
        "navigation_fork_left" to M_FORK_LEFT,
        "navigation_fork_right" to M_FORK_RIGHT
    )

    fun fromRussianText(text: String): Int {
        val lower = text.lowercase().trim()
            .replace('\u00A0', ' ')        // normalize non-breaking space
            .replace(Regex("\\s+"), " ")   // collapse multiple spaces
        for ((phrase, m) in RU_PHRASES) {
            if (phrase in lower) return m
        }
        return M_UNKNOWN
    }

    fun fromIconName(name: String): Int {
        if (name.isEmpty()) return M_UNKNOWN
        val lower = name.lowercase().removeSuffix(".xml")

        val ru = fromRussianText(lower)
        if (ru != M_UNKNOWN) return ru

        val exact = EN_ICON_NAMES[lower]
        if (exact != null) return exact

        return when {
            lower.contains("straight") || lower.contains("go_ahead") -> M_STRAIGHT
            lower.contains("hard_left") -> M_HARD_LEFT
            lower.contains("hard_right") -> M_HARD_RIGHT
            lower.contains("slight_left") -> M_SLIGHT_LEFT
            lower.contains("slight_right") -> M_SLIGHT_RIGHT
            lower.contains("uturn_right") || lower.contains("right_uturn") -> M_UTURN_RIGHT
            lower.contains("uturn") -> M_UTURN_LEFT
            lower.contains("fork_left") -> M_FORK_LEFT
            lower.contains("fork_right") -> M_FORK_RIGHT
            lower.contains("exit_left") -> M_EXIT_LEFT
            lower.contains("exit_right") -> M_EXIT_RIGHT
            lower.contains("roundabout") -> M_ROUNDABOUT_ENTER
            lower.contains("finish") || lower.contains("arrive") || lower.contains("destination") -> M_ARRIVE
            lower.contains("ferry") -> M_FERRY
            lower.contains("left") -> M_LEFT
            lower.contains("right") -> M_RIGHT
            lower.contains("forward") || lower.contains("ahead") -> M_STRAIGHT
            else -> M_UNKNOWN
        }
    }

    // --- Детерминированные таблицы для RemoteViewsActionExtractor ---
    // primaryIconTinted -> setImageResource(name): манёвр Яндекса
    private val YANDEX_MANEUVER_RES = mapOf(
        "notification_straight_sdl" to M_STRAIGHT,
        "notification_left_sdl" to M_LEFT,
        "notification_right_sdl" to M_RIGHT,
        "notification_slight_left_sdl" to M_SLIGHT_LEFT,
        "notification_slight_right_sdl" to M_SLIGHT_RIGHT,
        "notification_hard_left_sdl" to M_HARD_LEFT,
        "notification_hard_right_sdl" to M_HARD_RIGHT,
        "notification_fork_left_sdl" to M_FORK_LEFT,
        "notification_fork_right_sdl" to M_FORK_RIGHT,
        "notification_uturn_left_sdl" to M_UTURN_LEFT,
        "notification_uturn_right_sdl" to M_UTURN_RIGHT,
        "notification_exit_left_sdl" to M_EXIT_LEFT,
        "notification_exit_right_sdl" to M_EXIT_RIGHT,
        "notification_enter_roundabout_sdl" to M_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to M_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to M_ARRIVE,
        "notification_ferry_sdl" to M_FERRY,
    )

    // primaryIcon -> setImageResource(name): тип дорожного события
    private val YANDEX_ROAD_ALERT_RES = mapOf(
        "road_alerts_camera_32" to "camera",
        "road_alerts_accident_32" to "accident",
        "road_alerts_road_works_32" to "roadworks",
        "road_alerts_other_32" to "other",
    )

    fun fromYandexRes(resName: String): Int = YANDEX_MANEUVER_RES[resName] ?: M_UNKNOWN

    fun roadAlertFromRes(resName: String): String = YANDEX_ROAD_ALERT_RES[resName] ?: ""

    fun trafficColorFromBgRes(resName: String): String? = when {
        "traffic_light_background_red" in resName -> "red"
        "traffic_light_background_yellow" in resName -> "yellow"
        "traffic_light_background_green" in resName -> "green"
        else -> null
    }

    /** Служебные строки descriptionView, которые не должны быть дорогой */
    private val SERVICE_PHRASES = setOf(
        "камера контроля скорости", "направо", "налево",
        "почти на месте", "кольцевое движение"
    )
    fun isServicePhrase(text: String): Boolean = SERVICE_PHRASES.any { it in text.lowercase() }

    fun maneuverName(code: Int): String = when (code) {
        M_UNKNOWN -> "NONE"
        M_STRAIGHT -> "STRAIGHT"
        M_LEFT -> "LEFT"
        M_RIGHT -> "RIGHT"
        M_HARD_LEFT -> "HARD_LEFT"
        M_HARD_RIGHT -> "HARD_RIGHT"
        M_SLIGHT_LEFT -> "SLIGHT_LEFT"
        M_SLIGHT_RIGHT -> "SLIGHT_RIGHT"
        M_UTURN_LEFT -> "UTURN_LEFT"
        M_UTURN_RIGHT -> "UTURN_RIGHT"
        M_ARRIVE -> "ARRIVE"
        M_FORK_LEFT -> "FORK_LEFT"
        M_FORK_RIGHT -> "FORK_RIGHT"
        M_EXIT_LEFT -> "EXIT_LEFT"
        M_EXIT_RIGHT -> "EXIT_RIGHT"
        M_ROUNDABOUT_ENTER -> "ROUNDABOUT_IN"
        M_ROUNDABOUT_EXIT -> "ROUNDABOUT_OUT"
        M_FERRY -> "FERRY"
        M_TUNNEL -> "TUNNEL"
        M_TOLL -> "TOLL"
        else -> "UNKNOWN($code)"
    }

    fun toGaode(m: Int): Int = when (m) {
        M_LEFT -> 1; M_RIGHT -> 2
        M_SLIGHT_LEFT -> 3; M_SLIGHT_RIGHT -> 4   // 3, не 5 — текстуры 0x5 нет в прошивке
        M_FORK_LEFT -> 3; M_FORK_RIGHT -> 4        // FORK_LEFT тоже 3 (текстуры 0x5 нет)
        M_HARD_LEFT -> 7; M_HARD_RIGHT -> 8
        M_EXIT_LEFT -> 7; M_EXIT_RIGHT -> 8
        M_UTURN_LEFT -> 9; M_UTURN_RIGHT -> 10
        M_STRAIGHT -> 11; M_ARRIVE -> 48
        M_ROUNDABOUT_ENTER -> 13; M_ROUNDABOUT_EXIT -> 24
        M_FERRY -> 46; M_TUNNEL -> 49; M_TOLL -> 47
        else -> 0
    }

    // GAODE-коды напрямую для A11y contentDescription (f28)
    private const val GAODE_STRAIGHT = 11
    private const val GAODE_LEFT = 1
    private const val GAODE_RIGHT = 2
    private const val GAODE_SLIGHT_LEFT = 3
    private const val GAODE_SLIGHT_RIGHT = 4
    private const val GAODE_HARD_LEFT = 7
    private const val GAODE_HARD_RIGHT = 8
    private const val GAODE_UTURN = 9
    private const val GAODE_ROUNDABOUT_ENTER = 13
    private const val GAODE_ARRIVE = 48
    private const val GAODE_TUNNEL = 49
    private const val GAODE_WAYPOINT = 45
    private const val GAODE_ROUNDABOUT_EXIT_BASE = 24

    private val ROUNDABOUT_EXIT = Regex("""(\d+)[-‑]й\s+съезд""")

    fun fromA11yDescription(text: String?): Int {
        if (text == null || text.isBlank()) return 0   // 0 = unknown, not STRAIGHT
        if (text == ">>>") return GAODE_STRAIGHT
        val lower = text.lowercase().trim()

        val exitMatch = ROUNDABOUT_EXIT.find(lower)
        val exitNum = exitMatch?.groupValues?.get(1)?.toIntOrNull()
        if (exitNum != null && exitNum in 1..10) {
            HudState.update { it.copy(arriveText = "$exitNum-й съезд") }
            return GAODE_ROUNDABOUT_EXIT_BASE
        }

        return when {
            "въезд на паром" in lower -> 46  // GAODE: FERRY
            "кольцевое" in lower || "круговое" in lower -> GAODE_ROUNDABOUT_ENTER
            "выезд с кольца" in lower || "съезд с кольца" in lower -> GAODE_ROUNDABOUT_EXIT_BASE
            "промежуточная точка" in lower -> GAODE_WAYPOINT
            "съезд с парома" in lower || "выезд с парома" in lower -> GAODE_STRAIGHT
            "прибытие" in lower || "маршрут окончен" in lower || "конечная" in lower || "достигнут" in lower -> GAODE_ARRIVE
            "тоннель" in lower || "туннель" in lower -> GAODE_TUNNEL
            "плавный поворот налево" in lower || "плавно налево" in lower || "держитесь левее" in lower -> GAODE_SLIGHT_LEFT
            "плавный поворот направо" in lower || "плавно направо" in lower || "держитесь правее" in lower -> GAODE_SLIGHT_RIGHT
            "резкий поворот налево" in lower || "резко налево" in lower -> GAODE_HARD_LEFT
            "резкий поворот направо" in lower || "резко направо" in lower -> GAODE_HARD_RIGHT
            "разворот" in lower || "развернитесь" in lower -> GAODE_UTURN
            "поверните налево" in lower || "поворот налево" in lower || "налево" in lower -> GAODE_LEFT
            "поверните направо" in lower || "поворот направо" in lower || "направо" in lower -> GAODE_RIGHT
            "прямо" in lower || "продолжайте" in lower || "двигайтесь" in lower -> GAODE_STRAIGHT
            else -> {
                val internal = fromRussianText(text)
                if (internal != M_UNKNOWN) toGaode(internal) else 0
            }
        }
    }
}
