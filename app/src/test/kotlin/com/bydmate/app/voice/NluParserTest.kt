package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class NluParserTest {
    private fun cmd(text: String, lang: VoiceLang = VoiceLang.RU): String? =
        (NluParser.parse(text, lang) as? ParseResult.Command)?.command

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
        assertEquals(ParseResult.Unrecognized, NluParser.parse("поставь температуру 40", VoiceLang.RU))
    }

    @Test fun english_lock_unlock() {
        assertEquals("车门上锁", cmd("lock the doors", VoiceLang.EN))
        assertEquals("车门解锁", cmd("unlock doors", VoiceLang.EN))
    }

    @Test fun garbage_is_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("сколько времени", VoiceLang.RU))
    }

    @Test fun missing_action_is_unrecognized() {
        // device but no action → never guess
        assertEquals(ParseResult.Unrecognized, NluParser.parse("окна", VoiceLang.RU))
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
        assertEquals(ParseResult.Unrecognized, NluParser.parse("температура 14", VoiceLang.RU))
    }

    @Test fun bare_temperature_without_number_stays_unrecognized() {
        // "температура" alone is ambiguous (no value) -> never guess
        assertEquals(ParseResult.Unrecognized, NluParser.parse("температура", VoiceLang.RU))
    }

    @Test fun relative_temperature_warmer_cooler() {
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("теплее", VoiceLang.RU))
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("сделай потеплее", VoiceLang.RU))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("холоднее", VoiceLang.RU))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("сделай прохладнее", VoiceLang.RU))
    }

    @Test fun windows_half_open() {
        assertEquals("车窗半开", cmd("приоткрой окна"))
        assertEquals("车窗半开", cmd("окна наполовину"))
    }

    @Test fun sunroof_tilt() {
        assertEquals("天窗打开50", cmd("приоткрой люк"))
    }

    @Test fun seat_heat_levels() {
        assertEquals("主驾座椅加热1档", cmd("подогрев сиденья водителя"))
        assertEquals("主驾座椅加热2档", cmd("подогрев сиденья водителя 2"))
        assertEquals("主驾座椅加热3档", cmd("подогрев сиденья водителя 3"))
    }

    @Test fun seat_vent_level_passenger() {
        assertEquals("副驾座椅通风3档", cmd("вентиляция сиденья пассажира 3"))
    }

    private fun vol(text: String, lang: VoiceLang = VoiceLang.RU): String? =
        (NluParser.parse(text, lang) as? ParseResult.Volume)?.payload

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

    @Test fun volume_mute_unmute_en() {
        assertEquals("mute", vol("turn off sound", VoiceLang.EN))
        assertEquals("unmute", vol("turn on sound", VoiceLang.EN))
    }
}
