@file:Suppress("MatchingDeclarationName") // the import checks of a shared rule, not a file for one type

package com.bydmate.app.ui.automation

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.automation.TimeOfDay
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.vehicle.CommandTranslator

/** Why an imported rule cannot be added: the engine would never fire it or the editor could not re-save it. */
internal sealed interface ImportProblem {
    data class Condition(val index: Int) : ImportProblem   // 1-based
    data class Action(val index: Int) : ImportProblem      // 1-based
    data object TooManyActions : ImportProblem
}

// The widget offers buttons 1..4, the same range the editor's button picker shows. The engine
// matches the pressed button by its text, so "01" or "+1" would never fire.
private val WIDGET_BUTTONS = (1..4).map { it.toString() }

/**
 * The editor's checks on a rule read from a share file: the action limit, then each condition,
 * then each action; the first problem wins. Rules built in the menus always pass.
 */
internal fun importProblem(rule: SharedRule): ImportProblem? {
    if (rule.actions.size > MAX_RULE_ACTIONS) return ImportProblem.TooManyActions
    rule.triggers.forEachIndexed { i, t -> if (!triggerOk(t)) return ImportProblem.Condition(i + 1) }
    rule.actions.forEachIndexed { i, a -> if (!actionOk(a)) return ImportProblem.Action(i + 1) }
    return null
}

internal fun ImportProblem.text(lc: Context): String = when (this) {
    is ImportProblem.Condition -> lc.getString(R.string.automation_import_bad_condition, index)
    is ImportProblem.Action -> lc.getString(R.string.automation_import_bad_action, index)
    ImportProblem.TooManyActions -> lc.getString(R.string.automation_import_too_many_actions, MAX_RULE_ACTIONS)
}

private fun triggerOk(t: TriggerDef): Boolean = when (t.kind) {
    "param" -> {
        val option = TRIGGER_PARAMS.firstOrNull { it.param == t.param }
        when {
            option == null -> false
            option.enumValues != null ->
                t.operator in operatorsFor(option) && option.enumValues.any { it.first == t.value }
            else -> t.operator in OPERATORS
        }
    }
    "time_range" -> ScheduleSpec.fromJson(t.value) != null
    // The engine compares the upper-cased value with the phase name.
    "time_of_day" -> TimeOfDay.entries.any { it.name == t.value.uppercase() }
    "button_press" -> t.value in WIDGET_BUTTONS
    else -> true
}

private fun actionOk(a: ActionDef): Boolean = when (a.kind) {
    // The write path's own test, not the menu list: a legacy command the car still accepts imports.
    // runCatching: a level number too big for an Int («设置温度2147483648») throws in the translator.
    "param" -> runCatching {
        CommandTranslator.resolveRearSeatHeat(a.command) != null ||
            CommandTranslator.resolveSeat(a.command) != null ||
            CommandTranslator.resolve(a.command).isNotEmpty()
    }.getOrDefault(false)
    "delay" -> a.payload?.toLongOrNull()?.let { it in 0..ActionDispatcher.MAX_DELAY_MS } == true
    else -> true
}
