package com.bydmate.app.voice

/**
 * The built-in Russian command dictionary (Java resource [RESOURCE]): a closed list of phrase
 * templates, expanded at load into every phrase they accept. A phrase runs offline only when it
 * equals one of them word for word after [words]; the file header documents the format. Numbers
 * stay symbolic in the expansion ("#"), each place keeping the values it accepts. Pure.
 */
class VoiceDictionary internal constructor(
    private val byKey: Map<String, List<DictPhrase>>,
    /** Template lines in the file. */
    val templateCount: Int,
) {

    /**
     * What [words] (already normalized by [words]) runs, or null when no phrase equals them or two
     * phrases disagree. [inCompound]: the words are one part of "A и B", where a `solo` template
     * (a phrase without its own verb) never matches.
     */
    fun match(words: List<String>, inCompound: Boolean = false): ParseResult? {
        val numbers = words.mapNotNull { VoiceNormalizer.number(it) }
        val key = words.joinToString(" ") { if (VoiceNormalizer.number(it) != null) NUM else it }
        return byKey[key].orEmpty()
            .filter { !inCompound || !it.template.solo }
            .mapNotNull { it.resolve(numbers) }
            .distinct()
            .singleOrNull()
    }

    /** Every phrase the dictionary accepts with what it runs; a phrase two templates share is
     *  listed once per template. */
    fun phrases(): Sequence<Pair<String, ParseResult>> = byKey.asSequence().flatMap { (key, list) ->
        list.asSequence().flatMap { p ->
            p.tuples().asSequence().mapNotNull { t -> p.resolve(t)?.let { spell(key, t) to it } }
        }
    }

    /** Phrases two templates accept with different results, one line each; empty when none. */
    fun conflicts(): List<String> = byKey.flatMap { (key, list) ->
        list.flatMapIndexed { i, a -> list.drop(i + 1).mapNotNull { b -> conflict(key, a, b) } }
    }

    private fun conflict(key: String, a: DictPhrase, b: DictPhrase): String? {
        val shared = b.tuples().toSet()
        val t = a.tuples().firstOrNull { it in shared && a.resolve(it) != b.resolve(it) } ?: return null
        return "\"${spell(key, t)}\": line ${a.template.line} => ${a.resolve(t)}, line ${b.template.line} => ${b.resolve(t)}"
    }

    private fun spell(key: String, numbers: List<Int>): String {
        val next = numbers.iterator()
        return key.split(' ').joinToString(" ") { if (it == NUM) next.next().toString() else it }
    }

    companion object {
        const val RESOURCE = "voice/ru_commands.txt"
        internal const val NUM = "#"

        /** Politeness and address words: dropped from the utterance anywhere, never in a template. */
        val FILLERS: Set<String> = VoicePhrase.FILLERS + setOf("спасибо", "хочу", "я")

        fun load(): VoiceDictionary {
            val stream = requireNotNull(VoiceDictionary::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
                "$RESOURCE is missing"
            }
            return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        /** Throws [IllegalArgumentException] naming the line on any error in [text]. */
        fun parse(text: String): VoiceDictionary = VoiceDictionaryLoader(text).build()

        /** The words matching reads: [VoiceNormalizer.tokens] without [FILLERS], numbers as digits. */
        fun words(text: String): List<String> =
            VoiceNormalizer.digits(VoiceNormalizer.tokens(text).filterNot { it in FILLERS })
    }
}

/** A number the phrase holds at one place: the values accepted there and the `range` slot it
 *  fills with the number itself, or null when a list or the template fixes the value. */
internal class Num(val range: IntRange, val slot: String?)

internal class DictTemplate(val line: Int, val solo: Boolean, val output: CommandExpr)

/** One expanded phrase: the numbers it accepts, the slot values its words chose, its template. */
internal class DictPhrase(val nums: List<Num>, val values: Map<String, String>, val template: DictTemplate) {

    fun resolve(numbers: List<Int>): ParseResult? {
        if (numbers.size != nums.size || nums.indices.any { numbers[it] !in nums[it].range }) return null
        val ranged = nums.indices.mapNotNull { i -> nums[i].slot?.let { it to numbers[i].toString() } }
        return template.output.resolve(values + ranged)
    }

    /** Every number combination the phrase accepts (one empty list when it holds no number). */
    fun tuples(): List<List<Int>> =
        nums.fold(listOf(emptyList())) { acc, n -> acc.flatMap { t -> n.range.map { t + it } } }
}

/** The command side of a template line. */
internal sealed interface CommandExpr {
    fun resolve(values: Map<String, String>): ParseResult

    class Temp(private val sign: Int) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.RelativeTemp(sign)
    }

    class Vol(private val payload: String) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Volume(fill(payload, values).single())
    }

    class Commands(private val terms: List<String>) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Command(terms.flatMap { fill(it, values) })
    }

    companion object {
        private val SLOT_REF = Regex("\\{([a-z0-9_]+)}")

        fun parse(text: String): CommandExpr = when {
            text == "TEMP +1" -> Temp(1)
            text == "TEMP -1" -> Temp(-1)
            text.startsWith("VOL ") -> Vol(text.removePrefix("VOL ").trim())
            else -> Commands(text.split(" + ").map { it.trim() }.onEach {
                require(it.isNotEmpty() && it.none(Char::isWhitespace)) { "bad command \"$it\" in \"$text\"" }
            })
        }

        /** [term] with each {slot} replaced by its value; a value "a,b" repeats the term per symbol. */
        fun fill(term: String, values: Map<String, String>): List<String> {
            val filled = SLOT_REF.findAll(term).map { it.groupValues[1] }.toSet().associateWith { name ->
                requireNotNull(values[name]) { "{$name} has no value in \"$term\"" }.split(',').map { it.trim() }
            }
            val fan = filled.filterValues { it.size > 1 }.keys
            require(fan.size <= 1) { "more than one multi-valued slot in \"$term\"" }
            val base = filled.filterKeys { it !in fan }.entries.fold(term) { t, (n, v) -> t.replace("{$n}", v.single()) }
            val multi = fan.singleOrNull() ?: return listOf(base)
            return filled.getValue(multi).map { base.replace("{$multi}", it) }
        }
    }
}
