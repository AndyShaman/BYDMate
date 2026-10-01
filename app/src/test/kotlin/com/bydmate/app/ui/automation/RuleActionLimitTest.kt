package com.bydmate.app.ui.automation

import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.RuleParseResult
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Up to [MAX_RULE_ACTIONS] actions in one rule: nothing past the editor cuts or refuses them. */
class RuleActionLimitTest {

    @Test fun `a rule holds up to 20 actions`() {
        assertEquals(20, MAX_RULE_ACTIONS)
    }

    @Test fun `20 actions of mixed kinds validate`() {
        assertNull(RuleDraftValidator.validateActions(twentyActions()))
    }

    @Test fun `20 actions survive the share file in their order`() {
        val rule = SharedRule(
            name = "Long", triggerLogic = "AND",
            triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость")),
            actions = twentyActions(), cooldownSeconds = 60, requirePark = false,
            confirmBeforeExecute = false, fireOncePerTrip = false, playSound = false,
        )
        val parsed = RuleShare.parse(RuleShare.exportJson(rule, "3.19.5"), "Звонок")
        assertTrue(parsed is RuleParseResult.Ok)
        val imported = (parsed as RuleParseResult.Ok).rule.actions
        assertEquals(twentyActions(), imported)
        assertEquals(twentyActions(), ActionDef.listFromJson(ActionDef.listToJson(imported)))
    }

    companion object {
        /** Twenty valid actions, kinds cycling, each with its own display name. */
        fun twentyActions(): List<ActionDef> {
            val kinds = listOf(
                ActionDef("车窗关闭", "Окна"),
                ActionDef(command = "", displayName = "Пауза", kind = "delay", payload = "500"),
                ActionDef(command = "media_volume", displayName = "Громкость", kind = "media_volume", payload = "10"),
                ActionDef(command = "", displayName = "Навигатор", kind = "app_launch",
                    payload = """{"packageName":"ru.yandex.yandexnavi"}"""),
                ActionDef(command = "", displayName = "Скажи", kind = "speak", payload = """{"text":"Привет"}"""),
            )
            return (1..20).map { n -> kinds[(n - 1) % kinds.size].let { it.copy(displayName = "${it.displayName} $n") } }
        }
    }
}
