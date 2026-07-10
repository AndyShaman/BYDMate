package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline eval harness (wave D1): a fixture table of REAL phrases (many straight
 * from in-car VoiceJournal reports) pinned to their expected route. Every future
 * NLU bug from the car gets one more row here before it gets a fix.
 *
 * expected = Chinese command string -> must resolve to exactly that command;
 * expected = null -> must be Unrecognized (handed to the agent).
 */
class NluRegressionTest {

    private val fixtures: List<Pair<String, String?>> = listOf(
        // windows
        "открой окно" to "车窗全开",
        "закрой все окна" to "车窗关闭",
        "открой окно водителя" to "主驾打开100",
        "открой заднее правое окно" to "后右打开100",
        "закрой заднее левое окно" to "后左打开0",
        "открой переднее левое окно" to "主驾打开100",
        "открой правое окно" to "后右打开100",
        "проветри окна" to "车窗通风",
        "открой передние окна" to "前排车窗全开",
        // climate
        "температура 24" to "设置温度24",
        "включи кондиционер" to "自动空调",
        "выключи климат" to "关闭空调",
        "включи внутреннюю рециркуляцию" to "内循环",
        // seats
        "включи подогрев сиденья" to "主驾座椅加热1档",
        "подогрев сиденья на 2" to "主驾座椅加热2档",
        "подогрев сиденья пассажира на 3" to "副驾座椅加热3档",
        "включи вентиляцию сиденья" to "主驾座椅通风1档",
        "подогрев сиденья на 5" to null,       // level 5 -> agent (seat_heat_driver_5)
        // locks / car
        "закрой машину" to "车门上锁",
        "открой машину" to "车门解锁",
        "запри двери" to "车门上锁",
        // trunks
        "открой багажник" to "开后备箱",
        "открой задний багажник" to "开后备箱",
        "открой передний багажник" to null,     // agent: front_trunk_open
        "закрой передний багажник" to null,     // agent: front_trunk_close
        // sunroof / shade
        "открой люк" to "天窗打开100",
        "приоткрой люк" to "天窗打开50",
        "закрой шторку" to "遮阳帘关闭",
        // lights / mirrors
        "включи свет" to "打开车内灯",
        "включи подсветку" to "氛围灯打开",
        "включи обогрев зеркал" to "后视镜加热",
        // negation -> agent
        "не открывай окно" to null,
        "нет не надо" to null,
        // out-of-catalog -> agent
        "включи холодильник" to null,           // agent: fridge_cool
        "какая погода" to null,
        "поставь музыку" to null,
    )

    @Test fun fixtures_route_as_expected() {
        val failures = StringBuilder()
        fixtures.forEach { (text, expected) ->
            val actual = (NluParser.parse(text, VoiceLang.RU) as? ParseResult.Command)?.command
            if (actual != expected) {
                failures.append("\"$text\": expected=$expected actual=$actual\n")
            }
        }
        assertEquals("NLU regressions:\n$failures", "", failures.toString())
    }

    @Test fun relative_temp_and_volume_shortcuts() {
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("сделай теплее", VoiceLang.RU))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("похолоднее", VoiceLang.RU))
        assertEquals(ParseResult.Volume("+1"), NluParser.parse("громче", VoiceLang.RU))
        assertEquals(ParseResult.Volume("mute"), NluParser.parse("выключи звук", VoiceLang.RU))
    }
}
