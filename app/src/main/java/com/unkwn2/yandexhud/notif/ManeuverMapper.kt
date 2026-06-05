package com.unkwn2.yandexhud.notif

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
}
