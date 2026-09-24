package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The matcher: a phrase runs only when the dictionary holds it whole. Per-phrase expectations
 *  live in the golden corpus; these pin the matching rules themselves. */
class NluParserTest {

    private fun commands(text: String): List<String>? = (NluParser.parse(text) as? ParseResult.Command)?.commands

    @Test fun phrases_not_in_the_dictionary_go_to_the_agent() {
        for (phrase in listOf(
            "не открывай окно",
            "открой окно на палец",
            "подожди и открой окна",
            "мы открываем багажник",
            "выключи вентиляцию руля",
            "правое сиденье водителя",
            "включи подогрев сиденья на три процента",
        )) {
            assertEquals(phrase, ParseResult.Unrecognized, NluParser.parse(phrase))
        }
    }

    @Test fun polite_words_are_dropped_anywhere() {
        assertEquals(listOf("主驾半开"), commands("открой окно на пятьдесят пожалуйста процентов"))
    }

    @Test fun listed_forms_resolve() {
        assertEquals(listOf("吹前挡"), commands("включи обдув переднего стекла"))
        assertEquals(listOf("后视镜加热"), commands("включи подогрев всех зеркал"))
        assertEquals(listOf("风量3"), commands("вентилятор климата на три"))
    }

    @Test fun qualifiers_and_except_resolve() {
        assertEquals(listOf("前排车窗全开"), commands("открой окна водителя и пассажира"))
        assertEquals(listOf("副驾打开0", "后左打开0", "后右打开0"), commands("закрой окна кроме водительского"))
    }

    @Test fun a_compound_runs_only_when_every_part_is_a_command() {
        assertEquals(listOf("天窗打开100", "车窗关闭"), commands("открой люк и закрой окна"))
        assertEquals(listOf("天窗打开100", "车窗关闭"), commands("открой люк а также закрой окна"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("открой окна и люк наполовину"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("жарко и холодно"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("открой люк и"))
    }

    @Test fun a_verbless_variant_never_carries_its_own_part_of_a_compound() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("выключи подогрев руля и подогрев сидений"))
        assertEquals(
            ParseResult.Unrecognized,
            NluParser.parse("выключи подогрев сиденья водителя и подогрев сиденья пассажира"),
        )
        assertEquals(listOf("方向盘加热", "主驾座椅加热1档", "副驾座椅加热1档"), commands("включи подогрев руля и сидений"))
        assertEquals(listOf("主驾座椅加热1档", "副驾座椅加热1档"), commands("подогрев сидений"))
    }

    @Test fun empty_and_filler_only_are_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse(""))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("пожалуйста спасибо"))
    }

    @Test fun a_failed_dictionary_load_is_unrecognized_not_a_crash() {
        val broken = Result.failure<VoiceDictionary>(IllegalArgumentException("corrupt resource"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("открой окно", broken))
    }
}
