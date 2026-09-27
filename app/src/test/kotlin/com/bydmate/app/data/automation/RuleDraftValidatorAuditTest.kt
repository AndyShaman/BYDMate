package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.vehicle.DriveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Automation audit 3.19: condition values, steering keys, one-shot rules, drive mode values. */
class RuleDraftValidatorAuditTest {

    private fun param(param: String, value: String, op: String = "<") =
        TriggerDef(param = param, chineseName = "", operator = op, value = value, displayName = param)

    private fun of(kind: String, value: String = "") =
        TriggerDef(param = kind, chineseName = "", operator = "==", value = value, displayName = kind, kind = kind)

    private fun validate(vararg triggers: TriggerDef, logic: String = "AND") =
        RuleDraftValidator.validateTriggers(
            triggers.toList(), editingId = -1L, existingRules = emptyList(), triggerLogic = logic,
        )

    private val moment = "2026-10-01T08:30"

    // --- Item 3 ---

    @Test fun `a comma decimal is a number`() {
        assertEquals(12.5, TriggerNumber.parse("12,5")!!, 0.0)
        assertEquals(12.5, TriggerNumber.parse(" 12.5 ")!!, 0.0)
        assertNull(validate(param("Voltage12V", "12,5")))
    }

    @Test fun `an empty or non-numeric value is refused`() {
        assertEquals(TriggerValidationError.ValueNotNumber("SOC"), validate(param("SOC", "")))
        assertEquals(TriggerValidationError.ValueNotNumber("SOC"), validate(param("SOC", "abc")))
        assertNull(TriggerNumber.parse("NaN"))
    }

    // --- Item 5 ---

    @Test fun `a steering key trigger with no key is refused`() {
        val unassigned = of(AutomationEngine.TRIGGER_KIND_STEERING_KEY, "0")
        assertEquals(TriggerValidationError.SteeringKeyUnassigned, validate(unassigned))
        assertNull(validate(of(AutomationEngine.TRIGGER_KIND_STEERING_KEY, "24")))
    }

    // An import accepts the unassigned key, so it must be the last verdict: nothing else hides behind it.
    @Test fun `an unassigned key is reported after every other check`() {
        assertEquals(
            TriggerValidationError.VoicePhraseEmpty,
            validate(of(AutomationEngine.TRIGGER_KIND_STEERING_KEY, "0"), of("voice", " ")),
        )
    }

    // --- Item 14 ---

    @Test fun `a one-shot moment with AND param conditions is accepted`() {
        assertNull(validate(of(OneShotTrigger.KIND, moment), param("SOC", "30", ">")))
    }

    @Test fun `a one-shot rule with OR is refused`() {
        assertEquals(
            TriggerValidationError.OneShotWithOr,
            validate(of(OneShotTrigger.KIND, moment), param("SOC", "30", ">"), logic = "OR"),
        )
    }

    @Test fun `a one-shot rule with an event trigger is refused`() {
        assertEquals(
            TriggerValidationError.OneShotWithEvent,
            validate(of(OneShotTrigger.KIND, moment), of("service_start", "true")),
        )
    }

    @Test fun `two one-shot moments are refused`() {
        assertEquals(
            TriggerValidationError.OneShotTwice,
            validate(of(OneShotTrigger.KIND, moment), of(OneShotTrigger.KIND, "2026-10-02T08:30")),
        )
    }

    @Test fun `a one-shot moment that is not a date is refused`() {
        assertEquals(TriggerValidationError.OneShotInvalid, validate(of(OneShotTrigger.KIND, "tomorrow")))
    }

    @Test fun `one-shot states around the moment`() {
        val at = OneShotTrigger.momentMs(moment)!!
        assertEquals(OneShotTrigger.State.PENDING, OneShotTrigger.state(moment, at - 1))
        assertEquals(OneShotTrigger.State.DUE, OneShotTrigger.state(moment, at))
        assertEquals(OneShotTrigger.State.DUE, OneShotTrigger.state(moment, at + OneShotTrigger.WINDOW_MS - 1))
        assertEquals(OneShotTrigger.State.EXPIRED, OneShotTrigger.state(moment, at + OneShotTrigger.WINDOW_MS))
    }

    // --- Item 4 ---

    @Test fun `the target mode refines only a legacy normal`() {
        // dev 1006 reads 3 on sand: the target says which
        assertEquals(5, DriveModeCondition.value(3, DriveMode.SAND.value))
        assertEquals(21, DriveModeCondition.value(3, DriveMode.SMART.value))
        assertEquals(3, DriveModeCondition.value(3, DriveMode.NORMAL.value))
        // a legacy mode of its own stays the answer
        assertEquals(1, DriveModeCondition.value(1, DriveMode.SAND.value))
        assertEquals(4, DriveModeCondition.value(4, DriveMode.SNOW.value))
    }

    @Test fun `without a known target the legacy value stays`() {
        assertEquals(3, DriveModeCondition.value(3, null))
        assertEquals(3, DriveModeCondition.value(3, DriveMode.TARGET_FLOTATION))
        assertEquals(2, DriveModeCondition.value(null, DriveMode.SPORT.value))
        assertNull(DriveModeCondition.value(null, null))
    }
}
