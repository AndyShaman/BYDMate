package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.vehicle.DriveMode
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime

/**
 * Automation audit 3.19, engine side: gates on fresh data per step, the new conditions, the
 * journal reasons for what did not run, the skip lines and the one-shot rule.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver (engine init).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineAuditTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private var rules: List<RuleEntity> = emptyList()
    private val ruleDao = mockk<RuleDao>(relaxed = true) {
        coEvery { getEnabled() } answers { rules }
        coEvery { getById(any()) } answers { rules.firstOrNull { it.id == firstArg<Long>() } }
    }
    private lateinit var engine: AutomationEngine

    @Before
    fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        engine = engineWith(dispatcher)
        ShadowLog.clear()
    }

    private fun engineWith(actionDispatcher: ActionDispatcher) = AutomationEngine(
        ruleDao = ruleDao,
        ruleLogDao = ruleLogDao,
        actionDispatcher = actionDispatcher,
        placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
        networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
            every { lastAvailableAt } returns 0L
            every { probePending } returns false
        },
        context = ctx,
        appStrings = com.bydmate.app.util.AppStrings(ctx),
    ).apply {
        interactiveProvider = { true }
        liveData = { null }
    }

    @After
    fun tearDown() {
        unmockkObject(ConfirmOverlayManager)
    }

    private fun param(param: String, op: String, value: String) =
        TriggerDef(param = param, chineseName = "", operator = op, value = value, displayName = param)

    private fun button(n: Int) = TriggerDef(
        param = "button_press", chineseName = "", operator = "==", value = "$n", displayName = "b", kind = "button_press",
    )

    private fun oneShot(value: String) = TriggerDef(
        param = OneShotTrigger.PARAM, chineseName = "", operator = "==", value = value, displayName = "once",
        kind = OneShotTrigger.KIND,
    )

    private fun rule(
        id: Long,
        triggers: List<TriggerDef>,
        actions: List<ActionDef> = listOf(ActionDef("notify", "n", "notification")),
        requirePark: Boolean = false,
    ) = RuleEntity(
        id = id, name = "r$id",
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(actions),
        requirePark = requirePark,
        cooldownSeconds = 0,
    )

    private fun reasonOf(entry: RuleLogEntity, step: Int = 0): String =
        JSONArray(entry.actionsResult).getJSONObject(step).getString("reason")

    private fun engineLines(): List<String> = ShadowLog.getLogsForTag("AutomationEngine").map { it.msg }

    // --- Item 1: speed gates on data at each step ---

    @Test fun `each step is dispatched with the data of its own moment`() = runBlocking {
        rules = listOf(rule(1, listOf(button(1)), actions = listOf(
            ActionDef("主驾打开0", "close"), ActionDef("主驾打开100", "open"),
        )))
        val snapshots = ArrayDeque(listOf(diParsData(speed = 0), diParsData(speed = 130)))
        engine.liveData = { snapshots.removeFirst() }

        engine.onButtonPress(1)

        coVerify { dispatcher.dispatch(match { it.command == "主驾打开0" }, match<DiParsData> { it.speed == 0 }) }
        coVerify { dispatcher.dispatch(match { it.command == "主驾打开100" }, match<DiParsData> { it.speed == 130 }) }
    }

    // The real gate behind the engine: the rule's own zero is never trusted for an opening step.
    @Test fun `an opening step reads the speed itself, a closing one does not`() = runBlocking {
        val vehicleApi = mockk<VehicleApi>(relaxed = true) { coEvery { dispatch(any()) } returns Result.success(Unit) }
        val realDispatcher = ActionDispatcher(vehicleApi, mockk(relaxed = true), ctx,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            com.bydmate.app.util.AppStrings(ctx), dagger.Lazy { mockk(relaxed = true) },
        )
        realDispatcher.readSpeedNow = { 130 }
        engine = engineWith(realDispatcher)
        engine.liveData = { diParsData(speed = 0) }
        rules = listOf(rule(1, listOf(button(1)), actions = listOf(
            ActionDef("主驾打开100", "open"), ActionDef("主驾打开0", "close"),
        )))
        val entry = slot<RuleLogEntity>()
        coEvery { ruleLogDao.insert(capture(entry)) } returns 1L

        engine.onButtonPress(1)

        coVerify(exactly = 0) { vehicleApi.dispatch("主驾打开100") }
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾打开0") }
        assertTrue(reasonOf(entry.captured, 0), reasonOf(entry.captured, 0).contains("130"))
    }

    // --- Items 3, 4, 11, 13: condition values ---

    @Test fun `a comma decimal compares as a number`() = runBlocking {
        rules = listOf(rule(1, listOf(param("Voltage12V", "<", "12,5"))))

        engine.evaluate(diParsData(voltage12v = 13.0), null)
        engine.evaluate(diParsData(voltage12v = 12.2), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `a terrain drive mode condition reads the target mode`() = runBlocking {
        rules = listOf(rule(1, listOf(param("DriveMode", "==", "5"))))

        engine.evaluate(diParsData(driveMode = 3).copy(driveModeTarget = DriveMode.NORMAL.value), null)
        engine.evaluate(diParsData(driveMode = 3).copy(driveModeTarget = DriveMode.SAND.value), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `rear belt and range conditions read their values`() = runBlocking {
        var range = 80.0
        engine.rangeKm = { range }
        rules = listOf(
            rule(1, listOf(param("SeatbeltRM", "==", "0"))),
            rule(2, listOf(param("RangeKm", "<", "50"))),
        )

        engine.evaluate(diParsData().copy(seatbeltRM = 1), null)
        range = 40.0
        engine.evaluate(diParsData().copy(seatbeltRM = 0), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(2, any()) }
    }

    // --- Item 6: park-only on a manual run ---

    @Test fun `a park-only rule refused on a button press goes to the journal`() = runBlocking {
        rules = listOf(rule(1, listOf(button(1)), requirePark = true))
        val entry = slot<RuleLogEntity>()
        coEvery { ruleLogDao.insert(capture(entry)) } returns 1L

        assertEquals(1, engine.onButtonPress(1))

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        assertEquals("Не выполнено: правило только на парковке, а передача не P", reasonOf(entry.captured))
        assertTrue(engineLines().any { it.contains("rule 1 'r1' skipped: park only, gear=null") })
    }

    @Test fun `a park-only rule refused by voice goes to the journal`() = runBlocking {
        rules = listOf(rule(1, listOf(param("Speed", ">", "0")), requirePark = true))

        assertEquals(VoiceFireResult.ParkRequired, engine.fireVoiceRule(1, diParsData(gear = 4)))

        coVerify(exactly = 1) { ruleLogDao.insert(match { !it.success && it.actionsResult.contains("skipped") }) }
    }

    // --- Item 7: confirmation cancelled or unanswered ---

    @Test fun `a cancelled and an unanswered confirmation are journaled with reasons`() = runBlocking {
        mockkObject(ConfirmOverlayManager)
        val onCancel = slot<() -> Unit>()
        every { ConfirmOverlayManager.show(any(), any(), any(), any(), capture(onCancel), any()) } returns true
        rules = listOf(rule(1, listOf(button(1))).copy(confirmBeforeExecute = true))
        val entries = mutableListOf<RuleLogEntity>()
        coEvery { ruleLogDao.insert(capture(entries)) } returns 1L

        engine.onButtonPress(1)
        onCancel.captured.invoke()
        coVerify(timeout = 2_000) { ruleLogDao.insert(any()) }
        (onCancel.captured as CancelOrTimeout).onTimeout()
        coVerify(timeout = 2_000, exactly = 2) { ruleLogDao.insert(any()) }

        assertEquals(
            setOf("Отменено в окне подтверждения", "Нет ответа в окне подтверждения, не выполнено"),
            entries.map { reasonOf(it) }.toSet(),
        )
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // --- Items 8, 15: why a rule did not fire ---

    @Test fun `a cooldown skip is logged once a minute per rule`() = runBlocking {
        var now = 1_000_000L
        engine.nowMs = { now }
        rules = listOf(rule(1, listOf(param("Speed", ">", "10"))).copy(cooldownSeconds = 600, lastTriggeredAt = now))

        repeat(2) {
            engine.evaluate(diParsData(speed = 0), null)
            engine.evaluate(diParsData(speed = 20), null)
            now += 1_000
        }

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        assertEquals(1, engineLines().count { it.startsWith("rule 1 'r1' skipped: cooldown") })
    }

    @Test fun `a condition with no data is named in the log`() = runBlocking {
        rules = listOf(rule(1, listOf(param("ExtTemp", ">", "20"))))

        engine.evaluate(diParsData(exteriorTemp = null), null)

        assertTrue(engineLines().any { it == "rule 1 'r1' skipped: no data (ExtTemp)" })
    }

    @Test fun `a condition already true when first seen is remembered and said once`() = runBlocking {
        rules = listOf(rule(1, listOf(param("Speed", ">", "10"))))

        engine.evaluate(diParsData(speed = 20), null)
        engine.evaluate(diParsData(speed = 20), null)

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        assertEquals(1, engineLines().count { it.contains("already true at first check, remembered without firing") })
    }

    // --- Item 14: one-shot date and time ---

    private val moment = LocalDateTime.of(2026, 10, 1, 8, 30)
    private val momentValue = OneShotTrigger.format(moment)
    private val momentMs = OneShotTrigger.momentMs(momentValue)!!

    @Test fun `a one-shot rule fires at the first check after its moment and switches itself off`() = runBlocking {
        rules = listOf(rule(1, listOf(oneShot(momentValue))))
        var now = momentMs - 60_000
        engine.nowMs = { now }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        now = momentMs + 5 * 60_000
        engine.evaluate(diParsData(), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { ruleDao.setEnabled(1, false) }
    }

    @Test fun `a one-shot rule seen first after its moment still fires`() = runBlocking {
        rules = listOf(rule(1, listOf(oneShot(momentValue))))
        engine.nowMs = { momentMs + 60 * 60_000 }

        engine.evaluate(diParsData(), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `a one-shot rule waits for the screen`() = runBlocking {
        rules = listOf(rule(1, listOf(oneShot(momentValue))))
        engine.nowMs = { momentMs + 60_000 }
        var screenOn = false
        engine.interactiveProvider = { screenOn }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        screenOn = true
        engine.evaluate(diParsData(), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `a one-shot rule a day past its moment expires into the journal`() = runBlocking {
        rules = listOf(rule(1, listOf(oneShot(momentValue))))
        engine.nowMs = { momentMs + OneShotTrigger.WINDOW_MS + 1 }
        val entry = slot<RuleLogEntity>()
        coEvery { ruleLogDao.insert(capture(entry)) } returns 1L

        engine.evaluate(diParsData(), null)

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 1) { ruleDao.setEnabled(1, false) }
        assertEquals("Разовое правило не сработало за сутки и выключено", reasonOf(entry.captured))
    }

    // --- Item 8: dump ---

    @Test fun `the dump reads every param as a condition does`() {
        engine.liveData = { diParsData(speed = 12).copy(seatbeltRL = 1) }
        engine.rangeKm = { 210.0 }

        assertEquals("Speed=12.0 SeatbeltRL=1.0 RangeKm=210.0 Hood=-",
            engine.paramSnapshotLine(listOf("Speed", "SeatbeltRL", "RangeKm", "Hood")))
    }
}
