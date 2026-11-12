package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPromptTest {

    private class Rule(val label: String, val phrase: String, val present: Boolean = true)

    private val rules = listOf(
        Rule("places tools: list_places", "list_places"),
        Rule("places tools: create_place", "create_place"),
        Rule("unknown semantics are pinned", "НЕИЗВЕСТЕН"),
        Rule("compound few-shot", "seat_heat_driver_3"),
        // #254: a Song Plus log showed the model picking seat level 3 for "включи подогрев водителя";
        // that car only takes levels 1-2, so a level-less request must default to 1 like the offline voice.
        Rule("#254 unnamed seat level: no level named", "без названного уровня"),
        Rule("#254 unnamed seat level: default to 1", "ставь 1 уровень"),
        Rule("exact time trigger", "time_range"),
        // Terse-while-driving is a static rule about a tag on the user turn: keeping it out of the
        // system prompt is what makes the cached prefix identical between turns.
        Rule("static moving rule: tag", AgentOrchestrator.MOVING_TAG),
        Rule("static moving rule: one confirmation", "одним подтверждением"),
        // The app only sees the electric side of the car (some models in the fleet are hybrids),
        // so the prompt must scope the data instead of declaring the powertrain.
        Rule("electric side only: scoped to no fuel data", "данных о топливе"),
        Rule("electric side only: never declares a hybrid", "Никогда не называй машину гибридом", present = false),
        // On-car log 25.09: the model named the time, villages and a "never asked" from thin air.
        Rule("log 25.09 time only from the tag: tag format", "(${AgentOrchestrator.TIME_TAG_PREFIX} ЧЧ:ММ)"),
        Rule("log 25.09 time only from the tag: no other source", "другого источника времени у тебя нет"),
        Rule("places only from where_am_i: tool", "where_am_i"),
        Rule("places only from where_am_i: no coordinates guessing", "не угадывай и не ищи координаты через web_search"),
        Rule("places only from where_am_i: admit not knowing", "скажи, что не знаешь, где мы"),
        Rule("places only from where_am_i: no settlement claim", "не говори, что мы в населённом пункте"),
        // Review of 2c994429: a live fix's hedge must not depend on current speed - zero speed does
        // not prove the car has been parked since the fix.
        Rule("where_am_i hedge (2c994429): fix_note", "fix_note"),
        Rule("where_am_i hedge (2c994429): regardless of speed", "даже если машина сейчас стоит"),
        Rule("forgetting: admit not remembering", "скажи, что не помню"),
        Rule("forgetting: no denying", "не утверждай, что такого не было"),
        Rule("pending confirmation status", "ожидает подтверждения на экране"),
        Rule("steering-key trigger refused by voice", "клавиша руля"),
        // Wave 4: an automation built on a guessed threshold is worse than a question.
        Rule("no guessed automation details: threshold", "Не додумывай ни порог"),
        Rule("no guessed automation details: ask first", "уточняющий вопрос и только потом создавай"),
        Rule("read the created rule back", "возвращает поле rule"),
        Rule("stored comfort preferences", "привычка по комфорту"),
        Rule("driver memory tools: remember_fact", "remember_fact"),
        Rule("driver memory tools: forget_fact", "forget_fact"),
        // Live charger status: the model must not claim a free connector the source does not know,
        // must say "по прямой" and must route to the pick by its coordinates.
        Rule("charger answers: tool", "find_chargers"),
        Rule("charger answers: unknown status", "status_known=false"),
        Rule("charger answers: straight-line distance", "\"по прямой\""),
        Rule("charger answers: route by coordinates", "navigate_to с её lat и lon"),
    )

    @Test fun system_prompt_carries_every_rule() {
        for (r in rules) {
            assertEquals(
                "case: ${r.label} (phrase: ${r.phrase})",
                r.present,
                AgentOrchestrator.SYSTEM_PROMPT.contains(r.phrase),
            )
        }
    }
}
