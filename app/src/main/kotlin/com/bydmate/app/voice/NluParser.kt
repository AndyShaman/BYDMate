package com.bydmate.app.voice

sealed interface ParseResult {
    /** One utterance can resolve to several dispatchable commands (plural seats fan
     *  out per side). Single-command callers keep using [command]. */
    data class Command(val commands: List<String>) : ParseResult {
        constructor(command: String) : this(listOf(command))
        val command: String? get() = commands.singleOrNull()
    }
    data class RelativeTemp(val sign: Int) : ParseResult
    data class Volume(val payload: String) : ParseResult
    data object Unrecognized : ParseResult
}

object NluParser {

    fun parse(text: String, lang: VoiceLang): ParseResult {
        val rawTokens = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return ParseResult.Unrecognized

        val stems = rawTokens.map { VoiceStemmer.stem(it) }

        // Negation ("не открывай", "нет, не надо") is beyond slot NLU: guessing an
        // affirmative command would do the OPPOSITE of what was said. Agent decides.
        if (VoiceStemmer.stem("не") in stems || VoiceStemmer.stem("нет") in stems) {
            return ParseResult.Unrecognized
        }

        val actions = matchSlots(stems, VoiceLexicon.actionWords(lang))
        val devices = matchSlots(stems, VoiceLexicon.deviceWords(lang))
        val qualifiers = detectQualifiers(stems, lang)
        val number = detectNumber(rawTokens, lang)

        // "передний багажник" is NOT the rear tailgate; TRUNK has no front variant
        // in the NLU catalog. Hand to the agent (front_trunk_open/close there).
        if (DeviceSlot.TRUNK in devices && Qual.FRONT in qualifiers) return ParseResult.Unrecognized

        // Relative temperature implies the AC; resolved against the live snapshot
        // by VoiceController (the parser stays pure).
        if (ActionSlot.WARMER in actions) return ParseResult.RelativeTemp(1)
        if (ActionSlot.COOLER in actions) return ParseResult.RelativeTemp(-1)

        resolveVolume(actions, devices, number)?.let { return it }

        // Bare absolute value: a temperature/number with no verb means SET
        // (e.g. "температура 24", "24 градуса"). The catalog ValueSpec still
        // range-gates 16..30, so an out-of-range number yields Unrecognized.
        val effectiveActions = if (actions.isEmpty() && DeviceSlot.AC_TEMP in devices && number != null)
            setOf(ActionSlot.SET) else actions

        if (effectiveActions.isEmpty() || devices.isEmpty()) return ParseResult.Unrecognized

        val devices2 = disambiguateAirflow(devices, stems, lang)
        val (actions2, devices3) = narrowSeatOff(effectiveActions, devices2)
        val leveledActions = upgradeSeatLevel(actions2, devices3, number)

        // "все сиденья"/"сидений" (plural) targets BOTH seats. The catalog emits one
        // command per (action, device), so fan out per side; each side must resolve to
        // exactly ONE command, otherwise the utterance is ambiguous and goes to the
        // agent (issue #98). EN has no plural detection ("seats" stems to "seat"):
        // it reaches this branch only via the ALL qualifier ("all seats").
        val seatPresent = devices3.any { it.name.startsWith("SEAT") }
        val pluralSeat = VoiceStemmer.stem("сидения") in stems
        if (seatPresent && (Qual.ALL in qualifiers || pluralSeat)) {
            val perSide = listOf(this::driverSeat, this::passengerSeat).map { side ->
                val sideDevices = devices3.mapTo(LinkedHashSet()) {
                    if (it.name.startsWith("SEAT")) side(it) else it
                }
                val sideResolved = LinkedHashSet<String>()
                for (a in leveledActions) for (d in sideDevices) {
                    VoiceCatalog.resolve(a, d, number)?.let { sideResolved.add(it) }
                }
                sideResolved
            }
            return if (perSide.all { it.size == 1 }) ParseResult.Command(perSide.map { it.first() })
            else ParseResult.Unrecognized
        }

        val refinedDevices = refineDevices(devices3, qualifiers)

        val resolved = LinkedHashSet<String>()
        for (a in leveledActions) for (d in refinedDevices) {
            VoiceCatalog.resolve(a, d, number)?.let { resolved.add(it) }
        }
        return if (resolved.size == 1) ParseResult.Command(resolved.first())
        else ParseResult.Unrecognized
    }

    private fun <T> matchSlots(stems: List<String>, words: Map<T, List<String>>): Set<T> {
        val out = LinkedHashSet<T>()
        for ((slot, surfaces) in words) {
            val surfStems = surfaces.map { VoiceStemmer.stem(it) }
            if (stems.any { it in surfStems }) out.add(slot)
        }
        return out
    }

    private enum class Qual { DRIVER, PASSENGER, FRONT, REAR, LEFT, RIGHT, ALL }

    /** ALL positional markers present in the phrase (the old version returned only the
     *  FIRST match, so "заднее правое" lost RIGHT and opened both rear windows). */
    private fun detectQualifiers(stems: List<String>, lang: VoiceLang): Set<Qual> {
        val s = stems.toSet()
        fun has(vararg w: String) = w.any { VoiceStemmer.stem(it) in s }
        val out = LinkedHashSet<Qual>()
        if (lang == VoiceLang.RU) {
            if (has("водитель", "водителя", "водительское")) out.add(Qual.DRIVER)
            if (has("пассажир", "пассажира", "пассажирское")) out.add(Qual.PASSENGER)
            if (has("передние", "переднее", "передний", "передняя")) out.add(Qual.FRONT)
            if (has("задние", "заднее", "задний", "задняя")) out.add(Qual.REAR)
            if (has("левое", "левый", "левая")) out.add(Qual.LEFT)
            if (has("правое", "правый", "правая")) out.add(Qual.RIGHT)
            if (has("все", "всех")) out.add(Qual.ALL)
        } else {
            if (has("driver")) out.add(Qual.DRIVER)
            if (has("passenger")) out.add(Qual.PASSENGER)
            if (has("front")) out.add(Qual.FRONT)
            if (has("rear")) out.add(Qual.REAR)
            if (has("left")) out.add(Qual.LEFT)
            if (has("right")) out.add(Qual.RIGHT)
            if (has("all")) out.add(Qual.ALL)
        }
        return out
    }

    /** Map a qualifier SET to one window. Compound corners resolve first; a bare
     *  LEFT/RIGHT means the rear pair side (front sides are named driver/passenger). */
    private fun windowFor(quals: Set<Qual>): DeviceSlot = when {
        Qual.DRIVER in quals -> DeviceSlot.WINDOW_DRIVER
        Qual.PASSENGER in quals -> DeviceSlot.WINDOW_PASSENGER
        Qual.FRONT in quals && Qual.LEFT in quals -> DeviceSlot.WINDOW_DRIVER
        Qual.FRONT in quals && Qual.RIGHT in quals -> DeviceSlot.WINDOW_PASSENGER
        Qual.LEFT in quals -> DeviceSlot.WINDOW_REAR_LEFT
        Qual.RIGHT in quals -> DeviceSlot.WINDOW_REAR_RIGHT
        Qual.FRONT in quals -> DeviceSlot.WINDOW_FRONT
        Qual.REAR in quals -> DeviceSlot.WINDOW_REAR
        else -> DeviceSlot.WINDOW_ALL  // ALL or no qualifier
    }

    /** Collapse the ambiguous window/seat families to a single target. A bare window
     *  noun matches every window slot, so without a qualifier we default to the
     *  WINDOW_ALL aggregate; qualifiers select the specific window (compound corners
     *  included). A seat with no side qualifier defaults to the DRIVER's seat so a
     *  bare "подогрев сиденья" resolves to one command instead of falling through. */
    private fun refineDevices(devices: Set<DeviceSlot>, quals: Set<Qual>): Set<DeviceSlot> {
        val out = LinkedHashSet<DeviceSlot>()
        for (d in devices) {
            val isWindow = d.name.startsWith("WINDOW")
            val isSeat = d.name.startsWith("SEAT")
            when {
                isWindow -> out.add(windowFor(quals))
                isSeat && Qual.PASSENGER in quals -> out.add(passengerSeat(d))
                isSeat -> out.add(driverSeat(d))
                else -> out.add(d)
            }
        }
        return out
    }

    /** When a seat heat/vent command carries a level number, upgrade the base
     *  HEAT_1/VENT_1 action to the matching level slot. Levels 4..5 exist only in
     *  the agent catalog (no NLU slots) — bail to Unrecognized instead of silently
     *  firing level 1. Only applies when a seat device is present. */
    private fun upgradeSeatLevel(actions: Set<ActionSlot>, devices: Set<DeviceSlot>, number: Int?): Set<ActionSlot> {
        if (number == null || devices.none { it.name.startsWith("SEAT") }) return actions
        val out = LinkedHashSet<ActionSlot>()
        for (a in actions) out.add(
            when (a) {
                ActionSlot.HEAT_1 -> when (number) {
                    1 -> a; 2 -> ActionSlot.HEAT_2; 3 -> ActionSlot.HEAT_3
                    else -> return emptySet()
                }
                ActionSlot.VENT_1 -> when (number) {
                    1 -> a; 2 -> ActionSlot.VENT_2; 3 -> ActionSlot.VENT_3
                    else -> return emptySet()
                }
                else -> a
            }
        )
        return out
    }

    /** "обдув"/"вентиляция" is overloaded: it tags AC_FLOW (climate vent),
     *  DEFROST_FRONT (windshield) and rides on seat-vent phrasing. Collapse the
     *  overlap to ONE device by specificity so a generic "включи обдув" resolves
     *  to exactly one command instead of several. Pure device-level prune. */
    private fun disambiguateAirflow(devices: Set<DeviceSlot>, stems: List<String>, lang: VoiceLang): Set<DeviceSlot> {
        if (DeviceSlot.AC_FLOW !in devices && DeviceSlot.DEFROST_FRONT !in devices) return devices
        val hasSeat = devices.any { it.name.startsWith("SEAT") }
        val s = stems.toSet()
        fun has(vararg w: String) = w.any { VoiceStemmer.stem(it) in s }
        val windshield = if (lang == VoiceLang.RU) has("лобовое", "стекло") else has("windshield", "windscreen")
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
        if (devices.none { it.name.startsWith("SEAT") }) return actions to devices
        val heatNoun = ActionSlot.HEAT_1 in actions
        val ventNoun = ActionSlot.VENT_1 in actions
        if (heatNoun == ventNoun) return actions to devices  // neither or both: nothing to narrow
        val family = if (heatNoun) "HEAT" else "VENT"
        val narrowedDevices = devices.filterTo(LinkedHashSet()) {
            !it.name.startsWith("SEAT") || it.name.endsWith(family)
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

    private fun detectNumber(rawTokens: List<String>, lang: VoiceLang): Int? {
        rawTokens.firstNotNullOfOrNull { it.toIntOrNull() }?.let { return it }
        val numbers = VoiceLexicon.numberWords(lang)
        // try longest multi-word number first ("двадцать четыре")
        val joined = rawTokens.joinToString(" ")
        return numbers.entries
            .sortedByDescending { it.key.split(" ").size }
            .firstOrNull { joined.contains(it.key) }?.value
    }
}
