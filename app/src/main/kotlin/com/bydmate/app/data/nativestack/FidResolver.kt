package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.SentinelDecoder

/** autoservice READ address: which device to ask and which fid to ask for. */
data class FidAddress(val device: Int, val fid: Int)

/** What happened to one [FidEntry] when the firmware catalog was applied. */
enum class FidResolution {
    /** No catalog, no symbol, or the catalog agrees — the compiled constant is used. */
    CONST,

    /** The catalog knows the symbol under a different fid and that fid answered plausibly. */
    CATALOG,

    /** The catalog offered a different fid and it was refused — the constant stays. */
    REJECTED,
}

/** One line of the resolution, kept for the diagnostic dump. */
data class FidResolutionNote(
    val field: String,
    val symbol: String?,
    val constant: FidAddress,
    val used: FidAddress,
    /** Address the catalog pointed at, null when there was nothing to consider. */
    val candidate: FidAddress?,
    val outcome: FidResolution,
    /** Rendered probe reading, null when no probe was needed. */
    val probe: String? = null,
    /** Why a candidate was refused, null otherwise. */
    val reason: String? = null,
) {
    /** `param symbol dev const→used reason` for the dump. */
    fun render(): String = buildString {
        append(field)
        append(' ')
        append(symbol ?: "(no symbol)")
        append(" dev=${constant.device}")
        if (used.device != constant.device) append("→${used.device}")
        append(" fid=${constant.fid}")
        if (used.fid != constant.fid) append("→${used.fid}")
        if (candidate != null && candidate != used) append(" catalog=${candidate.fid}")
        if (probe != null) append(" probe=$probe")
        append(' ')
        append(
            when (outcome) {
                FidResolution.CONST -> "const"
                FidResolution.CATALOG -> "catalog"
                FidResolution.REJECTED -> "rejected (${reason ?: "?"})"
            }
        )
    }
}

/**
 * Immutable result of one resolution pass: every field's READ address plus the
 * reasoning behind the ones that were not simply the constant.
 */
class ResolvedFidTable(
    private val addresses: Map<String, FidAddress>,
    val notes: List<FidResolutionNote>,
    /** `constants` before any catalog arrived, else `daemon`/`file` + fingerprint. */
    val source: String,
    /**
     * INT_SCALED scale in force per field: [FidEntry.catalogScale] where the catalog moved
     * the address and the entry declares one, the entry's own scale everywhere else.
     * Empty for a table built without it — those fields fall back to the compiled scale.
     */
    private val scales: Map<String, Double> = emptyMap(),
) {
    /** Address for [field]; throws for a name that is not in [FidMap] (a code bug). */
    fun address(field: String): FidAddress =
        addresses[field] ?: throw IllegalArgumentException("unknown fid field: $field")

    fun fid(field: String): Int = address(field).fid

    fun device(field: String): Int = address(field).device

    /** Scale the INT_SCALED decoder must use for [field] with the address in force. */
    fun scale(field: String): Double =
        scales[field] ?: FidMap.byField[field]?.scale ?: 1.0

    val catalogCount: Int get() = notes.count { it.outcome == FidResolution.CATALOG }
    val rejectedCount: Int get() = notes.count { it.outcome == FidResolution.REJECTED }
    val constCount: Int get() = notes.count { it.outcome == FidResolution.CONST }
    val noSymbolCount: Int get() = notes.count { it.symbol == null }

    fun summary(): String =
        "resolved: $constCount const, $catalogCount catalog, $rejectedCount rejected, $noSymbolCount no-symbol"
}

/** One read the resolver wants to make before trusting a catalog fid. */
data class FidProbeRequest(val device: Int, val fid: Int, val transact: Int)

/**
 * Reads candidate addresses on the live car. Returns one raw autoservice word per
 * request, in the same order; null where the read itself failed (no answer, transport
 * down) as opposed to answering a sentinel.
 */
fun interface FidProbe {
    suspend fun read(requests: List<FidProbeRequest>): List<Int?>
}

/**
 * Picks the READ address of every [FidEntry] for the car the app is running on.
 *
 * The BYD SDK computes fid values per platform at class-init time, so our Leopard 3
 * constants are wrong on most other head units while the *symbol* behind them is the
 * same everywhere. Given the firmware's own catalog, this maps each entry's symbol back
 * to the number this car uses. Every step is conservative: anything unclear keeps the
 * constant, which is exactly today's behaviour.
 */
object FidResolver {

    /**
     * Nested SDK class prefix → `BYDAUTO_DEVICE_*` name. Uppercasing the prefix covers
     * almost all of them; the exceptions are spelled out.
     *
     * Only consulted for an entry whose fid the catalog actually moved: on Leopard 3 two
     * entries sit on a device their symbol's prefix does not name (autoWipers is a
     * `Setting.` symbol read from dev 1046, compressorW a `Power.` symbol read from dev
     * 1000), and re-deriving their device from the prefix would break a working read.
     */
    private val PREFIX_DEVICE: Map<String, String> = mapOf(
        "Ac" to "AC",
        "Adas" to "ADAS",
        "Bodywork" to "BODYWORK",
        "Charging" to "CHARGING",
        "Door" to "DOOR_LOCK",
        "Energy" to "ENERGY",
        "Engine" to "ENGINE",
        "Gb" to "GB",
        "Gearbox" to "GEARBOX",
        "Instrument" to "INSTRUMENT",
        "Light" to "LIGHT",
        "Ota" to "OTA",
        "Power" to "POWER",
        "Safety" to "SAFETY_BELT",
        "Sensor" to "SENSOR",
        "Setting" to "SETTING",
        "Speed" to "SPEED",
        "Statistic" to "STATISTIC",
        "Tyre" to "TYRE",
        "Wiper" to "WIPER",
    )

    /**
     * Physical envelope of a decoded probe reading, for the decoders whose own decode
     * step does not already enforce one (INT_PERCENT, INT_TEMP_C and INT_TEMP_C_OFS40
     * reject out-of-range values themselves). Deliberately short: a range that is too
     * tight would refuse a good fid, and the sentinel filter already catches the
     * "this address means nothing" case.
     */
    private val INT_RANGES: Map<Decoder, IntRange> = mapOf(
        Decoder.INT_KPA to 0..600,
    )

    private val FLOAT_RANGES: Map<Decoder, ClosedFloatingPointRange<Double>> = mapOf(
        Decoder.FLOAT_PERCENT to 0.0..100.0,
        Decoder.FLOAT_VOLT to 0.0..60.0,
    )

    /** Table that uses the compiled constant for everything — the state before a catalog arrives. */
    fun constants(entries: List<FidEntry>): ResolvedFidTable = ResolvedFidTable(
        addresses = entries.associate { it.field to FidAddress(it.device, it.fid) },
        notes = entries.map {
            val address = FidAddress(it.device, it.fid)
            FidResolutionNote(it.field, it.symbol, address, address, null, FidResolution.CONST)
        },
        source = "constants",
        scales = entries.associate { it.field to it.scale },
    )

    /** True when every probe read failed, i.e. the answer says nothing about the candidates. */
    class Outcome(val table: ResolvedFidTable, val probeTransportDead: Boolean)

    /**
     * Resolves [entries] against [catalog], probing the candidates through [probe].
     *
     * Rules, in order: no symbol / symbol absent from the catalog / catalog value equal to
     * the constant → constant, silently. A different value becomes a candidate, which is
     * dropped when it collides with another entry's address (a fid that already means
     * something else here cannot also mean this), and otherwise has to answer the probe
     * with a value that is neither a sentinel nor outside its decoder's envelope.
     *
     * [onlyWhereConstantFails] (the DM-i hybrid set, #394): the compiled address of every
     * candidate is probed as well, and the candidate is only considered when that read fails
     * (no answer or an error sentinel). A compiled address answering anything else, the words
     * an EV gives for an absent signal included, is alive and keeps the field on it, so every
     * car that reads its hybrid fids today keeps reading exactly those.
     */
    suspend fun resolve(
        entries: List<FidEntry>,
        catalog: FidCatalog,
        probe: FidProbe,
        source: String,
        onlyWhereConstantFails: Boolean = false,
    ): Outcome {
        val constant = entries.associate { it.field to FidAddress(it.device, it.fid) }
        val candidates = LinkedHashMap<String, FidAddress>()
        for (entry in entries) {
            val symbol = entry.symbol ?: continue
            val catalogFid = catalog.fidOf(symbol) ?: continue
            if (catalogFid == entry.fid) continue
            candidates[entry.field] = FidAddress(deviceFor(entry, symbol, catalog), catalogFid)
        }

        // Collision guard. An address another entry already reads means something else on
        // this car, so the candidate cannot be this entry's fid. Entries that share a symbol
        // are aliases of one address by construction and never collide with each other.
        val symbolOf = entries.associate { it.field to it.symbol }
        val occupied = HashMap<FidAddress, MutableSet<String?>>()
        for (entry in entries) {
            val address = candidates[entry.field] ?: constant.getValue(entry.field)
            occupied.getOrPut(address) { HashSet() } += entry.symbol
        }
        val rejected = HashMap<String, String>()
        for ((field, address) in candidates) {
            val owners = occupied[address].orEmpty()
            if (owners.any { it != symbolOf[field] }) rejected[field] = "collision"
        }

        // One probe round for everything that survived the collision guard.
        val toProbe = candidates.keys.filter { it !in rejected }.map { field -> entries.first { it.field == field } }
        val round = probeCandidates(toProbe, candidates, probe, onlyWhereConstantFails)
        rejected.putAll(round.rejected)

        val addresses = LinkedHashMap<String, FidAddress>()
        val scales = LinkedHashMap<String, Double>()
        val notes = ArrayList<FidResolutionNote>(entries.size)
        for (entry in entries) {
            val base = constant.getValue(entry.field)
            val candidate = candidates[entry.field]
            val reason = rejected[entry.field]
            val used = if (candidate != null && reason == null) candidate else base
            addresses[entry.field] = used
            val onCatalogAddress = candidate != null && reason == null
            scales[entry.field] =
                if (onCatalogAddress) entry.catalogScale ?: entry.scale else entry.scale
            notes += FidResolutionNote(
                field = entry.field,
                symbol = entry.symbol,
                constant = base,
                used = used,
                candidate = candidate,
                outcome = when {
                    candidate == null -> FidResolution.CONST
                    reason == null -> FidResolution.CATALOG
                    else -> FidResolution.REJECTED
                },
                probe = round.probeText[entry.field],
                reason = reason,
            )
        }
        notes.filter { it.outcome != FidResolution.CONST }
            .forEach { android.util.Log.i(TAG, "fid resolve: ${it.render()}") }
        return Outcome(ResolvedFidTable(addresses, notes, source, scales), round.transportDead)
    }

    private class ProbeRound(
        val rejected: Map<String, String>,
        val probeText: Map<String, String>,
        val transportDead: Boolean,
    )

    /**
     * Reads every candidate of [toProbe] once and judges the answer. With
     * [onlyWhereConstantFails] the compiled address of each is read in the same round, after
     * the candidates, and a compiled address that answers a value refuses its candidate.
     */
    private suspend fun probeCandidates(
        toProbe: List<FidEntry>,
        candidates: Map<String, FidAddress>,
        probe: FidProbe,
        onlyWhereConstantFails: Boolean,
    ): ProbeRound {
        val requests = toProbe.map { entry ->
            val address = candidates.getValue(entry.field)
            FidProbeRequest(address.device, address.fid, entry.transact)
        } + if (!onlyWhereConstantFails) emptyList() else toProbe.map { entry ->
            FidProbeRequest(entry.device, entry.fid, entry.transact)
        }
        val samples: List<Int?> = if (requests.isEmpty()) emptyList() else {
            val answered = runCatching { probe.read(requests) }.getOrElse { error ->
                android.util.Log.w(TAG, "fid resolve: probe failed (${error.message}), keeping constants")
                emptyList()
            }
            if (answered.size == requests.size) answered else List(requests.size) { null }
        }
        val rejected = HashMap<String, String>()
        val probeText = HashMap<String, String>()
        toProbe.forEachIndexed { index, entry ->
            val word = samples.getOrNull(index)
            if (word == null) {
                rejected[entry.field] = "no answer"
                return@forEachIndexed
            }
            val verdict = judge(entry, word)
            probeText[entry.field] = verdict.rendered
            verdict.rejection?.let { rejected[entry.field] = it }
            val constantWord = if (onlyWhereConstantFails) samples.getOrNull(toProbe.size + index) else null
            if (constantWord != null && !constantFails(entry, constantWord)) {
                rejected[entry.field] = "constant alive: $constantWord"
            }
        }
        return ProbeRound(rejected, probeText, requests.isNotEmpty() && samples.all { it == null })
    }

    /**
     * Device id a moved fid should be read from: the one the catalog gives for the symbol's
     * own namespace, falling back to the entry's device for a prefix we do not map or a
     * device name this firmware does not list.
     */
    private fun deviceFor(entry: FidEntry, symbol: String, catalog: FidCatalog): Int {
        val prefix = symbol.substringBefore('.')
        val deviceName = PREFIX_DEVICE[prefix] ?: return entry.device
        return catalog.deviceOf(deviceName) ?: entry.device
    }

    /**
     * True when a compiled address answered an error rather than a value: the link, transact
     * and direction sentinels, or a float read that is not a number. The "absent" words an EV
     * answers (255, 2046, 1048575) are answers and do not count.
     */
    private fun constantFails(entry: FidEntry, word: Int): Boolean =
        word in ERROR_WORDS || (entry.transact == 7 && SentinelDecoder.parseFloatFromShellInt(word) == null)

    private val ERROR_WORDS = setOf(SentinelDecoder.FEATURE_LINK_ERROR, -10011, -10013)

    private class Verdict(val rendered: String, val rejection: String?)

    /** Decodes one raw probe word exactly like the read path would, then range-checks it. */
    private fun judge(entry: FidEntry, word: Int): Verdict = when (entry.transact) {
        5 -> {
            if (SentinelDecoder.decodeInt(word) == null) Verdict(word.toString(), "sentinel")
            else if (entry.decoder == Decoder.INT_SCALED) {
                // Only catalog candidates are ever probed, so the catalog scale is the one
                // this reading would be decoded with if the candidate is accepted.
                val value = ParamDecoder.decodeScaled(word, entry.catalogScale ?: entry.scale)
                Verdict(value?.toString() ?: word.toString(), if (value == null) "sentinel" else null)
            } else {
                val value = ParamDecoder.decodeInt(word, entry.decoder)
                when {
                    value == null -> Verdict(word.toString(), "out of range")
                    INT_RANGES[entry.decoder]?.contains(value) == false -> Verdict("$value", "out of range")
                    else -> Verdict("$value", null)
                }
            }
        }
        7 -> {
            val value = ParamDecoder.decodeFloat(word, entry.decoder)
            when {
                value == null -> Verdict(word.toString(), "sentinel")
                FLOAT_RANGES[entry.decoder]?.contains(value) == false -> Verdict("$value", "out of range")
                else -> Verdict("$value", null)
            }
        }
        else -> Verdict(word.toString(), "unsupported transact")
    }

    private const val TAG = "FidCatalog"
}
