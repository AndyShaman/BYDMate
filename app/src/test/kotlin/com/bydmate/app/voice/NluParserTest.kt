package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class NluParserTest {
    private fun cmd(text: String): String? =
        (NluParser.parse(text) as? ParseResult.Command)?.command

    @Test fun word_order_does_not_matter() {
        assertEquals("车窗关闭", cmd("закрой окна"))
        assertEquals("车窗关闭", cmd("окна закрой"))
    }

    @Test fun synonyms_window_glass_vent_qualifier() {
        assertEquals("主驾打开100", cmd("открой стекло водителя"))
        assertEquals("主驾打开100", cmd("открой окно водителя"))
        assertEquals("主驾打开100", cmd("водительскую форточку открой"))
    }

    @Test fun passenger_window_qualifier_resolves() {
        assertEquals("副驾打开100", cmd("открой стекло пассажира"))
        assertEquals("副驾打开0", cmd("закрой пассажирское окно"))
        // bare root "пассажир" (the easier Vosk target) must also resolve the side
        assertEquals("副驾打开100", cmd("открой окно пассажир"))
    }

    @Test fun individual_window_vent_resolves() {
        assertEquals("副驾通风", cmd("проветри окно пассажира"))
        assertEquals("后左通风", cmd("проветри левое окно"))
    }

    @Test fun polarity_is_strict() {
        assertEquals("氛围灯打开", cmd("включи амбиент"))
        assertEquals("氛围灯关闭", cmd("выключи амбиент"))
    }

    @Test fun temperature_with_digit_and_word() {
        assertEquals("设置温度22", cmd("поставь температуру 22"))
        assertEquals("设置温度24", cmd("сделай двадцать четыре градуса"))
    }

    @Test fun temperature_out_of_range_is_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("поставь температуру 40"))
    }

    @Test fun garbage_is_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("сколько времени"))
    }

    @Test fun missing_action_is_unrecognized() {
        // device but no action → never guess
        assertEquals(ParseResult.Unrecognized, NluParser.parse("окна"))
    }

    @Test fun ac_off_recognized() {
        assertEquals("关闭空调", cmd("выключи кондиционер"))
        assertEquals("关闭空调", cmd("выключи климат"))
    }

    @Test fun airflow_collision_defaults_to_climate() {
        // was: resolved to 2 commands (打开空调通风 + 吹前挡) → Unrecognized
        assertEquals("打开空调通风", cmd("включи обдув"))
    }

    @Test fun airflow_windshield_with_verb() {
        assertEquals("吹前挡", cmd("включи обдув лобового"))
    }

    @Test fun airflow_seat_wins_over_climate() {
        assertEquals("主驾座椅通风1档", cmd("обдув сиденья водителя"))
    }

    @Test fun bare_temperature_without_verb() {
        assertEquals("设置温度24", cmd("температура 24"))
        assertEquals("设置温度24", cmd("24 градуса"))
        assertEquals("设置温度26", cmd("температура двадцать шесть"))
    }

    @Test fun bare_temperature_out_of_range_is_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("температура 14"))
    }

    @Test fun bare_temperature_without_number_stays_unrecognized() {
        // "температура" alone is ambiguous (no value) -> never guess
        assertEquals(ParseResult.Unrecognized, NluParser.parse("температура"))
    }

    @Test fun relative_temperature_warmer_cooler() {
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("теплее"))
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("сделай потеплее"))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("холоднее"))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("сделай прохладнее"))
    }

    @Test fun windows_half_open() {
        assertEquals("车窗半开", cmd("окна наполовину"))
        assertEquals("车窗半开", cmd("открой окна на половину"))
    }

    // "приоткрой" is the vent detent (Andy 2026-09-24): windows crack, the sunroof tilts.
    @Test fun ajar_is_vent() {
        assertEquals("车窗通风", cmd("приоткрой окна"))
        assertEquals("天窗通风", cmd("приоткрой люк"))
    }

    @Test fun sunroof_half_slides() {
        assertEquals("天窗打开50", cmd("открой люк наполовину"))
    }

    @Test fun seat_heat_levels() {
        assertEquals("主驾座椅加热1档", cmd("подогрев сиденья водителя"))
        assertEquals("主驾座椅加热2档", cmd("подогрев сиденья водителя 2"))
        assertEquals("主驾座椅加热3档", cmd("подогрев сиденья водителя 3"))
    }

    @Test fun seat_vent_level_passenger() {
        assertEquals("副驾座椅通风3档", cmd("вентиляция сиденья пассажира 3"))
    }

    private fun vol(text: String): String? =
        (NluParser.parse(text) as? ParseResult.Volume)?.payload

    @Test fun volume_absolute() {
        assertEquals("10", vol("громкость на 10"))
        assertEquals("20", vol("громкость 20"))
    }
    @Test fun volume_relative() {
        assertEquals("+1", vol("сделай громче"))
        assertEquals("-1", vol("сделай тише"))
    }
    @Test fun volume_mute_unmute() {
        assertEquals("mute", vol("выключи звук"))
        assertEquals("unmute", vol("включи звук"))
    }

    // Seat OFF (singular): "подогрев"/"обдув" also tags an action slot (HEAT_1/VENT_1),
    // so OFF used to fan out across both seat subsystems (heat-off + vent-off + heat-1
    // = 3 commands -> Unrecognized -> LLM). With an explicit OFF verb the noun must
    // narrow the seat family instead. In-car reports: "выключить подогрев сидений не работает".
    @Test fun seat_heat_off_singular() = assertEquals("主驾座椅加热关闭", cmd("выключи подогрев сиденья"))
    @Test fun seat_vent_off_singular() = assertEquals("主驾座椅通风关闭", cmd("выключи обдув сиденья"))
    @Test fun seat_vent_off_ventilation_word() = assertEquals("主驾座椅通风关闭", cmd("выключи вентиляцию сиденья"))
    @Test fun seat_heat_off_passenger() = assertEquals("副驾座椅加热关闭", cmd("выключи подогрев сиденья пассажира"))
    @Test fun seat_heat_off_otklyuchi() = assertEquals("主驾座椅加热关闭", cmd("отключи подогрев сиденья"))
    // Regression guards: ON path and mirror heat must not change.
    @Test fun seat_heat_on_still_level1() = assertEquals("主驾座椅加热1档", cmd("включи подогрев сиденья"))
    @Test fun mirror_heat_off_still_resolves() = assertEquals("关闭后视镜加热", cmd("выключи подогрев зеркал"))

    @Test fun steering_heat_resolves() {
        assertEquals("方向盘加热", cmd("включи подогрев руля"))
        assertEquals("关闭方向盘加热", cmd("выключи подогрев руля"))
        assertEquals("方向盘加热", cmd("включи руль"))
    }

    // The heater needs a heat word or an on/off verb: the wheel alone is not the heater.
    @Test fun steering_wheel_without_heat_or_switch_is_not_the_heater() {
        for (phrase in listOf("поверни руль", "руль налево", "подогрев руля", "открой руль")) {
            assertEquals(phrase, ParseResult.Unrecognized, NluParser.parse(phrase))
        }
    }

    // ASR slip seen in a real transcript (2026-09-15): "руля" heard as "роля".
    @Test fun steering_heat_asr_slip() = assertEquals("方向盘加热", cmd("включи подогрев роля"))
}
