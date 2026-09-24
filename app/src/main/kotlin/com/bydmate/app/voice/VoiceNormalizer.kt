package com.bydmate.app.voice

/**
 * Pure Russian pre-pass for the offline parser. GigaAM emits lowercase text without
 * punctuation and spells numbers as words; this turns it into tokens and reads the
 * measure out of them (a share of an opening, a level, a bare number), so the parser can
 * tell "open" from "open halfway" and never widen an utterance it could not read.
 */
object VoiceNormalizer {

    /** Share the vent detent stands for (the translator's 10 % window vent). */
    const val VENT_SHARE = 10
    const val FULL_SHARE = 100
    private const val HALF_SHARE = 50
    private const val THREE_QUARTERS = 75

    enum class Extreme { MAX, MIN }

    /**
     * @param numbers cardinals named in the phrase (tens + units composed, digits parsed)
     * @param numberIsShare the single number reads as an opening share: "на/до N" or "N процентов"
     * @param share share named by words: a fraction, a vent word or a "fully" word
     * @param softVent a soft verb ("приоткрой") asked for the vent detent unless something else is named
     * @param shareConflict two different shares named ("полностью наполовину")
     * @param ordinal "первый".."пятый" as a level
     * @param extreme "максимум"/"минимум"
     * @param unexplained a measure word nobody can read (a dangling "до"/"пол")
     * @param words indices of the tokens the readers above explain (the number words, "процентов"
     *   after a number, share, level and "до конца" words); the parser reads no other word as a measure
     * @param levels how many levels the phrase names: each number, ordinal and "максимум"/"минимум"/
     *   "полную" counts once, so "третий второй" or "максимум минимум" is two
     * @param levelWords indices of the words behind [levels]; a measure word outside them ("процентов",
     *   "наполовину") is not a level
     */
    data class Measure(
        val numbers: List<Int> = emptyList(),
        val numberIsShare: Boolean = false,
        val share: Int? = null,
        val softVent: Boolean = false,
        val shareConflict: Boolean = false,
        val ordinal: Int? = null,
        val extreme: Extreme? = null,
        val unexplained: Boolean = false,
        val words: Set<Int> = emptySet(),
        val levels: Int = 0,
        val levelWords: Set<Int> = emptySet(),
    ) {
        /** Some word asks for a share of an opening (numbers alone do not: they are levels elsewhere). */
        val hasShare: Boolean get() = share != null || softVent || shareConflict
    }

    private val UNITS: Map<String, Int> = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "одного" to 1, "двух" to 2, "трех" to 3, "четырех" to 4, "пяти" to 5, "шести" to 6,
        "семи" to 7, "восьми" to 8, "девяти" to 9,
    )
    private val TEENS: Map<String, Int> = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "десяти" to 10, "одиннадцати" to 11,
        "двенадцати" to 12, "тринадцати" to 13, "четырнадцати" to 14, "пятнадцати" to 15,
        "шестнадцати" to 16, "семнадцати" to 17, "восемнадцати" to 18, "девятнадцати" to 19,
    )
    private val TENS: Map<String, Int> = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
        "двадцати" to 20, "тридцати" to 30, "сорока" to 40, "пятидесяти" to 50,
        "шестидесяти" to 60, "семидесяти" to 70, "восьмидесяти" to 80, "девяноста" to 90,
        "сто" to 100, "ста" to 100,
    )

    private val ORDINALS: Map<String, Int> = buildMap {
        listOf("первый", "первая", "первое", "первую", "первого", "первом", "первой").forEach { put(it, 1) }
        listOf("второй", "вторая", "второе", "вторую", "второго", "втором").forEach { put(it, 2) }
        listOf("третий", "третья", "третье", "третью", "третьего", "третьем", "третьей").forEach { put(it, 3) }
        listOf("четвертый", "четвертая", "четвертое", "четвертую", "четвертого", "четвертом").forEach { put(it, 4) }
        listOf("пятый", "пятая", "пятое", "пятую", "пятого", "пятом").forEach { put(it, 5) }
    }

    private val MAX_WORDS = setOf("максимум", "максимума", "максимальный", "максимальная", "максимальную", "максимальной")
    private val MIN_WORDS = setOf("минимум", "минимума", "минимальный", "минимальная", "минимальную", "минимальной")
    private val EXTREME_WORDS = MAX_WORDS + MIN_WORDS + "полную"
    private val VENT_WORDS = setOf("чуть", "чуточку", "немного", "немножко", "слегка")
    private val FULL_WORDS = setOf("полностью", "целиком", "полную", "полной")
    private val END_WORDS = setOf("конца", "упора")
    private val FRACTIONS: Map<String, Int> = mapOf(
        "наполовину" to HALF_SHARE, "треть" to 33, "трети" to 33, "четверть" to 25, "четверти" to 25,
    )
    private val SOFT_OPEN_PREFIXES = listOf("приоткр", "приспуст")
    private val HALF_NOUNS = listOf("окн", "окош", "стекл", "форточ", "люк")

    /** Lowercase, ё -> е, "%" -> "процентов", hyphens and punctuation split tokens,
     *  "полокна" -> "пол окна". */
    fun tokens(text: String): List<String> = text.lowercase()
        .replace('ё', 'е')
        .replace("%", " процентов ")
        .replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .flatMap { splitHalfNoun(it) }

    private fun splitHalfNoun(token: String): List<String> {
        val rest = token.removePrefix("пол")
        return if (rest != token && HALF_NOUNS.any { rest.startsWith(it) }) listOf("пол", rest) else listOf(token)
    }

    /** The whole number words of the phrase with their token spans, tens + units composed. */
    private data class NumberSpan(val value: Int, val first: Int, val last: Int)

    private fun numberSpans(tokens: List<String>): List<NumberSpan> {
        val out = ArrayList<NumberSpan>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tens = TENS[t]
            val unit = tokens.getOrNull(i + 1)?.let { UNITS[it] }
            when {
                tens != null && tens < FULL_SHARE && unit != null && unit > 0 -> { out.add(NumberSpan(tens + unit, i, i + 1)); i++ }
                tens != null -> out.add(NumberSpan(tens, i, i))
                t.all { it.isDigit() } && t.length <= 3 -> out.add(NumberSpan(t.toInt(), i, i))
                else -> (TEENS[t] ?: UNITS[t])?.let { out.add(NumberSpan(it, i, i)) }
            }
            i++
        }
        return out
    }

    /** "три четверти" is a fraction (75 %), not the number three. */
    private fun threeQuarters(tokens: List<String>, i: Int) =
        tokens[i] == "три" && tokens.getOrNull(i + 1)?.startsWith("четверт") == true

    fun measure(tokens: List<String>): Measure {
        val spans = numberSpans(tokens).filterNot { threeQuarters(tokens, it.first) }
        val shares = LinkedHashSet<Int>()
        var unexplained = false
        tokens.forEachIndexed { i, t ->
            val share = wordShare(tokens, i)
            if (share != null) shares.add(share)
            if (t == "до" && isUnreadable(tokens, i, spans)) unexplained = true
            if (t == "пол" && share == null) unexplained = true
        }
        val single = spans.singleOrNull()
        val levelTokens = tokens.indices.filter { tokens[it] in ORDINALS || tokens[it] in EXTREME_WORDS }
        return Measure(
            numbers = spans.map { it.value },
            numberIsShare = single != null && isShareNumber(tokens, single),
            share = shares.singleOrNull(),
            softVent = tokens.any { t -> SOFT_OPEN_PREFIXES.any { t.startsWith(it) } },
            shareConflict = shares.size > 1,
            ordinal = tokens.firstNotNullOfOrNull { ORDINALS[it] },
            extreme = extremeOf(tokens),
            unexplained = unexplained,
            words = tokens.indices.filterTo(HashSet()) { explained(tokens, it, spans) },
            levels = spans.size + levelTokens.size,
            levelWords = spans.flatMapTo(HashSet()) { it.first..it.last } + levelTokens,
        )
    }

    /** The word at [j] is read by one of the measure readers: a number, its "процентов", a share
     *  word, "конца" after "до", the "четверти" of "три четверти", a level. */
    private fun explained(tokens: List<String>, j: Int, spans: List<NumberSpan>): Boolean {
        val t = tokens[j]
        return spans.any { j in it.first..it.last } || wordShare(tokens, j) != null ||
            t in MAX_WORDS || t in MIN_WORDS || t in ORDINALS ||
            t.startsWith("процент") && spans.any { it.last == j - 1 } ||
            t in END_WORDS && tokens.getOrNull(j - 1) == "до" || j > 0 && threeQuarters(tokens, j - 1)
    }

    private fun extremeOf(tokens: List<String>): Extreme? = when {
        tokens.any { it in MAX_WORDS } || tokens.any { it == "полную" } -> Extreme.MAX
        tokens.any { it in MIN_WORDS } -> Extreme.MIN
        else -> null
    }

    /** Share named by the word at [i]: fractions, vent words, "fully" words. */
    private fun wordShare(tokens: List<String>, i: Int): Int? {
        val t = tokens[i]
        return when {
            threeQuarters(tokens, i) -> THREE_QUARTERS
            t.startsWith("четверт") && tokens.getOrNull(i - 1) == "три" -> null
            t.startsWith("половин") -> HALF_SHARE
            // "на пол" / "пол окна" is half; "обдув в пол" is the floor and names no share.
            t == "пол" -> HALF_SHARE.takeIf {
                tokens.getOrNull(i - 1) == "на" || HALF_NOUNS.any { tokens.getOrNull(i + 1)?.startsWith(it) == true }
            }
            t == "до" -> FULL_SHARE.takeIf { tokens.getOrNull(i + 1) in END_WORDS }
            t in VENT_WORDS || t.startsWith("щел") -> VENT_SHARE
            t in FULL_WORDS -> FULL_SHARE
            else -> FRACTIONS[t]
        }
    }

    /** A "до" none of the readers above explains ("до середины", a trailing "до"). */
    private fun isUnreadable(tokens: List<String>, i: Int, spans: List<NumberSpan>): Boolean {
        val next = tokens.getOrNull(i + 1) ?: return true
        return !(next in END_WORDS || next.startsWith("половин") || spans.any { it.first == i + 1 })
    }

    private fun isShareNumber(tokens: List<String>, span: NumberSpan): Boolean {
        val before = tokens.getOrNull(span.first - 1)
        val after = tokens.getOrNull(span.last + 1)
        return before == "на" || before == "до" || after?.startsWith("процент") == true
    }
}
