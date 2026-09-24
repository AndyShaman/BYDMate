package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.util.appLocalizedContext
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Import, share and «Тестовый запуск» through the real AutomationViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationRuleShareViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private val placeRepository = mockk<PlaceRepository>(relaxed = true)
    private val actionDispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val rules = MutableStateFlow<List<RuleEntity>>(emptyList())
    private val places = MutableStateFlow<List<PlaceEntity>>(emptyList())
    private lateinit var downloads: File

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("ru")
        // No starter templates: they would show up as inserts.
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit().putBoolean("templates_inserted", true).commit()
        every { ruleDao.getAll() } returns rules
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns places
        coEvery { actionDispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        downloads = tmp.newFolder("Download")
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = AutomationViewModel(
        ruleDao = ruleDao,
        ruleLogDao = ruleLogDao,
        placeRepository = placeRepository,
        vehicleApi = mockk(relaxed = true),
        actionDispatcher = actionDispatcher,
        context = ctx,
    ).also {
        it.downloadsDir = { downloads }
        it.ioDispatcher = testDispatcher
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun copyFixture(name: String): File {
        val text = requireNotNull(javaClass.classLoader?.getResource("rule-share/$name")).readText()
        return File(downloads, name).apply { writeText(text) }
    }

    private fun AutomationViewModel.importFile(file: File) {
        openImport()
        testDispatcher.scheduler.advanceUntilIdle()
        pickImportFile(file)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun `import lists share files in Download`() {
        val file = copyFixture("bydmate_rule_speed.json")
        File(downloads, "bydmate_backup_20260924.zip").writeText("x")
        val vm = vm()
        vm.openImport()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(file), vm.uiState.value.importFiles)
    }

    @Test fun `unknown place stays unresolved and the rule is added disabled`() {
        places.value = listOf(PlaceEntity(id = 1, name = "Дом", lat = 54.0, lon = 27.0))
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_dacha.json"))

        val draft = requireNotNull(vm.uiState.value.importDraft)
        assertEquals(listOf(0), draft.rule.unresolvedPlaceIndexes())
        assertEquals(listOf(0), draft.rule.unresolvedCallIndexes())
        vm.setImportEnableNow(true)
        assertFalse(vm.uiState.value.importDraft!!.enableNow)

        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 1L
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.importDraft)
        assertFalse(inserted.captured.enabled)
        assertEquals("Navi", inserted.captured.name)
        val trigger = TriggerDef.listFromJson(inserted.captured.triggers).single()
        assertNull(trigger.placeId)
        assertEquals("Дача", trigger.placeName)
    }

    @Test fun `resolved place and contact allow enabling right away`() {
        val dacha = PlaceEntity(id = 5, name = "Дом", lat = 54.0, lon = 27.0)
        places.value = listOf(dacha)
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_dacha.json"))
        vm.resolveImportPlace(0, dacha)
        vm.resolveImportContact(0, "+375291234567", "Мама", true)
        vm.setImportEnableNow(true)

        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 1L
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(inserted.captured.enabled)
        assertEquals(5L, TriggerDef.listFromJson(inserted.captured.triggers).single().placeId)
        assertEquals("Въезд в «Дом»", TriggerDef.listFromJson(inserted.captured.triggers).single().displayName)
        assertTrue(ActionDef.listFromJson(inserted.captured.actions).first().payload!!.contains("+375291234567"))
    }

    @Test fun `name collision appends the import suffix`() {
        rules.value = listOf(RuleEntity(id = 3, name = "Navi", triggers = "[]", actions = "[]"))
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_speed.json"))

        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 1L
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Navi (импорт)", inserted.captured.name)
        assertEquals("OR", inserted.captured.triggerLogic)
    }

    @Test fun `unknown kind is refused with the update message`() {
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_newer_kind.json"))

        assertNull(vm.uiState.value.importDraft)
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_import_newer_version), vm.uiState.value.importError)
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun `share writes a stripped file to Download`() {
        val rule = RuleEntity(
            id = 4, name = "Багажник", triggerCount = 47, lastTriggeredAt = 1L,
            triggers = TriggerDef.listToJson(listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость"))),
            actions = ActionDef.listToJson(listOf(ActionDef("", "Звонок", "call", """{"phone":"+375291234567"}"""))),
        )
        val vm = vm()
        vm.shareRule(rule)
        testDispatcher.scheduler.advanceUntilIdle()

        val file = requireNotNull(vm.uiState.value.sharedRuleFile)
        assertEquals("bydmate_rule_bagazhnik.json", file.name)
        assertEquals(downloads, file.parentFile)
        assertFalse(file.readText().contains("375291234567"))
    }

    @Test fun `test run dispatches every action and records nothing`() {
        val vm = vm()
        val actions = listOf(
            ActionDef("车窗关闭", "Закрыть все окна"),
            ActionDef("", "Пауза", "delay", "10"),
        )
        vm.updateEditing {
            copy(
                id = 9, isNew = false, name = "Navi",
                triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость")),
                actions = actions,
            )
        }
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { actionDispatcher.dispatch(actions[0], any()) }
        coVerify(exactly = 1) { actionDispatcher.dispatch(actions[1], any()) }
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 0) { ruleDao.update(any()) }
        coVerify(exactly = 0) { ruleLogDao.insert(any()) }
        assertFalse(vm.uiState.value.testRunning)
    }
}
