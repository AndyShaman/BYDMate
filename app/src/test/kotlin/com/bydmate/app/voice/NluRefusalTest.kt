package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the parser does not act on, and why. A word the parser does not read leaves the whole
 * utterance [ParseResult.Unrecognized]; a refusal carries its own journal code. Both go to the
 * agent, and nothing contained in the utterance acts on it.
 */
class NluRefusalTest {

    private fun parse(text: String) = NluParser.parse(text)
    private fun refused(reason: String) = ParseResult.Refused(reason)
    private fun commands(text: String) = (parse(text) as? ParseResult.Command)?.commands

    private fun assertUnrecognized(vararg texts: String) {
        for (text in texts) assertEquals(text, ParseResult.Unrecognized, parse(text))
    }

    @Test fun negation_words_refuse() {
        for (text in listOf("не открой окно", "не надо открывать окно", "нельзя открывать окно",
            "нет открой люк", "отмена закрой окна", "отмени открой люк", "Не надо, открой окно")) {
            assertEquals(text, refused(VoiceRefusal.NEGATION), parse(text))
        }
    }

    // The one rule: every word is read by the command, or the phrase goes to the agent.
    @Test fun a_word_the_parser_does_not_read_leaves_the_phrase_unrecognized() {
        assertUnrecognized(
            "открой окно завтра", "хочу открыть окно потом", "открой люк позже", "открой окна как раньше",
            "открой окно в салоне на палец", "открой окно на пол пальца", "открой багажник на ладонь",
            "открой окно на палец", "открой люк на ладонь", "открой окно на сколько-то",
            "открой окно на два сантиметра", "открой окна по кругу",
        )
        assertEquals(listOf("主驾打开100"), commands("открыть окно"))
    }

    // Verbs are read only in the forms the lexicon lists: other tenses and persons are not commands.
    @Test fun other_verb_forms_are_not_commands() {
        assertUnrecognized(
            "мы откроем багажник", "мы откроем багажник завтра", "открыли бы окно", "открывал люк",
            "открывается ли багажник", "мы открываем багажник", "закрываем окна", "я открываю люк",
            "открывал окно", "багажник открывается",
        )
    }

    @Test fun a_preposition_that_joins_nothing_is_not_read() {
        assertUnrecognized("открой окно на", "открой окно до")
    }

    @Test fun measures_read_but_not_expressible_refuse() {
        for (text in listOf("открой два окна", "открой окно на минимум", "закрой окна немного",
            "открой багажник наполовину", "приоткрой багажник")) {
            assertEquals(text, refused(VoiceRefusal.UNKNOWN_MEASURE), parse(text))
        }
    }

    // A measure only fits what reads it: a step, the volume, a light or a lock read none.
    @Test fun a_measure_the_command_does_not_read_is_not_dropped() {
        assertUnrecognized(
            "сделай теплее на два градуса", "громче на пять", "включи подогрев руля на максимум",
            "вентилятор на три чуть", "сделай теплее водителю",
        )
    }

    // Every device word names what the command does.
    @Test fun a_device_the_command_does_not_touch_is_not_dropped() {
        assertUnrecognized("открой багажник климат", "включи климат на двадцать два", "открой окно в салоне")
        assertEquals(listOf("吹前挡"), commands("включи обдув стекла"))
        assertEquals(listOf("车门上锁"), commands("заблокируй двери машины"))
    }

    // Every action word is read by the command: the airing mode on the trunk, the ventilation of
    // the wheel heater, a heater word on the fan are not dropped.
    @Test fun an_action_the_command_does_not_use_is_not_dropped() {
        assertUnrecognized("открой багажник на проветривание", "выключи вентиляцию руля", "включи вентиляцию зеркал",
            "проветри вентилятор на три", "включи подогрев климата")
        assertEquals(listOf("关闭方向盘加热"), commands("выключи подогрев руля"))
        assertEquals(listOf("主驾座椅加热2档"), commands("поставь подогрев сиденья на два"))
        assertEquals(listOf("打开空调通风"), commands("включи обдув"))
    }

    // A seat reads exactly one level (1..3, an ordinal, максимум/минимум/на полную) and no other
    // measure word; the temperature exactly one number.
    @Test fun a_seat_level_is_one_level_and_nothing_else() {
        assertUnrecognized("включи подогрев сиденья на три процента", "включи подогрев сиденья на полную наполовину",
            "включи подогрев сиденья на третий второй уровень", "включи подогрев сиденья на максимум минимум",
            "включи подогрев сидений на три процента", "поставь температуру 22 на максимум")
        assertEquals(listOf("主驾座椅加热3档"), commands("включи подогрев сиденья на полную"))
        assertEquals(listOf("主驾座椅加热3档"), commands("включи подогрев сиденья на третий уровень"))
        assertEquals(listOf("副驾座椅通风1档"), commands("включи вентиляцию сиденья пассажира на минимум"))
        assertEquals(refused(VoiceRefusal.CONFLICTING_MEASURE), parse("открой окно на максимум минимум"))
    }

    // Fillers go before any word is read; the window glass, "всех" and the climate are read by
    // the command that uses them.
    @Test fun fillers_and_words_the_command_uses_do_not_break_it() {
        assertEquals(listOf("主驾半开"), commands("открой окно на пятьдесят пожалуйста процентов"))
        assertEquals(listOf("主驾打开100"), commands("открой окно до пожалуйста конца"))
        assertEquals(listOf("主驾半开"), commands("открой окно на пожалуйста пятьдесят"))
        assertEquals(listOf("吹前挡"), commands("включи обдув переднего стекла"))
        assertEquals(listOf("后视镜加热"), commands("включи подогрев всех зеркал"))
        assertEquals(listOf("风量3"), commands("вентилятор климата на три"))
        assertUnrecognized("включи обдув переднего", "включи подогрев всех руля")
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

    // Floor: the widest detent that does not exceed the spoken share; under the smallest
    // detent nothing is small enough and the phrase goes to the agent.
    @Test fun spoken_shares_round_down_to_a_detent() {
        val windows = mapOf(
            "сто" to "主驾打开100", "восемьдесят" to "主驾半开", "семьдесят пять" to "主驾半开",
            "шестьдесят" to "主驾半开", "пятьдесят" to "主驾半开", "сорок" to "主驾通风",
            "тридцать три" to "主驾通风", "двадцать пять" to "主驾通风", "пятнадцать" to "主驾通风",
            "десять" to "主驾通风",
        )
        windows.forEach { (pct, want) ->
            assertEquals(pct, listOf(want), commands("открой окно на $pct процентов"))
        }
        assertEquals(listOf("主驾通风"), commands("открой окно на треть"))
        assertEquals(listOf("车窗半开"), commands("открой окна на три четверти"))
        val sunroof = mapOf("сто" to "天窗打开100", "восемьдесят" to "天窗打开50", "сорок" to "天窗通风", "семь" to "天窗通风")
        sunroof.forEach { (pct, want) ->
            assertEquals(pct, listOf(want), commands("открой люк на $pct процентов"))
        }
        assertUnrecognized("открой окно на пять процентов", "открой окно на девять процентов",
            "открой люк на пять процентов", "открой люк до пяти процентов")
    }

    @Test fun except_lists_every_excluded_window() {
        assertEquals(listOf("后左打开100", "后右打开100"),
            commands("открой окна кроме водительского окна и пассажирского окна"))
        assertEquals(listOf("主驾打开0", "副驾打开0"), commands("закрой окна кроме заднего левого и заднего правого"))
        assertEquals(listOf("后左打开0", "后右打开0"), commands("закрой окна кроме водительского и пассажирского"))
    }

    // «кроме» only with whole windows: a bare «правого» may be either row.
    @Test fun except_that_names_no_whole_window_is_unrecognized() {
        assertUnrecognized(
            "открой окна кроме", "открой люк кроме шторки", "включи подогрев всех сидений кроме пассажира",
            "открой окна кроме водительского и закрой люк", "открой окна кроме окна",
            "открой окна кроме переднего левого и правого", "закрой окна кроме заднего левого и правого",
            "сделай теплее кроме водительского сиденья", "громче кроме задних",
        )
    }

    // The exclusion resolves like any qualifier set: one window or pair, or nothing; a second
    // «кроме» is never half honoured.
    @Test fun except_resolves_whole_windows_only_once() {
        assertUnrecognized("открой окна кроме заднего окна водителя", "открой окна кроме заднего левого кроме заднего правого")
        assertEquals(listOf("主驾打开100", "后左打开100", "后右打开100"), commands("открой окна кроме переднего правого"))
    }

    @Test fun commands_the_parser_cannot_split_refuse() {
        for (text in listOf("открой окна и люк наполовину", "открой люк наполовину и окна", "жарко и холодно",
            "открой закрой окно", "подожди и открой окна", "открой окно и люк на палец")) {
            assertEquals(text, refused(VoiceRefusal.MULTIPLE_COMMANDS), parse(text))
        }
        // One target joined by «и» still parses whole.
        assertEquals(listOf("前排车窗全开"), commands("открой окна водителя и пассажира"))
    }

    @Test fun refusals_map_to_their_journal_codes() {
        assertEquals(NluOutcome.Refused(VoiceRefusal.NEGATION), NluOutcome.of(parse("не открывай окно")))
        assertEquals(NluOutcome.Refused(VoiceRefusal.UNRECOGNIZED), NluOutcome.of(parse("расскажи анекдот")))
    }
}
