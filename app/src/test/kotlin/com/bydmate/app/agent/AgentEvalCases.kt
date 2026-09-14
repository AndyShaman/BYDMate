package com.bydmate.app.agent

/**
 * What the agent is expected to do with one driver utterance.
 *
 * [ACT] — a tool call is the correct answer, and [tool] / [args] say which one.
 * [NO_ACT] — talking, refusing, thinking out loud or taking it back: the agent must answer in
 * words and touch nothing in the car. [AUTOMATION] — a rule is being discussed; either
 * create_automation with the right shape, or, for the trap cases, ONE clarifying question and no
 * rule at all (expressed as tool = null).
 */
internal enum class EvalKind { ACT, NO_ACT, AUTOMATION }

internal data class EvalCase(
    val utterance: String,
    val kind: EvalKind,
    /** Tool the model must call first; null means "no tool call at all". */
    val tool: String? = null,
    /** Substrings that must appear in the tool arguments (command ids, field names). */
    val args: List<String> = emptyList(),
    /** Why this case is in the set — what it would catch if it regressed. */
    val note: String,
)

/**
 * The eval set: real phrasings a driver uses, in the register they use it in. Half of the value
 * is in the cases where the right move is to do nothing — those are the ones that turn into
 * «сказал готово, а ничего не просил» complaints.
 */
internal object AgentEvalCases {

    val ALL: List<EvalCase> = listOf(
        // ── Act: the driver asked for something concrete ──────────────────────────
        EvalCase("закрой окна", EvalKind.ACT, "vehicle_control", listOf("windows_close_all"),
            note = "the single most common command; must not become a per-window loop"),
        EvalCase("приоткрой окно водителя на проветривание", EvalKind.ACT, "vehicle_control",
            listOf("window_driver_vent"), note = "vent detent, not open"),
        EvalCase("сделай потеплее, поставь двадцать два", EvalKind.ACT, "vehicle_control",
            listOf("ac_set_temp"), note = "value-carrying climate command"),
        EvalCase("включи подогрев сиденья на тройку", EvalKind.ACT, "vehicle_control",
            listOf("seat_heat_driver_3"), note = "colloquial level; driver seat is the default"),
        EvalCase("дует в ноги, переключи на лицо", EvalKind.ACT, "vehicle_control",
            listOf("ac_wind_face"), note = "blow direction, wave 4 made it readable too"),
        EvalCase("закрой люк", EvalKind.ACT, "vehicle_control", listOf("sunroof_close"),
            note = "sunroof must not be confused with windows"),
        EvalCase("запри машину", EvalKind.ACT, "vehicle_control", listOf("doors_lock"),
            note = "locks"),
        EvalCase("какой заряд", EvalKind.ACT, "get_vehicle_state",
            note = "read, not guess: the number must come from the tool"),
        EvalCase("сколько я проехал за неделю", EvalKind.ACT, "query_trips",
            note = "trip statistics, not vehicle state"),
        EvalCase("сколько потратил на зарядки в этом месяце", EvalKind.ACT, "query_charges",
            note = "charge statistics with a period"),
        EvalCase("какая завтра погода", EvalKind.ACT, "get_weather", note = "weather tool exists"),
        EvalCase("поехали домой", EvalKind.ACT, "navigate_to", note = "navigation to a saved place"),
        EvalCase("найди зарядку поблизости", EvalKind.ACT, "find_chargers", note = "charger search"),
        EvalCase("включи на ютубе подкаст про машины", EvalKind.ACT, "youtube", listOf("подкаст"),
            note = "media tool, query passed through"),
        EvalCase("выведи навигатор на приборку", EvalKind.ACT, "set_cluster_projection",
            note = "cluster projection, wave 3 made its result honest"),

        // ── Don't act: words, doubts and take-backs ───────────────────────────────
        EvalCase("да ну что за пробки сегодня", EvalKind.NO_ACT,
            note = "thinking out loud; nothing to do"),
        EvalCase("открой окно... хотя нет, не надо, холодно", EvalKind.NO_ACT,
            note = "take-back inside one utterance: the last word wins"),
        EvalCase("не открывай окна", EvalKind.NO_ACT,
            note = "negation must not be read as the command it negates"),
        EvalCase("окна открывать не нужно, я сам", EvalKind.NO_ACT,
            note = "negation with a command word at the front"),
        EvalCase("а ты умеешь мыть машину", EvalKind.NO_ACT,
            note = "capability question: answer honestly, invent nothing"),
        EvalCase("сколько стоит бензин", EvalKind.NO_ACT,
            note = "out of scope for an electric-side app; must not be answered from tools"),
        EvalCase("расскажи анекдот", EvalKind.NO_ACT, note = "small talk, no tool"),
        EvalCase("напомни, что я просил вчера", EvalKind.NO_ACT,
            note = "yesterday is deliberately not in the day memory (wave 5)"),

        // ── Automations, traps included ───────────────────────────────────────────
        EvalCase("создай правило: если заряд меньше двадцати процентов, закрой все окна",
            EvalKind.AUTOMATION, "create_automation", listOf("SOC", "20", "windows_close_all"),
            note = "fully specified rule: trigger, threshold and action are all present"),
        EvalCase("сделай так, чтобы при въезде домой включался климат",
            EvalKind.AUTOMATION, "create_automation", listOf("place_enter"),
            note = "place trigger; the place has to be looked up, not invented"),
        EvalCase("каждый будний день в 7:30 прогревай салон", EvalKind.AUTOMATION,
            "create_automation", listOf("time_range", "07:30"),
            note = "schedule trigger with weekdays"),
        EvalCase("покажи мои автоматизации", EvalKind.AUTOMATION, "list_automations",
            note = "listing is not creating"),
        EvalCase("выключи автоматизацию ночной режим", EvalKind.AUTOMATION,
            "set_automation_enabled", listOf("ночной режим"),
            note = "toggling an existing rule by name"),
        EvalCase("сделай что-нибудь, когда станет холодно", EvalKind.AUTOMATION, tool = null,
            note = "trap: no threshold and no action — ONE question, never a guessed rule"),
        EvalCase("настрой автоматизацию на низкий заряд", EvalKind.AUTOMATION, tool = null,
            note = "trap: «низкий» is not a number — ask, do not pick 20 silently"),
    )

    val ACT = ALL.filter { it.kind == EvalKind.ACT }
    val NO_ACT = ALL.filter { it.kind == EvalKind.NO_ACT }
    val AUTOMATION = ALL.filter { it.kind == EvalKind.AUTOMATION }
}
