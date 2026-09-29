package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// #262: a steering-key rule alone must keep the a11y key filter under the self-heal gate.
class SteeringKeyRuleGateTest {

    private fun trigger(kind: String, param: String = kind, value: String = "1") = TriggerDef(
        param = param, chineseName = "", operator = "==", value = value, displayName = kind, kind = kind,
    )

    private val steeringKey = trigger(AutomationEngine.TRIGGER_KIND_STEERING_KEY, value = "305")

    private fun rule(vararg triggers: TriggerDef, enabled: Boolean = true) = RuleEntity(
        name = "r", enabled = enabled,
        triggers = TriggerDef.listToJson(triggers.toList()),
        actions = ActionDef.listToJson(listOf(ActionDef("车窗关闭", "Close windows"))),
    )

    @Test fun `enabled rule with a steering key trigger opens the gate`() {
        assertTrue(AutomationEngine.hasEnabledSteeringKeyRule(listOf(rule(steeringKey))))
    }

    @Test fun `the same rule disabled does not`() {
        assertFalse(AutomationEngine.hasEnabledSteeringKeyRule(listOf(rule(steeringKey, enabled = false))))
    }

    @Test fun `enabled rules with other triggers only do not`() {
        val rules = listOf(
            rule(trigger("button_press")),
            rule(trigger("param", param = "车速")),
            rule(trigger("service_start")),
        )
        assertFalse(AutomationEngine.hasEnabledSteeringKeyRule(rules))
    }

    @Test fun `a steering key among several triggers opens the gate`() {
        val r = rule(trigger("param", param = "车速"), steeringKey, trigger("time_of_day"))
        assertTrue(AutomationEngine.hasEnabledSteeringKeyRule(listOf(r)))
    }

    @Test fun `no rules do not`() {
        assertFalse(AutomationEngine.hasEnabledSteeringKeyRule(emptyList()))
    }
}
