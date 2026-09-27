package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Automation audit 3.19, editor save path: what is refused and what is renamed on save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationAuditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private val placeRepository = mockk<PlaceRepository>(relaxed = true)
    private val stored = MutableStateFlow<List<RuleEntity>>(emptyList())

    private val windowClose = ActionDef("车窗关闭", "stale name")

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("ru")
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit()
            .putBoolean("templates_inserted", true).putBoolean("templates_tg_report_inserted", true).commit()
        every { ruleDao.getAll() } returns stored
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns MutableStateFlow<List<PlaceEntity>>(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = AutomationViewModel(
        ruleDao = ruleDao,
        ruleLogDao = ruleLogDao,
        placeRepository = placeRepository,
        vehicleApi = mockk(relaxed = true),
        actionDispatcher = mockk(relaxed = true),
        settingsRepository = mockk(relaxed = true),
        context = ctx,
    ).also { testDispatcher.scheduler.advanceUntilIdle() }

    private fun AutomationViewModel.draft(trigger: TriggerDef) = updateEditing {
        copy(name = "R", triggers = listOf(trigger), actions = listOf(windowClose))
    }

    private fun saved(vm: AutomationViewModel): RuleEntity? {
        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 1L
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()
        return if (inserted.isCaptured) inserted.captured else null
    }

    // Item 3: a value the engine can not read never lands in a rule.
    @Test fun `a non-numeric condition value is not saved`() {
        val vm = vm()
        vm.openNewRule()
        vm.draft(TriggerDef("SOC", "电量百分比", "<", "", "SOC"))

        assertEquals(null, saved(vm))
        assertNotNull(vm.uiState.value.editorError)
    }

    // Item 5: a steering key trigger with no key never lands in a rule.
    @Test fun `a steering key trigger with no key is not saved`() {
        val vm = vm()
        vm.openNewRule()
        vm.draft(TriggerDef("steering_key", "", "==", "0", "Клавиша", kind = "steering_key"))

        assertEquals(null, saved(vm))
        assertEquals("Клавиша не назначена", vm.uiState.value.editorError)
    }

    // Item 8: the names follow the catalog as saved.
    @Test fun `names are rebuilt from the catalog on save`() {
        val vm = vm()
        vm.openNewRule()
        vm.draft(TriggerDef("Trunk", "后备箱门", "==", "2", "Багажник == 0"))

        val rule = saved(vm)!!

        assertEquals("Багажник == Закрыт", TriggerDef.listFromJson(rule.triggers).single().displayName)
        assertEquals("Закрыть все окна", ActionDef.listFromJson(rule.actions).single().displayName)
    }

    // Item 9: 50 rules is the limit for a new rule and for a copy.
    @Test fun `a new rule over the limit is refused`() {
        stored.value = List(MAX_RULES) { RuleEntity(id = it + 1L, name = "r$it", triggers = "[]", actions = "[]") }
        val vm = vm()
        vm.updateEditing { copy(isNew = true) }
        vm.draft(TriggerDef("SOC", "电量百分比", "<", "20", "SOC"))

        assertEquals(null, saved(vm))
        assertEquals("Можно создать не больше 50 автоматизаций", vm.uiState.value.editorError)
    }

    @Test fun `a copy over the limit is refused`() {
        coEvery { ruleDao.getCount() } returns MAX_RULES
        val vm = vm()

        vm.duplicateRule(RuleEntity(id = 1, name = "r", triggers = "[]", actions = "[]"))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun `a copy under the limit is made`() {
        coEvery { ruleDao.getCount() } returns MAX_RULES - 1
        val vm = vm()

        vm.duplicateRule(RuleEntity(id = 1, name = "r", triggers = "[]", actions = "[]"))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { ruleDao.insert(match { it.id == 0L && !it.enabled }) }
    }
}
