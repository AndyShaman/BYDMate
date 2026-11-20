package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.EcuFirmware

/** The texts of the firmware window and message, in the app language; [ENGLISH] where no context is at hand. */
interface FirmwareLabels {
    val title: String
    fun checked(at: String): String
    fun previous(at: String): String
    /** The section headers take the row count: `Перепрошились (3)`. */
    fun updated(count: Int): String
    fun unchanged(count: Int): String
    fun first(count: Int): String
    fun silent(count: Int): String
    /** [count] rows left out to fit one message. */
    fun more(count: Int): String
    /** Empty for a block without a known name: the address alone is shown. */
    fun blockName(name: DtcBlockName): String

    companion object {
        val ENGLISH: FirmwareLabels = object : FirmwareLabels {
            override val title = "BYDMate · module firmware"
            override fun checked(at: String) = "Checked $at"
            override fun previous(at: String) = "Previous check: $at"
            override fun updated(count: Int) = "Reflashed ($count)"
            override fun unchanged(count: Int) = "Unchanged ($count)"
            override fun first(count: Int) = "Read for the first time ($count)"
            override fun silent(count: Int) = "No answer ($count)"
            override fun more(count: Int) = "… more: $count"
            override fun blockName(name: DtcBlockName) = ""
        }
    }
}

/** The rows of one check sorted into what the window and the message show, in this order; each by address. */
class FirmwareSections(all: List<FirmwareRow>) {
    private val rows = all.sortedBy { it.addr }
    val updated = rows.filter { it.change == FirmwareChange.UPDATED }
    val unchanged = rows.filter { it.change == FirmwareChange.UNCHANGED }
    val first = rows.filter { it.change == FirmwareChange.FIRST }
    /** Answered before and not now, or never: the address only. */
    val silent = rows.filter { it.change == FirmwareChange.GONE || it.change == FirmwareChange.SILENT }
}

object EcuFirmwareText {
    /** Telegram's limit for one text message. */
    private const val MAX_CHARS = 4096

    /** `0x720`, the address as every diagnostic screen writes it. */
    fun addr(addr: Int): String = "0x%X".format(addr)

    /** `10.09.2026` from the parser's ISO date; null without a date. */
    fun date(fw: EcuFirmware?): String? = fw?.date?.split('-')?.takeIf { it.size == 3 }?.let { (y, m, d) -> "$d.$m.$y" }

    /** `4.00.10 → 4.00.12 · 10.09.2026` for a reflashed block, `4.00.12 · 10.09.2026` for the rest. */
    fun versionLine(row: FirmwareRow): String {
        val after = row.after
        val version = if (row.change == FirmwareChange.UPDATED) {
            "${row.before?.version ?: "?"} → ${after?.version ?: "?"}"
        } else {
            after?.version ?: row.before?.version.orEmpty()
        }
        return listOfNotNull(version.ifEmpty { null }, date(after)).joinToString(" · ")
    }

    /**
     * One plain message: when, the previous check, the reflashed blocks «было → стало», then the
     * unchanged and first-read ones, then the silent addresses. A list too long for one message
     * keeps as many rows as fit (the reflashed first) and says how many were left out.
     */
    fun build(rows: List<FirmwareRow>, checkedAt: String, previousAt: String?, labels: FirmwareLabels): String {
        val s = FirmwareSections(rows)
        val listed = s.updated + s.unchanged + s.first
        var shown = listed.size
        var text = render(s, listed.take(shown).toSet(), listed.size - shown, checkedAt, previousAt, labels)
        while (text.length > MAX_CHARS && shown > 0) {
            shown--
            text = render(s, listed.take(shown).toSet(), listed.size - shown, checkedAt, previousAt, labels)
        }
        return text
    }

    @Suppress("LongParameterList") // one render pass of build()
    private fun render(
        s: FirmwareSections,
        kept: Set<FirmwareRow>,
        left: Int,
        checkedAt: String,
        previousAt: String?,
        labels: FirmwareLabels,
    ): String = buildString {
        appendLine(labels.title)
        appendLine(labels.checked(checkedAt))
        previousAt?.let { appendLine(labels.previous(it)) }
        val sections = listOf(
            labels.updated(s.updated.size) to s.updated,
            labels.unchanged(s.unchanged.size) to s.unchanged,
            labels.first(s.first.size) to s.first,
        )
        for ((header, list) in sections) {
            if (list.isEmpty()) continue
            appendLine()
            appendLine("$header:")
            list.filter { it in kept }.forEach { row ->
                val name = listOf(addr(row.addr), labels.blockName(row.name)).filter { it.isNotEmpty() }.joinToString(" ")
                if (row.change == FirmwareChange.UPDATED) {
                    appendLine(name)
                    appendLine("  " + versionLine(row))
                } else {
                    appendLine(listOf(name, versionLine(row)).filter { it.isNotEmpty() }.joinToString(" · "))
                }
            }
        }
        if (left > 0) appendLine(labels.more(left))
        if (s.silent.isNotEmpty()) {
            appendLine()
            append(labels.silent(s.silent.size)).append(": ").append(s.silent.joinToString(", ") { addr(it.addr) })
        }
    }.trimEnd()
}
