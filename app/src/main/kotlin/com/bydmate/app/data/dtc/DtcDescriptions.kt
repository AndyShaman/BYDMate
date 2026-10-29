package com.bydmate.app.data.dtc

import java.io.BufferedReader
import java.io.Reader

/** What drives the car, as the manuals tell their series apart: EV, DM (plug-in hybrid) or not known. */
enum class DtcEnergy { EV, DM, UNKNOWN }

/** EXACT = the BYD manual of this model; SIMILAR = the same text in every manual that has the block and code. */
enum class DtcDescLevel { EXACT, SIMILAR }

/** [manual] = the manual id the text came from (the first one for SIMILAR), for the log. */
data class DtcDescription(val en: String, val zh: String, val level: DtcDescLevel, val manual: String? = null)

/** One `rows.tsv` line: [blockClass] null when the manual does not say which block. */
class DtcTableRow(val manual: String, val blockClass: String?, val text: DtcText)

class DtcText(val zh: String, val en: String)

/** The four tables, indexed: rows by SAE code without dash, manuals by model code in preference order. */
class DtcTables(
    val rowsByCode: Map<String, List<DtcTableRow>>,
    val manualEnergy: Map<String, String>,
    val manualsByModel: Map<Int, List<String>>,
) {
    val rowCount: Int get() = rowsByCode.values.sumOf { it.size }

    companion object {
        val EMPTY = DtcTables(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Plain UTF-8 TSV without header, as `build_asset.py` writes them: `texts` (id, zh, en),
 * `manuals` (id, energy, series), `rows` (manual, class, code7, text id), `models` (autotype,
 * manual ids). A line that does not fit (fields missing, unknown text id, not a number) is skipped.
 */
object DtcTablesLoader {
    private const val TEXT_FIELDS = 3
    private const val MANUAL_FIELDS = 2
    private const val ROW_FIELDS = 4
    private const val MODEL_FIELDS = 2

    fun parse(texts: Reader, manuals: Reader, rows: Reader, models: Reader): DtcTables {
        val textById = HashMap<String, DtcText>()
        lines(texts) { f -> if (f.size >= TEXT_FIELDS) textById[f[0]] = DtcText(f[1], f[2]) }

        val energy = HashMap<String, String>()
        lines(manuals) { f -> if (f.size >= MANUAL_FIELDS) energy[f[0]] = f[1] }

        // Manual ids and classes repeat over 100k rows: one String instance each.
        val names = HashMap<String, String>()
        val byCode = HashMap<String, MutableList<DtcTableRow>>()
        lines(rows) { f ->
            if (f.size < ROW_FIELDS || f[2].isEmpty()) return@lines
            val text = textById[f[3]] ?: return@lines
            val manual = names.getOrPut(f[0]) { f[0] }
            val cls = f[1].ifEmpty { null }?.let { names.getOrPut(it) { it } }
            byCode.getOrPut(f[2]) { ArrayList(1) } += DtcTableRow(manual, cls, text)
        }

        val byModel = HashMap<Int, List<String>>()
        lines(models) { f ->
            if (f.size < MODEL_FIELDS) return@lines
            val model = f[0].toIntOrNull() ?: return@lines
            byModel[model] = f[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return DtcTables(byCode, energy, byModel)
    }

    private inline fun lines(reader: Reader, each: (List<String>) -> Unit) {
        BufferedReader(reader).useLines { seq ->
            seq.forEach { line -> if (line.isNotBlank()) each(line.trimEnd('\r').split('\t')) }
        }
    }
}

/**
 * Description of a found code by (car model + block class + code). EXACT: the first manual of
 * the model (after the energy filter) that has the code; with a known class only its rows, with
 * an unknown class all of them; one distinct text or nothing. SIMILAR: a known class only, all
 * manuals, every English text the same. Never picks one of two differing texts.
 */
class DtcDescriptions(private val tables: DtcTables) {

    fun lookup(autoType: Int?, energy: DtcEnergy, blockClass: String?, code7: String): DtcDescription? {
        val rows = tables.rowsByCode[code7] ?: return null
        exact(autoType, energy, blockClass, rows)?.let { return it }
        return similar(blockClass, rows)
    }

    private fun exact(autoType: Int?, energy: DtcEnergy, blockClass: String?, rows: List<DtcTableRow>): DtcDescription? {
        val manuals = autoType?.let { tables.manualsByModel[it] } ?: return null
        val manual = manuals.filter { fits(it, energy) }.firstOrNull { m -> rows.any { it.manual == m } } ?: return null
        val texts = rows
            .filter { it.manual == manual && (blockClass == null || it.blockClass == blockClass) }
            .map { it.text }
            .distinct()
        val text = texts.singleOrNull() ?: return null
        return DtcDescription(text.en.trim(), text.zh.trim(), DtcDescLevel.EXACT, manual)
    }

    private fun similar(blockClass: String?, rows: List<DtcTableRow>): DtcDescription? {
        if (blockClass == null) return null
        val same = rows.filter { it.blockClass == blockClass }
        val first = same.firstOrNull() ?: return null
        val en = first.text.en.trim()
        if (same.any { !it.text.en.trim().equals(en, ignoreCase = true) }) return null
        return DtcDescription(en, first.text.zh.trim(), DtcDescLevel.SIMILAR, first.manual)
    }

    /** A manual of the other energy is dropped; `?` and an unknown car keep everything. */
    private fun fits(manual: String, energy: DtcEnergy): Boolean {
        val of = tables.manualEnergy[manual] ?: return true
        return when (energy) {
            DtcEnergy.UNKNOWN -> true
            DtcEnergy.EV -> of == "EV" || of == "?"
            DtcEnergy.DM -> of == "DM" || of == "?"
        }
    }
}
