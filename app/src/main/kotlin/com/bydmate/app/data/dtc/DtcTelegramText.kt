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
}

object DtcTelegramText {
    /** Telegram's limit for one text message. */
    private const val MAX_CHARS = 4096

    /**
     * Plain text (no markup) for one manual send; [checkedAt] is the time already formatted. A list
     * too long for one message keeps as many codes as fit and says how many were left out.
     */
    fun build(summary: DtcScanSummary, checkedAt: String, labels: DtcLabels): String {
        val all = summary.found.filter { it.row.kind == DtcKind.NOW } + summary.found.filter { it.row.kind != DtcKind.NOW }
        var shown = all.size
        var text = render(summary, all, shown, checkedAt, labels)
        while (text.length > MAX_CHARS && shown > 0) {
            shown--
            text = render(summary, all, shown, checkedAt, labels)
        }
        return text
    }

    private fun render(
        summary: DtcScanSummary,
        all: List<DtcFound>,
        shown: Int,
        checkedAt: String,
        labels: DtcLabels,
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
            now.forEach { appendLine(line(it, labels)) }
            if (old.isEmpty()) more?.let { appendLine(it) }
            appendLine()
        }
        if (old.isNotEmpty()) {
            appendLine(labels.oldHeader)
            old.forEach { appendLine(line(it, labels)) }
            more?.let { appendLine(it) }
            appendLine()
        }
        if (summary.unreadBlocks > 0) {
            appendLine(labels.unread(summary.unreadBlocks))
            appendLine()
        }
        append(labels.footer)
    }

    private fun line(f: DtcFound, labels: DtcLabels): String =
        listOfNotNull(
            f.row.code,
            labels.blockName(f.block),
            labels.pendingMark.takeIf { f.row.kind == DtcKind.PENDING },
        ).joinToString(" · ")
}
