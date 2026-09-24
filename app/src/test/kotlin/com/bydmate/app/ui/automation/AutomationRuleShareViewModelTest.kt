package com.bydmate.app.ui.automation

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.RuleParseResult
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.automation.RuleShareFiles
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.loop.SharedAdaptiveLoop
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.util.appLocalizedContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
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
    private val sharedFiles = mutableListOf<File>()

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
        it.shareSheet = { file -> sharedFiles += file }
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
        val token = vm.uiState.value.importDraft!!.token
        vm.resolveImportPlace(token, 0, dacha)
        vm.resolveImportContact(token, 0, "+375291234567", "Мама", true)
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
        vm.confirmShare()
        testDispatcher.scheduler.advanceUntilIdle()

        val file = sharedFiles.single()
        assertEquals("bydmate_rule_bagazhnik.json", file.name)
        assertEquals(downloads, file.parentFile)
        assertFalse(file.readText().contains("375291234567"))
    }

    @Test fun `share shows the note first and writes only on continue`() {
        val rule = RuleEntity(
            id = 4, name = "Багажник",
            triggers = TriggerDef.listToJson(listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость"))),
            actions = ActionDef.listToJson(listOf(windowClose)),
        )
        val vm = vm()
        vm.shareRule(rule)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingShare)
        assertTrue(downloads.list()!!.isEmpty())
        assertTrue(sharedFiles.isEmpty())

        vm.cancelShare()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingShare)
        assertTrue(downloads.list()!!.isEmpty())

        vm.shareRule(rule)
        vm.confirmShare()
        assertNull(vm.uiState.value.pendingShare)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("bydmate_rule_bagazhnik.json"), sharedFiles.map { it.name })
        assertEquals(
            ctx.appLocalizedContext().getString(R.string.automation_share_saved_toast, "bydmate_rule_bagazhnik.json"),
            ShadowToast.getTextOfLatestToast(),
        )
        assertFalse(vm.uiState.value.shareInProgress)
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

    private fun snapshot(speed: Int?): DiParsData = diParsData(speed = speed)

    /** Telemetry as the test run reads it: [sample], the service state and the monotonic clock. */
    private fun AutomationViewModel.telemetry(sample: TimedSnapshot?, running: Boolean = true, now: Long = 50_000L) {
        liveSample = { sample }
        serviceRunning = { running }
        elapsedNow = { now }
    }

    private fun AutomationViewModel.editWith(actions: List<ActionDef>) = updateEditing {
        copy(
            id = 9, isNew = false, name = "Navi",
            triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость")),
            actions = actions,
        )
    }

    private val windowOpen = ActionDef("车窗全开", "Открыть все окна")
    private val sunroofToggle = ActionDef("", "Люк", "toggle", ActionDispatcher.TOGGLE_SUNROOF)
    private val windowClose = ActionDef("车窗关闭", "Закрыть все окна")

    @Test fun `test run skips speed-gated actions without a snapshot`() {
        val vm = vm()
        vm.telemetry(null)
        vm.editWith(listOf(windowOpen, sunroofToggle, windowClose))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { actionDispatcher.dispatch(windowOpen, any()) }
        coVerify(exactly = 0) { actionDispatcher.dispatch(sunroofToggle, any()) }
        coVerify(exactly = 1) { actionDispatcher.dispatch(windowClose, any()) }
        val toast = ShadowToast.getTextOfLatestToast()
        assertTrue(toast, toast.contains(ctx.appLocalizedContext().getString(R.string.automation_test_run_no_speed)))
    }

    @Test fun `test run skips speed-gated actions on a stale snapshot`() {
        val vm = vm()
        vm.telemetry(TimedSnapshot(snapshot(0), measuredAtElapsedMs = 20_000L), now = 50_000L)
        vm.editWith(listOf(windowOpen, windowClose))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { actionDispatcher.dispatch(windowOpen, any()) }
        coVerify(exactly = 1) { actionDispatcher.dispatch(windowClose, any()) }
    }

    @Test fun `test run sends speed-gated actions with a fresh snapshot for the dispatcher gate`() {
        val vm = vm()
        val fresh = snapshot(0)
        vm.telemetry(TimedSnapshot(fresh, measuredAtElapsedMs = 49_000L), now = 50_000L)
        vm.editWith(listOf(windowOpen, sunroofToggle))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { actionDispatcher.dispatch(windowOpen, fresh) }
        coVerify(exactly = 1) { actionDispatcher.dispatch(sunroofToggle, fresh) }
    }

    @Test fun `speed gate covers window, sunroof, frunk and unlock only`() {
        assertTrue(isSpeedGatedAction(windowOpen))
        assertTrue(isSpeedGatedAction(ActionDef("车门解锁", "Открыть замки")))
        assertTrue(isSpeedGatedAction(ActionDef("", "Замки", "toggle", ActionDispatcher.TOGGLE_LOCKS)))
        assertTrue(isSpeedGatedAction(ActionDef("", "Капот", "toggle", ActionDispatcher.TOGGLE_FRONT_TRUNK)))
        assertTrue(isSpeedGatedAction(sunroofToggle))
        assertFalse(isSpeedGatedAction(windowClose))
        assertFalse(isSpeedGatedAction(ActionDef("", "Аварийка", "toggle", ActionDispatcher.TOGGLE_HAZARD)))
        val sample = TimedSnapshot(snapshot(0), 1_000L)
        assertFalse(isSampleFresh(sample, true, 1_000L + TEST_RUN_MAX_SNAPSHOT_AGE_MS))
        assertTrue(isSampleFresh(sample, true, 1_000L + TEST_RUN_MAX_SNAPSHOT_AGE_MS - 1))
        assertFalse(isSampleFresh(sample, false, 1_000L + 1))
        assertFalse(isSampleFresh(TimedSnapshot(snapshot(0), 5_000L), true, 1_000L))
    }

    @Test fun `test run skips speed-gated actions when the service is not running`() {
        val vm = vm()
        vm.telemetry(TimedSnapshot(snapshot(0), measuredAtElapsedMs = 49_000L), running = false, now = 50_000L)
        vm.editWith(listOf(windowOpen, windowClose))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { actionDispatcher.dispatch(windowOpen, any()) }
        coVerify(exactly = 1) { actionDispatcher.dispatch(windowClose, any()) }
    }

    @Test fun `a snapshot replayed to a restarted service keeps its read time and is rejected`() {
        var clock = 5_000L
        val reader = mockk<ParsReader>()
        // One good read (standing still), then the link is lost.
        coEvery { reader.fetch() } returnsMany listOf(snapshot(0), null)
        val loop = SharedAdaptiveLoop(reader, mockk(relaxed = true), mockk(relaxed = true), testDispatcher, elapsedNow = { clock })
        val firstService = loop.start(CoroutineScope(testDispatcher))
        testDispatcher.scheduler.runCurrent()
        firstService.cancel()

        // Half a minute later the service starts again in the same process: its collector gets
        // the replayed snapshot, still stamped with the time of the read.
        clock += 30_000L
        var replayed: TimedSnapshot? = null
        CoroutineScope(testDispatcher).launch { replayed = loop.samples.first() }
        testDispatcher.scheduler.runCurrent()
        assertEquals(5_000L, replayed!!.measuredAtElapsedMs)

        val vm = vm()
        vm.telemetry(replayed, running = true, now = clock)
        vm.editWith(listOf(windowOpen))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { actionDispatcher.dispatch(windowOpen, any()) }
    }

    @Test fun `test run survives a command number too long for an Int`() {
        val huge = ActionDef("天窗打开999999999999999999999", "Люк")
        assertFalse(ActionDispatcher.isSunroofOpenCommand(huge.command))
        assertFalse(ActionDispatcher.isSunroofOpenCommand("天窗打开150"))
        assertTrue(ActionDispatcher.isSunroofOpenCommand("天窗打开100"))
        assertFalse(ActionDispatcher.isWindowOpenCommand("主驾打开99999999999"))
        assertTrue(ActionDispatcher.isWindowOpenCommand("主驾打开100"))

        val vm = vm()
        vm.telemetry(null)
        vm.editWith(listOf(huge))
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.testRunning)
        // Not an open command, so no speed pre-check: the dispatcher gets it (and, on the car,
        // refuses it as an unknown command).
        coVerify(exactly = 1) { actionDispatcher.dispatch(huge, any()) }
    }

    @Test fun `closing the editor stops the test run`() {
        coEvery { actionDispatcher.dispatch(any(), any()) } coAnswers {
            delay(60_000)
            DispatchResult(true)
        }
        val vm = vm()
        vm.editWith(listOf(windowClose, ActionDef("", "Пауза", "delay", "10")))
        vm.testRun()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.testRunning)

        vm.closeEditor()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.testRunning)
        coVerify(exactly = 1) { actionDispatcher.dispatch(any(), any()) }
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_test_run_stopped), ShadowToast.getTextOfLatestToast())
    }

    @Test fun `import preview names a call by what it does, not by its label`() {
        val action = ActionDef("", "Уведомление", "call", """{"phone":"+375291234567","autoDial":true}""")
        val lc = ctx.appLocalizedContext()
        assertEquals(
            "${lc.getString(R.string.automation_action_call)}: +375291234567, ${lc.getString(R.string.automation_call_auto_dial_label)}",
            RuleImportSummary.action(action, ctx),
        )
        val param = RuleImportSummary.action(ActionDef("车窗全开", "Уведомление"), ctx)
        assertTrue(param, param.endsWith("(车窗全开)"))
        val trigger = RuleImportSummary.trigger(TriggerDef("Speed", "车速", ">", "7", "Уведомление"), ctx)
        assertTrue(trigger, trigger.contains("> 7"))
    }

    @Test fun `import preview shows the rule flags in one line`() {
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_dacha.json"))
        val rule = vm.uiState.value.importDraft!!.rule
        val flags = RuleImportSummary.flags(rule.copy(requirePark = true, playSound = false), ctx)
        val lc = ctx.appLocalizedContext()
        assertTrue(flags, flags.startsWith(lc.getString(R.string.automation_rule_cooldown, rule.cooldownSeconds)))
        assertTrue(flags, flags.contains(lc.getString(R.string.automation_setting_park_only)))
        assertFalse(flags, flags.contains(lc.getString(R.string.automation_setting_play_sound)))
    }

    @Test fun `editing an imported disabled rule keeps it disabled`() {
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_speed.json"))
        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 21L
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(inserted.captured.enabled)

        val stored = inserted.captured.copy(id = 21, triggerCount = 3, lastTriggeredAt = 77L, createdAt = 5L)
        coEvery { ruleDao.getById(21) } returns stored
        vm.openEditRule(stored)
        vm.updateEditing { copy(name = "Navi 2") }
        val updated = slot<RuleEntity>()
        coEvery { ruleDao.update(capture(updated)) } returns Unit
        vm.saveRule()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Navi 2", updated.captured.name)
        assertFalse(updated.captured.enabled)
        assertEquals(3, updated.captured.triggerCount)
        assertEquals(77L, updated.captured.lastTriggeredAt)
        assertEquals(5L, updated.captured.createdAt)
    }

    @Test fun `a file over the size limit is refused as invalid`() {
        val file = File(downloads, "bydmate_rule_big.json")
        val body = requireNotNull(javaClass.classLoader?.getResource("rule-share/bydmate_rule_speed.json")).readText()
        file.writeText(body.replace("\"Navi\"", "\"" + "x".repeat(RuleShareFiles.MAX_FILE_BYTES) + "\""))
        val vm = vm()
        vm.importFile(file)

        assertNull(vm.uiState.value.importDraft)
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_import_invalid), vm.uiState.value.importError)
    }

    @Test fun `import stops at the rule limit and keeps the preview`() {
        coEvery { ruleDao.getCount() } returns MAX_RULES
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_speed.json"))
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ruleDao.insert(any()) }
        val draft = requireNotNull(vm.uiState.value.importDraft)
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_rule_limit, MAX_RULES), draft.error)
    }

    @Test fun `a failed insert keeps the preview with the error`() {
        coEvery { ruleDao.insert(any()) } throws SQLiteException("disk full")
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_speed.json"))
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()

        val draft = requireNotNull(vm.uiState.value.importDraft)
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_import_save_failed, "disk full"), draft.error)
    }

    @Test fun `a contact picked for an older draft does not land on the new one`() {
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_dacha.json"))
        val oldToken = vm.uiState.value.importDraft!!.token
        vm.pickImportFile(copyFixture("bydmate_rule_dacha.json").copyTo(File(downloads, "bydmate_rule_dacha_2.json")))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.resolveImportContact(oldToken, 0, "+375291234567", "Мама", true)
        assertEquals(listOf(0), vm.uiState.value.importDraft!!.rule.unresolvedCallIndexes())
        assertTrue(vm.uiState.value.importDraft!!.token != oldToken)
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

    @Test fun `while the import inserts the preview is frozen and the tapped copy goes in`() {
        val gate = CompletableDeferred<Unit>()
        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } coAnswers {
            gate.await()
            1L
        }
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_speed.json"))
        vm.setImportEnableNow(true)
        vm.confirmImport()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.importDraft!!.saving)

        // Neither a late switch nor «Отмена» changes what is being added.
        vm.setImportEnableNow(false)
        vm.closeImport()
        vm.confirmImport()
        assertTrue(vm.uiState.value.importDraft!!.enableNow)
        assertTrue(vm.uiState.value.importDraft!!.saving)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.importDraft)
        assertTrue(inserted.captured.enabled)
        coVerify(exactly = 1) { ruleDao.insert(any()) }
    }

    @Test fun `a link emptied on export imports disabled and asks for the address`() {
        val file = File(downloads, "bydmate_rule_link.json")
        file.writeText(
            requireNotNull(javaClass.classLoader?.getResource("rule-share/bydmate_rule_speed.json")).readText().replace(
                """{"command":"车窗关闭","displayName":"Закрыть все окна","kind":"param"}""",
                """{"command":"","displayName":"","kind":"url","payload":"{\"url\":\"\",\"minimize\":false,\"urlRequired\":true}"}""",
            )
        )
        val vm = vm()
        vm.importFile(file)
        val draft = requireNotNull(vm.uiState.value.importDraft)
        assertEquals(listOf(0), draft.rule.unresolvedUrlIndexes())
        vm.setImportEnableNow(true)
        assertFalse(vm.uiState.value.importDraft!!.enableNow)

        val inserted = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(inserted)) } returns 1L
        vm.confirmImport()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.importDraft)
        assertFalse(inserted.captured.enabled)
    }

    @Test fun `a contact picked for an sms link keeps its body`() {
        val file = File(downloads, "bydmate_rule_sms.json")
        file.writeText(
            requireNotNull(javaClass.classLoader?.getResource("rule-share/bydmate_rule_speed.json")).readText().replace(
                """{"command":"车窗关闭","displayName":"Закрыть все окна","kind":"param"}""",
                """{"command":"","displayName":"sms:","kind":"url","payload":"{\"url\":\"sms:?body=hello\",\"minimize\":false,\"contactRequired\":true}"}""",
            )
        )
        val vm = vm()
        vm.importFile(file)
        val token = vm.uiState.value.importDraft!!.token
        vm.resolveImportContact(token, 0, "+375291234567", "Мама", false)
        val action = vm.uiState.value.importDraft!!.rule.actions.single()
        assertEquals("sms:+375291234567?body=hello", action.urlString())
        assertTrue(vm.uiState.value.importDraft!!.preview.actions.single().contains("sms:+375291234567?body=hello"))
    }

    @Test fun `a file with JSON nested too deep in a string is refused`() {
        val vm = vm()
        vm.importFile(copyFixture("bydmate_rule_deep.json"))
        assertNull(vm.uiState.value.importDraft)
        assertEquals(ctx.appLocalizedContext().getString(R.string.automation_import_invalid), vm.uiState.value.importError)
    }

    @Test fun `import preview shows the trigger logic and what navigate, music and split really run`() {
        val lc = ctx.appLocalizedContext()
        val rule = SharedRuleFixture.withActions(
            ActionDef("", "Дача", "navigate", """{"lat":54.1,"lon":27.2,"name":"Дача","shortcut":"home","go":true}"""),
            ActionDef("", "Музыка", "yandex_music", """{"mode":"search","query":"Queen","minimize":true}"""),
            ActionDef("", "Сплит", "split_screen", """{"narrow":"a.b","wide":"c.d","side":"right"}"""),
            ActionDef("", "Поиск", "navigate", """{"query":"АЗС","show":true,"go":true,"app":"maps"}"""),
        )
        val preview = RuleImportSummary.preview(rule.copy(triggerLogic = "OR"), ctx)
        assertEquals(lc.getString(R.string.automation_import_logic_any), preview.logic)
        assertEquals(lc.getString(R.string.automation_import_logic_all), RuleImportSummary.preview(rule, ctx).logic)

        val home = preview.actions[0]
        val music = preview.actions[1]
        val split = preview.actions[2]
        val search = preview.actions[3]
        assertTrue(home, home.contains(lc.getString(R.string.automation_import_nav_home)))
        assertTrue(home, home.contains(lc.getString(R.string.automation_import_nav_go)))
        assertFalse(home, home.contains("54.1"))
        assertTrue(music, music.contains("search «Queen»"))
        assertTrue(music, music.contains(lc.getString(R.string.automation_import_minimize)))
        assertTrue(split, split.contains(lc.getString(R.string.split_action_side_right)))
        assertTrue(search, search.contains(lc.getString(R.string.automation_import_nav_search, "АЗС")))
        assertTrue(search, search.contains(lc.getString(R.string.automation_import_nav_maps)))
        assertFalse(search, search.contains(lc.getString(R.string.automation_import_nav_go)))
        assertFalse(search, search.contains(lc.getString(R.string.automation_import_nav_show)))
    }

    private object SharedRuleFixture {
        fun withActions(vararg actions: ActionDef) = com.bydmate.app.data.automation.SharedRule(
            name = "Navi", triggerLogic = "AND",
            triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость")),
            actions = actions.toList(), cooldownSeconds = 60, requirePark = false,
            confirmBeforeExecute = false, fireOncePerTrip = false, playSound = false,
        )
    }

    @Test fun `two quick shares write one file`() {
        val rule = RuleEntity(
            id = 4, name = "Багажник",
            triggers = TriggerDef.listToJson(listOf(TriggerDef("Speed", "车速", ">", "7", "Скорость"))),
            actions = ActionDef.listToJson(listOf(windowClose)),
        )
        val vm = vm()
        vm.shareRule(rule)
        vm.confirmShare()
        assertTrue(vm.uiState.value.shareInProgress)
        vm.shareRule(rule)
        vm.confirmShare()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("bydmate_rule_bagazhnik.json"), downloads.list()!!.toList())
        assertEquals(1, sharedFiles.size)
        assertFalse(vm.uiState.value.shareInProgress)
        assertTrue(RuleShare.parse(File(downloads, "bydmate_rule_bagazhnik.json").readText(), "x") is RuleParseResult.Ok)
    }
}
