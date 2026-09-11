package com.bydmate.app.helper

/**
 * Pure line-filtering core of the cluster-display diagnostic snapshot (TX_CLUSTER_DISPLAY_DIAG).
 *
 * The daemon only runs the commands and logs; every decision about what is worth keeping out of a
 * multi-kilobyte dumpsys lives here so it can be tested without a device. All outputs are already
 * trimmed and capped at [MAX_LINE] characters — the caller just prefixes them with "cdiag: ".
 */
/**
 * One display of the firmware's display stack as the daemon reads it out of `dumpsys display`
 * under shell uid. This is the app-uid [android.view.Display] the projection cannot see on
 * firmwares that whitelist DisplayManager per app (DiLink 4.0, issue #194), reduced to the
 * fields the cluster pick needs.
 *
 * [ownerPkg]/[ownerUid] are null/-1 for a physical display (dumpsys prints no owner for them)
 * and [flags] is empty when the dump carries none.
 */
data class DisplayDevice(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val ownerPkg: String?,
    val ownerUid: Int,
    val flags: List<String>,
)

internal object ClusterDisplayDiag {

    const val MAX_LINE = 300
    const val MAX_DISPLAY_LINES = 8
    const val MAX_SURFACE_FLINGER_LINES = 6
    const val MAX_BYD_PROPS = 3

    /** Kept lines plus how many matching lines did not fit the budget. */
    data class Ranked(val kept: List<String>, val dropped: Int)

    /** Props we ask getprop for, in the order they appear in the summary line. */
    val PROP_KEYS: List<String> = listOf(
        "ro.build.version.sdk",
        "ro.build.version.release",
        "ro.build.display.id",
        "ro.build.product",
        "ro.product.model",
        "ro.board.platform",
        "ro.build.system.fission_single_os",
    )

    private val PROP_LABELS = listOf(
        "sdk", "rel", "id", "product", "model", "platform", "fission_single_os",
    )

    private val DISPLAY_KEYWORDS = listOf(
        "DisplayDeviceInfo{", "mDisplayId=", "DisplayInfo{", "uniqueId=", "mBaseDisplayInfo",
        "name=", "type ", "flags ", "state ", "owner", "layerStack",
    )

    private val SURFACE_FLINGER_KEYWORDS = listOf(
        "Display ", "layerStack", "displayId", "name=", "physical", "virtual",
    )

    /** Markers of the main head-unit screen; anything without them is ranked first. */
    private val DEFAULT_DISPLAY_MARKERS = listOf("displayid=0", "built-in", "\"tela\"")

    /** Within the non-default group, lines carrying flags/owner/id info outrank plain noise. */
    private val NON_DEFAULT_PRIORITY_MARKERS = listOf("flags ", "FLAG_", "mDisplayId=")

    private val SERVICE_NAME = Regex("""^\s*\d+\s+([^:]+):""")

    private val DISPLAY_DEVICE_NAME = Regex("""DisplayDeviceInfo\{"([^"]*)"""")
    private val DISPLAY_UNIQUE_ID = Regex("""uniqueId="([^"]*)"""")
    private val DISPLAY_DIMENSIONS = Regex("""(\d+)\s*x\s*(\d+)""")
    private val DISPLAY_TYPE = Regex("""\btype\s+(\w+)""")
    private val DISPLAY_STATE = Regex("""\bstate\s+(\w+)""")
    private val DISPLAY_OWNER = Regex("""\bowner\s+(\S+\s*\(uid\s+\d+\))""")
    private val DISPLAY_FLAG = Regex("""FLAG_\w+""")

    /** `mBaseDisplayInfo=DisplayInfo{"<name>, displayId N", ...` — the name/id link of a logical display. */
    private val LOGICAL_DISPLAY = Regex("""DisplayInfo\{"([^"]*), displayId (\d+)"""")
    private val DISPLAY_REAL_SIZE = Regex("""\breal (\d+) x (\d+)""")
    private val DISPLAY_DENSITY = Regex("""\bdensity (\d+)""")
    private val DISPLAY_OWNER_PARTS = Regex("""\bowner (\S+) \(uid (\d+)\)""")

    /**
     * Builds the one-line props summary from `key=value` output. A key the firmware does not
     * define is reported as an empty value rather than omitted — its absence is itself a signal.
     */
    fun propsLine(raw: String): String {
        val values = raw.lines().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()
        return PROP_KEYS.indices.joinToString(" ") { i ->
            "${PROP_LABELS[i]}=${values[PROP_KEYS[i]].orEmpty()}"
        }.take(MAX_LINE)
    }

    /** `ro.byd.*` props: up to [MAX_BYD_PROPS] entries on one line plus the count of the rest. */
    fun bydPropsLine(raw: String, max: Int = MAX_BYD_PROPS): String {
        val entries = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return "byd props: none"
        val head = entries.take(max).joinToString(", ")
        val rest = entries.size - minOf(entries.size, max)
        return ("byd props: " + head + if (rest > 0) " +$rest more" else "").take(MAX_LINE)
    }

    /** Projection-related services on one line: names from `service list`, both `service check`
     *  spellings of the cluster compositor, and the graphics device nodes. */
    fun servicesLine(
        serviceList: String, checkSnake: String, checkCamel: String, graphicsNodes: String,
    ): String {
        val names = serviceList.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) null
            else SERVICE_NAME.find(line)?.groupValues?.get(1)?.trim() ?: trimmed
        }.take(4)
        return ("services container=[${names.joinToString(", ")}] " +
            "check_snake=${checkSnake.trim().ifEmpty { "-" }} " +
            "check_camel=${checkCamel.trim().ifEmpty { "-" }} " +
            "graphics=${graphicsNodes.trim().ifEmpty { "-" }}").take(MAX_LINE)
    }

    /** Display entries out of `dumpsys display`, non-default displays first. */
    fun displayLines(raw: String, max: Int = MAX_DISPLAY_LINES): Ranked =
        rank(raw, DISPLAY_KEYWORDS, max)

    /**
     * One compact, never-truncated-of-its-important-parts line per `DisplayDeviceInfo{` entry in
     * `dumpsys display`: name, resolution, type, state, owner (with uid) and flags survive even
     * when [MAX_LINE] would otherwise cut them off; uniqueId is the part sacrificed if any is.
     */
    fun displaySummaries(raw: String): List<String> =
        raw.lines().filter { it.contains("DisplayDeviceInfo{") }.map { line ->
            val name = DISPLAY_DEVICE_NAME.find(line)?.groupValues?.get(1) ?: "?"
            val dims = DISPLAY_DIMENSIONS.find(line)
                ?.let { "${it.groupValues[1]}x${it.groupValues[2]}" } ?: "?"
            val type = DISPLAY_TYPE.find(line)?.groupValues?.get(1) ?: "?"
            val state = DISPLAY_STATE.find(line)?.groupValues?.get(1) ?: "?"
            val owner = DISPLAY_OWNER.find(line)?.groupValues?.get(1)?.trim() ?: "?"
            val flags = DISPLAY_FLAG.findAll(line).map { it.value }.joinToString(",").ifEmpty { "-" }
            val uniqueId = DISPLAY_UNIQUE_ID.find(line)?.groupValues?.get(1) ?: "?"
            ("dev: name=\"$name\" $dims type=$type state=$state owner=$owner flags=$flags " +
                "uniqueId=$uniqueId").take(MAX_LINE)
        }

    /**
     * Every logical display of `dumpsys display`, joined with the physical/virtual device behind
     * it (TX_LIST_DISPLAYS). The display id and the name come from the logical section
     * (`mBaseDisplayInfo=DisplayInfo{"<name>, displayId N"`), because that is the id an
     * `am start --display` understands; size, density, owner and flags come from the
     * `DisplayDeviceInfo{"<name>"...}` line of the same name, which is where dumpsys prints them.
     *
     * A display whose device line is missing (or truncated before the owner/flags) still comes
     * back, with the size taken from the logical `real W x H`, no owner and no flags: on the
     * firmware this was written for the owner is a system uid anyway, and dropping the display
     * would hide the only cluster surface it has.
     */
    fun parseDisplayDevices(raw: String): List<DisplayDevice> {
        val devices = HashMap<String, String>()
        raw.lines().filter { it.contains("DisplayDeviceInfo{") }.forEach { line ->
            val name = DISPLAY_DEVICE_NAME.find(line)?.groupValues?.get(1) ?: return@forEach
            devices.putIfAbsent(name, line)
        }
        val seen = HashSet<Int>()
        return raw.lines().filter { it.contains("mBaseDisplayInfo=") }.mapNotNull { line ->
            val match = LOGICAL_DISPLAY.find(line) ?: return@mapNotNull null
            val name = match.groupValues[1]
            val id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val device = devices[name]
            val size = DISPLAY_REAL_SIZE.find(line)
                ?: device?.let { DISPLAY_DIMENSIONS.find(it) }
            val owner = device?.let { DISPLAY_OWNER_PARTS.find(it) }
            DisplayDevice(
                id = id,
                name = name,
                width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                densityDpi = (device?.let { DISPLAY_DENSITY.find(it) } ?: DISPLAY_DENSITY.find(line))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                ownerPkg = owner?.groupValues?.get(1),
                ownerUid = owner?.groupValues?.get(2)?.toIntOrNull() ?: -1,
                flags = device?.let { d -> DISPLAY_FLAG.findAll(d).map { it.value }.toList() }.orEmpty(),
            )
        }
    }

    /** Display entries out of `dumpsys SurfaceFlinger`, non-default displays first. */
    fun surfaceFlingerLines(raw: String, max: Int = MAX_SURFACE_FLINGER_LINES): Ranked =
        rank(raw, SURFACE_FLINGER_KEYWORDS, max)

    /** True when the SurfaceFlinger `--displays` argument was not understood by this build. */
    fun surfaceFlingerFallbackNeeded(raw: String): Boolean =
        raw.isBlank() || raw.contains("unknown", ignoreCase = true) ||
            raw.contains("Usage", ignoreCase = true)

    private fun rank(raw: String, keywords: List<String>, max: Int): Ranked {
        val matched = raw.lines().map { it.trim() }
            .filter { line -> line.isNotEmpty() && keywords.any { line.contains(it) } }
        val (nonDefault, default) = matched.partition { line ->
            DEFAULT_DISPLAY_MARKERS.none { line.contains(it, ignoreCase = true) }
        }
        val (nonDefaultPriority, nonDefaultRest) = nonDefault.partition { line ->
            NON_DEFAULT_PRIORITY_MARKERS.any { line.contains(it) }
        }
        val ordered = nonDefaultPriority + nonDefaultRest + default
        return Ranked(ordered.take(max).map { it.take(MAX_LINE) }, (ordered.size - max).coerceAtLeast(0))
    }
}
