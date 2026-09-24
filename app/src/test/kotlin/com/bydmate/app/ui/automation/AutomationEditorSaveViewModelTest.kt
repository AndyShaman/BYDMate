package com.bydmate.app.ui.automation

import android.content.Context
import android.database.sqlite.SQLiteException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Сохранить» in the editor through the real AutomationViewModel: a rule deleted meanwhile, a save that returns late. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEditorSaveViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private val placeRepository = mockk<PlaceRepository>(relaxed = true)

    private val windowClose = ActionDef("车窗关闭", "Закрыть все окна")

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("ru")
        // No starter templates: they would show up as inserts.
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit().putBoolean("templates_inserted", true).commit()
        every { ruleDao.getAll() } returns MutableStateFlow(emptyList())
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
        context = ctx,
    ).also { testDispatcher.scheduler.advanceUntilIdle() }

    private fun AutomationViewModel.editWith(actions: List<ActionDef>) = updateEditing {
        copy(
            id = 9, isNew = false, name = "Navi",
            triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость")),
            actions = actions,
        )
    }

    @Test fun `saving a rule deleted meanwhile inserts nothing and says so`() {
        coEvery { ruleDao.getById(9) } returns null
        val vm = vm()
        vm.openEditRule(RuleEntity(id = 9, name = "Navi", triggers = "[]", actions = "[]"))
        vm.editWith(listOf(windowClose))
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleDao.insert(any()) }
        coVerify(exactly = 0) { ruleDao.update(any()) }
        assertTrue(vm.uiState.value.showEditor)
        assertTrue(vm.uiState.value.editorRuleDeleted)

        vm.closeEditor()
        assertFalse(vm.uiState.value.showEditor)
        assertFalse(vm.uiState.value.editorRuleDeleted)
    }

    @Test fun `a deleted rule saved as new inserts the draft once with its switch and closes the editor`() {
        coEvery { ruleDao.getById(9) } returns null
        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 30L
        val vm = vm()
        vm.openEditRule(RuleEntity(id = 9, name = "Navi", enabled = false, triggers = "[]", actions = "[]"))
        vm.editWith(listOf(windowClose))
        vm.updateEditing { copy(name = "Navi 2") }
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveDeletedRuleAsNew()
        vm.saveDeletedRuleAsNew()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { ruleDao.insert(any()) }
        coVerify(exactly = 0) { ruleDao.update(any()) }
        assertEquals(0L, inserted.captured.id)
        assertEquals("Navi 2", inserted.captured.name)
        assertFalse(inserted.captured.enabled)
        assertEquals(listOf(windowClose), ActionDef.listFromJson(inserted.captured.actions))
        assertFalse(vm.uiState.value.showEditor)
        assertFalse(vm.uiState.value.editorRuleDeleted)
    }

    @Test fun `saving a deleted rule as new keeps the editor open until the insert returns`() {
        coEvery { ruleDao.getById(9) } returns null
        val gate = CompletableDeferred<Long>()
        coEvery { ruleDao.insert(any()) } coAnswers { gate.await() }
        val vm = vm()
        vm.openEditRule(RuleEntity(id = 9, name = "Navi", triggers = "[]", actions = "[]"))
        vm.editWith(listOf(windowClose))
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveDeletedRuleAsNew()
        testDispatcher.scheduler.runCurrent()
        // Insert still in flight: editor and draft stay on screen.
        assertTrue(vm.uiState.value.showEditor)
        assertEquals("Navi", vm.uiState.value.editing.name)

        gate.complete(30L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.showEditor)
        coVerify(exactly = 1) { ruleDao.insert(any()) }
    }

    @Test fun `a failed insert on a deleted rule keeps the draft and shows a message`() {
        coEvery { ruleDao.getById(9) } returns null
        coEvery { ruleDao.insert(any()) } throws SQLiteException("disk full")
        val vm = vm()
        vm.openEditRule(RuleEntity(id = 9, name = "Navi", triggers = "[]", actions = "[]"))
        vm.editWith(listOf(windowClose))
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveDeletedRuleAsNew()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.showEditor)
        assertFalse(vm.uiState.value.editorRuleDeleted)
        assertEquals("Navi", vm.uiState.value.editing.name)
        assertEquals(listOf(windowClose), vm.uiState.value.editing.actions)
        assertTrue(vm.uiState.value.editorError?.contains("disk full") == true)
    }

    @Test fun `dismissing the deleted-rule dialog keeps the editor and the draft`() {
        coEvery { ruleDao.getById(9) } returns null
        val vm = vm()
        vm.openEditRule(RuleEntity(id = 9, name = "Navi", triggers = "[]", actions = "[]"))
        vm.editWith(listOf(windowClose))
        vm.updateEditing { copy(name = "Navi 2") }
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.dismissRuleDeleted()
        assertTrue(vm.uiState.value.showEditor)
        assertFalse(vm.uiState.value.editorRuleDeleted)
        assertEquals("Navi 2", vm.uiState.value.editing.name)
        assertEquals(listOf(windowClose), vm.uiState.value.editing.actions)
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun `a save that returns late does not touch the editor opened after it`() {
        val gate = CompletableDeferred<Unit>()
        val stored = RuleEntity(id = 9, name = "A", triggers = "[]", actions = "[]")
        var storedRow: RuleEntity? = stored
        coEvery { ruleDao.getById(9) } coAnswers {
            gate.await()
            storedRow
        }
        val vm = vm()
        // Rule A: save, then the editor is closed and rule B opened while the DAO is busy.
        vm.openEditRule(stored)
        vm.editWith(listOf(windowClose))
        vm.saveRule()
        testDispatcher.scheduler.runCurrent()
        vm.closeEditor()
        vm.openEditRule(RuleEntity(id = 12, name = "B", triggers = "[]", actions = "[]"))
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { ruleDao.update(any()) }
        assertTrue(vm.uiState.value.showEditor)
        assertEquals("B", vm.uiState.value.editing.name)

        // Same when A turns out deleted: B does not get A's «Правило уже удалено».
        val gate2 = CompletableDeferred<Unit>()
        storedRow = null
        coEvery { ruleDao.getById(9) } coAnswers {
            gate2.await()
            storedRow
        }
        vm.openEditRule(stored)
        vm.editWith(listOf(windowClose))
        vm.saveRule()
        testDispatcher.scheduler.runCurrent()
        vm.closeEditor()
        vm.openEditRule(RuleEntity(id = 12, name = "B", triggers = "[]", actions = "[]"))
        gate2.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.showEditor)
        assertFalse(vm.uiState.value.editorRuleDeleted)
    }
}
