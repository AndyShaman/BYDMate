package com.bydmate.app.voice

import com.bydmate.app.voice.VoiceNormalizer.Extreme
import com.bydmate.app.voice.VoiceNormalizer.Measure

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
    /** Understood enough to know it must NOT act: a negation, a measure it cannot read or
     *  that contradicts itself, several commands it cannot split. [reason] is a [VoiceRefusal]
     *  code; like [Unrecognized], the phrase goes to the agent. */
    data class Refused(val reason: String) : ParseResult
}

/**
 * Offline slot parser for Russian voice commands. Deterministic and pure. A command fires
 * only when EVERY word of the utterance is one the command reads (see [readsEveryWord]):
 * any word left over ("палец", "подожди", "мы", "завтра") makes it [ParseResult.Unrecognized]
 * and the phrase goes to the agent.
 */
object NluParser {

    fun parse(text: String): ParseResult {
        // Politeness changes nothing asked for: dropped before any word is read, so "на
        // пожалуйста пятьдесят" is "на пятьдесят" to the measure and to its neighbours.
        val tokens = VoiceNormalizer.tokens(text).filterNot { it in FILLERS }
        if (tokens.isEmpty()) return ParseResult.Unrecognized

        // Negation ("не открывай", "нет, не надо") is beyond slot NLU: guessing an
        // affirmative command would do the OPPOSITE of what was said. Agent decides.
        if (tokens.any { it in NEGATION_WORDS }) return ParseResult.Refused(VoiceRefusal.NEGATION)
        // "кроме" lists its exclusions with «и»: they are never commands of their own.
        if (EXCEPT in tokens) return parseClause(tokens)

        val clauses = splitClauses(tokens)
        return if (clauses.size > 1) parseClauses(tokens, clauses) else parseClause(tokens)
    }

    private val NEGATION_WORDS = setOf("не", "нет", "нельзя", "отмена", "отмени", "отменить", "отмените")

    /** How the cabin feels, as a temperature step: cold asks for warmer air, heat for cooler.
     *  Only these exact words: "прохладнее"/"холоднее" are the COOLER request itself. */
    private val FEELINGS: Map<String, Int> = mapOf(
        "холодно" to 1, "прохладно" to 1, "замерз" to 1, "замерзла" to 1, "замерзли" to 1,
        "жарко" to -1, "душно" to -1,
    )

    /** Places a feeling may name ("в салоне", "в машине", "в климате") without being about another device. */
    private val FEELING_PLACES = setOf(DeviceSlot.CAR, DeviceSlot.LIGHT_INTERIOR, DeviceSlot.AC_AUTO, DeviceSlot.AC_TEMP)

    /** A feeling steps the temperature only when nothing else is asked: an explicit verb
     *  wins ("душно, открой окно"), and a feeling about another device ("в окно дует,
     *  холодно") or two opposite feelings go to the agent. */
    private fun feelingResult(feeling: List<Int>, actions: Set<ActionSlot>, devices: Set<DeviceSlot>): ParseResult? {
        if (feeling.isEmpty() || actions.isNotEmpty()) return null
        val sign = feeling.singleOrNull()
        return if (sign != null && FEELING_PLACES.containsAll(devices)) ParseResult.RelativeTemp(sign)
        else ParseResult.Unrecognized
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
     * one ("закрой окна и люк"). Otherwise the utterance is parsed whole ("открой окна
     * водителя и пассажира" is one target, not two clauses). When neither reading works but
     * some clause alone meant something, the phrase held commands the parser cannot split:
     * refused, never one part of it.
     */
    private fun parseClauses(tokens: List<String>, clauses: List<List<String>>): ParseResult {
        var verbs = emptyList<String>()
        val filled = clauses.map { clause ->
            val own = clause.filter { VoiceStemmer.stem(it) in actionStems }
            if (own.isNotEmpty()) verbs = own
            if (own.isEmpty()) verbs + clause else clause
        }
        val parsed = filled.map { parseClause(it) }
        compound(filled, parsed)?.let { return it }
        val whole = parseClause(tokens)
        if (whole != ParseResult.Unrecognized) return whole
        parsed.firstOrNull { it is ParseResult.Refused }?.let { return it }
        return if (parsed.all { it == ParseResult.Unrecognized }) whole
        else ParseResult.Refused(VoiceRefusal.MULTIPLE_COMMANDS)
    }

    /** Every clause as its commands, or null. A share named in one clause next to a full
     *  open in another ("открой окна и люк наполовину") is ambiguous and never dispatched. */
    private fun compound(filled: List<List<String>>, parsed: List<ParseResult>): ParseResult.Command? {
        val commands = parsed.map { (it as? ParseResult.Command)?.commands ?: return null }.flatten()
        val namesShare = filled.any { VoiceNormalizer.measure(it).let { m -> m.hasShare || m.numberIsShare } }
        if (namesShare && commands.any { it in fullOpenCommands }) return null
        return ParseResult.Command(commands)
    }

    private fun parseClause(input: List<String>): ParseResult {
        val feeling = input.mapNotNull { FEELINGS[it] }.distinct()
        val tokens = VoiceSpelling.correct(input.filterNot { it in FEELINGS })
        val stems = tokens.map { VoiceStemmer.stem(it) }
        val measure = VoiceNormalizer.measure(tokens)
        val actions = matchSlots(stems, VoiceLexicon.actionWords())
        val devices = pruneDevices(matchSlots(stems, VoiceLexicon.deviceWords()), tokens, stems, actions, measure)
        val qualifiers = detectQualifiers(tokens)

        if (!readsEveryWord(tokens, stems, measure, devices) || !placesDevices(tokens, devices, qualifiers)) {
            return ParseResult.Unrecognized
        }
        relativeStep(feeling, actions, devices, qualifiers, measure)?.let { return it }
        resolveVolume(actions, devices, measure, qualifiers)?.let { return it }
        if (devices.any { isAperture(it) }) return resolveAperture(tokens, actions, devices, measure)
        if (isFan(devices, stems, measure)) return resolveFan(stems, actions, qualifiers, measure)
        // A share ("наполовину", "чуть", "приоткрой") only fits an opening; on anything
        // else it cannot be read, and doing the rest in full would do more than was asked.
        if (measure.hasShare && measure.extreme == null) return ParseResult.Refused(VoiceRefusal.UNKNOWN_MEASURE)
        return resolveComfort(stems, actions, devices, qualifiers, measure)
    }

    /** "кроме" is expanded for windows only; elsewhere dropping it would do the excluded part
     *  too. "передний багажник" is NOT the rear tailgate: TRUNK has no front variant in the NLU
     *  catalog (front_trunk_open/close live in the agent's). */
    private fun placesDevices(tokens: List<String>, devices: Set<DeviceSlot>, qualifiers: Set<Qual>): Boolean =
        (EXCEPT !in tokens || devices.any { isAperture(it) }) && !(DeviceSlot.TRUNK in devices && Qual.FRONT in qualifiers)

    /** Relative temperature implies the AC; resolved against the live snapshot by
     *  VoiceController (the parser stays pure). A step reads no measure, no side and no device
     *  but the cabin itself. Null when the phrase asks for no step. */
    private fun relativeStep(
        feeling: List<Int>,
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        qualifiers: Set<Qual>,
        measure: Measure,
    ): ParseResult? {
        val step = feelingResult(feeling, actions, devices) ?: when {
            ActionSlot.WARMER in actions -> ParseResult.RelativeTemp(1)
            ActionSlot.COOLER in actions -> ParseResult.RelativeTemp(-1)
            else -> return null
        }
        val bare = !measure.named && qualifiers.isEmpty() && FEELING_PLACES.containsAll(devices)
        return if (bare) step else ParseResult.Unrecognized
    }

    private fun <T> matchSlots(stems: List<String>, words: Map<T, List<String>>): Set<T> {
        val out = LinkedHashSet<T>()
        for ((slot, surfaces) in words) {
            val surfStems = surfaces.map { VoiceStemmer.stem(it) }
            if (stems.any { it in surfStems }) out.add(slot)
        }
        return out
    }

    /** Words that join the others without meaning anything alone. A preposition closing the
     *  clause ("открой окно на") joins nothing and is not read. */
    private val CONNECTORS = setOf("на", "до", "по", "в", "во", "у", "и", "а", "также", EXCEPT)
    private val PREPOSITIONS = setOf("на", "до", "по", "в", "во", "у")
    private val FILLERS: Set<String> by lazy { VoicePhrase.FILLERS + setOf("спасибо", "хочу", "я") }

    /** "на водительской стороне": the side word itself, in these forms only. */
    private val SIDE_WORDS = setOf("сторона", "стороны", "стороне", "сторону", "стороной", "сторон")

    /** Words read as they are, wherever they stand. Qualifiers are read only in the forms
     *  [QUAL_WORDS] lists: "лев" or "сторонник" is not a side. */
    private val plainWords: Set<String> by lazy {
        CONNECTORS - PREPOSITIONS + verbForms + MY_WORDS + NEUTER_SIDES + SIDE_WORDS + QUAL_WORDS.values.flatten()
    }

    /** "второй уровень", "на третий уровень": read only next to a level. */
    private val LEVEL_WORDS = setOf("уровень", "уровня", "уровне")

    /** Action nouns inflect ("подогрева сидений", "вентиляцию"); verbs are read only in the
     *  forms the lexicon lists, so "открываем", "открывал" are words the parser does not read. */
    private val NOUN_ACTIONS = setOf(ActionSlot.HEAT_1, ActionSlot.VENT_1)
    private val verbForms: Set<String> by lazy {
        VoiceLexicon.actionWords().filterKeys { it !in NOUN_ACTIONS }.values.flatten().toSet()
    }
    private val readableStems: Set<String> by lazy {
        val nouns = VoiceLexicon.actionWords().filterKeys { it in NOUN_ACTIONS }.values.flatten()
        (nouns + VoiceLexicon.deviceWords().values.flatten()).mapTo(HashSet()) { VoiceStemmer.stem(it) }
    }

    /**
     * The one rule for firing offline: every word is an action in a listed form, a device, a
     * side/row/all qualifier, a word of a fully read measure, a connector or a filler. Whether
     * the resolved command then uses that measure, side or «кроме» is checked where it is
     * resolved; a word nobody reads is never skipped.
     */
    private fun readsEveryWord(
        tokens: List<String>,
        stems: List<String>,
        measure: Measure,
        devices: Set<DeviceSlot>,
    ): Boolean {
        val fan = DeviceSlot.AC_FAN in devices || DeviceSlot.AC_FLOW in devices
        val level = measure.numbers.isNotEmpty() || measure.ordinal != null || measure.extreme != null
        return tokens.indices.all { i ->
            val t = tokens[i]
            when {
                t in PREPOSITIONS -> i < tokens.lastIndex
                t in plainWords -> true
                t in LEVEL_WORDS -> level
                stems[i] == SPEED_STEM -> fan
                else -> i in measure.words || stems[i] in readableStems
            }
        }
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

    // Adjective endings by stem type: "лев-ое", "передн-ее", "водительск-ое".
    private val HARD_ENDINGS = listOf("ый", "ая", "ое", "ые", "ого", "ому", "ую", "ой", "ым", "ом", "ых", "ыми")
    private val SOFT_ENDINGS = listOf("ий", "яя", "ее", "ие", "его", "ему", "юю", "ей", "им", "ем", "их", "ими")
    private val VELAR_ENDINGS = listOf("ий", "ая", "ое", "ие", "ого", "ому", "ую", "ой", "им", "ом", "их", "ими")

    private fun forms(stem: String, endings: List<String>): Set<String> = endings.mapTo(HashSet()) { stem + it }

    /** Every form a qualifier is read in, compared word for word (no stemming, no prefixes). */
    private val QUAL_WORDS: Map<Qual, Set<String>> = mapOf(
        Qual.DRIVER to setOf("водитель", "водителя", "водителю", "водителем", "водителе") + forms("водительск", VELAR_ENDINGS),
        Qual.PASSENGER to setOf("пассажир", "пассажира", "пассажиру", "пассажиром", "пассажире") +
            forms("пассажирск", VELAR_ENDINGS),
        Qual.FRONT to forms("передн", SOFT_ENDINGS) + "спереди",
        Qual.REAR to forms("задн", SOFT_ENDINGS) + "сзади",
        Qual.LEFT to forms("лев", HARD_ENDINGS) + "слева",
        Qual.RIGHT to forms("прав", HARD_ENDINGS) + "справа",
        Qual.ALL to setOf("все", "всех", "всем", "всеми"),
    )

    /** "моё окно", "моего сиденья": the speaker sits in the driver's seat. */
    private val MY_WORDS = setOf("мое", "мой", "моя", "мою", "моего", "моей", "моем", "моему")

    /** ALL positional markers present in the phrase (compound corners need both). */
    private fun detectQualifiers(tokens: List<String>): Set<Qual> {
        val out = QUAL_WORDS.filterValues { forms -> tokens.any { it in forms } }.keys.toMutableSet()
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

    /** Detents each opening supports, as (share, slot), ascending. Sunroof vent (tilt) reads
     *  7 % on the percent fid. */
    private val WINDOW_STOPS = listOf(10 to ActionSlot.VENT, 50 to ActionSlot.HALF, 100 to ActionSlot.OPEN)
    private val SUNROOF_STOPS = listOf(7 to ActionSlot.VENT, 50 to ActionSlot.HALF, 100 to ActionSlot.OPEN)

    private val APERTURE_ACTIONS = setOf(ActionSlot.OPEN, ActionSlot.CLOSE, ActionSlot.SET, ActionSlot.ON, ActionSlot.VENT)
    private val OPENING_ACTIONS = setOf(ActionSlot.OPEN, ActionSlot.SET, ActionSlot.ON)
    private const val UNKNOWN_SHARE = -1
    private const val CONFLICT_SHARE = -2
    private const val HALF_SHARE = 50

    /** The detent an aperture phrase asks for: a slot, a refusal with its reason, or nothing
     *  an opening can do (no verb, a verb that is not about openings). */
    private sealed interface Detent {
        data class Slot(val slot: ActionSlot) : Detent
        data class Refuse(val reason: String) : Detent
        data object None : Detent
    }

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
        val slot = when (val d = apertureDetent(actions, measure, if (sunroof) SUNROOF_STOPS else WINDOW_STOPS)) {
            is Detent.Slot -> d.slot
            is Detent.Refuse -> return ParseResult.Refused(d.reason)
            Detent.None -> return ParseResult.Unrecognized
        }
        val targets = (if (sunroof) sunroofTarget(tokens) else windowTargets(tokens)) ?: return ParseResult.Unrecognized
        val commands = targets.map { VoiceCatalog.resolve(slot, it, null) ?: return ParseResult.Unrecognized }
        return ParseResult.Command(commands)
    }

    /** The sunroof is one opening: a side or «кроме» names nothing on it. */
    private fun sunroofTarget(tokens: List<String>): List<DeviceSlot>? =
        listOf(DeviceSlot.SUNROOF).takeIf { EXCEPT !in tokens && detectQualifiers(tokens).isEmpty() }

    /** The detent the phrase asks for; a measure or verbs it cannot read are refused, never
     *  a full open by default. A spoken share maps to the widest detent that does not exceed
     *  it; anything above zero but below the smallest detent is not understood. */
    private fun apertureDetent(actions: Set<ActionSlot>, m: Measure, stops: List<Pair<Int, ActionSlot>>): Detent {
        if (actions.any { it !in APERTURE_ACTIONS }) return Detent.None
        val closes = ActionSlot.CLOSE in actions
        if (closes && actions.any { it in OPENING_ACTIONS }) return Detent.Refuse(VoiceRefusal.MULTIPLE_COMMANDS)
        val share = namedShare(actions, m)
        shareRefusal(share)?.let { return Detent.Refuse(it) }
        val target = when {
            closes -> closingTarget(share) ?: return Detent.Refuse(VoiceRefusal.UNKNOWN_MEASURE)
            share == null -> if (ActionSlot.OPEN in actions) VoiceNormalizer.FULL_SHARE else return Detent.None
            share == 0 && ActionSlot.OPEN in actions -> return Detent.Refuse(VoiceRefusal.CONFLICTING_MEASURE)
            else -> share
        }
        if (target == 0) return Detent.Slot(ActionSlot.CLOSE)
        // Above zero but under the smallest detent: no detent is small enough.
        val stop = stops.lastOrNull { it.first <= target } ?: return Detent.None
        return Detent.Slot(stop.second)
    }

    /** The refusal an unreadable or contradictory share stands for, or null. */
    private fun shareRefusal(share: Int?): String? = when (share) {
        UNKNOWN_SHARE -> VoiceRefusal.UNKNOWN_MEASURE
        CONFLICT_SHARE -> VoiceRefusal.CONFLICTING_MEASURE
        else -> null
    }

    /** "закрой" / "закрой до конца" close; "закрой наполовину" leaves half open; any
     *  other share on a closing verb is relative and ambiguous. */
    private fun closingTarget(share: Int?): Int? = when (share) {
        null, 0, VoiceNormalizer.FULL_SHARE -> 0
        HALF_SHARE -> HALF_SHARE
        else -> null
    }

    /** Share of the opening the words name: null = none, [UNKNOWN_SHARE] = unreadable,
     *  [CONFLICT_SHARE] = two different shares or numbers. */
    private fun namedShare(actions: Set<ActionSlot>, m: Measure): Int? {
        if (m.shareConflict || m.numbers.size > 1 || m.levels > 1) return CONFLICT_SHARE
        if (unreadableShare(m)) return UNKNOWN_SHARE
        val named = LinkedHashSet<Int>()
        m.share?.let { named.add(it) }
        m.numbers.firstOrNull()?.let { if (it in 0..VoiceNormalizer.FULL_SHARE) named.add(it) else return UNKNOWN_SHARE }
        if (m.extreme == Extreme.MAX) named.add(VoiceNormalizer.FULL_SHARE)
        if (ActionSlot.VENT in actions) named.add(VoiceNormalizer.VENT_SHARE)
        if (named.size > 1) return CONFLICT_SHARE
        return named.firstOrNull() ?: VoiceNormalizer.VENT_SHARE.takeIf { m.softVent }
    }

    /** A measure that cannot be a share of an opening: an unexplained word, a level
     *  (ordinal, "минимум") or a number that is not a share. */
    private fun unreadableShare(m: Measure): Boolean {
        if (m.unexplained || m.ordinal != null || m.extreme == Extreme.MIN) return true
        return m.numbers.isNotEmpty() && !m.numberIsShare
    }

    private const val EXCEPT = "кроме"

    private val DOOR_ORDER = listOf(
        DeviceSlot.WINDOW_DRIVER, DeviceSlot.WINDOW_PASSENGER, DeviceSlot.WINDOW_REAR_LEFT, DeviceSlot.WINDOW_REAR_RIGHT,
    )

    /** One window slot, or per-door slots for "кроме X и Y" (never touching X or Y); null
     *  when the qualifiers name no single window or pair, an exclusion names no whole window
     *  (a bare "правого" may be either row), «кроме» is said twice or nothing is left. */
    private fun windowTargets(tokens: List<String>): List<DeviceSlot>? {
        val cut = tokens.indexOf(EXCEPT)
        if (cut < 0) return windowFor(detectQualifiers(tokens), pluralWindows(tokens))?.let { listOf(it) }
        if (tokens.count { it == EXCEPT } > 1) return null
        // Each excluded window on its own: "заднего левого и заднего правого" is two corners,
        // not one set of qualifiers.
        val excluded = splitClauses(tokens.subList(cut + 1, tokens.size)).flatMap { part ->
            val quals = detectQualifiers(part)
            if (quals.all { it == Qual.LEFT || it == Qual.RIGHT }) return null
            doorsOf(windowFor(quals, plural = false) ?: return null)
        }
        if (excluded.isEmpty()) return null
        val baseQuals = detectQualifiers(tokens.subList(0, cut)) - Qual.ALL
        val base = if (baseQuals.isEmpty()) DeviceSlot.WINDOW_ALL else windowFor(baseQuals, plural = true) ?: return null
        val kept = doorsOf(base) - excluded.toSet()
        return kept.takeIf { it.isNotEmpty() }
    }

    private fun doorsOf(slot: DeviceSlot): List<DeviceSlot> = when (slot) {
        DeviceSlot.WINDOW_ALL -> DOOR_ORDER
        DeviceSlot.WINDOW_FRONT -> DOOR_ORDER.take(2)
        DeviceSlot.WINDOW_REAR -> DOOR_ORDER.drop(2)
        else -> listOf(slot)
    }

    /** The window or pair each qualifier set names, every qualifier used. A bare LEFT/RIGHT is
     *  the rear pair side (front sides are named driver/passenger). A set not listed here ("заднее
     *  окно водителя", a corner of each row) names no window the parser can place. */
    private val WINDOW_OF: Map<Set<Qual>, DeviceSlot> = mapOf(
        setOf(Qual.DRIVER) to DeviceSlot.WINDOW_DRIVER,
        setOf(Qual.DRIVER, Qual.FRONT) to DeviceSlot.WINDOW_DRIVER,
        setOf(Qual.DRIVER, Qual.LEFT) to DeviceSlot.WINDOW_DRIVER,
        setOf(Qual.DRIVER, Qual.FRONT, Qual.LEFT) to DeviceSlot.WINDOW_DRIVER,
        setOf(Qual.FRONT, Qual.LEFT) to DeviceSlot.WINDOW_DRIVER,
        setOf(Qual.PASSENGER) to DeviceSlot.WINDOW_PASSENGER,
        setOf(Qual.PASSENGER, Qual.FRONT) to DeviceSlot.WINDOW_PASSENGER,
        setOf(Qual.PASSENGER, Qual.RIGHT) to DeviceSlot.WINDOW_PASSENGER,
        setOf(Qual.PASSENGER, Qual.FRONT, Qual.RIGHT) to DeviceSlot.WINDOW_PASSENGER,
        setOf(Qual.FRONT, Qual.RIGHT) to DeviceSlot.WINDOW_PASSENGER,
        setOf(Qual.LEFT) to DeviceSlot.WINDOW_REAR_LEFT,
        setOf(Qual.REAR, Qual.LEFT) to DeviceSlot.WINDOW_REAR_LEFT,
        setOf(Qual.RIGHT) to DeviceSlot.WINDOW_REAR_RIGHT,
        setOf(Qual.REAR, Qual.RIGHT) to DeviceSlot.WINDOW_REAR_RIGHT,
        setOf(Qual.FRONT) to DeviceSlot.WINDOW_FRONT,
        setOf(Qual.DRIVER, Qual.PASSENGER) to DeviceSlot.WINDOW_FRONT,
        setOf(Qual.DRIVER, Qual.PASSENGER, Qual.FRONT) to DeviceSlot.WINDOW_FRONT,
        setOf(Qual.REAR) to DeviceSlot.WINDOW_REAR,
        setOf(Qual.FRONT, Qual.REAR) to DeviceSlot.WINDOW_ALL,
    )
    private val WINDOW_GROUPS = setOf(DeviceSlot.WINDOW_ALL, DeviceSlot.WINDOW_FRONT, DeviceSlot.WINDOW_REAR)

    /** Map a qualifier SET to one window or pair, or null. A bare singular "окно"/"моё окно" is
     *  the driver's; plural "окна"/"все" is every window; "все" next to a side names a pair only. */
    private fun windowFor(quals: Set<Qual>, plural: Boolean): DeviceSlot? {
        val sides = quals - Qual.ALL
        if (sides.isEmpty()) return if (plural || Qual.ALL in quals) DeviceSlot.WINDOW_ALL else DeviceSlot.WINDOW_DRIVER
        val window = WINDOW_OF[sides] ?: return null
        return window.takeIf { Qual.ALL !in quals || it in WINDOW_GROUPS }
    }

    private fun isWindow(d: DeviceSlot) = d.name.startsWith("WINDOW")
    private fun isSeat(d: DeviceSlot) = d.name.startsWith("SEAT")
    private fun isAperture(d: DeviceSlot) = isWindow(d) || d == DeviceSlot.SUNROOF

    // ---- Fan speed ----------------------------------------------------------------------

    private const val FAN_MAX = 7
    private const val SEAT_MAX = 3
    /** Verbs and nouns a fan level reads: "включи", "поставь", "обдув"/"вентиляция". */
    private val FAN_ACTIONS = setOf(ActionSlot.ON, ActionSlot.SET, ActionSlot.VENT_1)

    /** The fan belongs to the climate: "вентилятор климата" names nothing else. */
    private val FAN_DEVICES = setOf(DeviceSlot.AC_FAN, DeviceSlot.AC_FLOW, DeviceSlot.AC_AUTO)

    /** "вентилятор на три", "скорость обдува пять", "обдув на четыре". A seat or the
     *  windshield keeps "обдув" for itself. */
    private fun isFan(devices: Set<DeviceSlot>, stems: List<String>, m: Measure): Boolean {
        if (DeviceSlot.AC_FAN in devices) return true
        if (DeviceSlot.AC_FLOW !in devices || devices.any { isSeat(it) } || WINDSHIELD_STEM in stems) return false
        return SPEED_STEM in stems || m.numbers.isNotEmpty() || m.extreme != null || m.ordinal != null
    }

    /** The fan reads a level and nothing else: no side, no other device, no share but "на
     *  полную" (the top level), no action but switching or setting it. */
    private fun resolveFan(stems: List<String>, actions: Set<ActionSlot>, qualifiers: Set<Qual>, m: Measure): ParseResult {
        val fanOnly = qualifiers.isEmpty() && readsDevices(stems, FAN_DEVICES)
        val shareless = !(m.hasShare && m.extreme == null) && !m.unexplained
        if (!fanOnly || !shareless || !FAN_ACTIONS.containsAll(actions)) return ParseResult.Unrecognized
        val level = levelOf(m, FAN_MAX) ?: return ParseResult.Unrecognized
        return VoiceCatalog.resolve(ActionSlot.SET, DeviceSlot.AC_FAN, level)
            ?.let { ParseResult.Command(it) } ?: ParseResult.Unrecognized
    }

    /** The level a phrase names by number, ordinal or максимум/минимум: null = none
     *  named, 0 = two levels named (never a valid level, even when they agree). */
    private fun levelOf(m: Measure, max: Int): Int? {
        if (m.levels > 1) return 0
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
        // Only a seat reads a side, and a few devices their own ("задний багажник" is just the trunk).
        if (!seatPresent && !readsSideOf(devices3, qualifiers)) return ParseResult.Unrecognized
        val sides = if (seatPresent) (seatSides(stems, qualifiers) ?: return ParseResult.Unrecognized) else emptySet()
        val leveledActions = upgradeSeatLevel(actions2, devices3, if (seatPresent) levelOf(measure, SEAT_MAX) else null)

        if (sides.size > 1) return resolveBothSeats(stems, measure, leveledActions, devices3, number)

        val resolved = resolveAll(leveledActions, refineSeats(devices3, sides.singleOrNull()), number)
        val (command, pairs) = resolved.entries.singleOrNull() ?: return ParseResult.Unrecognized
        return if (readsAll(stems, measure, leveledActions, pairs)) ParseResult.Command(command) else ParseResult.Unrecognized
    }

    /** The command reads everything the phrase names: its measure, every action word and every
     *  device word ("открой багажник климат" is not the trunk alone, "выключи вентиляцию руля" is
     *  not the wheel heater). */
    private fun readsAll(stems: List<String>, measure: Measure, actions: Set<ActionSlot>, pairs: Set<Pair<ActionSlot, DeviceSlot>>): Boolean {
        val used = pairs.mapTo(HashSet()) { it.second }
        return readsMeasure(measure, used) && readsActions(actions, pairs) && readsDevices(stems, used)
    }

    /** Only a seat reads a level and only the temperature a number, each exactly one and with no
     *  other measure word next to it: "на три процента", "на полную наполовину" are not levels. */
    private fun readsMeasure(m: Measure, used: Set<DeviceSlot>): Boolean = when {
        !m.named -> true
        m.unexplained || m.levels != 1 || (m.words - m.levelWords).isNotEmpty() -> false
        used.any { isSeat(it) } -> true
        DeviceSlot.AC_TEMP in used -> m.numbers.size == 1
        else -> false
    }

    /** The subsystem noun each device is: "подогрев" warms, "обдув"/"вентиляция" blows. The
     *  windshield defrost is warm air, so it is both. */
    private fun nounsOf(d: DeviceSlot): Set<ActionSlot> = when (d) {
        DeviceSlot.STEERING_HEAT, DeviceSlot.MIRROR_HEAT, DeviceSlot.SEAT_DRIVER_HEAT, DeviceSlot.SEAT_PASSENGER_HEAT ->
            setOf(ActionSlot.HEAT_1)
        DeviceSlot.AC_FLOW, DeviceSlot.SEAT_DRIVER_VENT, DeviceSlot.SEAT_PASSENGER_VENT -> setOf(ActionSlot.VENT_1)
        DeviceSlot.DEFROST_FRONT -> setOf(ActionSlot.HEAT_1, ActionSlot.VENT_1)
        else -> emptySet()
    }

    private val SEAT_LEVEL_ACTIONS: Set<ActionSlot> by lazy { SEAT_LEVELS.values.flatten().toSet() }
    private val LEVEL_VERBS = setOf(ActionSlot.ON, ActionSlot.SET)

    /** Every action word is read by the command: its own action, the subsystem noun of its
     *  device ("подогрев руля") or the on/set verb of a seat level ("включи подогрев сиденья").
     *  "открой багажник на проветривание" leaves the airing unread. */
    private fun readsActions(actions: Set<ActionSlot>, pairs: Set<Pair<ActionSlot, DeviceSlot>>): Boolean =
        actions.all { a ->
            pairs.any { (own, d) -> a == own || a in nounsOf(d) || a in LEVEL_VERBS && own in SEAT_LEVEL_ACTIONS }
        }

    /** The sides a device other than a seat reads: the tailgate is the rear one, the windshield
     *  the front glass, and the mirror heater warms every mirror. */
    private val SIDES_READ: Map<DeviceSlot, Set<Qual>> = mapOf(
        DeviceSlot.TRUNK to setOf(Qual.REAR),
        DeviceSlot.DEFROST_FRONT to setOf(Qual.FRONT),
        DeviceSlot.MIRROR_HEAT to setOf(Qual.ALL),
    )

    private fun readsSideOf(devices: Set<DeviceSlot>, qualifiers: Set<Qual>): Boolean =
        qualifiers.isEmpty() || SIDES_READ[devices.singleOrNull()]?.containsAll(qualifiers) == true

    private val deviceSlotsByStem: Map<String, Set<DeviceSlot>> by lazy {
        val out = HashMap<String, MutableSet<DeviceSlot>>()
        VoiceLexicon.deviceWords().forEach { (slot, words) ->
            words.forEach { out.getOrPut(VoiceStemmer.stem(it)) { LinkedHashSet() }.add(slot) }
        }
        out
    }

    private val actionNounStems: Set<String> by lazy {
        VoiceLexicon.actionWords().filterKeys { it in NOUN_ACTIONS }.values.flatten().mapTo(HashSet()) { VoiceStemmer.stem(it) }
    }

    /** Every device word names one of [used]: a seat word any seat (the side is a qualifier),
     *  a glass word the windshield, the sunroof word the shade it belongs to. A word that is
     *  also an action noun ("обдув", "вентиляция") is read as the action. */
    private fun readsDevices(stems: List<String>, used: Set<DeviceSlot>): Boolean = stems.all { st ->
        val slots = deviceSlotsByStem[st].orEmpty()
        slots.isEmpty() || st in actionNounStems || slots.any { it in used } ||
            used.any { isSeat(it) } && slots.any { isSeat(it) } ||
            DeviceSlot.DEFROST_FRONT in used && slots.any { isWindow(it) } ||
            DeviceSlot.SUNSHADE in used && DeviceSlot.SUNROOF in slots
    }

    /** Bare absolute value: a temperature/number with no verb means SET
     *  (e.g. "температура 24", "24 градуса"). The catalog ValueSpec still
     *  range-gates 16..30, so an out-of-range number yields Unrecognized. */
    private fun impliedSet(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): Set<ActionSlot> =
        if (actions.isEmpty() && DeviceSlot.AC_TEMP in devices && number != null) setOf(ActionSlot.SET) else actions

    private enum class Seat { DRIVER, PASSENGER }

    /** The seats the phrase names. The car is left-hand drive: LEFT is the driver's seat and
     *  RIGHT the passenger's, so a side word always picks its seat. "все"/"водителя и пассажира"/
     *  "левого и правого" is both; with no side, plural "сидений" is both and a bare seat the
     *  driver's. Null when the sides contradict ("правое сиденье водителя", "всех сидений водителя").
     *  issue #185: the stemmer collapses genitive singular "сидения" (as in "сидения водителя")
     *  and genitive plural "сидений" to the same stem, so a named side always wins over the
     *  plural guess. */
    private fun seatSides(stems: List<String>, quals: Set<Qual>): Set<Seat>? {
        val named = SEAT_OF.filterKeys { it in quals }.values.toSet()
        if (CROSSED_SIDES.any { quals.containsAll(it) }) return null
        return when {
            Qual.ALL in quals -> BOTH_SEATS.takeIf { named.isEmpty() }
            named.isNotEmpty() -> named
            VoiceStemmer.stem("сидения") in stems -> BOTH_SEATS
            else -> setOf(Seat.DRIVER)
        }
    }

    private val SEAT_OF = mapOf(
        Qual.DRIVER to Seat.DRIVER, Qual.LEFT to Seat.DRIVER, Qual.PASSENGER to Seat.PASSENGER, Qual.RIGHT to Seat.PASSENGER,
    )
    private val BOTH_SEATS = setOf(Seat.DRIVER, Seat.PASSENGER)
    private val CROSSED_SIDES = listOf(setOf(Qual.DRIVER, Qual.RIGHT), setOf(Qual.PASSENGER, Qual.LEFT))

    /** Each distinct command the (action, device) pairs resolve to, with the pairs behind it. */
    private fun resolveAll(
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        number: Int?,
    ): Map<String, Set<Pair<ActionSlot, DeviceSlot>>> {
        val resolved = LinkedHashMap<String, MutableSet<Pair<ActionSlot, DeviceSlot>>>()
        for (a in actions) for (d in devices) {
            VoiceCatalog.resolve(a, d, number)?.let { resolved.getOrPut(it) { LinkedHashSet() }.add(a to d) }
        }
        return resolved
    }

    /** The catalog emits one command per (action, device), so fan out per side; each
     *  side must resolve to exactly ONE command, otherwise the utterance is ambiguous
     *  and goes to the agent (issue #98). */
    private fun resolveBothSeats(
        stems: List<String>,
        measure: Measure,
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        number: Int?,
    ): ParseResult {
        val perSide = listOf(Seat.DRIVER, Seat.PASSENGER).map { resolveAll(actions, refineSeats(devices, it), number) }
        val pairs = perSide.flatMapTo(HashSet()) { it.values.flatten() }
        return if (perSide.all { it.size == 1 } && readsAll(stems, measure, actions, pairs)) {
            ParseResult.Command(perSide.map { it.keys.first() })
        } else {
            ParseResult.Unrecognized
        }
    }

    /** Every seat device on [seat]'s side; other devices unchanged. */
    private fun refineSeats(devices: Set<DeviceSlot>, seat: Seat?): Set<DeviceSlot> =
        devices.mapTo(LinkedHashSet()) {
            when {
                !isSeat(it) || seat == null -> it
                seat == Seat.PASSENGER -> passengerSeat(it)
                else -> driverSeat(it)
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
    private fun resolveVolume(
        actions: Set<ActionSlot>,
        devices: Set<DeviceSlot>,
        m: Measure,
        qualifiers: Set<Qual>,
    ): ParseResult? {
        val step = if (ActionSlot.LOUDER in actions) "+1" else if (ActionSlot.QUIETER in actions) "-1" else null
        if (step == null && DeviceSlot.VOLUME !in devices) return null
        // Volume reads no side and no other device; only an absolute level reads a number.
        if (qualifiers.isNotEmpty() || devices.any { it != DeviceSlot.VOLUME }) return ParseResult.Unrecognized
        val payload = step ?: muteOf(actions)
        if (payload != null) return if (m.named) ParseResult.Unrecognized else ParseResult.Volume(payload)
        val level = m.numbers.singleOrNull()?.takeIf { !m.copy(numbers = emptyList()).named }
        return if (level != null) ParseResult.Volume(level.toString()) else ParseResult.Unrecognized
    }

    private fun muteOf(actions: Set<ActionSlot>): String? = when {
        ActionSlot.OFF in actions -> "mute"
        ActionSlot.ON in actions -> "unmute"
        else -> null
    }

    /** Some word of the phrase names a measure: a number, a share, a level or an unreadable one. */
    private val Measure.named: Boolean
        get() = numbers.isNotEmpty() || hasShare || ordinal != null || extreme != null || unexplained
}
