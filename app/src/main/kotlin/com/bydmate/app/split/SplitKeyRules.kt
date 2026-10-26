package com.bydmate.app.split

import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef

/** Actions that start the split (owner: "start or toggle"); a close-only rule is not one. */
val SPLIT_START_ACTION_KINDS = setOf("split_screen", "split_screen_toggle")

/** What the automation rules on a key mean for making it the split key. */
sealed interface SplitKeyRuleVerdict {
    data object Free : SplitKeyRuleVerdict

    /** An enabled rule without a split start sits on the key: it cannot be chosen. */
    data class Occupied(val ruleName: String) : SplitKeyRuleVerdict

    /** Only split rules sit on the key: offer to turn them off. */
    data class Conflicts(val rules: List<RuleEntity>) : SplitKeyRuleVerdict
}

/**
 * Enabled `steering_key` rules on [keyCode]. A rule with a split start plus other actions is a
 * conflict as a whole: there is no per-action switch, so turning it off turns off all of it.
 */
fun splitKeyRuleVerdict(keyCode: Int, rules: List<RuleEntity>): SplitKeyRuleVerdict {
    val onKey = rules.filter { rule ->
        rule.enabled && TriggerDef.listFromJson(rule.triggers).any {
            it.kind == AutomationEngine.TRIGGER_KIND_STEERING_KEY && it.value == keyCode.toString()
        }
    }
    onKey.firstOrNull { rule -> ActionDef.listFromJson(rule.actions).none { it.kind in SPLIT_START_ACTION_KINDS } }
        ?.let { return SplitKeyRuleVerdict.Occupied(it.name) }
    return if (onKey.isEmpty()) SplitKeyRuleVerdict.Free else SplitKeyRuleVerdict.Conflicts(onKey)
}
