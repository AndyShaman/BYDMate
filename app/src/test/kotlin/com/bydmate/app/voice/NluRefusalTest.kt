package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the parser refuses, and why: a refusal (unlike a phrase it simply did not understand)
 * also stops every user or automation phrase contained in the utterance, so each code here is
 * a promise that nothing acts on the phrase but the agent.
 */
class NluRefusalTest {

    private fun parse(text: String) = NluParser.parse(text)
    private fun refused(reason: String) = ParseResult.Refused(reason)
    private fun commands(text: String) = (parse(text) as? ParseResult.Command)?.commands

    @Test fun negation_words_refuse() {
        for (text in listOf("не открой окно", "не надо открывать окно", "нельзя открывать окно",
            "нет открой люк", "отмена закрой окна", "отмени открой люк")) {
            assertEquals(text, refused(VoiceRefusal.NEGATION), parse(text))
        }
        assertEquals(true, NluParser.negated("Не надо, открой окно"))
        assertEquals(false, NluParser.negated("открой окно на ноль"))
    }

    @Test fun commands_for_later_refuse() {
        for (text in listOf("открой окно завтра", "хочу открыть окно потом", "открой люк позже",
            "вчера открывал люк", "открой окна как раньше", "мы откроем багажник завтра")) {
            assertEquals(text, refused(VoiceRefusal.DEFERRED), parse(text))
        }
        assertEquals(listOf("主驾打开100"), commands("хочу открыть окно"))
    }

    // Other tenses and questions are not commands at all: no verb guess makes them one.
    @Test fun other_tenses_and_questions_are_not_commands() {
        for (text in listOf("мы откроем багажник", "открыли бы окно", "открывал люк", "открывается ли багажник")) {
            assertEquals(text, ParseResult.Unrecognized, parse(text))
        }
    }

    @Test fun unreadable_measures_refuse() {
        for (text in listOf("открой окно на палец", "открой люк на ладонь", "открой окно на сколько-то",
            "открой окно на два сантиметра", "открой окна по кругу", "открой окно на", "открой два окна",
            "открой окно на минимум", "закрой окна немного", "открой багажник наполовину", "приоткрой багажник")) {
            assertEquals(text, refused(VoiceRefusal.UNKNOWN_MEASURE), parse(text))
        }
    }

    @Test fun soft_measures_after_na_are_the_vent() {
        for (text in listOf("открой окно на щёлку", "открой окно на щелочку", "открой окно на чуть", "открой окно на немножко")) {
            assertEquals(text, listOf("主驾通风"), commands(text))
        }
    }

    @Test fun a_side_or_the_airing_mode_after_na_is_not_a_measure() {
        assertEquals(listOf("主驾打开100"), commands("открой окно на водительской стороне"))
        assertEquals(listOf("车窗通风"), commands("поставь окна на проветривание"))
        assertEquals(listOf("主驾半开"), commands("окно водителя на пол"))
    }

    @Test fun contradictory_measures_refuse() {
        for (text in listOf("открой окно полностью наполовину", "открой окно на ноль процентов", "открой окно на двадцать на тридцать")) {
            assertEquals(text, refused(VoiceRefusal.CONFLICTING_MEASURE), parse(text))
        }
    }

    @Test fun zero_without_an_opening_verb_closes() {
        assertEquals(listOf("主驾打开0"), commands("поставь окно на ноль процентов"))
    }

    // Floor: the widest detent that does not exceed the spoken share; above zero but under
    // the vent it is the vent.
    @Test fun spoken_shares_round_down_to_a_detent() {
        val windows = mapOf(
            "сто" to "主驾打开100", "восемьдесят" to "主驾半开", "семьдесят пять" to "主驾半开",
            "шестьдесят" to "主驾半开", "пятьдесят" to "主驾半开", "сорок" to "主驾通风",
            "тридцать три" to "主驾通风", "двадцать пять" to "主驾通风", "пятнадцать" to "主驾通风",
            "десять" to "主驾通风", "пять" to "主驾通风",
        )
        windows.forEach { (pct, want) ->
            assertEquals(pct, listOf(want), commands("открой окно на $pct процентов"))
        }
        assertEquals(listOf("主驾通风"), commands("открой окно на треть"))
        assertEquals(listOf("车窗半开"), commands("открой окна на три четверти"))
        val sunroof = mapOf("сто" to "天窗打开100", "восемьдесят" to "天窗打开50", "сорок" to "天窗通风", "пять" to "天窗通风")
        sunroof.forEach { (pct, want) ->
            assertEquals(pct, listOf(want), commands("открой люк на $pct процентов"))
        }
    }

    @Test fun except_lists_every_excluded_window() {
        assertEquals(listOf("后左打开100", "后右打开100"),
            commands("открой окна кроме водительского окна и пассажирского окна"))
        assertEquals(listOf("主驾打开0", "副驾打开0"), commands("закрой окна кроме заднего левого и заднего правого"))
        assertEquals(listOf("后左打开0", "后右打开0"), commands("закрой окна кроме водительского и пассажирского"))
    }

    @Test fun except_that_cannot_be_expressed_refuses() {
        for (text in listOf("открой окна кроме", "открой люк кроме шторки", "включи подогрев всех сидений кроме пассажира",
            "открой окна кроме водительского и закрой люк", "открой окна кроме окна")) {
            assertEquals(text, refused(VoiceRefusal.EXCEPT_UNSUPPORTED), parse(text))
        }
    }

    @Test fun commands_the_parser_cannot_split_refuse() {
        for (text in listOf("открой окна и люк наполовину", "открой люк наполовину и окна", "жарко и холодно",
            "открой закрой окно")) {
            assertEquals(text, refused(VoiceRefusal.MULTIPLE_COMMANDS), parse(text))
        }
        assertEquals(refused(VoiceRefusal.UNKNOWN_MEASURE), parse("открой окно и люк на палец"))
        // One target joined by «и» still parses whole.
        assertEquals(listOf("前排车窗全开"), commands("открой окна водителя и пассажира"))
    }

    @Test fun refusals_map_to_their_journal_codes() {
        assertEquals(NluOutcome.Refused(VoiceRefusal.NEGATION), NluOutcome.of(parse("не открывай окно")))
        assertEquals(NluOutcome.Refused(VoiceRefusal.UNRECOGNIZED), NluOutcome.of(parse("расскажи анекдот")))
    }
}
