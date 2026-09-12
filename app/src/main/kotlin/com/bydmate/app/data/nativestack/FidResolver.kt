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
) {
    /** Address for [field]; throws for a name that is not in [FidMap] (a code bug). */
    fun address(field: String): FidAddress =
        addresses[field] ?: throw IllegalArgumentException("unknown fid field: $field")

    fun fid(field: String): Int = address(field).fid

    fun device(field: String): Int = address(field).device

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
     */
    suspend fun resolve(
        entries: List<FidEntry>,
        catalog: FidCatalog,
        probe: FidProbe,
        source: String,
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
        val toProbe = candidates.keys.filter { it !in rejected }
        val requests = toProbe.map { field ->
            val entry = entries.first { it.field == field }
            val address = candidates.getValue(field)
            FidProbeRequest(address.device, address.fid, entry.transact)
        }
        val samples: List<Int?> = if (requests.isEmpty()) emptyList() else {
            val answered = runCatching { probe.read(requests) }.getOrElse { error ->
                android.util.Log.w(TAG, "fid resolve: probe failed (${error.message}), keeping constants")
                emptyList()
            }
            if (answered.size == requests.size) answered else List(requests.size) { null }
        }
        val probeText = HashMap<String, String>()
        toProbe.forEachIndexed { index, field ->
            val entry = entries.first { it.field == field }
            val word = samples.getOrNull(index)
            if (word == null) {
                rejected[field] = "no answer"
                return@forEachIndexed
            }
            val verdict = judge(entry, word)
            probeText[field] = verdict.rendered
            verdict.rejection?.let { rejected[field] = it }
        }
        val probeTransportDead = requests.isNotEmpty() && samples.all { it == null }

        val addresses = LinkedHashMap<String, FidAddress>()
        val notes = ArrayList<FidResolutionNote>(entries.size)
        for (entry in entries) {
            val base = constant.getValue(entry.field)
            val candidate = candidates[entry.field]
            val reason = rejected[entry.field]
            val used = if (candidate != null && reason == null) candidate else base
            addresses[entry.field] = used
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
                probe = probeText[entry.field],
                reason = reason,
            )
        }
        notes.filter { it.outcome != FidResolution.CONST }
            .forEach { android.util.Log.i(TAG, "fid resolve: ${it.render()}") }
        return Outcome(ResolvedFidTable(addresses, notes, source), probeTransportDead)
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

    private class Verdict(val rendered: String, val rejection: String?)

    /** Decodes one raw probe word exactly like the read path would, then range-checks it. */
    private fun judge(entry: FidEntry, word: Int): Verdict = when (entry.transact) {
        5 -> {
            if (SentinelDecoder.decodeInt(word) == null) Verdict(word.toString(), "sentinel")
            else if (entry.decoder == Decoder.INT_SCALED) {
                val value = ParamDecoder.decodeScaled(word, entry.scale)
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
