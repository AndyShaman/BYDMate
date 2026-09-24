package com.bydmate.app.voice

import com.bydmate.app.data.vehicle.CommandTranslator

/**
 * The Russian golden corpus (test resource voice/ru_commands_golden.tsv): utterances in
 * the exact form GigaAM emits (lowercase, no punctuation, numbers as words) pinned to
 * the command string the offline parser must produce. Pure, so the scoring can run
 * outside JUnit too.
 */
object RuGoldenCorpus {

    const val UNRECOGNIZED = "UNRECOGNIZED"

    data class Row(val utterance: String, val expected: String, val category: String, val danger: Boolean)

    fun load(): List<Row> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("voice/ru_commands_golden.tsv"))
        return parse(stream.bufferedReader(Charsets.UTF_8).readText())
    }

    fun parse(tsv: String): List<Row> = tsv.lines()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val cols = line.split('\t')
            Row(cols[0], cols[1], cols[2], cols.getOrNull(3) == "danger")
        }

    /** One comparable string per parse result: commands joined with "+" in dispatch order. */
    fun render(result: ParseResult): String = when (result) {
        is ParseResult.Command -> result.commands.joinToString("+")
        is ParseResult.RelativeTemp -> "TEMP:" + if (result.sign > 0) "+1" else "-1"
        is ParseResult.Volume -> "VOL:" + result.payload
        ParseResult.Unrecognized -> UNRECOGNIZED
    }

    fun actual(row: Row): String = render(NluParser.parse(row.utterance))

    /**
     * Aperture share each command leaves per opening (window door or sunroof), taken from
     * the real write the translator produces. Sunroof enum: 1 open, 3 half, 5 vent (tilt),
     * 6 comfort (treated as fully open), 2 close.
     */
    fun apertures(rendered: String): Map<String, Int> {
        if (rendered == UNRECOGNIZED || rendered.startsWith("TEMP:") || rendered.startsWith("VOL:")) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        rendered.split('+').flatMap { CommandTranslator.resolve(it) }.forEach { w ->
            aperture(w.actionName, w.value)?.let { (target, pct) -> out[target] = pct }
        }
        return out
    }

    private fun aperture(name: String, value: Int): Pair<String, Int>? = when {
        name.startsWith("sunroof_") -> "sunroof" to sunroofShare(value)
        !name.startsWith("window_") -> null
        name.endsWith("_open") -> name.removeSuffix("_open") to 100
        name.endsWith("_close") -> name.removeSuffix("_close") to 0
        name.endsWith("_pos") -> name.removeSuffix("_pos") to value
        else -> null
    }

    private fun sunroofShare(value: Int): Int = when (value) {
        1, 6 -> 100
        3 -> 50
        5 -> 7
        else -> 0
    }

    /** True when [actual] opens some aperture wider than [expected] asks for (or at all
     *  when the expectation does not touch it). */
    fun doesMoreThanExpected(expected: String, actual: String): Boolean {
        val want = apertures(expected)
        return apertures(actual).any { (target, pct) -> pct > (want[target] ?: 0) }
    }

    data class Score(val category: String, val correct: Int, val total: Int) {
        val accuracy: Double get() = if (total == 0) 1.0 else correct.toDouble() / total
    }

    fun score(rows: List<Row>, actualOf: (Row) -> String = ::actual): List<Score> {
        val byCategory = rows.groupBy { it.category }.map { (cat, rs) ->
            Score(cat, rs.count { actualOf(it) == it.expected }, rs.size)
        }
        return byCategory + Score("overall", byCategory.sumOf { it.correct }, rows.size)
    }
}
