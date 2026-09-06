package com.bydmate.app.helper

/**
 * Pure line-filtering core of the cluster-display diagnostic snapshot (TX_CLUSTER_DISPLAY_DIAG).
 *
 * The daemon only runs the commands and logs; every decision about what is worth keeping out of a
 * multi-kilobyte dumpsys lives here so it can be tested without a device. All outputs are already
 * trimmed and capped at [MAX_LINE] characters — the caller just prefixes them with "cdiag: ".
 */
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

    private val SERVICE_NAME = Regex("""^\s*\d+\s+([^:]+):""")

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
        val ordered = nonDefault + default
        return Ranked(ordered.take(max).map { it.take(MAX_LINE) }, (ordered.size - max).coerceAtLeast(0))
    }
}
