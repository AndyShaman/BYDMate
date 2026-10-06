package com.bydmate.app.navdata

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/** Extracts guidance widgets from a Navigator a11y tree into NavGuidance.
 *  View ids are package-relative (the .inhouse / .rustore builds prefix them with their
 *  own package), so the prefix is derived from the root node. Every found node is
 *  recycled after reading: the a11y feed fires many times per second and the framework
 *  node pool is finite on DiLink (Codex fix 5). */
@Suppress("TooManyFunctions") // the parse plus one probe per field-diagnostics log line
object NavA11yExtractor {
    private const val TAG = "NavA11yExtractor"
    @Volatile private var readingSecondLayout = false

    /** The maneuver image; read by the parse and by [probeManeuver]. */
    private const val MANEUVER_ID = "image_maneuverballoon_maneuver"
    private const val DISTANCE_ID = "text_maneuverballoon_distance"
    private const val METRICS_ID = "text_maneuverballoon_metrics"
    private const val NEXT_STREET_ID = "text_nextstreet"
    private const val STATUS_ID = "status_panel_text"
    private const val ETA_TIME_ID = "textview_eta_time"
    /** The Navigator's second guidance layout (ids as Denza Lab reads them on a projected Navigator):
     *  issue #199, on the Han cluster the balloon leaves the window about a kilometre before a turn. */
    private const val NEXT_MANEUVER_ID = "next_maneuver_image"
    private const val NEXT_DISTANCE_ID = "next_maneuver_distance_value"
    private const val NEXT_UNIT_ID = "next_maneuver_distance_unit"
    /** Same layout family; whether it is the next maneuver or the one after is unknown, so it is
     *  only counted, never read. */
    private const val UPCOMING_ID = "next_upcoming_maneuver"
    /** The widgets the parse decides guidance by, counted by [countIds] under these names. */
    private val TRACED_IDS = listOf(
        "maneuver" to MANEUVER_ID, "distance" to DISTANCE_ID, "metrics" to METRICS_ID,
        "nextstreet" to NEXT_STREET_ID, "status" to STATUS_ID, "eta" to ETA_TIME_ID,
        "next" to NEXT_MANEUVER_ID, "nextdist" to NEXT_DISTANCE_ID, "upcoming" to UPCOMING_ID,
    )
    /** Children of the maneuver node and extras keys per node the unknown-maneuver probe lists. */
    private const val MAX_PROBE_CHILDREN = 6
    private const val MAX_PROBE_EXTRAS = 8
    private val SPACES = Regex("""[\s\p{Z}]+""")
    private val EDGE_PUNCT = Regex("""^\p{Punct}+|\p{Punct}+$""")

    sealed class ReadResult {
        object NotNavigator : ReadResult()
        object NoGuidance : ReadResult()
        data class Guidance(val data: NavGuidance) : ReadResult()

        override fun toString(): String = this::class.java.simpleName
    }

    fun read(root: AccessibilityNodeInfo?): ReadResult {
        if (root == null) return ReadResult.NotNavigator
        val pkg = root.packageName?.toString() ?: return ReadResult.NotNavigator
        if (pkg !in NavPackages.GUIDANCE_SOURCES) return ReadResult.NotNavigator
        val raw = NavGuidanceParser.RawFields(
            maneuverDesc = descOf(root, "$pkg:id/$MANEUVER_ID"),
            exitNumber = textOf(root, "$pkg:id/exit_number_text"),
            distance = textOf(root, "$pkg:id/$DISTANCE_ID"),
            distanceUnit = textOf(root, "$pkg:id/$METRICS_ID"),
            nextStreet = textOf(root, "$pkg:id/$NEXT_STREET_ID"),
            statusPanel = textOf(root, "$pkg:id/$STATUS_ID"),
            etaTime = descOrTextOf(root, "$pkg:id/$ETA_TIME_ID"),
            etaDistance = textOf(root, "$pkg:id/textview_eta_distance"),
            speedLimit = textOf(root, "$pkg:id/text_speedlimit"),
        )
        // The second layout is read only when the balloon gave nothing: where the balloon is
        // on screen, the read is the same as before it.
        NavGuidanceParser.parse(raw)?.let {
            noteLayout(secondLayout = false)
            return ReadResult.Guidance(it)
        }
        // exitNumber is the balloon's: dropped, or a stale one turns the turn into a roundabout exit.
        val parsed = NavGuidanceParser.parse(raw.copy(
            exitNumber = null,
            maneuverDesc = descOf(root, "$pkg:id/$NEXT_MANEUVER_ID"),
            distance = textOf(root, "$pkg:id/$NEXT_DISTANCE_ID"),
            distanceUnit = textOf(root, "$pkg:id/$NEXT_UNIT_ID"),
        )) ?: return ReadResult.NoGuidance
        noteLayout(secondLayout = true)
        return ReadResult.Guidance(parsed)
    }

    /** One log line per switch between the balloon and the second layout, never per read. */
    private fun noteLayout(secondLayout: Boolean) {
        if (secondLayout == readingSecondLayout) return
        readingSecondLayout = secondLayout
        Log.i(TAG, if (secondLayout) "guidance read from the second layout" else "guidance read from the balloon again")
    }

    /** Raw view of the maneuver image for the unknown-maneuver log: how many nodes carry its id,
     *  and the class and masked contentDescription of the one the parse reads (the first non-blank,
     *  else the first), then that node's other properties and its first [MAX_PROBE_CHILDREN]
     *  children's (#198: which property, if any, carries the direction). The nodes are recycled;
     *  [root] stays the caller's. */
    internal fun probeManeuver(root: AccessibilityNodeInfo): String {
        val pkg = root.packageName?.toString() ?: return "found=0"
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$MANEUVER_ID") }
            .getOrNull().orEmpty()
        try {
            if (nodes.isEmpty()) return "found=0"
            val descs = nodes.map { runCatching { it.contentDescription?.toString() }.getOrNull() }
            val read = descs.indexOfFirst { !it.isNullOrBlank() }.coerceAtLeast(0)
            val cls = runCatching { nodes[read].className?.toString() }.getOrNull()
            return "found=${nodes.size} class=$cls desc=${maskValue(descs[read])} " +
                "node${formatNode(nodeFacts(nodes[read], withDesc = false))} ${probeChildren(nodes[read])}"
        } finally {
            @Suppress("DEPRECATION")
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    /** `children=N` and the facts of the first [MAX_PROBE_CHILDREN]; each child is recycled. */
    private fun probeChildren(node: AccessibilityNodeInfo): String {
        val count = runCatching { node.childCount }.getOrNull() ?: return "children=?"
        val shown = (0 until minOf(count, MAX_PROBE_CHILDREN)).joinToString(" ") { i ->
            val child = runCatching { node.getChild(i) }.getOrNull() ?: return@joinToString "[$i]null"
            try {
                "[$i]${formatNode(nodeFacts(child, withDesc = true))}"
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
        return if (shown.isEmpty()) "children=$count" else "children=$count $shown"
    }

    /** What a node exposes for the probe line; every value is masked by [formatNode]. */
    internal data class NodeFacts(
        val viewId: String?,
        val className: String?,
        /** Log name -> raw value; null = the property is absent and is left out of the line. */
        val fields: List<Pair<String, String?>>,
        val drawingOrder: Int?,
        val selected: Boolean?,
        val checked: Boolean?,
        val extrasKeys: List<String>,
    )

    /** [withDesc] false for the maneuver node, whose description the line already gives;
     *  stateDescription exists from API 30. */
    private fun nodeFacts(node: AccessibilityNodeInfo, withDesc: Boolean): NodeFacts {
        fun read(get: () -> CharSequence?): String? = runCatching { get()?.toString() }.getOrNull()
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) read { node.stateDescription } else null
        return NodeFacts(
            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
            className = read { node.className },
            fields = listOf(
                "text" to read { node.text },
                "desc" to if (withDesc) read { node.contentDescription } else null,
                "state" to state,
                "tooltip" to read { node.tooltipText },
                "hint" to read { node.hintText },
                "pane" to read { node.paneTitle },
            ),
            drawingOrder = runCatching { node.drawingOrder }.getOrNull(),
            selected = runCatching { node.isSelected }.getOrNull(),
            checked = runCatching { node.isChecked }.getOrNull(),
            extrasKeys = runCatching { node.extras?.keySet()?.toList() }.getOrNull().orEmpty(),
        )
    }

    /** One node for the probe line: the id without its package, the class without its package,
     *  each present field masked by [maskValue], `?` for what could not be read. */
    internal fun formatNode(facts: NodeFacts): String {
        val fields = facts.fields.filter { it.second != null }
            .joinToString("") { (name, value) -> " $name=${maskValue(value)}" }
        val extras = facts.extrasKeys.sorted().take(MAX_PROBE_EXTRAS).joinToString(",")
        return "{id=${facts.viewId?.substringAfter(":id/") ?: "?"} " +
            "cls=${facts.className?.substringAfterLast('.') ?: "?"}$fields " +
            "order=${facts.drawingOrder ?: "?"} sel=${facts.selected ?: "?"} chk=${facts.checked ?: "?"} " +
            "extras=[$extras]}"
    }

    /** A node value is kept only when it is made of maneuver vocabulary (a table phrase or only
     *  vocabulary words), else `*`: any other text may be the route's street. Its length always. */
    internal fun maskValue(raw: String?): String {
        if (raw == null) return "null"
        val words = raw.split(SPACES).filter { it.isNotEmpty() }
        val vocabulary = NavManeuverCodes.matchedPhrase(raw) == raw.trim().lowercase() ||
            (words.isNotEmpty() && words.all { NavManeuverCodes.isVocabularyWord(it.lowercase().replace(EDGE_PUNCT, "")) })
        return if (vocabulary) "\"$raw\" len=${raw.length}" else "* len=${raw.length}"
    }

    /** The donor's guidance test: a node with the maneuver icon, maneuver distance or next street
     *  id exists, whatever its text. For a read the parse found no guidance in; the nodes are
     *  recycled, [root] stays the caller's. */
    internal fun hasGuidanceNodes(root: AccessibilityNodeInfo): Boolean {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: return false
        return listOf(MANEUVER_ID, DISTANCE_ID, NEXT_STREET_ID, NEXT_MANEUVER_ID, NEXT_DISTANCE_ID).any { id ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }.getOrNull()
            @Suppress("DEPRECATION")
            nodes?.forEach { runCatching { it.recycle() } }
            !nodes.isNullOrEmpty()
        }
    }

    /** The window [root] is drawn in, for the no-guidance log: display (API 30+), window id,
     *  type and its active/focused flags; `?` for what cannot be read. [root] stays the caller's. */
    internal fun windowFacts(root: AccessibilityNodeInfo): String {
        val windowId = runCatching { root.windowId }.getOrNull()
        val window = runCatching { root.window }.getOrNull()
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { window?.displayId }.getOrNull()
            } else null
            val type = runCatching { window?.type }.getOrNull()
            val active = runCatching { window?.isActive }.getOrNull()
            val focused = runCatching { window?.isFocused }.getOrNull()
            return "display=${display ?: "?"} window=${windowId ?: "?"} type=${type ?: "?"} " +
                "active=${active ?: "?"} focused=${focused ?: "?"}"
        } finally {
            @Suppress("DEPRECATION")
            runCatching { window?.recycle() }
        }
    }

    /** How many nodes carry each id the parse decides guidance by, for the no-guidance log:
     *  counts only, `?` for a lookup that threw. The nodes are recycled; [root] stays the caller's. */
    internal fun countIds(root: AccessibilityNodeInfo): String {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        return TRACED_IDS.joinToString(" ", prefix = "ids[", postfix = "]") { (name, id) ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }.getOrNull()
            @Suppress("DEPRECATION")
            nodes?.forEach { runCatching { it.recycle() } }
            "$name=${nodes?.size ?: "?"}"
        }
    }

    private fun textOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.text?.toString() }

    private fun descOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.contentDescription?.toString() }

    private fun descOrTextOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.contentDescription?.toString() ?: it.text?.toString() }

    private inline fun readNodes(
        root: AccessibilityNodeInfo,
        viewId: String,
        crossinline extract: (AccessibilityNodeInfo) -> String?,
    ): String? = runCatching {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return@runCatching null
        var value: String? = null
        for (node in nodes) {
            // extract() guarded per node: a stale node throwing must not leak the rest unrecycled
            if (value == null) value = runCatching { extract(node) }.getOrNull()?.takeIf { it.isNotBlank() }
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
        value
    }.getOrNull()
}
