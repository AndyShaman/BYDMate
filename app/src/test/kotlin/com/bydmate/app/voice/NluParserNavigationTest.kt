package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Offline «домой / на работу» and «открой навигатор» (#416): only whole phrases start anything. */
class NluParserNavigationTest {

    @Test fun home_phrases_navigate_to_the_home_shortcut() {
        for (phrase in listOf(
            "поехали домой", "едем домой", "маршрут домой", "проложи маршрут домой", "построй маршрут домой",
        )) {
            assertEquals(phrase, ParseResult.Navigate("home"), NluParser.parse(phrase))
        }
    }

    @Test fun work_phrases_navigate_to_the_work_shortcut() {
        for (phrase in listOf(
            "поехали на работу", "едем на работу", "маршрут на работу", "проложи маршрут на работу",
            "построй маршрут на работу",
        )) {
            assertEquals(phrase, ParseResult.Navigate("work"), NluParser.parse(phrase))
        }
    }

    @Test fun navigator_phrases_open_the_navigator() {
        for (phrase in listOf(
            "навигация", "открой навигацию", "запусти навигацию", "включи навигацию",
            "открой навигатор", "запусти навигатор", "включи навигатор",
        )) {
            assertEquals(phrase, ParseResult.OpenNavigator, NluParser.parse(phrase))
        }
    }

    @Test fun partial_or_other_phrases_start_nothing() {
        for (phrase in listOf(
            "поехали", "домой", "работа", "включи музыку", "запусти радио", "поехали в магазин", "маршрут",
        )) {
            assertEquals(phrase, ParseResult.Unrecognized, NluParser.parse(phrase))
        }
    }

    // Like Music: a non-Command result is never a part of a compound, the whole phrase goes to the agent.
    @Test fun a_navigation_phrase_is_not_part_of_a_compound() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("поехали домой и закрой окна"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("закрой окна и открой навигатор"))
    }
}
