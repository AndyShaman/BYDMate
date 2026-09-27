package com.bydmate.app.ui.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Эко при низком заряде» is back exactly as before its removal in ec94f907 (#253). */
class StarterTemplatesTest {

    @Test fun `eco at low charge is shipped disabled with SOC below 15 and a 300 s cooldown`() {
        val rule = starterTemplates("ru").single { it.name == "Эко при низком заряде" }
        assertFalse(rule.enabled)
        assertEquals("AND", rule.triggerLogic)
        assertEquals(300, rule.cooldownSeconds)
        val trigger = TriggerDef.listFromJson(rule.triggers).single()
        assertEquals("SOC", trigger.param)
        assertEquals("<", trigger.operator)
        assertEquals("15", trigger.value)
        val action = ActionDef.listFromJson(rule.actions).single()
        assertEquals("ECO模式", action.command)
        assertEquals("ECO режим", action.displayName)
        assertTrue(CommandTranslator.resolve(action.command).isNotEmpty())
    }

    @Test fun `the rule name follows the language`() {
        assertTrue(starterTemplates("en").any { it.name == "ECO at low SOC" })
        assertTrue(starterTemplates("zh").any { it.name == "低电量ECO" })
        assertTrue(starterTemplates("be").any { it.name == "Эко при низком заряде" })
    }
}
