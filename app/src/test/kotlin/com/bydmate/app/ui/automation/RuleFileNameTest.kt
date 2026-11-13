package com.bydmate.app.ui.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Open with» offers only share files `bydmate_rule_*.json`, whatever a browser did to the name. */
class RuleFileNameTest {

    @Test fun share_file_names_are_accepted() {
        assertTrue(isRuleFileName("bydmate_rule_seat_heat.json"))
        assertTrue(isRuleFileName("bydmate_rule_x (1).json"))
        assertTrue(isRuleFileName("BYDMate_Rule_X.JSON"))
        assertTrue(isRuleFileName("bydmate_rule_.json"))
    }

    @Test fun other_names_are_rejected() {
        assertFalse(isRuleFileName("rule.json"))
        assertFalse(isRuleFileName("bydmate_backup_2026.json"))
        assertFalse(isRuleFileName("bydmate_rule_x.json.txt"))
        assertFalse(isRuleFileName("bydmate_rule_x.txt"))
        assertFalse(isRuleFileName(""))
        assertFalse(isRuleFileName(null))
    }
}
