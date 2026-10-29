package com.bydmate.app.data.dtc

/** The texts of the scan message and card, in the app language; a plain implementation in tests. */
interface DtcLabels {
    val title: String
    fun checked(at: String, answered: Int, total: Int): String
    val nowHeader: String
    val oldHeader: String
    val pendingMark: String
    val noErrors: String
    val footer: String
    fun blockName(name: DtcBlockName): String
    /** [count] blocks counted codes but their list could not be read. */
    fun unread(count: Int): String
    /** [count] codes left out to fit one message. */
    fun moreCodes(count: Int): String
    /** The line under U1166-87 of the ADAS block: the key was not seen, e.g. after a remote start. */
    val keyHint: String
    /** One line before the closing note when a description came from similar models. */
    val similarNote: String
    /** The app language is Chinese: descriptions in Chinese, else in English. */
    val zh: Boolean get() = false
}

object DtcTelegramText {
    /** Telegram's limit for one text message. */
    private const val MAX_CHARS = 4096

    /** The code the ADAS block sets when it did not see the key; it gets [DtcLabels.keyHint]. */
    const val KEY_CODE = "U1166-87"
    const val KEY_CLASS = "ADAS"

    /**
     * Plain text (no markup) for one manual send; [checkedAt] is the time already formatted. The
     * codes are grouped by block under «now» and «old»; [describe] gives the description of a code,
     * Chinese when [zh], else English. A list too long for one message keeps as many codes as fit
     * and says how many were left out.
     */
    fun build(
        summary: DtcScanSummary,
        checkedAt: String,
        labels: DtcLabels,
        describe: (DtcFound) -> DtcDescription? = { null },
        zh: Boolean = false,
    ): String {
        val all = summary.found.filter { it.row.kind == DtcKind.NOW } + summary.found.filter { it.row.kind != DtcKind.NOW }
        val descriptions = all.associateWith(describe)
        var shown = all.size
        var text = render(summary, all, shown, checkedAt, labels, descriptions, zh)
        while (text.length > MAX_CHARS && shown > 0) {
            shown--
            text = render(summary, all, shown, checkedAt, labels, descriptions, zh)
        }
        return text
    }

    /** True for U1166-87 of the ADAS block, whatever its description. */
    fun isKeyCode(f: DtcFound): Boolean = f.row.code == KEY_CODE && DtcBlockClass.of(f.block) == KEY_CLASS

    @Suppress("LongParameterList") // one render pass of build()
    private fun render(
        summary: DtcScanSummary,
        all: List<DtcFound>,
        shown: Int,
        checkedAt: String,
        labels: DtcLabels,
        descriptions: Map<DtcFound, DtcDescription?>,
        zh: Boolean,
    ): String = buildString {
        appendLine(labels.title)
        appendLine(labels.checked(checkedAt, summary.answered, summary.total))
        appendLine()
        val kept = all.take(shown)
        val now = kept.filter { it.row.kind == DtcKind.NOW }
        val old = kept.filter { it.row.kind != DtcKind.NOW }
        val more = labels.moreCodes(all.size - shown).takeIf { shown < all.size }
        if (all.isEmpty() && summary.unreadBlocks == 0) {
            appendLine(labels.noErrors)
            appendLine()
        }
        if (now.isNotEmpty()) {
            appendLine(labels.nowHeader)
            blocks(now, labels, descriptions, zh)
            if (old.isEmpty()) more?.let { appendLine(it) }
            appendLine()
        }
        if (old.isNotEmpty()) {
            appendLine(labels.oldHeader)
            blocks(old, labels, descriptions, zh)
            more?.let { appendLine(it) }
            appendLine()
        }
        if (summary.unreadBlocks > 0) {
            appendLine(labels.unread(summary.unreadBlocks))
            appendLine()
        }
        if (kept.any { descriptions[it]?.level == DtcDescLevel.SIMILAR }) appendLine(labels.similarNote)
        append(labels.footer)
    }

    /** One header per block (`name (0x712)`, the address only when the name lacks it), then its codes. */
    private fun StringBuilder.blocks(
        rows: List<DtcFound>,
        labels: DtcLabels,
        descriptions: Map<DtcFound, DtcDescription?>,
        zh: Boolean,
    ) {
        rows.groupBy { it.addr }.values.forEach { group ->
            val block = group.first().block
            val name = labels.blockName(block)
            val addr = "0x%X".format(block.addr)
            appendLine(if (name.contains(addr, ignoreCase = true)) name else "$name ($addr)")
            group.forEach { f ->
                appendLine("  " + codeLine(f, labels, descriptions[f], zh))
                if (isKeyCode(f)) appendLine("  ${labels.keyHint}")
            }
        }
    }

    /** `U1166-87 · description · pending mark`, the parts that are there. */
    private fun codeLine(f: DtcFound, labels: DtcLabels, description: DtcDescription?, zh: Boolean): String =
        listOfNotNull(
            f.row.code,
            description?.let { if (zh) it.zh else it.en }?.takeIf { it.isNotBlank() },
            labels.pendingMark.takeIf { f.row.kind == DtcKind.PENDING },
        ).joinToString(" · ")
}
