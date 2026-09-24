package com.bydmate.app.voice

/**
 * Pure Russian pre-pass shared by the utterance and the command dictionary. GigaAM emits
 * lowercase text without punctuation and spells numbers as words; this turns it into tokens
 * and writes every number as digits, so a template reads "на 50" however the number was said.
 */
object VoiceNormalizer {

    private const val HUNDRED = 100
    private const val MAX_DIGITS = 3

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

    /** [tokens] with every number word written as digits, tens and units composed:
     *  "двадцать пять" -> "25", "пятидесяти" -> "50". Other words pass unchanged. */
    fun digits(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tens = TENS[t]
            val unit = tokens.getOrNull(i + 1)?.let { UNITS[it] }
            when {
                tens != null && tens < HUNDRED && unit != null && unit > 0 -> { out.add((tens + unit).toString()); i++ }
                tens != null -> out.add(tens.toString())
                else -> out.add(((TEENS[t] ?: UNITS[t])?.toString()) ?: t)
            }
            i++
        }
        return out
    }

    /** The number a digit token stands for, or null for any other word. */
    fun number(token: String): Int? =
        token.takeIf { it.length <= MAX_DIGITS && it.all { c -> c in '0'..'9' } }?.toInt()
}
