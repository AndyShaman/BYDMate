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

    private class PhraseCase(
        val label: String,
        val triggers: List<TriggerDef>,
        val expected: String,
        val actions: List<ActionDef> = emptyList(),
        val logic: String = "AND",
    )

    private fun sched(spec: ScheduleSpec) = TriggerDef("Schedule", "", "==", spec.toJson(), "", kind = "time_range")

    private val placeEnter = TriggerDef("Place", "", "==", "enter", "", kind = "place_enter", placeId = 1, placeName = "Дом")
    private val oneShot = { at: String -> TriggerDef(OneShotTrigger.PARAM, "", "==", at, "", kind = OneShotTrigger.KIND) }
    private val gearAndSpeed = listOf(param("Gear", "==", "1"), param("Speed", "<", "5"))

    private val phraseCases by lazy {
        listOf(
            PhraseCase("numeric param with unit and word operator", listOf(param("Speed", "<", "5")), "Скорость меньше 5 км/ч"),
            PhraseCase("operator >", listOf(param("SOC", ">", "20")), "SOC больше 20%"),
            PhraseCase("operator <", listOf(param("SOC", "<", "20")), "SOC меньше 20%"),
            PhraseCase("operator >=", listOf(param("SOC", ">=", "20")), "SOC не меньше 20%"),
            PhraseCase("operator <=", listOf(param("SOC", "<=", "20")), "SOC не больше 20%"),
            PhraseCase("operator ==", listOf(param("SOC", "==", "20")), "SOC равно 20%"),
            PhraseCase("operator !=", listOf(param("SOC", "!=", "20")), "SOC не равно 20%"),
            PhraseCase("hint units in brackets stay out", listOf(param("Rain", ">", "0")), "Датчик дождя больше 0"),
            PhraseCase("enum equals", listOf(param("Gear", "==", "1")), "Передача P"),
            PhraseCase("enum not equals", listOf(param("Gear", "!=", "4")), "Передача не D"),
            PhraseCase("drive mode enum", listOf(param("DriveMode", "==", "4")), "Режим вождения снег"),
            PhraseCase("door driver open", listOf(param("DoorFL", "==", "1")), "Дверь водителя открыта"),
            PhraseCase("door rear right closed", listOf(param("DoorRR", "==", "0")), "Дверь сзади справа закрыта"),
            PhraseCase("window rear left percent", listOf(param("WindowRL", ">", "50")), "Окно сзади слева больше 50% откр."),
            PhraseCase("wheel named in full", listOf(param("TirePressFL", "<", "230")), "Давление левой передней шины меньше 230 кПа"),
            PhraseCase("min cell voltage", listOf(param("MinCellVoltage", "<", "3,2")), "Мин. напряжение ячейки меньше 3,2 В"),
            PhraseCase("max cell voltage", listOf(param("MaxCellVoltage", ">", "4")), "Макс. напряжение ячейки больше 4 В"),
            PhraseCase("place enter", listOf(placeEnter), "Въезд в «Дом»"),
            PhraseCase("place exit", listOf(placeEnter.copy(kind = "place_exit")), "Выезд из «Дом»"),
            PhraseCase("time of day", listOf(TriggerDef("TimeOfDay", "", "==", "DAWN", "", kind = "time_of_day")), "Рассвет"),
            PhraseCase("schedule exact", listOf(sched(ScheduleSpec(480, 480, emptySet()))), "В 08:00"),
            PhraseCase("schedule window on weekdays", listOf(sched(ScheduleSpec(480, 600, setOf(1, 2, 3, 4, 5)))), "По будням с 08:00 до 10:00"),
            PhraseCase("schedule on weekends", listOf(sched(ScheduleSpec(570, 570, setOf(6, 7)))), "По выходным в 09:30"),
            PhraseCase("schedule on listed days", listOf(sched(ScheduleSpec(420, 420, setOf(3, 1)))), "Пн, Ср в 07:00"),
            PhraseCase("service start", listOf(TriggerDef("ServiceStart", "", "==", "true", "", kind = "service_start")), "Запуск BYDMate"),
            PhraseCase("internet", listOf(TriggerDef("NetworkAvailable", "", "==", "true", "", kind = "network_available")), "Доступен интернет"),
            PhraseCase("widget button", listOf(newButtonPressTrigger(1)), "Кнопка 1 на виджете"),
            PhraseCase("steering key not assigned", listOf(newSteeringKeyTrigger(0)), "Клавиша руля не назначена"),
            PhraseCase("voice", listOf(TriggerDef("Voice", "", "==", "открой багажник", "", kind = "voice")), "Голосом «открой багажник»"),
            PhraseCase(
                "one-shot with a param closes with a comma", listOf(oneShot("2026-09-30T07:30"), param("ExtTemp", "<", "5")),
                "30 сентября в 07:30, один раз, и температура снаружи меньше 5 °C: авто AC, температура: 22 °C",
                actions = listOf(cmd("自动空调"), cmd("设置温度22")),
            ),
            PhraseCase("one-shot tomorrow", listOf(oneShot("2026-09-28T07:30")), "Завтра в 07:30, один раз"),
            PhraseCase(
                "and with an action", gearAndSpeed, "Передача P и скорость меньше 5 км/ч: закрыть все окна",
                actions = listOf(cmd("车窗关闭")),
            ),
            PhraseCase("or", gearAndSpeed, "Передача P или скорость меньше 5 км/ч", logic = "OR"),
        )
    }

    @Test fun `condition phrases read as words`() {
        for (c in phraseCases) assertEquals("case: ${c.label}", c.expected, phrase(c.triggers, c.actions, c.logic))
    }

    @Test fun `a steering key shows its code in quotes`() {
        assertTrue(phrase(listOf(newSteeringKeyTrigger(383))).startsWith("Клавиша руля «"))
    }

    // --- Actions ---

    @Test fun `actions by kind`() {
        val lc = ctx
        fun act(a: ActionDef) = phrase(listOf(param("Speed", ">", "0")), listOf(a)).substringAfter(": ")
        assertEquals("передний багажник, переключить",
            act(ActionDef("", "x", "toggle", ActionDispatcher.TOGGLE_FRONT_TRUNK)))
        assertEquals("пауза 30 с", act(ActionDef("delay_30000", "x", "delay", "30000")))
        assertEquals("пауза 0,5 с", act(ActionDef("delay_500", "x", "delay", "500")))
        assertEquals("пауза 1 мин", act(ActionDef("delay_60000", "x", "delay", "60000")))
        assertEquals("пауза 5 мин", act(ActionDef("delay_300000", "x", "delay", "300000")))
        assertEquals("пауза 1 ч", act(ActionDef("delay_3600000", "x", "delay", "3600000")))
        assertEquals("пауза 1,5 ч", act(ActionDef("delay_5400000", "x", "delay", "5400000")))
        assertEquals("пауза 1,5 мин", act(ActionDef("delay_90000", "x", "delay", "90000")))
        assertEquals("пауза 24 ч", act(ActionDef("delay_86400000", "x", "delay", "86400000")))
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
        assertEquals("Температура: 22 °C", ok.what)
        assertEquals("Передача P, скорость 0 км/ч", ok.why)

        val err = journalLine(entry(ms(2026, 9, 24, 8, 10), false, reason = "Машина не приняла команду"), ctx, now, zone)
        assertEquals(RuleStatusKind.ERROR, err.kind)
        assertEquals("Температура: 22 °C не выполнено", err.what)
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

    @Test fun `a close step names the app it closes`() {
        val lc = ctx.appLocalizedContext()
        val close = ActionDef("", "x", "app_close", """{"packageName":"com.example.radio","appLabel":"Радио"}""")
        assertEquals("Закрыть приложение «Радио»", actionText(close, lc))
        assertEquals("Закрыть приложение «Радио»", journalLine(run(recorded(close)), ctx, now, zone).what)
    }

    @Test fun `a media key step reads as its picker entry, also in the journal`() {
        val lc = ctx.appLocalizedContext()
        val play = ActionDef("", "x", "media_key", "play")
        val pause = ActionDef("", "x", "media_key", "pause")
        assertEquals("Медиа: играть", actionText(play, lc))
        assertEquals("Медиа: пауза", actionText(pause, lc))
        assertEquals("Медиа: пауза не выполнено", journalLine(run(recorded(pause, success = false), success = false), ctx, now, zone).what)
    }

    @Test fun `a screen step reads as its picker entry in all six languages, also in the journal`() {
        val off = ActionDef("screen", "x", "screen", "0")
        val on = ActionDef("screen", "x", "screen", "1")
        val labels = mapOf(
            "en" to ("Center screen: off" to "Center screen: on"),
            "be" to ("Цэнтральны экран: выключыць" to "Цэнтральны экран: уключыць"),
            "pl" to ("Ekran centralny: wyłącz" to "Ekran centralny: włącz"),
            "pt" to ("Tela central: desligar" to "Tela central: ligar"),
            "zh" to ("关闭中控屏" to "打开中控屏"),
            "ru" to ("Центральный экран: выключить" to "Центральный экран: включить"),
        )
        for ((tag, expected) in labels) {
            LocalePreferences(ctx).setLanguage(tag)
            val lc = ctx.appLocalizedContext()
            assertEquals(tag, expected, actionText(off, lc) to actionText(on, lc))
        }
        assertEquals("Центральный экран: выключить не выполнено", journalLine(run(recorded(off, success = false), success = false), ctx, now, zone).what)
    }

    @Test fun `a long delay reads in hours in English`() {
        LocalePreferences(ctx).setLanguage("en")
        assertEquals("Wait 2 h", actionText(ActionDef("delay_7200000", "x", "delay", "7200000"), ctx.appLocalizedContext()))
    }

    @Test fun `a run shows the parameters it recorded`() {
        val pause = ActionDef("", "Пауза", "delay", "30000")
        val note = ActionDef("", "Уведомление", "notification", """{"title":"Заряд","text":"t"}""")
        val sentry = ActionDef("", "Охрана", "sentry", "1")
        assertEquals(
            "Пауза 30 с, уведомление «Заряд», режим охраны, включить",
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
