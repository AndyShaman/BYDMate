package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDictionaryTest {

    private val dictionary = VoiceDictionary.load()

    private val catalog: Set<String> = VoiceCatalog.ALL.flatMapTo(HashSet()) { spec ->
        spec.value?.let { v -> (v.min..v.max).map { spec.command(it) } } ?: listOf(spec.command(null))
    }

    @Test fun the_dictionary_loads_and_reports_its_size() {
        val lines = VoiceDictionary::class.java.classLoader!!
            .getResourceAsStream(VoiceDictionary.RESOURCE)!!.bufferedReader().readLines().size
        val phrases = dictionary.phrases().map { it.first }.toSet()
        println("[dictionary] lines=$lines templates=${dictionary.templateCount} phrases=${phrases.size}")
        assertTrue(dictionary.templateCount > 0)
        assertTrue(phrases.isNotEmpty())
    }

    @Test fun no_phrase_runs_two_different_things() {
        val conflicts = dictionary.conflicts()
        assertTrue(conflicts.joinToString("\n"), conflicts.isEmpty())
    }

    @Test fun every_command_the_dictionary_emits_is_in_the_catalog() {
        val unknown = dictionary.phrases()
            .flatMap { (phrase, r) -> ((r as? ParseResult.Command)?.commands.orEmpty()).map { phrase to it } }
            .filter { it.second !in catalog }
            .toList()
        assertTrue(unknown.joinToString("\n"), unknown.isEmpty())
    }

    @Test fun every_dictionary_phrase_parses_to_its_own_result() {
        val wrong = dictionary.phrases()
            .filter { (phrase, r) -> NluParser.parse(phrase) != r }
            .map { (phrase, r) -> "\"$phrase\" => ${NluParser.parse(phrase)} (want $r)" }
            .toList()
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun a_conflict_is_reported() {
        val d = VoiceDictionary.parse(
            """
            открой окно => 主驾打开100
            открой окно => 车窗全开
            """.trimIndent(),
        )
        assertEquals(1, d.conflicts().size)
        assertEquals(null, d.match(listOf("открой", "окно")))
    }

    @Test fun rules_lists_and_ranges_expand() {
        val d = VoiceDictionary.parse(
            """
            rule open: открой|опусти
            list win: водителя => 主驾; пассажира => 副驾
            range temp: 16..30
            <open> окно {win} => {win}打开100
            [поставь] температуру {temp} => 设置温度{temp}
            """.trimIndent(),
        )
        assertEquals(ParseResult.Command("副驾打开100"), d.match(VoiceDictionary.words("опусти окно пассажира")))
        assertEquals(ParseResult.Command("设置温度22"), d.match(VoiceDictionary.words("температуру двадцать два")))
        assertEquals(null, d.match(VoiceDictionary.words("температуру 40")))
    }

    @Test fun errors_name_their_cause() {
        val undefined = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("открой {win} => {win}打开100")
        }
        assertTrue(undefined.message, "win" in undefined.message.orEmpty())
        val filler = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("открой окно пожалуйста => 主驾打开100")
        }
        assertTrue(filler.message, "filler" in filler.message.orEmpty())
        val unused = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("rule open: открой\nоткрой окно => 主驾打开100")
        }
        assertTrue(unused.message, "never used" in unused.message.orEmpty())
        assertThrows(IllegalArgumentException::class.java) { VoiceDictionary.parse("открой окно") }
    }

    @Test fun a_name_used_as_the_wrong_kind_is_a_loader_error_whichever_line_comes_first() {
        val slotFirst = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("rule x: открой\n<x> окно => 主驾打开100\n{x} люк => 天窗打开100")
        }
        assertTrue(slotFirst.message, "x" in slotFirst.message.orEmpty())
        val ruleFirst = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("rule x: открой\n{x} люк => 天窗打开100\n<x> окно => 主驾打开100")
        }
        assertTrue(ruleFirst.message, "x" in ruleFirst.message.orEmpty())
    }

    @Test fun a_reversed_or_non_numeric_range_is_a_loader_error() {
        val reversed = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("range n: 30..16\n{n} штука => X{n}")
        }
        assertTrue(reversed.message, "30..16" in reversed.message.orEmpty())
        val nonNumeric = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("range n: тридцать..сорок\n{n} штука => X{n}")
        }
        assertTrue(nonNumeric.message, "тридцать..сорок" in nonNumeric.message.orEmpty())
    }

    @Test fun a_stray_closing_bracket_in_a_template_is_a_loader_error() {
        for (bad in listOf("открой окно} => 主驾打开100", "открой окно> => 主驾打开100",
            "открой окно) => 主驾打开100", "открой окно] => 主驾打开100")) {
            assertThrows(IllegalArgumentException::class.java) { VoiceDictionary.parse(bad) }
        }
    }
}
