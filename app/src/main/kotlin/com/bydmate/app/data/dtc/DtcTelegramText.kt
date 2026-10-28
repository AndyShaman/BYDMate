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
    // Stubs: the real texts are added with the fix.
    fun unread(count: Int): String = ""
    fun moreCodes(count: Int): String = ""
}

object DtcTelegramText {
    /** Plain text (no markup) for one manual send; [checkedAt] is the time already formatted. */
    fun build(summary: DtcScanSummary, checkedAt: String, labels: DtcLabels): String = buildString {
        appendLine(labels.title)
        appendLine(labels.checked(checkedAt, summary.answered, summary.total))
        appendLine()
        val now = summary.found.filter { it.row.kind == DtcKind.NOW }
        val old = summary.found.filter { it.row.kind != DtcKind.NOW }
        if (now.isEmpty() && old.isEmpty()) {
            appendLine(labels.noErrors)
            appendLine()
        }
        if (now.isNotEmpty()) {
            appendLine(labels.nowHeader)
            now.forEach { appendLine(line(it, labels)) }
            appendLine()
        }
        if (old.isNotEmpty()) {
            appendLine(labels.oldHeader)
            old.forEach { appendLine(line(it, labels)) }
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
