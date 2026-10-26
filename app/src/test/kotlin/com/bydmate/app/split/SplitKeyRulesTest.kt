package com.bydmate.app.split

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.ui.automation.newSteeringKeyTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitKeyRulesTest {
    private fun rule(id: Long, name: String, key: Int, vararg kinds: String, enabled: Boolean = true) = RuleEntity(
        id = id, name = name, enabled = enabled,
        triggers = TriggerDef.listToJson(listOf(newSteeringKeyTrigger(key))),
        actions = ActionDef.listToJson(kinds.map { ActionDef(command = "", displayName = it, kind = it) }),
    )

    @Test fun `no rules leave the key free`() {
        assertEquals(SplitKeyRuleVerdict.Free, splitKeyRuleVerdict(383, emptyList()))
    }
    @Test fun `rules on other keys leave the key free`() {
        assertEquals(SplitKeyRuleVerdict.Free,
            splitKeyRuleVerdict(383, listOf(rule(1, "Музыка", 384, "yandex_music"))))
    }
    @Test fun `a toggle rule on the key is offered for turning off`() {
        val r = rule(1, "Сплит", 383, "split_screen_toggle")
        assertEquals(SplitKeyRuleVerdict.Conflicts(listOf(r)), splitKeyRuleVerdict(383, listOf(r)))
    }
    @Test fun `a start rule with its own pair is offered too`() {
        val r = rule(1, "Сплит пара", 383, "split_screen")
        assertEquals(SplitKeyRuleVerdict.Conflicts(listOf(r)), splitKeyRuleVerdict(383, listOf(r)))
    }
    @Test fun `a rule mixing a split start with other actions is still a conflict`() {
        val r = rule(1, "Сплит и голос", 383, "split_screen_toggle", "speak")
        assertEquals(SplitKeyRuleVerdict.Conflicts(listOf(r)), splitKeyRuleVerdict(383, listOf(r)))
    }
    @Test fun `a rule without split actions makes the key occupied`() {
        assertEquals(SplitKeyRuleVerdict.Occupied("Музыка"),
            splitKeyRuleVerdict(383, listOf(rule(1, "Музыка", 383, "yandex_music"))))
    }
    @Test fun `a close-only rule occupies the key`() {
        assertEquals(SplitKeyRuleVerdict.Occupied("Закрыть"),
            splitKeyRuleVerdict(383, listOf(rule(1, "Закрыть", 383, "split_screen_close"))))
    }
    @Test fun `a non-split rule wins over split rules on the same key`() {
        val rules = listOf(rule(1, "Сплит", 383, "split_screen_toggle"), rule(2, "Музыка", 383, "yandex_music"))
        assertEquals(SplitKeyRuleVerdict.Occupied("Музыка"), splitKeyRuleVerdict(383, rules))
    }
    @Test fun `disabled rules neither occupy nor conflict`() {
        val rules = listOf(
            rule(1, "Сплит", 383, "split_screen_toggle", enabled = false),
            rule(2, "Музыка", 383, "yandex_music", enabled = false),
        )
        assertEquals(SplitKeyRuleVerdict.Free, splitKeyRuleVerdict(383, rules))
    }
    @Test fun `every split rule on the key is offered`() {
        val a = rule(1, "Сплит", 383, "split_screen_toggle")
        val b = rule(2, "Сплит 2", 383, "split_screen")
        assertEquals(SplitKeyRuleVerdict.Conflicts(listOf(a, b)), splitKeyRuleVerdict(383, listOf(a, b)))
    }
    @Test fun `a trigger without a learned key claims nothing`() {
        assertEquals(SplitKeyRuleVerdict.Free,
            splitKeyRuleVerdict(383, listOf(rule(1, "Пустое", 0, "yandex_music"))))
    }
}
