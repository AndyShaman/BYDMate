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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Engine init registers a BroadcastReceiver (real Context) — same Robolectric
// setup as AutomationEngineButtonPressTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineSteeringKeyTest {

    private fun steeringKeyTrigger(keyCode: Int) = TriggerDef(
        param = AutomationEngine.TRIGGER_PARAM_STEERING_KEY,
        chineseName = "",
        operator = "==",
        value = keyCode.toString(),
        displayName = "Key $keyCode",
        kind = AutomationEngine.TRIGGER_KIND_STEERING_KEY,
    )

    private fun rule(id: Long, trigger: TriggerDef, enabled: Boolean = true) = RuleEntity(
        id = id, name = "r$id", enabled = enabled,
        triggers = TriggerDef.listToJson(listOf(trigger)),
        actions = ActionDef.listToJson(listOf(ActionDef("车窗关闭", "Close windows"))),
    )

    private fun setup(rules: List<RuleEntity>): Triple<AutomationEngine, RuleDao, ActionDispatcher> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns rules.filter { it.enabled }
            every { getAll() } returns flowOf(rules)
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

    // The keycode cache is filled from the DAO flow on the engine's own IO scope.
    private suspend fun awaitKeyCodes(engine: AutomationEngine, expected: Set<Int>): Set<Int> {
        repeat(50) {
            if (engine.steeringKeyCodes.value == expected) return expected
            delay(20)
        }
        return engine.steeringKeyCodes.value
    }

    @Test fun `fires enabled rule bound to the pressed keycode`() = runBlocking {
        val (engine, ruleDao, dispatcher) = setup(listOf(rule(1, steeringKeyTrigger(305))))
        val matched = engine.onSteeringKey(305)
        assertEquals(1, matched)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `ignores rules bound to a different keycode`() = runBlocking {
        val (engine, _, dispatcher) = setup(listOf(rule(1, steeringKeyTrigger(306))))
        assertEquals(0, engine.onSteeringKey(305))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `a button_press rule is not fired by a steering key with the same number`() = runBlocking {
        val (engine, _, dispatcher) = setup(listOf(rule(1, newButtonPressTrigger(2))))
        assertEquals(0, engine.onSteeringKey(2))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `a steering key rule is not fired by a widget button with the same number`() = runBlocking {
        val (engine, _, dispatcher) = setup(listOf(rule(1, steeringKeyTrigger(2))))
        assertEquals(0, engine.onButtonPress(2))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `keycode cache holds the codes of enabled steering key rules only`() = runBlocking {
        val (engine, _, _) = setup(
            listOf(
                rule(1, steeringKeyTrigger(305)),
                rule(2, steeringKeyTrigger(306), enabled = false),
                rule(3, newButtonPressTrigger(1)),
            )
        )
        assertEquals(setOf(305), awaitKeyCodes(engine, setOf(305)))
    }
}
