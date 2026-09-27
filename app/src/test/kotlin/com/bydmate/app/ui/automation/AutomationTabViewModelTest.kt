package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Automation tab 3.19: last result per rule, the rule's own journal, the notes at the bottom. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationTabViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private val placeRepository = mockk<PlaceRepository>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val stored = MutableStateFlow<List<RuleEntity>>(emptyList())

    private val limitText = "Можно создать не больше 50 правил. Удалите ненужное, чтобы добавить новое."

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("ru")
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit()
            .putBoolean("templates_inserted", true).putBoolean("templates_tg_report_inserted", true).commit()
        every { ruleDao.getAll() } returns stored
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { ruleLogDao.getLastPerRule() } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns MutableStateFlow<List<PlaceEntity>>(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = AutomationViewModel(
        ruleDao = ruleDao,
        ruleLogDao = ruleLogDao,
        placeRepository = placeRepository,
        vehicleApi = mockk(relaxed = true),
        actionDispatcher = dispatcher,
        settingsRepository = mockk(relaxed = true),
        context = ctx,
    ).also { testDispatcher.scheduler.advanceUntilIdle() }

    private fun log(id: Long, ruleId: Long) = RuleLogEntity(
        id = id, ruleId = ruleId, ruleName = "r$ruleId", triggeredAt = id, triggersSnapshot = "{}",
        actionsResult = "[]", success = true,
    )

    private fun rules(n: Int) = (1..n).map { RuleEntity(id = it.toLong(), name = "r$it", triggers = "[]", actions = "[]") }

    @Test fun `the last entry of each rule is kept by rule id`() {
        every { ruleLogDao.getLastPerRule() } returns flowOf(listOf(log(5, 1), log(9, 2)))
        val vm = vm()
        assertEquals(setOf(1L, 2L), vm.uiState.value.lastLogs.keys)
        assertEquals(9L, vm.uiState.value.lastLogs.getValue(2).id)
    }

    @Test fun `the status line opens the journal of that rule only`() {
        every { ruleLogDao.getByRule(2) } returns flowOf(listOf(log(9, 2)))
        val vm = vm()

        vm.showRuleJournal(2)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.showJournal)
        assertEquals(2L, vm.uiState.value.journalRuleId)
        assertEquals(listOf(9L), vm.uiState.value.ruleLogs.map { it.id })

        vm.showJournal()
        assertNull(vm.uiState.value.journalRuleId)
        vm.hideJournal()
        assertFalse(vm.uiState.value.showJournal)
    }

    @Test fun `create at the limit says so and opens nothing`() {
        stored.value = rules(MAX_RULES)
        val vm = vm()

        vm.openNewRule()
        assertFalse(vm.uiState.value.showEditor)
        assertEquals(limitText, vm.uiState.value.message)
        vm.dismissMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test fun `a copy over the limit says so`() {
        coEvery { ruleDao.getCount() } returns MAX_RULES
        val vm = vm()

        vm.duplicateRule(RuleEntity(id = 1, name = "r", triggers = "[]", actions = "[]"))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleDao.insert(any()) }
        assertEquals(limitText, vm.uiState.value.message)
    }

    @Test fun `a park-only test run off P is refused, noted and journaled`() {
        val rule = RuleEntity(id = 7, name = "Окна", triggers = "[]", actions = "[]", requirePark = true)
        coEvery { ruleDao.getById(7) } returns rule
        val inserted = slot<RuleLogEntity>()
        coEvery { ruleLogDao.insert(capture(inserted)) } returns 1L
        val vm = vm()
        vm.liveSnapshot = { diParsData(gear = 4) }
        vm.updateEditing { copy(id = 7, isNew = false, name = "Окна", requirePark = true, actions = listOf(ActionDef("车窗关闭", "x"))) }

        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        assertEquals("Не выполнено: правило работает только на парковке. Включите P.", vm.uiState.value.message)
        assertEquals(7L, inserted.captured.ruleId)
        assertEquals("skipped", JSONArray(inserted.captured.actionsResult).getJSONObject(0).getString("result"))
        assertTrue(inserted.captured.triggersSnapshot.contains(TEST_RUN_KEY))
    }

    @Test fun `a park-only test run of a new rule off P writes no journal`() {
        val vm = vm()
        vm.liveSnapshot = { diParsData(gear = 4) }
        vm.openNewRule()
        vm.updateEditing { copy(requirePark = true, actions = listOf(ActionDef("车窗关闭", "x"))) }

        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleLogDao.insert(any()) }
        assertEquals("Не выполнено: правило работает только на парковке. Включите P.", vm.uiState.value.message)
    }

    @Test fun `a park-only test run in P runs`() {
        val vm = vm()
        vm.liveSnapshot = { diParsData(gear = 1) }
        vm.liveSample = { TimedSnapshot(diParsData(gear = 1, speed = 0), measuredAtElapsedMs = 49_000L) }
        vm.serviceRunning = { true }
        vm.elapsedNow = { 50_000L }
        vm.openNewRule()
        vm.updateEditing { copy(requirePark = true, actions = listOf(ActionDef("车窗关闭", "x"))) }

        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertNull(vm.uiState.value.message)
    }

    private fun parkOnlyRunWith(sample: TimedSnapshot?, running: Boolean): AutomationViewModel {
        val vm = vm()
        vm.liveSnapshot = { diParsData(gear = 1) }
        vm.liveSample = { sample }
        vm.serviceRunning = { running }
        vm.elapsedNow = { 50_000L }
        vm.openNewRule()
        // A pause has no speed gate: nothing later checks the snapshot's age.
        vm.updateEditing { copy(requirePark = true, actions = listOf(ActionDef("", "Пауза", "delay", "10"))) }
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test fun `a park-only test run on a P left by a stopped service is refused`() {
        val vm = parkOnlyRunWith(TimedSnapshot(diParsData(gear = 1, speed = 0), measuredAtElapsedMs = 49_000L), running = false)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        assertEquals("Не выполнено: правило работает только на парковке. Включите P.", vm.uiState.value.message)
    }

    @Test fun `a park-only test run on an old P is refused`() {
        val old = TimedSnapshot(diParsData(gear = 1, speed = 0), measuredAtElapsedMs = 50_000L - TEST_RUN_MAX_SNAPSHOT_AGE_MS)
        parkOnlyRunWith(old, running = true)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `a list condition with an operator the editor does not offer is not saved`() {
        val vm = vm()
        vm.openNewRule()
        vm.updateEditing {
            copy(name = "R", triggers = listOf(TriggerDef("Gear", "档位", ">", "1", "Передача")), actions = listOf(ActionDef("车窗关闭", "x")))
        }

        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleDao.insert(any()) }
        assertEquals("Выберите «равно» или «не равно» в условии 1", vm.uiState.value.editorError)
    }

    @Test fun `a park-only test run with no polled snapshot is refused`() {
        parkOnlyRunWith(null, running = true)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }
}
