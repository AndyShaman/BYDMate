package com.bydmate.app.voice

/** One element of template syntax. */
internal sealed interface Node {
    data class Word(val text: String) : Node
    data class Rule(val name: String) : Node
    data class Slot(val name: String) : Node
    data class Choice(val options: List<List<Node>>, val optional: Boolean) : Node
}

/** One way a `list` or `range` slot is filled: its phrases and number ranges, and the value they
 *  give the slot (null for a `range`, whose value is the number said). */
private class SlotGroup(val value: String?, val phrases: List<List<Node>>, val ranges: List<IntRange>)

/** A partly expanded phrase: its words ("#" for a number), the numbers, the slot values chosen. */
private data class Part(val words: List<String>, val nums: List<Num>, val values: Map<String, String>) {
    operator fun plus(o: Part): Part {
        val twice = values.keys.intersect(o.values.keys)
        require(twice.isEmpty()) { "slot {${twice.first()}} used twice in one phrase" }
        return Part(words + o.words, nums + o.nums, values + o.values)
    }

    companion object {
        val EMPTY = Part(emptyList(), emptyList(), emptyMap())
    }
}

private val NAME = Regex("[a-z0-9_]+")

private class TemplateLine(val options: List<List<Node>>, val def: DictTemplate)

/** Reads the dictionary text (format in the resource header) and expands it into phrases. */
internal class VoiceDictionaryLoader(text: String) {

    private val rules = HashMap<String, List<List<Node>>>()
    private val slots = HashMap<String, List<SlotGroup>>()
    private val templates = ArrayList<TemplateLine>()
    // Cache keys are namespaced ("rule:" / "slot:") so a <name> reference never serves a {name}
    // reference from the same cache entry or the reverse: a name defined as one but referenced
    // as the other must still fail with "unknown rule"/"unknown slot", whichever expands first.
    private val expanded = HashMap<String, List<Part>>()
    private val expanding = HashSet<String>()

    init {
        logicalLines(text).forEach { (line, body) -> readLine(body, "line $line", line) }
    }

    fun build(): VoiceDictionary {
        val byKey = HashMap<String, MutableList<DictPhrase>>()
        for (t in templates) {
            val where = "line ${t.def.line}"
            for (part in t.options.flatMap { expand(it) }) {
                require(part.words.isNotEmpty()) { "$where: a phrase with no words" }
                val phrase = DictPhrase(part.nums, part.values, t.def)
                // Every {slot} of the command must be filled by every phrase of the template.
                val sample = runCatching { phrase.resolve(part.nums.map { it.range.first }) }
                require(sample.isSuccess) { "$where: ${sample.exceptionOrNull()?.message}" }
                byKey.getOrPut(part.words.joinToString(" ")) { ArrayList() }.add(phrase)
            }
        }
        val unused = rules.keys.filterNot { "rule:$it" in expanded } + slots.keys.filterNot { "slot:$it" in expanded }
        require(unused.isEmpty()) { "defined but never used: $unused" }
        return VoiceDictionary(byKey, templates.size)
    }

    private fun readLine(body: String, where: String, line: Int) {
        val head = body.substringBefore(' ')
        val definition = head in setOf("rule", "list", "range")
        if (!definition) {
            val solo = head == "solo"
            val template = if (solo) body.substringAfter(' ') else body
            val arrow = template.indexOf("=>")
            require(arrow > 0) { "$where: no \"=>\" in \"$body\"" }
            val output = CommandExpr.parse(template.substring(arrow + 2).trim())
            val options = SyntaxReader(template.substring(0, arrow), where).read()
            templates.add(TemplateLine(options, DictTemplate(line, solo, output)))
            return
        }
        val name = body.substringAfter(' ').substringBefore(':').trim()
        require(NAME.matches(name) && ':' in body) { "$where: bad definition \"$body\"" }
        require(name !in rules && name !in slots) { "$where: \"$name\" defined twice" }
        val rest = body.substringAfter(':').trim()
        when (head) {
            "rule" -> rules[name] = SyntaxReader(rest, where).read()
            "range" -> slots[name] = listOf(SlotGroup(null, emptyList(), listOf(range(rest, where))))
            else -> slots[name] = splitTop(rest, ';').map { readGroup(it, where) }
        }
    }

    private fun readGroup(group: String, where: String): SlotGroup {
        val arrow = group.indexOf("=>")
        require(arrow > 0) { "$where: no \"=>\" in list group \"$group\"" }
        val value = group.substring(arrow + 2).trim()
        require(value.isNotEmpty() && value.none(Char::isWhitespace)) { "$where: bad list value \"$value\"" }
        val forms = splitTop(group.substring(0, arrow), '|').map { it.trim() }
        val (ranges, phrases) = forms.partition { RANGE.matches(it) }
        return SlotGroup(value, phrases.flatMap { SyntaxReader(it, where).read() }, ranges.map { range(it, where) })
    }

    private fun expand(seq: List<Node>): List<Part> =
        seq.fold(listOf(Part.EMPTY)) { acc, node -> expandNode(node).let { next -> acc.flatMap { a -> next.map { a + it } } } }

    private fun expandNode(node: Node): List<Part> = when (node) {
        is Node.Word -> listOf(word(node.text))
        is Node.Rule -> named("rule", node.name) {
            requireNotNull(rules[node.name]) { "unknown rule <${node.name}>" }.flatMap { expand(it) }
        }
        is Node.Slot -> named("slot", node.name) { slotParts(node.name) }
        is Node.Choice -> node.options.flatMap { expand(it) } + if (node.optional) listOf(Part.EMPTY) else emptyList()
    }

    private fun word(text: String): Part {
        val n = VoiceNormalizer.number(text) ?: return Part(listOf(text), emptyList(), emptyMap())
        return Part(listOf(VoiceDictionary.NUM), listOf(Num(n..n, null)), emptyMap())
    }

    /** The expansion of a rule or slot, computed once; a definition using itself is an error.
     *  [kind] ("rule" or "slot") namespaces the cache so <name> and {name} never share a cache
     *  entry when the file uses [name] as only one of the two. */
    private fun named(kind: String, name: String, compute: () -> List<Part>): List<Part> {
        val key = "$kind:$name"
        expanded[key]?.let { return it }
        require(expanding.add(key)) { "\"$name\" refers to itself" }
        return compute().also { expanded[key] = it; expanding.remove(key) }
    }

    private fun slotParts(name: String): List<Part> =
        requireNotNull(slots[name]) { "unknown slot {$name}" }.flatMap { g ->
            val values = g.value?.let { mapOf(name to it) }.orEmpty()
            val numSlot = if (g.value == null) name else null
            g.ranges.map { Part(listOf(VoiceDictionary.NUM), listOf(Num(it, numSlot)), values) } +
                g.phrases.flatMap { expand(it) }.map { it + Part(emptyList(), emptyList(), values) }
        }

    private companion object {
        val RANGE = Regex("(\\d+)\\.\\.(\\d+)")

        fun range(text: String, where: String): IntRange {
            val m = requireNotNull(RANGE.matchEntire(text.trim())) { "$where: bad range \"$text\"" }
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toInt()
            require(lo <= hi) { "$where: reversed range \"$text\"" }
            return lo..hi
        }

        /** [text] split at [sep] outside (), [] and {}; "=>" and <rule> never hold a separator. */
        fun splitTop(text: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, c ->
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    sep -> if (depth == 0) { out.add(text.substring(start, i)); start = i + 1 }
                }
            }
            out.add(text.substring(start))
            return out
        }

        /** Lines without comments; a line starting with whitespace continues the previous one. */
        fun logicalLines(text: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#')
                when {
                    line.isBlank() -> Unit
                    line.first().isWhitespace() && out.isNotEmpty() ->
                        out[out.lastIndex] = out.last().let { it.first to it.second + " " + line.trim() }
                    else -> out.add(i + 1 to line.trim())
                }
            }
            return out
        }
    }
}

/** Reads template syntax: words, <rule>, {slot}, [optional part], (a|b) choice, top-level a|b. */
private class SyntaxReader(private val text: String, private val where: String) {
    private var i = 0

    fun read(): List<List<Node>> = alternatives(null)

    private fun alternatives(close: Char?): List<List<Node>> {
        val options = ArrayList<List<Node>>()
        var seq = ArrayList<Node>()
        val words = StringBuilder()
        while (true) {
            val c = text.getOrNull(i)
            // A bare "}" or ">" here (one <rule>/{slot} already consumes its own closer via
            // name()) is as unbalanced as a stray ")" or "]": every one of the four must fail
            // to load, never get silently stripped by punctuation normalization later.
            if (c == null || c in "|)]}>") {
                seq.addAll(literal(words))
                words.clear()
                require(seq.isNotEmpty()) { "$where: empty choice in \"$text\"" }
                options.add(seq)
                i++
                if (c != '|') {
                    require(c == close) { "$where: unbalanced brackets in \"$text\"" }
                    return options
                }
                seq = ArrayList()
            } else if (c in "([<{") {
                seq.addAll(literal(words))
                words.clear()
                i++
                seq.add(element(c))
            } else {
                words.append(c)
                i++
            }
        }
    }

    private fun element(open: Char): Node = when (open) {
        '(' -> Node.Choice(alternatives(')'), optional = false)
        '[' -> Node.Choice(alternatives(']'), optional = true)
        '<' -> Node.Rule(name('>'))
        else -> Node.Slot(name('}'))
    }

    private fun name(close: Char): String {
        val end = text.indexOf(close, i)
        require(end > i) { "$where: unclosed name in \"$text\"" }
        val name = text.substring(i, end)
        require(NAME.matches(name)) { "$where: bad name \"$name\"" }
        i = end + 1
        return name
    }

    /** Template words normalized like the utterance; a filler could never match, so it is an error. */
    private fun literal(words: CharSequence): List<Node> {
        val tokens = VoiceNormalizer.tokens(words.toString())
        require(tokens.none { it in VoiceDictionary.FILLERS }) { "$where: filler word in \"$words\"" }
        return VoiceNormalizer.digits(tokens).map { Node.Word(it) }
    }
}
