package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.ui.automation.newButtonPressTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Engine init registers a BroadcastReceiver (real Context) — same Robolectric
// setup as AutomationEngineEdgeTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineButtonPressTest {

    private fun rule(
        id: Long,
        buttonId: Int,
        cooldown: Int = 0,
        requirePark: Boolean = false,
        fireOncePerTrip: Boolean = false,
        lastTriggeredAt: Long? = null,
        enabled: Boolean = true,
    ) = RuleEntity(
        id = id, name = "r$id", enabled = enabled,
        triggers = TriggerDef.listToJson(listOf(newButtonPressTrigger(buttonId))),
        actions = ActionDef.listToJson(listOf(ActionDef("车窗关闭", "Close windows"))),
        cooldownSeconds = cooldown,
        requirePark = requirePark,
        fireOncePerTrip = fireOncePerTrip,
        lastTriggeredAt = lastTriggeredAt,
    )

    private fun setup(enabledRules: List<RuleEntity>): Triple<AutomationEngine, RuleDao, ActionDispatcher> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns enabledRules
        }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true) {
            coEvery { dispatch(any(), any()) } returns DispatchResult(success = true)
        }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = dispatcher,
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ApplicationProvider.getApplicationContext<Context>(),
        )
        return Triple(engine, ruleDao, dispatcher)
    }

    @Test fun `fires enabled rule matching the pressed button number`() = runBlocking {
        val (engine, ruleDao, dispatcher) = setup(listOf(rule(1, buttonId = 2)))
        val matched = engine.onButtonPress(2)
        assertEquals(1, matched)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `ignores rules for a different button number`() = runBlocking {
        val (engine, ruleDao, dispatcher) = setup(listOf(rule(1, buttonId = 4)))
        val matched = engine.onButtonPress(2)
        assertEquals(0, matched)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `disabled rules are never seen (getEnabled excludes them)`() = runBlocking {
        // getEnabled() returns only enabled rows in production; simulate that by
        // returning an empty list when the only rule is disabled.
        val (engine, _, dispatcher) = setup(emptyList())
        val matched = engine.onButtonPress(2)
        assertEquals(0, matched)
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `bypasses cooldown - fires even within cooldown window`() = runBlocking {
        val r = rule(1, buttonId = 1, cooldown = 600, lastTriggeredAt = System.currentTimeMillis())
        val (engine, ruleDao, dispatcher) = setup(listOf(r))
        val matched = engine.onButtonPress(1)
        assertEquals(1, matched)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `bypasses fireOncePerTrip`() = runBlocking {
        val (engine, _, dispatcher) = setup(listOf(rule(1, buttonId = 1, fireOncePerTrip = true)))
        engine.onButtonPress(1)
        engine.onButtonPress(1)
        coVerify(exactly = 2) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `requirePark gates execution when not parked (no live data)`() = runBlocking {
        // TrackingService.lastData.value is null in unit tests (no public setter),
        // so the park gate is closed -> no dispatch, but the rule still counts as
        // matched so the caller does NOT show the "no rules" toast.
        val (engine, ruleDao, dispatcher) = setup(listOf(rule(1, buttonId = 1, requirePark = true)))
        val matched = engine.onButtonPress(1)
        assertEquals(1, matched)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `no matching rules is a no-op returning zero`() = runBlocking {
        val (engine, ruleDao, dispatcher) = setup(emptyList())
        val matched = engine.onButtonPress(3)
        assertEquals(0, matched)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }
}
