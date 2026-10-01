package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.OneShotTrigger
import com.bydmate.app.data.automation.RuleJournal
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.util.appLocalizedContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The Automation tab's words: card phrase, last result, journal lines, what «Сохранить» misses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuleTextTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 27, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Before fun setUp() { LocalePreferences(ctx).setLanguage("ru") }

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun param(p: String, op: String, v: String) = TriggerDef(p, "", op, v, p)
    private fun cmd(command: String) = ActionDef(command, "stale")

    private fun phrase(triggers: List<TriggerDef>, actions: List<ActionDef> = emptyList(), logic: String = "AND") =
        rulePhrase(triggers, logic, actions, ctx, now, zone).text(ctx)

    // --- Conditions, every kind ---

    @Test fun `numeric param with unit and word operator`() =
        assertEquals("Скорость меньше 5 км/ч", phrase(listOf(param("Speed", "<", "5"))))

    @Test fun `every operator reads as a word`() {
        val words = listOf(">" to "больше", "<" to "меньше", ">=" to "не меньше", "<=" to "не больше", "==" to "равно", "!=" to "не равно")
        for ((op, word) in words) assertEquals("SOC $word 20%", phrase(listOf(param("SOC", op, "20"))))
    }

    @Test fun `hint units in brackets stay out`() =
        assertEquals("Датчик дождя больше 0", phrase(listOf(param("Rain", ">", "0"))))

    @Test fun `enum equals and not equals`() {
        assertEquals("Передача P", phrase(listOf(param("Gear", "==", "1"))))
        assertEquals("Передача не D", phrase(listOf(param("Gear", "!=", "4"))))
        assertEquals("Режим вождения снег", phrase(listOf(param("DriveMode", "==", "4"))))
    }

    @Test fun `doors are named in full`() {
        assertEquals("Дверь водителя открыта", phrase(listOf(param("DoorFL", "==", "1"))))
        assertEquals("Дверь сзади справа закрыта", phrase(listOf(param("DoorRR", "==", "0"))))
        assertEquals("Окно сзади слева больше 50% откр.", phrase(listOf(param("WindowRL", ">", "50"))))
    }

    @Test fun `wheels are named in full`() =
        assertEquals("Давление левой передней шины меньше 230 кПа", phrase(listOf(param("TirePressFL", "<", "230"))))

    @Test fun `cell voltages differ in the first word`() {
        assertEquals("Мин. напряжение ячейки меньше 3,2 В", phrase(listOf(param("MinCellVoltage", "<", "3,2"))))
        assertEquals("Макс. напряжение ячейки больше 4 В", phrase(listOf(param("MaxCellVoltage", ">", "4"))))
    }

    @Test fun `place enter and exit`() {
        val enter = TriggerDef("Place", "", "==", "enter", "", kind = "place_enter", placeId = 1, placeName = "Дом")
        assertEquals("Въезд в «Дом»", phrase(listOf(enter)))
        assertEquals("Выезд из «Дом»", phrase(listOf(enter.copy(kind = "place_exit"))))
    }

    @Test fun `time of day`() =
        assertEquals("Рассвет", phrase(listOf(TriggerDef("TimeOfDay", "", "==", "DAWN", "", kind = "time_of_day"))))

    @Test fun `schedule exact, window and days`() {
        fun sched(spec: ScheduleSpec) = TriggerDef("Schedule", "", "==", spec.toJson(), "", kind = "time_range")
        assertEquals("В 08:00", phrase(listOf(sched(ScheduleSpec(480, 480, emptySet())))))
        assertEquals("По будням с 08:00 до 10:00", phrase(listOf(sched(ScheduleSpec(480, 600, setOf(1, 2, 3, 4, 5))))))
        assertEquals("По выходным в 09:30", phrase(listOf(sched(ScheduleSpec(570, 570, setOf(6, 7))))))
        assertEquals("Пн, Ср в 07:00", phrase(listOf(sched(ScheduleSpec(420, 420, setOf(3, 1))))))
    }

    @Test fun `service start and internet`() {
        assertEquals("Запуск BYDMate", phrase(listOf(TriggerDef("ServiceStart", "", "==", "true", "", kind = "service_start"))))
        assertEquals("Доступен интернет", phrase(listOf(TriggerDef("NetworkAvailable", "", "==", "true", "", kind = "network_available"))))
    }

    @Test fun `button, steering key and voice`() {
        assertEquals("Кнопка 1 на виджете", phrase(listOf(newButtonPressTrigger(1))))
        assertEquals("Клавиша руля не назначена", phrase(listOf(newSteeringKeyTrigger(0))))
        assertTrue(phrase(listOf(newSteeringKeyTrigger(383))).startsWith("Клавиша руля «"))
        assertEquals("Голосом «открой багажник»", phrase(listOf(TriggerDef("Voice", "", "==", "открой багажник", "", kind = "voice"))))
    }

    @Test fun `one-shot with a param closes with a comma`() {
        val once = TriggerDef(OneShotTrigger.PARAM, "", "==", "2026-09-30T07:30", "", kind = OneShotTrigger.KIND)
        assertEquals(
            "30 сентября в 07:30, один раз, и температура снаружи меньше 5 °C: авто AC, темп. 22°C",
            phrase(listOf(once, param("ExtTemp", "<", "5")), listOf(cmd("自动空调"), cmd("设置温度22"))),
        )
    }

    @Test fun `one-shot tomorrow`() {
        val once = TriggerDef(OneShotTrigger.PARAM, "", "==", "2026-09-28T07:30", "", kind = OneShotTrigger.KIND)
        assertEquals("Завтра в 07:30, один раз", phrase(listOf(once)))
    }

    @Test fun `and, or and several actions`() {
        val t = listOf(param("Gear", "==", "1"), param("Speed", "<", "5"))
        assertEquals("Передача P и скорость меньше 5 км/ч: закрыть все окна", phrase(t, listOf(cmd("车窗关闭"))))
        assertEquals("Передача P или скорость меньше 5 км/ч", phrase(t, logic = "OR"))
    }

    // --- Actions ---

    @Test fun `actions by kind`() {
        val lc = ctx
        fun act(a: ActionDef) = phrase(listOf(param("Speed", ">", "0")), listOf(a)).substringAfter(": ")
        assertEquals("передний багажник, переключить",
            act(ActionDef("", "x", "toggle", ActionDispatcher.TOGGLE_FRONT_TRUNK)))
        assertEquals("подождать 30 с", act(ActionDef("delay_30000", "x", "delay", "30000")))
        assertEquals("подождать 0,5 с", act(ActionDef("delay_500", "x", "delay", "500")))
        assertEquals("подождать 1 мин", act(ActionDef("delay_60000", "x", "delay", "60000")))
        assertEquals("уведомление «Закройте дверь»",
            act(newNotificationAction(lc).withNotification("Закройте дверь", "")))
        assertEquals("отчёт в Telegram", act(newTelegramReportAction(lc)))
        assertEquals("режим охраны, включить", act(newSentryAction(lc)))
        assertEquals("режим охраны, переключить", act(ActionDef("", "x", "toggle", ActionDispatcher.TOGGLE_SENTRY)))
        assertEquals("громкость медиа 2", act(ActionDef("media_volume", "x", "media_volume", "2")))
    }

    @Test fun `a long quoted text is cut`() {
        val a = newSpeakAction(ctx).withSpeakText("а".repeat(60))
        assertTrue(phrase(listOf(param("Speed", ">", "0")), listOf(a)).endsWith("…»"))
    }

    // --- Every language ---

    @Test fun `phrase and status in all six languages`() {
        val rule = RuleEntity(
            name = "R",
            triggers = TriggerDef.listToJson(listOf(param("Gear", "==", "1"), param("Speed", "<", "5"))),
            actions = ActionDef.listToJson(listOf(cmd("车窗关闭"))),
        )
        val log = RuleLogEntity(ruleId = 1, ruleName = "R", triggeredAt = ms(2026, 9, 27, 8, 12), triggersSnapshot = "{}", actionsResult = "[]", success = true)
        val expected = mapOf(
            "ru" to "Передача P и скорость меньше 5 км/ч: закрыть все окна",
            "en" to "Gear is P and speed below 5 km/h",
            "zh" to "档位为P且车速小于5 km/h",
        )
        for (lang in listOf("ru", "en", "be", "pl", "pt", "zh")) {
            LocalePreferences(ctx).setLanguage(lang)
            val text = rulePhrase(rule, ctx, now).text(ctx)
            assertFalse("$lang: $text", text.contains("==") || text.contains("<"))
            expected[lang]?.let { assertTrue("$lang: $text", text.startsWith(it)) }
            val status = ruleStatus(rule, log, ctx, now, zone)
            assertEquals(RuleStatusKind.OK, status.kind)
            assertTrue("$lang: ${status.text}", status.text.contains("08:12"))
        }
    }

    // --- Status on the card ---

    private fun rule(triggers: List<TriggerDef> = listOf(param("Speed", ">", "0")), enabled: Boolean = true) = RuleEntity(
        id = 1, name = "R", enabled = enabled, triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(cmd("车窗关闭"), ActionDef("设置温度22", "x"))),
    )

    private fun entry(at: Long, success: Boolean, result: String? = null, reason: String? = null, snapshot: String = "{}"): RuleLogEntity {
        val arr = JSONArray()
        if (result != null) arr.put(JSONObject().put("result", result).put("reason", reason))
        else arr.put(JSONObject().put("command", "设置温度22").put("displayName", "x").put("kind", "param").put("success", success)
            .apply { if (reason != null) put("reason", reason) })
        return RuleLogEntity(ruleId = 1, ruleName = "R", triggeredAt = at, triggersSnapshot = snapshot, actionsResult = arr.toString(), success = success && result == null)
    }

    @Test fun `status by the last entry`() {
        assertEquals("Ещё не срабатывало", ruleStatus(rule(), null, ctx, now, zone).text)
        assertEquals("Сработало сегодня в 08:12", ruleStatus(rule(), entry(ms(2026, 9, 27, 8, 12), true), ctx, now, zone).text)
        assertEquals("Отменено вчера в 08:05", ruleStatus(rule(), entry(ms(2026, 9, 26, 8, 5), false, "timeout", "x"), ctx, now, zone).text)
        assertEquals("Ошибка 25 сентября в 12:31", ruleStatus(rule(), entry(ms(2026, 9, 25, 12, 31), false), ctx, now, zone).text)
        assertEquals(RuleStatusKind.SKIPPED, ruleStatus(rule(), entry(now, false, "skipped", "x"), ctx, now, zone).kind)
    }

    @Test fun `a waiting one-shot says when it runs`() {
        val once = TriggerDef(OneShotTrigger.PARAM, "", "==", "2026-09-30T07:30", "", kind = OneShotTrigger.KIND)
        val status = ruleStatus(rule(listOf(once)), null, ctx, now, zone)
        assertEquals(RuleStatusKind.PLANNED, status.kind)
        assertEquals("Сработает 30 сентября в 07:30", status.text)
        assertEquals(RuleStatusKind.NEVER, ruleStatus(rule(listOf(once), enabled = false), null, ctx, now, zone).kind)
    }

    // --- Journal ---

    @Test fun `journal lines`() {
        val ok = journalLine(entry(ms(2026, 9, 27, 8, 12), true, snapshot = """{"Gear":1,"Speed":0.0}"""), ctx, now, zone)
        assertEquals("Выполнено", ok.status)
        assertEquals("Сегодня в 08:12", ok.time)
        assertEquals("Темп. 22°C", ok.what)
        assertEquals("Передача P, скорость 0 км/ч", ok.why)

        val err = journalLine(entry(ms(2026, 9, 24, 8, 10), false, reason = "Машина не приняла команду"), ctx, now, zone)
        assertEquals(RuleStatusKind.ERROR, err.kind)
        assertEquals("Темп. 22°C не выполнено", err.what)
        assertEquals("Машина не приняла команду", err.why)

        val cancelled = journalLine(entry(now, false, "cancelled", "Отменено в окне подтверждения"), ctx, now, zone)
        assertEquals("Отменено", cancelled.status)
        // Written before the journal kept the actions: none, rather than the rule's current ones.
        assertEquals("", cancelled.what)

        val test = journalLine(entry(now, false, "skipped", "x", snapshot = """{"$TEST_RUN_KEY":true}"""), ctx, now, zone)
        assertEquals("Не выполнено", test.status)
        assertEquals("Тестовый запуск", test.what)

        val once = journalLine(entry(now, true, snapshot = """{"once_at":"2026-09-27T07:30"}"""), ctx, now, zone)
        assertEquals("Выполнено, правило выключено", once.status)
        assertEquals("Разовое правило", once.why)

        val expired = journalLine(entry(now, false, "expired", "x"), ctx, now, zone)
        assertEquals("Не выполнено, правило выключено", expired.status)
    }

    private fun run(vararg steps: JSONObject, success: Boolean = true) = RuleLogEntity(
        ruleId = 1, ruleName = "R", triggeredAt = now, triggersSnapshot = "{}", success = success,
        actionsResult = JSONArray().apply { steps.forEach { put(it) } }.toString(),
    )

    private fun recorded(a: ActionDef, success: Boolean = true) = RuleJournal.recordedAction(a).put("success", success)

    /** A step as builds before 3.20 wrote it: no payload key. */
    private fun legacy(a: ActionDef, success: Boolean = true) =
        JSONObject().put("command", a.command).put("displayName", a.displayName).put("kind", a.kind).put("success", success)

    @Test fun `a projection step with its own app names it, without one reads as before`() {
        val lc = ctx.appLocalizedContext()
        fun cluster(payload: String) = ActionDef("cluster_projection", "x", "cluster_projection", payload)
        assertEquals(
            "Вывод на приборку «Радио»",
            actionText(cluster("""{"state":"1","packageName":"com.example.radio","appLabel":"Радио"}"""), lc),
        )
        assertEquals("Вывод на приборку, включить", actionText(cluster("1"), lc))
        assertEquals("Вывод на приборку, отключить", actionText(cluster("0"), lc))
    }

    @Test fun `a run shows the parameters it recorded`() {
        val pause = ActionDef("", "Пауза", "delay", "30000")
        val note = ActionDef("", "Уведомление", "notification", """{"title":"Заряд","text":"t"}""")
        val sentry = ActionDef("", "Охрана", "sentry", "1")
        assertEquals(
            "Подождать 30 с, уведомление «Заряд», режим охраны, включить",
            journalLine(run(recorded(pause), recorded(note), recorded(sentry)), ctx, now, zone).what,
        )
        assertEquals(
            "Режим охраны, включить не выполнено",
            journalLine(run(recorded(pause), recorded(sentry, success = false), success = false), ctx, now, zone).what,
        )
    }

    @Test fun `an old run never shows a parameter it did not record`() {
        val line = journalLine(run(legacy(ActionDef("", "Пауза", "delay")), legacy(ActionDef("", "", "sentry"))), ctx, now, zone)
        assertEquals("Пауза, режим охраны", line.what)
        // A param step reads its command, as before.
        assertEquals("Закрыть все окна", journalLine(run(legacy(cmd("车窗关闭"))), ctx, now, zone).what)
    }

    @Test fun `a cancelled entry shows the actions it recorded`() {
        val entry = JSONObject().put("result", "cancelled").put("reason", "x")
            .put("actions", JSONArray().put(RuleJournal.recordedAction(ActionDef("", "Багажник", "toggle", ActionDispatcher.TOGGLE_TRUNK))))
        val line = journalLine(
            RuleLogEntity(ruleId = 1, ruleName = "R", triggeredAt = now, triggersSnapshot = "{}", success = false,
                actionsResult = JSONArray().put(entry).toString()),
            ctx, now, zone,
        )
        assertEquals(actionText(ActionDef("", "", "toggle", ActionDispatcher.TOGGLE_TRUNK), ctx.appLocalizedContext()), line.what)
    }

    // --- The day the relative dates hang on ---

    @Test fun `the day turns at midnight`() {
        val beforeMidnight = ms(2026, 9, 27, 23, 59)
        assertEquals(60_000L, msUntilNextDay(beforeMidnight, zone))
        assertEquals(epochDay(beforeMidnight, zone) + 1, epochDay(beforeMidnight + msUntilNextDay(beforeMidnight, zone), zone))
        assertEquals(24L * 60 * 60 * 1000, msUntilNextDay(ms(2026, 9, 28, 0, 0), zone))
    }

    // --- Editor ---

    private val complete = EditingRule(
        name = "R", triggers = listOf(param("Speed", "<", "5")), actions = listOf(cmd("车窗关闭")),
    )

    @Test fun `nothing missing on a complete draft`() {
        assertTrue(missingParts(complete, ctx).isEmpty())
        assertNull(saveReason(emptyList(), ctx))
    }

    @Test fun `each missing part and its line`() {
        val draft = EditingRule(
            name = "",
            triggers = listOf(newParamTrigger(), param("DoorFL", "==", ""), param("SOC", "<", "abc"), newSteeringKeyTrigger(0)),
            actions = emptyList(),
        )
        val missing = missingParts(draft, ctx)
        assertEquals(
            listOf(Missing.Name, Missing.Param(1), Missing.Value(2), Missing.Number(3), Missing.Key(4), Missing.NoActions),
            missing,
        )
        assertEquals("Выберите, что проверять в условии 1", saveReason(listOf(Missing.Param(1)), ctx))
        assertEquals("Введите число в условии 1 и добавьте действие", saveReason(listOf(Missing.Number(1), Missing.NoActions), ctx))
        assertEquals("Введите название и ещё 5", saveReason(missing, ctx))
        assertEquals(listOf(Missing.NoConditions), missingParts(complete.copy(triggers = emptyList()), ctx))
    }

    @Test fun `a list condition saved with a number operator asks for one`() {
        val gearAbove = complete.copy(triggers = listOf(param("Gear", ">", "1")))
        assertEquals(listOf(Missing.Operator(1)), missingParts(gearAbove, ctx))
        assertEquals("Выберите «равно» или «не равно» в условии 1", saveReason(missingParts(gearAbove, ctx), ctx))
        assertTrue(missingParts(complete.copy(triggers = listOf(param("Gear", "!=", "1"))), ctx).isEmpty())
        // A number keeps all six operators.
        assertTrue(missingParts(complete.copy(triggers = listOf(param("Speed", ">=", "5"))), ctx).isEmpty())
    }

    @Test fun `a decimal comma is a number`() =
        assertTrue(missingParts(complete.copy(triggers = listOf(param("SOC", "<", "12,5"))), ctx).isEmpty())

    @Test fun `an action that is not set up is named`() {
        val missing = missingParts(complete.copy(actions = listOf(newNotificationAction(ctx))), ctx)
        assertEquals(1, missing.size)
        assertTrue(missing[0] is Missing.Action)
    }

    @Test fun `unsaved changes`() {
        assertFalse(hasUnsavedChanges(complete, complete.copy(saving = true)))
        assertTrue(hasUnsavedChanges(complete, complete.copy(name = "R2")))
        assertTrue(hasUnsavedChanges(complete, complete.copy(cooldownSeconds = 30)))
    }

    @Test fun `event trigger with other conditions`() {
        assertNull(eventTriggerWithOthers(listOf(newButtonPressTrigger(1))))
        assertEquals("button_press", eventTriggerWithOthers(listOf(newButtonPressTrigger(1), param("Speed", "<", "5"))))
        assertNull(eventTriggerWithOthers(listOf(param("Gear", "==", "1"), param("Speed", "<", "5"))))
    }

    @Test fun `a new parameter resets operator and value`() {
        val door = TRIGGER_PARAMS.first { it.param == "DoorFL" }
        val speed = TRIGGER_PARAMS.first { it.param == "Speed" }
        val t = withParam(param("Speed", "<", "5"), door, ctx)
        assertEquals("==" to "", t.operator to t.value)
        assertEquals(listOf("==", "!="), operatorsFor(door))
        assertEquals(">" to "", withParam(t, speed, ctx).let { it.operator to it.value })
        assertEquals(OPERATORS, operatorsFor(speed))
    }
}
