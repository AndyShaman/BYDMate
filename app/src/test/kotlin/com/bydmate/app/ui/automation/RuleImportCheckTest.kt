package com.bydmate.app.ui.automation

import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor's checks run on an imported rule: a rule the editor would refuse never reaches the DB. */
class RuleImportCheckTest {

    private val speed = TriggerDef("Speed", "车速", ">", "7", "Скорость")
    private val window = ActionDef("车窗关闭", "Окна")

    private fun rule(
        triggers: List<TriggerDef> = listOf(speed),
        actions: List<ActionDef> = listOf(window),
    ) = SharedRule(
        name = "R", triggerLogic = "AND", triggers = triggers, actions = actions,
        cooldownSeconds = 60, requirePark = false, confirmBeforeExecute = false,
        fireOncePerTrip = false, playSound = false,
    )

    private fun withTrigger(t: TriggerDef) = rule(triggers = listOf(t))
    private fun withAction(a: ActionDef) = rule(actions = listOf(a))

    private fun delay(payload: String?) = ActionDef("", "Пауза", kind = "delay", payload = payload)
    private fun schedule(value: String) = TriggerDef("Schedule", "", "==", value, "Расписание", kind = "time_range")

    @Test fun `rear logo light actions pass the import check (383)`() {
        for (c in listOf("后车标灯打开", "后车标灯关闭")) assertNull(c, importProblem(withAction(ActionDef(c, "x"))))
    }

    @Test fun `a valid base rule passes`() {
        assertNull(importProblem(rule()))
    }

    @Test fun `unknown parameter is a bad condition`() {
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(speed.copy(param = "Foo"))))
    }

    @Test fun `enum code outside the catalog is a bad condition`() {
        val gear = TriggerDef("Gear", "档位", "==", "9", "Передача")
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(gear)))
    }

    @Test fun `enum parameter with a greater-than operator is a bad condition`() {
        val code = TRIGGER_PARAMS.first { it.param == "Gear" }.enumValues!!.first().first
        val gear = TriggerDef("Gear", "档位", ">", code, "Передача")
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(gear)))
    }

    @Test fun `unknown operator is a bad condition`() {
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(speed.copy(operator = "=>"))))
    }

    @Test fun `the index of the bad condition is 1-based`() {
        assertEquals(
            ImportProblem.Condition(2),
            importProblem(rule(triggers = listOf(speed, speed.copy(param = "Foo")))),
        )
    }

    @Test fun `schedule that is not json is a bad condition`() {
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(schedule("not json"))))
    }

    @Test fun `schedule with a day outside 1 to 7 is a bad condition`() {
        assertEquals(
            ImportProblem.Condition(1),
            importProblem(withTrigger(schedule("""{"from":"08:00","to":"10:00","days":[0]}"""))),
        )
    }

    @Test fun `time of day accepts only the known names`() {
        fun t(v: String) = TriggerDef("TimeOfDay", "", "==", v, "Время суток", kind = "time_of_day")
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(t("NOON"))))
        assertNull(importProblem(withTrigger(t("day"))))
    }

    @Test fun `button press accepts buttons 1 to 4`() {
        fun t(v: String) = TriggerDef("button", "", "==", v, "Кнопка", kind = "button_press")
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(t("5"))))
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(t("0"))))
        assertNull(importProblem(withTrigger(t("4"))))
    }

    @Test fun `button press value must be written as the button number`() {
        fun t(v: String) = TriggerDef("button", "", "==", v, "Кнопка", kind = "button_press")
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(t("01"))))
        assertEquals(ImportProblem.Condition(1), importProblem(withTrigger(t("+1"))))
    }

    @Test fun `a level number too big for an Int is a bad action, not a crash`() {
        listOf("设置温度2147483648", "风量2147483648").forEach {
            assertEquals(it, ImportProblem.Action(1), importProblem(withAction(ActionDef(it, "x"))))
        }
    }

    @Test fun `unknown action command is a bad action`() {
        assertEquals(ImportProblem.Action(1), importProblem(withAction(ActionDef("不存在的命令", "x"))))
    }

    @Test fun `the index of the bad action is 1-based`() {
        assertEquals(
            ImportProblem.Action(2),
            importProblem(rule(actions = listOf(window, ActionDef("不存在的命令", "x")))),
        )
    }

    @Test fun `delay payload must be a number from 0 to 60000`() {
        listOf("abc", "-1", "60001", null).forEach {
            assertEquals(it, ImportProblem.Action(1), importProblem(withAction(delay(it))))
        }
        listOf("60000", "0", "500").forEach {
            assertNull(it, importProblem(withAction(delay(it))))
        }
    }

    @Test fun `more than 20 actions is too many`() {
        val twenty = RuleActionLimitTest.twentyActions()
        assertEquals(ImportProblem.TooManyActions, importProblem(rule(actions = twenty + window)))
        assertNull(importProblem(rule(actions = twenty)))
    }

    @Test fun `too many actions is reported before a bad condition`() {
        val twenty = RuleActionLimitTest.twentyActions()
        assertEquals(
            ImportProblem.TooManyActions,
            importProblem(rule(triggers = listOf(speed.copy(param = "Foo")), actions = twenty + window)),
        )
    }

    // --- no false positives ---

    @Test fun `every catalog action passes`() {
        ACTION_COMMANDS.forEach { opt ->
            val action = if (opt.toggleTarget == null) ActionDef(opt.command, "x")
            else ActionDef("", "x", kind = "toggle", payload = opt.toggleTarget)
            assertNull(opt.command + opt.toggleTarget, importProblem(withAction(action)))
        }
    }

    @Test fun `every level family value passes`() {
        LevelFamily.entries.forEach { family ->
            val values = family.range.step(family.step).toList() + if (family.offAtZero) listOf(0) else emptyList()
            values.forEach { v ->
                val command = family.command(v)
                assertNull("$family $command", importProblem(withAction(ActionDef(command, "x"))))
            }
        }
    }

    @Test fun `every catalog condition with every operator passes`() {
        TRIGGER_PARAMS.forEach { option ->
            val value = option.enumValues?.first()?.first ?: "1"
            operatorsFor(option).forEach { op ->
                val t = TriggerDef(option.param, option.chineseName, op, value, "x")
                assertNull("${option.param} $op", importProblem(withTrigger(t)))
            }
        }
    }

    @Test fun `every starter and report template passes`() {
        listOf("ru", "en").forEach { lang ->
            (starterTemplates(lang) + telegramReportTemplates(lang)).forEach { e: RuleEntity ->
                val r = rule(TriggerDef.listFromJson(e.triggers), ActionDef.listFromJson(e.actions))
                assertNull("$lang ${e.name}", importProblem(r))
            }
        }
    }

    @Test fun `valid schedules and eventless kinds pass`() {
        assertNull(importProblem(withTrigger(schedule("""{"from":"07:30","to":"09:20","days":[1,2,3,4,5]}"""))))
        assertNull(importProblem(withTrigger(schedule("""{"from":"08:00","to":"10:00","days":[]}"""))))
        listOf("service_start", "network_available").forEach {
            assertNull(it, importProblem(withTrigger(TriggerDef(it, "", "==", "1", "x", kind = it))))
        }
        assertNull(importProblem(withTrigger(TriggerDef("voice", "", "==", "привет", "x", kind = "voice"))))
        assertNull(importProblem(withTrigger(TriggerDef("Place", "", "==", "enter", "x", kind = "place_enter"))))
    }
}
