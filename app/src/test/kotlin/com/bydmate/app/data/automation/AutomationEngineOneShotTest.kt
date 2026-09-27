package com.bydmate.app.data.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * A one-shot rule on a real Room database: it fires once, and what keeps it from firing again
 * is the stored enabled=false, so a later check or a new process sees it spent. Cooldown and
 * «Только на парковке» only hold it back inside its day; a confirmation spends it either way.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver (engine init).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineOneShotTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private lateinit var db: AppDatabase

    private val momentValue = OneShotTrigger.format(LocalDateTime.of(2026, 10, 1, 8, 30))
    private val momentMs = OneShotTrigger.momentMs(momentValue)!!
    private var now = momentMs + 60_000

    @Before fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
    }

    @After fun tearDown() {
        unmockkObject(ConfirmOverlayManager)
        db.close()
    }

    /** A fresh engine is what a process restart gives: no memory, only the database. */
    private fun engine() = AutomationEngine(
        ruleDao = db.ruleDao(),
        ruleLogDao = db.ruleLogDao(),
        actionDispatcher = dispatcher,
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
        nowMs = { now }
    }

    private suspend fun store(rule: RuleEntity = RuleEntity(
        name = "once",
        triggers = TriggerDef.listToJson(listOf(TriggerDef(
            OneShotTrigger.PARAM, "", "==", momentValue, "once", kind = OneShotTrigger.KIND,
        ))),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )): Long = db.ruleDao().insert(rule)

    private suspend fun stored(id: Long): RuleEntity = db.ruleDao().getById(id)!!

    private suspend fun journalReasons(): List<String> = db.ruleLogDao().getAll().first()
        .map { org.json.JSONArray(it.actionsResult).getJSONObject(0).optString("reason") }

    @Test fun `a fired one-shot rule is stored switched off and a later check does not fire it`() = runBlocking {
        val id = store()
        val engine = engine()

        engine.evaluate(diParsData(), null)
        coVerify(timeout = 2_000, exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertFalse(stored(id).enabled)
        val firedAt = stored(id).lastTriggeredAt

        now += 60_000
        engine.evaluate(diParsData(), null)

        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertEquals(firedAt, stored(id).lastTriggeredAt)
    }

    @Test fun `a new process does not fire a spent one-shot rule`() = runBlocking {
        val id = store()
        engine().evaluate(diParsData(), null)
        coVerify(timeout = 2_000, exactly = 1) { dispatcher.dispatch(any(), any()) }

        now += 10 * 60_000
        engine().evaluate(diParsData(), null)

        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertFalse(stored(id).enabled)
    }

    @Test fun `a new process fires a one-shot rule not spent yet`() = runBlocking {
        val id = store()
        engine().apply { interactiveProvider = { false } }.evaluate(diParsData(), null)
        assertTrue(stored(id).enabled)

        now += 60_000
        engine().evaluate(diParsData(), null)

        coVerify(timeout = 2_000, exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertFalse(stored(id).enabled)
    }

    @Test fun `a cooldown holds a one-shot rule back until it runs out`() = runBlocking {
        val id = store().also { db.ruleDao().updateLastTriggered(it, now - 60_000) }
        db.ruleDao().update(stored(id).copy(cooldownSeconds = 600))
        val engine = engine()

        engine.evaluate(diParsData(), null)
        assertTrue(stored(id).enabled)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }

        now += 10 * 60_000
        engine.evaluate(diParsData(), null)

        coVerify(timeout = 2_000, exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertFalse(stored(id).enabled)
    }

    @Test fun `a park-only one-shot rule waits for P within its day`() = runBlocking {
        val id = store().also { db.ruleDao().update(stored(it).copy(requirePark = true)) }
        val engine = engine()

        engine.evaluate(diParsData(gear = 4), null)
        assertTrue(stored(id).enabled)

        now += 60_000
        engine.evaluate(diParsData(gear = 1), null)

        coVerify(timeout = 2_000, exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertFalse(stored(id).enabled)
    }

    @Test fun `a cancelled confirmation spends a one-shot rule without running it`() = runBlocking {
        mockkObject(ConfirmOverlayManager)
        val onCancel = slot<() -> Unit>()
        every { ConfirmOverlayManager.show(any(), any(), any(), any(), capture(onCancel), any()) } returns true
        val id = store().also { db.ruleDao().update(stored(it).copy(confirmBeforeExecute = true)) }
        val engine = engine()

        engine.evaluate(diParsData(), null)
        onCancel.captured.invoke()
        now += 60_000
        engine.evaluate(diParsData(), null)

        assertFalse(stored(id).enabled)
        var reasons = journalReasons()
        repeat(20) { if (reasons.isEmpty()) { Thread.sleep(50); reasons = journalReasons() } }
        assertEquals(listOf("Отменено в окне подтверждения"), reasons)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }
}
