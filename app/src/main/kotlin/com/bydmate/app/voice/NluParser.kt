package com.bydmate.app.voice

import com.bydmate.app.voice.VoiceNormalizer.Extreme
import com.bydmate.app.voice.VoiceNormalizer.Measure
import kotlin.math.abs

sealed interface ParseResult {
    /** One utterance can resolve to several dispatchable commands (plural seats fan
     *  out per side, "кроме" expands per window, "и" joins clauses). Single-command
     *  callers keep using [command]. */
    data class Command(val commands: List<String>) : ParseResult {
        constructor(command: String) : this(listOf(command))
        val command: String? get() = commands.singleOrNull()
    }
    data class RelativeTemp(val sign: Int) : ParseResult
    data class Volume(val payload: String) : ParseResult
    data object Unrecognized : ParseResult
}

/**
 * Offline slot parser for Russian voice commands. Deterministic and pure. Every word it
 * cannot place is ignored EXCEPT measure words: a share or a number it cannot read on a
 * window or the sunroof makes the whole utterance [ParseResult.Unrecognized], never a
 * full open.
 */
object NluParser {

    /** Offline commands are Russian only; [lang] has the single value RU and is not consulted. */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun parse(text: String, lang: VoiceLang): ParseResult {
        val tokens = VoiceNormalizer.tokens(text)
        if (tokens.isEmpty()) return ParseResult.Unrecognized

        // Negation ("не открывай", "нет, не надо") is beyond slot NLU: guessing an
        // affirmative command would do the OPPOSITE of what was said. Agent decides.
        val stems = tokens.map { VoiceStemmer.stem(it) }
        if (VoiceStemmer.stem("не") in stems || VoiceStemmer.stem("нет") in stems) {
            return ParseResult.Unrecognized
        }

        val clauses = splitClauses(tokens)
        if (clauses.size > 1) parseCompound(clauses)?.let { return it }
        return parseClause(tokens)
    }

    /** "закрой окна и люк", "открой люк а также окна": clauses joined by «и» / «а также». */
    private fun splitClauses(tokens: List<String>): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val alsoJoin = t == "а" && tokens.getOrNull(i + 1) == "также"
            if (t == "и" || alsoJoin) parts.add(mutableListOf()) else parts.last().add(t)
            i += if (alsoJoin) 2 else 1
        }
        return parts.filter { it.isNotEmpty() }
    }

    private val actionStems: Set<String> by lazy {
        VoiceLexicon.actionWords().values.flatten().mapTo(HashSet()) { VoiceStemmer.stem(it) }
    }

    private val fullOpenCommands: Set<String> by lazy {
        VoiceCatalog.ALL.filter { it.action == ActionSlot.OPEN && isAperture(it.device) }
            .mapTo(HashSet()) { it.command(null) }
    }

    /**
     * Every clause must parse on its own; a clause without a verb borrows the previous
     * one ("закрой окна и люк"). Otherwise null and the utterance is parsed whole
     * ("открой окна водителя и пассажира" is one target, not two clauses). A share named
     * in one clause next to a full open in another ("открой окна и люк наполовину") is
     * ambiguous and never dispatched.
     */
    private fun parseCompound(clauses: List<List<String>>): ParseResult? {
        var verbs = emptyList<String>()
        val filled = clauses.map { clause ->
            val own = clause.filter { VoiceStemmer.stem(it) in actionStems }
            if (own.isNotEmpty()) verbs = own
            if (own.isEmpty()) verbs + clause else clause
        }
        val parsed = filled.map { (parseClause(it) as? ParseResult.Command)?.commands ?: return null }
        val namesShare = filled.any { VoiceNormalizer.measure(it).let { m -> m.hasShare || m.numberIsShare } }
        if (namesShare && parsed.flatten().any { it in fullOpenCommands }) return null
        return ParseResult.Command(parsed.flatten())
    }

    private fun parseClause(input: List<String>): ParseResult {
        val tokens = VoiceSpelling.correct(input)
        val stems = tokens.map { VoiceStemmer.stem(it) }
        val measure = VoiceNormalizer.measure(tokens)
        val actions = matchSlots(stems, VoiceLexicon.actionWords())
        val devices = pruneDevices(matchSlots(stems, VoiceLexicon.deviceWords()), tokens, stems, actions, measure)
        val qualifiers = detectQualifiers(tokens)

        // "передний багажник" is NOT the rear tailgate; TRUNK has no front variant
        // in the NLU catalog. Hand to the agent (front_trunk_open/close there).
        if (DeviceSlot.TRUNK in devices && Qual.FRONT in qualifiers) return ParseResult.Unrecognized

        // Relative temperature implies the AC; resolved against the live snapshot
        // by VoiceController (the parser stays pure).
        if (ActionSlot.WARMER in actions) return ParseResult.RelativeTemp(1)
        if (ActionSlot.COOLER in actions) return ParseResult.RelativeTemp(-1)

        resolveVolume(actions, devices, measure.numbers.singleOrNull())?.let { return it }
        if (devices.any { isAperture(it) }) return resolveAperture(tokens, actions, devices, measure)
        // "кроме" is expanded for windows only; elsewhere dropping it would do the excluded part too.
        if (EXCEPT in tokens) return ParseResult.Unrecognized
        if (isFan(devices, stems, measure)) return resolveFan(actions, measure)
        // A share ("наполовину", "чуть", "приоткрой") only fits an opening; on anything
        // else the phrase was not understood.
        if (measure.hasShare && measure.extreme == null) return ParseResult.Unrecognized
        return resolveComfort(stems, actions, devices, qualifiers, measure)
    }

    private fun <T> matchSlots(stems: List<String>, words: Map<T, List<String>>): Set<T> {
        val out = LinkedHashSet<T>()
        for ((slot, surfaces) in words) {
            val surfStems = surfaces.map { VoiceStemmer.stem(it) }
            if (stems.any { it in surfStems }) out.add(slot)
        }
        return out
    }

    private val WINDSHIELD_STEM = VoiceStemmer.stem("лобовое")
    private val GLASS_STEM = VoiceStemmer.stem("стекло")
    private val SPEED_STEM = VoiceStemmer.stem("скорость")

    /** "открой водительское": the neuter adjective alone can only mean the window. */
    private val NEUTER_SIDES = setOf("водительское", "пассажирское")
    private val APERTURE_VERBS = setOf(ActionSlot.OPEN, ActionSlot.CLOSE, ActionSlot.VENT)

    private fun pruneDevices(
        devices: Set<DeviceSlot>,
        tokens: List<String>,
        stems: List<String>,
        actions: Set<ActionSlot>,
        measure: Measure,
    ): Set<DeviceSlot> {
        var out = devices
        // "обдув лобового стекла" is the windshield defrost, not a side window.
        val movesGlass = actions.any { it in APERTURE_VERBS } || measure.hasShare
        val windshield = WINDSHIELD_STEM in stems ||
            DeviceSlot.DEFROST_FRONT in devices && GLASS_STEM in stems && !movesGlass
        if (windshield) out = out.filterNotTo(LinkedHashSet()) { isWindow(it) }
        // "шторка люка" names the shade; the sunroof word only says which shade.
        if (DeviceSlot.SUNSHADE in out) out = out - DeviceSlot.SUNROOF
        if (out.isEmpty() && tokens.any { it in NEUTER_SIDES } && actions.any { it in APERTURE_VERBS }) {
            out = setOf(DeviceSlot.WINDOW_ALL)
        }
        return out
    }

    private enum class Qual { DRIVER, PASSENGER, FRONT, REAR, LEFT, RIGHT, ALL }

    private val QUAL_WORDS: Map<Qual, List<String>> = mapOf(
        Qual.DRIVER to listOf("водитель", "водителя", "водительское"),
        Qual.PASSENGER to listOf("пассажир", "пассажира", "пассажирское"),
        Qual.FRONT to listOf("передние", "переднее", "передний", "передняя", "передних", "спереди"),
        Qual.REAR to listOf("задние", "заднее", "задний", "задняя", "задних", "сзади"),
        Qual.LEFT to listOf("левое", "левый", "левая", "слева"),
        Qual.RIGHT to listOf("правое", "правый", "правая", "справа"),
        Qual.ALL to listOf("все", "всех"),
    )

    /** "моё окно", "моего сиденья": the speaker sits in the driver's seat. */
    private val MY_WORDS = setOf("мое", "мой", "моя", "мою", "моего", "моей", "моем", "моему")

    /** ALL positional markers present in the phrase (compound corners need both). */
    private fun detectQualifiers(tokens: List<String>): Set<Qual> {
        val s = tokens.mapTo(HashSet()) { VoiceStemmer.stem(it) }
        val out = QUAL_WORDS.filterValues { words -> words.any { VoiceStemmer.stem(it) in s } }.keys.toMutableSet()
        if (tokens.any { it in MY_WORDS }) out.add(Qual.DRIVER)
        return out
    }

    // ---- Windows and sunroof ------------------------------------------------------------

    private val PLURAL_WINDOWS = setOf(
        "окна", "окон", "окнам", "окнами", "окнах", "стекла", "стекол", "стеклами", "окошки", "окошек", "форточки",
    )

    /** Plural "окна"/"стекла" means every window; "пол окна" is a genitive singular. */
    private fun pluralWindows(tokens: List<String>) =
        tokens.indices.any { i -> tokens[i] in PLURAL_WINDOWS && tokens.getOrNull(i - 1) != "пол" }

    /** Detents each opening supports, as (share, slot), ascending so a tie picks the
     *  smaller opening. Sunroof vent (tilt) reads 7 % on the percent fid. */
    private val WINDOW_STOPS = listOf(10 to ActionSlot.VENT, 50 to ActionSlot.HALF, 100 to ActionSlot.OPEN)
    private val SUNROOF_STOPS = listOf(7 to ActionSlot.VENT, 50 to ActionSlot.HALF, 100 to ActionSlot.OPEN)

    private val APERTURE_ACTIONS = setOf(ActionSlot.OPEN, ActionSlot.CLOSE, ActionSlot.SET, ActionSlot.ON, ActionSlot.VENT)
    private val OPENING_ACTIONS = setOf(ActionSlot.OPEN, ActionSlot.SET, ActionSlot.ON)
    private const val BAD_SHARE = -1
    private const val HALF_SHARE = 50

    private fun resolveAperture(
        tokens: List<String>,
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        measure: Measure,
    ): ParseResult {
        val sunroof = DeviceSlot.SUNROOF in devices
        // "окна и люк" is split into clauses upstream; windows plus anything else here
        // ("окно в салоне") is not a phrase the parser can place.
        val others = devices.filterNot { isAperture(it) || it == DeviceSlot.CAR }
        if (others.isNotEmpty() || sunroof && devices.any { isWindow(it) }) return ParseResult.Unrecognized
        val slot = apertureSlot(actions, measure, if (sunroof) SUNROOF_STOPS else WINDOW_STOPS)
            ?: return ParseResult.Unrecognized
        val targets = when {
            !sunroof -> windowTargets(tokens)
            EXCEPT in tokens -> null
            else -> listOf(DeviceSlot.SUNROOF)
        } ?: return ParseResult.Unrecognized
        val commands = targets.map { VoiceCatalog.resolve(slot, it, null) ?: return ParseResult.Unrecognized }
        return ParseResult.Command(commands)
    }

    /** The detent slot the phrase asks for, or null when the measure or the verbs
     *  cannot be read (never a full open by default). */
    private fun apertureSlot(actions: Set<ActionSlot>, m: Measure, stops: List<Pair<Int, ActionSlot>>): ActionSlot? {
        if (actions.any { it !in APERTURE_ACTIONS }) return null
        val closes = ActionSlot.CLOSE in actions
        if (closes && actions.any { it in OPENING_ACTIONS }) return null
        val share = namedShare(actions, m)
        val target = when {
            share == BAD_SHARE -> return null
            closes -> closingTarget(share) ?: return null
            share == null -> if (ActionSlot.OPEN in actions) VoiceNormalizer.FULL_SHARE else return null
            share == 0 -> return null
            else -> share
        }
        if (target == 0) return ActionSlot.CLOSE
        return stops.minBy { abs(it.first - target) }.second
    }

    /** "закрой" / "закрой до конца" close; "закрой наполовину" leaves half open; any
     *  other share on a closing verb is relative and ambiguous. */
    private fun closingTarget(share: Int?): Int? = when (share) {
        null, 0, VoiceNormalizer.FULL_SHARE -> 0
        HALF_SHARE -> HALF_SHARE
        else -> null
    }

    /** Share of the opening the words name: null = none, [BAD_SHARE] = unreadable or contradictory. */
    private fun namedShare(actions: Set<ActionSlot>, m: Measure): Int? {
        if (unreadableShare(m)) return BAD_SHARE
        val named = LinkedHashSet<Int>()
        m.share?.let { named.add(it) }
        m.numbers.firstOrNull()?.let { if (it in 0..VoiceNormalizer.FULL_SHARE) named.add(it) else return BAD_SHARE }
        if (m.extreme == Extreme.MAX) named.add(VoiceNormalizer.FULL_SHARE)
        if (ActionSlot.VENT in actions) named.add(VoiceNormalizer.VENT_SHARE)
        if (named.size > 1) return BAD_SHARE
        return named.firstOrNull() ?: VoiceNormalizer.VENT_SHARE.takeIf { m.softVent }
    }

    /** A measure that cannot be a share of an opening: an unexplained word, two shares,
     *  a level (ordinal, "минимум"), several numbers or a number that is not a share. */
    private fun unreadableShare(m: Measure): Boolean {
        if (m.unexplained || m.shareConflict || m.ordinal != null) return true
        if (m.extreme == Extreme.MIN || m.numbers.size > 1) return true
        return m.numbers.isNotEmpty() && !m.numberIsShare
    }

    private const val EXCEPT = "кроме"

    private val DOOR_ORDER = listOf(
        DeviceSlot.WINDOW_DRIVER, DeviceSlot.WINDOW_PASSENGER, DeviceSlot.WINDOW_REAR_LEFT, DeviceSlot.WINDOW_REAR_RIGHT,
    )

    /** One window slot, or per-door slots for "кроме X" (never touching X); null when the
     *  exclusion names nothing or leaves nothing. */
    private fun windowTargets(tokens: List<String>): List<DeviceSlot>? {
        val cut = tokens.indexOf(EXCEPT)
        if (cut < 0) return listOf(windowFor(detectQualifiers(tokens), pluralWindows(tokens)))
        val excludedQuals = detectQualifiers(tokens.subList(cut + 1, tokens.size))
        if (excludedQuals.isEmpty()) return null
        val baseQuals = detectQualifiers(tokens.subList(0, cut)) - Qual.ALL
        val base = if (baseQuals.isEmpty()) DeviceSlot.WINDOW_ALL else windowFor(baseQuals, plural = true)
        val kept = doorsOf(base) - doorsOf(windowFor(excludedQuals, plural = false)).toSet()
        return kept.takeIf { it.isNotEmpty() }
    }

    private fun doorsOf(slot: DeviceSlot): List<DeviceSlot> = when (slot) {
        DeviceSlot.WINDOW_ALL -> DOOR_ORDER
        DeviceSlot.WINDOW_FRONT -> DOOR_ORDER.take(2)
        DeviceSlot.WINDOW_REAR -> DOOR_ORDER.drop(2)
        else -> listOf(slot)
    }

    /** Map a qualifier SET to one window. A bare singular "окно"/"моё окно" is the
     *  driver's; plural "окна"/"все" is every window. */
    private fun windowFor(quals: Set<Qual>, plural: Boolean): DeviceSlot =
        sideWindow(quals) ?: positionWindow(quals)
            ?: if (plural || Qual.ALL in quals) DeviceSlot.WINDOW_ALL else DeviceSlot.WINDOW_DRIVER

    private fun sideWindow(quals: Set<Qual>): DeviceSlot? {
        val driver = Qual.DRIVER in quals
        val passenger = Qual.PASSENGER in quals
        return when {
            driver && passenger -> DeviceSlot.WINDOW_FRONT
            driver -> DeviceSlot.WINDOW_DRIVER
            passenger -> DeviceSlot.WINDOW_PASSENGER
            else -> null
        }
    }

    /** Compound corners resolve first; a bare LEFT/RIGHT means the rear pair side
     *  (front sides are named driver/passenger). */
    private fun positionWindow(quals: Set<Qual>): DeviceSlot? {
        val front = Qual.FRONT in quals
        val rear = Qual.REAR in quals
        return when {
            front && rear -> DeviceSlot.WINDOW_ALL
            front && Qual.LEFT in quals -> DeviceSlot.WINDOW_DRIVER
            front && Qual.RIGHT in quals -> DeviceSlot.WINDOW_PASSENGER
            Qual.LEFT in quals -> DeviceSlot.WINDOW_REAR_LEFT
            Qual.RIGHT in quals -> DeviceSlot.WINDOW_REAR_RIGHT
            front -> DeviceSlot.WINDOW_FRONT
            rear -> DeviceSlot.WINDOW_REAR
            else -> null
        }
    }

    private fun isWindow(d: DeviceSlot) = d.name.startsWith("WINDOW")
    private fun isSeat(d: DeviceSlot) = d.name.startsWith("SEAT")
    private fun isAperture(d: DeviceSlot) = isWindow(d) || d == DeviceSlot.SUNROOF

    // ---- Fan speed ----------------------------------------------------------------------

    private const val FAN_MAX = 7
    private const val SEAT_MAX = 3
    private val NOT_FAN_ACTIONS = setOf(ActionSlot.OFF, ActionSlot.OPEN, ActionSlot.CLOSE)

    /** "вентилятор на три", "скорость обдува пять", "обдув на четыре". A seat or the
     *  windshield keeps "обдув" for itself. */
    private fun isFan(devices: Set<DeviceSlot>, stems: List<String>, m: Measure): Boolean {
        if (DeviceSlot.AC_FAN in devices) return true
        if (DeviceSlot.AC_FLOW !in devices || devices.any { isSeat(it) } || WINDSHIELD_STEM in stems) return false
        return SPEED_STEM in stems || m.numbers.isNotEmpty() || m.extreme != null || m.ordinal != null
    }

    private fun resolveFan(actions: Set<ActionSlot>, m: Measure): ParseResult {
        if (actions.any { it in NOT_FAN_ACTIONS }) return ParseResult.Unrecognized
        val level = levelOf(m, FAN_MAX) ?: return ParseResult.Unrecognized
        return VoiceCatalog.resolve(ActionSlot.SET, DeviceSlot.AC_FAN, level)
            ?.let { ParseResult.Command(it) } ?: ParseResult.Unrecognized
    }

    /** The level a phrase names by number, ordinal or максимум/минимум: null = none
     *  named, 0 = contradictory (never a valid level). */
    private fun levelOf(m: Measure, max: Int): Int? {
        if (m.numbers.size > 1) return 0
        val named = LinkedHashSet<Int>()
        m.numbers.firstOrNull()?.let { named.add(it) }
        m.ordinal?.let { named.add(it) }
        when (m.extreme) {
            Extreme.MAX -> named.add(max)
            Extreme.MIN -> named.add(1)
            null -> Unit
        }
        return if (named.size > 1) 0 else named.firstOrNull()
    }

    // ---- Climate, seats, lights, locks ---------------------------------------------------

    private fun resolveComfort(
        stems: List<String>,
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        qualifiers: Set<Qual>,
        measure: Measure,
    ): ParseResult {
        val number = measure.numbers.singleOrNull()
        val effectiveActions = impliedSet(actions, devices, number)
        if (effectiveActions.isEmpty() || devices.isEmpty()) return ParseResult.Unrecognized

        val devices2 = disambiguateAirflow(devices, stems)
        val (actions2, devices3) = narrowSeatOff(effectiveActions, devices2)
        val seatPresent = devices3.any { isSeat(it) }
        // Rear seats have no NLU slots: "подогрев сиденья сзади" must not heat the driver.
        if (seatPresent && Qual.REAR in qualifiers) return ParseResult.Unrecognized
        val leveledActions = upgradeSeatLevel(actions2, devices3, if (seatPresent) levelOf(measure, SEAT_MAX) else null)

        if (seatPresent && targetsBothSeats(stems, qualifiers)) {
            return resolveBothSeats(leveledActions, devices3, number)
        }

        val resolved = resolveAll(leveledActions, refineSeats(devices3, qualifiers), number)
        return if (resolved.size == 1) ParseResult.Command(resolved.first())
        else ParseResult.Unrecognized
    }

    /** Bare absolute value: a temperature/number with no verb means SET
     *  (e.g. "температура 24", "24 градуса"). The catalog ValueSpec still
     *  range-gates 16..30, so an out-of-range number yields Unrecognized. */
    private fun impliedSet(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): Set<ActionSlot> =
        if (actions.isEmpty() && DeviceSlot.AC_TEMP in devices && number != null) setOf(ActionSlot.SET) else actions

    /** "все сиденья"/"сидений" (plural) or "водителя и пассажира" targets BOTH seats.
     *  issue #185: the stemmer collapses genitive singular "сидения" (as in
     *  "сидения водителя") and genitive plural "сидений" to the same stem, so
     *  the plural alone can't tell them apart. A single DRIVER/PASSENGER qualifier
     *  already names one side explicitly -- that always wins over the plural guess. */
    private fun targetsBothSeats(stems: List<String>, qualifiers: Set<Qual>): Boolean {
        val driver = Qual.DRIVER in qualifiers
        val passenger = Qual.PASSENGER in qualifiers
        if (Qual.ALL in qualifiers || driver && passenger) return true
        return !driver && !passenger && VoiceStemmer.stem("сидения") in stems
    }

    private fun resolveAll(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): Set<String> {
        val resolved = LinkedHashSet<String>()
        for (a in actions) for (d in devices) {
            VoiceCatalog.resolve(a, d, number)?.let { resolved.add(it) }
        }
        return resolved
    }

    /** The catalog emits one command per (action, device), so fan out per side; each
     *  side must resolve to exactly ONE command, otherwise the utterance is ambiguous
     *  and goes to the agent (issue #98). */
    private fun resolveBothSeats(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): ParseResult {
        val perSide = listOf(this::driverSeat, this::passengerSeat).map { side ->
            resolveAll(actions, devices.mapTo(LinkedHashSet()) { if (isSeat(it)) side(it) else it }, number)
        }
        return if (perSide.all { it.size == 1 }) ParseResult.Command(perSide.map { it.first() })
        else ParseResult.Unrecognized
    }

    /** A seat with no side qualifier defaults to the DRIVER's seat so a bare
     *  "подогрев сиденья" resolves to one command instead of falling through. */
    private fun refineSeats(devices: Set<DeviceSlot>, quals: Set<Qual>): Set<DeviceSlot> =
        devices.mapTo(LinkedHashSet()) {
            when {
                isSeat(it) && Qual.PASSENGER in quals -> passengerSeat(it)
                isSeat(it) -> driverSeat(it)
                else -> it
            }
        }

    private val SEAT_LEVELS: Map<ActionSlot, List<ActionSlot>> = mapOf(
        ActionSlot.HEAT_1 to listOf(ActionSlot.HEAT_1, ActionSlot.HEAT_2, ActionSlot.HEAT_3),
        ActionSlot.VENT_1 to listOf(ActionSlot.VENT_1, ActionSlot.VENT_2, ActionSlot.VENT_3),
    )

    /** When a seat heat/vent command names a level (number, ordinal, максимум/минимум),
     *  upgrade the base HEAT_1/VENT_1 action to the matching level slot. Levels 4..5
     *  exist only in the agent catalog (no NLU slots) — bail to Unrecognized instead of
     *  silently firing level 1. */
    private fun upgradeSeatLevel(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, level: Int?): Set<ActionSlot> {
        if (level == null || devices.none { isSeat(it) }) return actions
        if (level !in 1..SEAT_MAX) return emptySet()
        return actions.mapTo(LinkedHashSet()) { SEAT_LEVELS[it]?.get(level - 1) ?: it }
    }

    /** "обдув"/"вентиляция" is overloaded: it tags AC_FLOW (climate vent),
     *  DEFROST_FRONT (windshield) and rides on seat-vent phrasing. Collapse the
     *  overlap to ONE device by specificity so a generic "включи обдув" resolves
     *  to exactly one command instead of several. Pure device-level prune. */
    private fun disambiguateAirflow(devices: Set<DeviceSlot>, stems: List<String>): Set<DeviceSlot> {
        if (DeviceSlot.AC_FLOW !in devices && DeviceSlot.DEFROST_FRONT !in devices) return devices
        val hasSeat = devices.any { isSeat(it) }
        val windshield = WINDSHIELD_STEM in stems || GLASS_STEM in stems
        return when {
            hasSeat -> devices - DeviceSlot.AC_FLOW - DeviceSlot.DEFROST_FRONT  // seat vent wins
            windshield -> devices - DeviceSlot.AC_FLOW                          // defrost wins
            else -> devices - DeviceSlot.DEFROST_FRONT                          // climate airflow default
        }
    }

    /** "выключи подогрев сиденья": the noun "подогрев"/"обдув" tags a second action
     *  slot (HEAT_1/VENT_1), so an explicit OFF fans out across BOTH seat subsystems
     *  (heat-off + vent-off + heat-1 = 3 commands -> Unrecognized). With OFF present
     *  the noun is a subsystem selector, not an action: keep only the named seat
     *  family and drop the noun slot. Non-seat devices pass through untouched
     *  ("выключи подогрев зеркал" already resolves via OFF+MIRROR_HEAT). */
    private fun narrowSeatOff(
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
    ): Pair<Set<ActionSlot>, Set<DeviceSlot>> {
        if (ActionSlot.OFF !in actions) return actions to devices
        if (devices.none { isSeat(it) }) return actions to devices
        val heatNoun = ActionSlot.HEAT_1 in actions
        val ventNoun = ActionSlot.VENT_1 in actions
        if (heatNoun == ventNoun) return actions to devices  // neither or both: nothing to narrow
        val family = if (heatNoun) "HEAT" else "VENT"
        val narrowedDevices = devices.filterTo(LinkedHashSet()) {
            !isSeat(it) || it.name.endsWith(family)
        }
        return (actions - ActionSlot.HEAT_1 - ActionSlot.VENT_1) to narrowedDevices
    }

    private fun driverSeat(d: DeviceSlot) = when (d) {
        DeviceSlot.SEAT_PASSENGER_HEAT -> DeviceSlot.SEAT_DRIVER_HEAT
        DeviceSlot.SEAT_PASSENGER_VENT -> DeviceSlot.SEAT_DRIVER_VENT
        else -> d
    }
    private fun passengerSeat(d: DeviceSlot) = when (d) {
        DeviceSlot.SEAT_DRIVER_HEAT -> DeviceSlot.SEAT_PASSENGER_HEAT
        DeviceSlot.SEAT_DRIVER_VENT -> DeviceSlot.SEAT_PASSENGER_VENT
        else -> d
    }

    /** Volume is a media_volume action, not a catalog param. "громче"/"тише" step
     *  +-1; on/off of "звук" mute/unmute; a bare number on the VOLUME device sets
     *  an absolute level. Returns null when no volume intent is present. */
    private fun resolveVolume(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): ParseResult.Volume? {
        if (ActionSlot.LOUDER in actions) return ParseResult.Volume("+1")
        if (ActionSlot.QUIETER in actions) return ParseResult.Volume("-1")
        if (DeviceSlot.VOLUME !in devices) return null
        return when {
            ActionSlot.OFF in actions -> ParseResult.Volume("mute")
            ActionSlot.ON in actions -> ParseResult.Volume("unmute")
            number != null -> ParseResult.Volume(number.toString())
            else -> null
        }
    }
}
