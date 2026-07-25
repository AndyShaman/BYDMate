package com.bydmate.app.navdata

/** Yandex Navigator maneuver -> GAODE code, three input forms: a11y balloon description,
 *  notification icon resource name, and back to a short Russian phrase for the voice agent.
 *  Ported from @rbgboost's YandexHUD (field-tested on DiLink 5); the RU phrase tables are
 *  kept verbatim, only camera/traffic-light paths were dropped. */
object NavManeuverCodes {
    const val GAODE_LEFT = 1
    const val GAODE_RIGHT = 2
    const val GAODE_SLIGHT_LEFT = 3
    const val GAODE_SLIGHT_RIGHT = 4
    const val GAODE_HARD_LEFT = 7
    const val GAODE_HARD_RIGHT = 8
    const val GAODE_UTURN = 9
    const val GAODE_UTURN_RIGHT = 10
    const val GAODE_STRAIGHT = 11
    const val GAODE_ROUNDABOUT_ENTER = 13
    const val GAODE_ROUNDABOUT_EXIT = 24
    const val GAODE_WAYPOINT = 45
    const val GAODE_FERRY = 46
    const val GAODE_ARRIVE = 48
    const val GAODE_TUNNEL = 49
    const val GAODE_TOLL = 47

    private val ROUNDABOUT_EXIT_RE = Regex("""(\d+)[-‑]й\s+съезд""")

    fun fromA11yDescription(text: String?): Int {
        if (text == null || text.isBlank()) return 0
        if (text == ">>>") return GAODE_STRAIGHT
        // NBSP via explicit escape: a literal NBSP is invisible and gets lost in copy/transcription
        val lower = text.lowercase().trim().replace('\u00A0', ' ')

        val exitNum = ROUNDABOUT_EXIT_RE.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        // AutoNavi CCW_N_EXIT = 24+N (right-hand traffic); flat 24 only when the
        // exit number is missing or out of the 1..10 icon range.
        if (exitNum != null) return if (exitNum in 1..10) GAODE_ROUNDABOUT_EXIT + exitNum else GAODE_ROUNDABOUT_EXIT

        return when {
            "въезд на паром" in lower -> GAODE_FERRY
            "кольцевое" in lower || "круговое" in lower -> GAODE_ROUNDABOUT_ENTER
            "выезд с кольца" in lower || "съезд с кольца" in lower -> GAODE_ROUNDABOUT_EXIT
            "промежуточная точка" in lower -> GAODE_WAYPOINT
            "съезд с парома" in lower || "выезд с парома" in lower -> GAODE_STRAIGHT
            "прибытие" in lower || "маршрут окончен" in lower || "конечная" in lower || "достигнут" in lower -> GAODE_ARRIVE
            "тоннель" in lower || "туннель" in lower -> GAODE_TUNNEL
            "плавный поворот налево" in lower || "плавно налево" in lower || "держитесь левее" in lower -> GAODE_SLIGHT_LEFT
            "плавный поворот направо" in lower || "плавно направо" in lower || "держитесь правее" in lower -> GAODE_SLIGHT_RIGHT
            "резкий поворот налево" in lower || "резко налево" in lower -> GAODE_HARD_LEFT
            "резкий поворот направо" in lower || "резко направо" in lower -> GAODE_HARD_RIGHT
            "разворот" in lower || "развернитесь" in lower ->
                if ("направо" in lower) GAODE_UTURN_RIGHT else GAODE_UTURN
            "поверните налево" in lower || "поворот налево" in lower || "налево" in lower -> GAODE_LEFT
            "поверните направо" in lower || "поворот направо" in lower || "направо" in lower -> GAODE_RIGHT
            "прямо" in lower || "продолжайте" in lower || "двигайтесь" in lower -> GAODE_STRAIGHT
            else -> fromRussianTextFallback(lower)
        }
    }

    /** Notification maneuver icon resource name -> GAODE (donor YANDEX_MANEUVER_RES
     *  collapsed through toGaode; board_ferry variant seen on the 2025 Navigator). */
    private val NOTIFICATION_RES = mapOf(
        "notification_straight_sdl" to GAODE_STRAIGHT,
        "notification_left_sdl" to GAODE_LEFT,
        "notification_right_sdl" to GAODE_RIGHT,
        "notification_slight_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_slight_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_hard_left_sdl" to GAODE_HARD_LEFT,
        "notification_hard_right_sdl" to GAODE_HARD_RIGHT,
        "notification_fork_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_fork_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_uturn_left_sdl" to GAODE_UTURN,
        "notification_uturn_right_sdl" to GAODE_UTURN_RIGHT,
        "notification_exit_left_sdl" to GAODE_HARD_LEFT,
        "notification_exit_right_sdl" to GAODE_HARD_RIGHT,
        "notification_enter_roundabout_sdl" to GAODE_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to GAODE_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to GAODE_ARRIVE,
        "notification_ferry_sdl" to GAODE_FERRY,
        "notification_board_ferry_sdl" to GAODE_FERRY,
    )

    fun fromNotificationRes(resName: String?): Int =
        resName?.let { NOTIFICATION_RES[it] } ?: 0

    /** GAODE -> short Russian phrase; used by get_route_info when only hub numerics exist. */
    private val PHRASES = mapOf(
        GAODE_LEFT to "налево",
        GAODE_RIGHT to "направо",
        GAODE_SLIGHT_LEFT to "левее",
        GAODE_SLIGHT_RIGHT to "правее",
        GAODE_HARD_LEFT to "резко налево",
        GAODE_HARD_RIGHT to "резко направо",
        GAODE_UTURN to "разворот",
        GAODE_UTURN_RIGHT to "разворот направо",
        GAODE_STRAIGHT to "прямо",
        GAODE_ROUNDABOUT_ENTER to "круговое движение",
        GAODE_ROUNDABOUT_EXIT to "съезд с кольца",
        GAODE_WAYPOINT to "промежуточная точка",
        GAODE_FERRY to "паром",
        GAODE_ARRIVE to "прибытие",
        GAODE_TUNNEL to "тоннель",
    )

    fun gaodePhrase(gaode: Int): String? = PHRASES[gaode]

    // -- fallback: donor's internal-enum phrase table, collapsed straight to GAODE --

    private val RU_PHRASES = linkedMapOf(
        "развернитесь направо" to GAODE_UTURN_RIGHT,
        "разворот направо" to GAODE_UTURN_RIGHT,
        "развернитесь налево" to GAODE_UTURN,
        "развернитесь" to GAODE_UTURN,
        "разворот" to GAODE_UTURN,
        "u-turn" to GAODE_UTURN,
        "резкий поворот налево" to GAODE_HARD_LEFT,
        "резко налево" to GAODE_HARD_LEFT,
        "резкий поворот направо" to GAODE_HARD_RIGHT,
        "резко направо" to GAODE_HARD_RIGHT,
        "плавный поворот налево" to GAODE_SLIGHT_LEFT,
        "плавно налево" to GAODE_SLIGHT_LEFT,
        "держитесь левее" to GAODE_SLIGHT_LEFT,
        "плавный поворот направо" to GAODE_SLIGHT_RIGHT,
        "плавно направо" to GAODE_SLIGHT_RIGHT,
        "держитесь правее" to GAODE_SLIGHT_RIGHT,
        "поверните налево" to GAODE_LEFT,
        "поворот налево" to GAODE_LEFT,
        "налево" to GAODE_LEFT,
        "левее" to GAODE_SLIGHT_LEFT,
        "правее" to GAODE_SLIGHT_RIGHT,
        "поверните направо" to GAODE_RIGHT,
        "поворот направо" to GAODE_RIGHT,
        "направо" to GAODE_RIGHT,
        "въезжайте на кольцо" to GAODE_ROUNDABOUT_ENTER,
        "войдите в кольцо" to GAODE_ROUNDABOUT_ENTER,
        "съезжайте с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезжайте из кольца" to GAODE_ROUNDABOUT_EXIT,
        "съезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "въезд на паром" to GAODE_FERRY,
        "вы прибыли" to GAODE_ARRIVE,
        "маршрут завершён" to GAODE_ARRIVE,
        "до конца маршрута" to GAODE_ARRIVE,
        "конец маршрута" to GAODE_ARRIVE,
        "конечная" to GAODE_ARRIVE,
        "достигнут" to GAODE_ARRIVE,
        "прибытие" to GAODE_ARRIVE,
        "прямо" to GAODE_STRAIGHT,
        "продолжайте прямо" to GAODE_STRAIGHT,
        "продолжить" to GAODE_STRAIGHT,
        "двигайтесь прямо" to GAODE_STRAIGHT,
    )

    private val WORD_BOUNDARY_PHRASES = linkedMapOf(
        "левый" to GAODE_LEFT,
        "правый" to GAODE_RIGHT,
        "паром" to GAODE_FERRY,
        "кольцо" to GAODE_ROUNDABOUT_ENTER,
        "круговое" to GAODE_ROUNDABOUT_ENTER,
        "туннель" to GAODE_TUNNEL,
        "тоннель" to GAODE_TUNNEL,
    )

    private fun fromRussianTextFallback(lower: String): Int {
        // lower is already NBSP-normalized by fromA11yDescription
        val norm = lower.replace(Regex("\\s+"), " ")
        for ((phrase, code) in RU_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in WORD_BOUNDARY_PHRASES) {
            if (Regex("""(?:^|\s|[\p{Punct}])${Regex.escape(phrase)}(?:$|\s|[\p{Punct}])""").containsMatchIn(norm)) return code
        }
        return 0
    }

    // -- donor rich-notification mappings (RemoteViewsParser/ManeuverMapper port) --

    private val ROAD_ALERT_RES = mapOf(
        "road_alerts_camera_32" to "camera",
        "road_alerts_accident_32" to "accident",
        "road_alerts_road_works_32" to "roadworks",
        "road_alerts_other_32" to "other",
    )

    /** Yandex road-alert drawable name -> alert kind; "" when not an alert icon. */
    fun roadAlertFromRes(resName: String): String = ROAD_ALERT_RES[resName] ?: ""

    private val SERVICE_PHRASES = setOf(
        "камера контроля скорости", "направо", "налево",
        "почти на месте", "кольцевое движение",
    )

    /** Donor's service-phrase filter: such texts are never a street name. */
    fun isServicePhrase(text: String): Boolean = SERVICE_PHRASES.any { it in text.lowercase() }

    /** Donor word-boundary table for the rich path. Differs from WORD_BOUNDARY_PHRASES:
     *  a bare "съезд" deliberately maps to unknown (stops the scan), toll words map to 47. */
    private val RICH_WORD_BOUNDARY = linkedMapOf(
        "левый" to GAODE_LEFT,
        "правый" to GAODE_RIGHT,
        "съезд" to 0,
        "паром" to GAODE_FERRY,
        "кольцо" to GAODE_ROUNDABOUT_ENTER,
        "круговое" to GAODE_ROUNDABOUT_ENTER,
        "туннель" to GAODE_TUNNEL,
        "тоннель" to GAODE_TUNNEL,
        "платный" to GAODE_TOLL,
        "пошлина" to GAODE_TOLL,
    )

    /** Donor ManeuverMapper.fromRussianText collapsed straight to GAODE; 0 = not a maneuver.
     *  Used by the rich notification path only (fromA11yDescription stays byte-identical). */
    fun richPhraseGaode(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val norm = text.lowercase().trim().replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
        for ((phrase, code) in RU_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in RICH_WORD_BOUNDARY) {
            if (Regex("""(?:^|\s|[\p{Punct}])${Regex.escape(phrase)}(?:$|\s|[\p{Punct}])""").containsMatchIn(norm)) return code
        }
        return 0
    }

    /** Donor ManeuverMapper EN_ICON_NAMES collapsed through toGaode. */
    private val RICH_ICON_NAMES = mapOf(
        "notification_straight_sdl" to GAODE_STRAIGHT,
        "notification_go_ahead_sdl" to GAODE_STRAIGHT,
        "notification_left_sdl" to GAODE_LEFT,
        "notification_right_sdl" to GAODE_RIGHT,
        "notification_hard_left_sdl" to GAODE_HARD_LEFT,
        "notification_hard_right_sdl" to GAODE_HARD_RIGHT,
        "notification_slight_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_slight_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_uturn_left_sdl" to GAODE_UTURN,
        "notification_uturn_right_sdl" to GAODE_UTURN_RIGHT,
        "notification_uturn_sdl" to GAODE_UTURN,
        "notification_fork_left_sdl" to GAODE_SLIGHT_LEFT,
        "notification_fork_right_sdl" to GAODE_SLIGHT_RIGHT,
        "notification_exit_left_sdl" to GAODE_HARD_LEFT,
        "notification_exit_right_sdl" to GAODE_HARD_RIGHT,
        "notification_enter_roundabout_sdl" to GAODE_ROUNDABOUT_ENTER,
        "notification_leave_roundabout_sdl" to GAODE_ROUNDABOUT_EXIT,
        "notification_finish_sdl" to GAODE_ARRIVE,
        "notification_arrive_sdl" to GAODE_ARRIVE,
        "notification_board_ferry_sdl" to GAODE_FERRY,
        "notification_leave_ferry_sdl" to GAODE_FERRY,
        "notification_ferry_sdl" to GAODE_FERRY,
        "direction_straight" to GAODE_STRAIGHT,
        "direction_left" to GAODE_LEFT,
        "direction_right" to GAODE_RIGHT,
        "direction_slight_left" to GAODE_SLIGHT_LEFT,
        "direction_slight_right" to GAODE_SLIGHT_RIGHT,
        "direction_hard_left" to GAODE_HARD_LEFT,
        "direction_hard_right" to GAODE_HARD_RIGHT,
        "direction_uturn" to GAODE_UTURN,
        "direction_roundabout" to GAODE_ROUNDABOUT_ENTER,
        "direction_arrive" to GAODE_ARRIVE,
        "direction_ferry" to GAODE_FERRY,
        "navigation_straight" to GAODE_STRAIGHT,
        "navigation_left" to GAODE_LEFT,
        "navigation_right" to GAODE_RIGHT,
        "navigation_slight_left" to GAODE_SLIGHT_LEFT,
        "navigation_slight_right" to GAODE_SLIGHT_RIGHT,
        "navigation_hard_left" to GAODE_HARD_LEFT,
        "navigation_hard_right" to GAODE_HARD_RIGHT,
        "navigation_uturn" to GAODE_UTURN,
        "navigation_roundabout" to GAODE_ROUNDABOUT_ENTER,
        "navigation_arrive" to GAODE_ARRIVE,
        "navigation_fork_left" to GAODE_SLIGHT_LEFT,
        "navigation_fork_right" to GAODE_SLIGHT_RIGHT,
    )

    /** Donor ManeuverMapper.fromIconName collapsed to GAODE; extras-fallback smallIcon path. */
    fun richIconNameGaode(name: String): Int {
        if (name.isEmpty()) return 0
        val lower = name.lowercase().removeSuffix(".xml")
        richPhraseGaode(lower).takeIf { it != 0 }?.let { return it }
        RICH_ICON_NAMES[lower]?.let { return it }
        return when {
            lower.contains("straight") || lower.contains("go_ahead") -> GAODE_STRAIGHT
            lower.contains("hard_left") -> GAODE_HARD_LEFT
            lower.contains("hard_right") -> GAODE_HARD_RIGHT
            lower.contains("slight_left") -> GAODE_SLIGHT_LEFT
            lower.contains("slight_right") -> GAODE_SLIGHT_RIGHT
            lower.contains("uturn_right") || lower.contains("right_uturn") -> GAODE_UTURN_RIGHT
            lower.contains("uturn") -> GAODE_UTURN
            lower.contains("fork_left") -> GAODE_SLIGHT_LEFT
            lower.contains("fork_right") -> GAODE_SLIGHT_RIGHT
            lower.contains("exit_left") -> GAODE_HARD_LEFT
            lower.contains("exit_right") -> GAODE_HARD_RIGHT
            lower.contains("roundabout") -> GAODE_ROUNDABOUT_ENTER
            lower.contains("finish") || lower.contains("arrive") || lower.contains("destination") -> GAODE_ARRIVE
            lower.contains("ferry") -> GAODE_FERRY
            lower.contains("left") -> GAODE_LEFT
            lower.contains("right") -> GAODE_RIGHT
            lower.contains("forward") || lower.contains("ahead") -> GAODE_STRAIGHT
            else -> 0
        }
    }
}
