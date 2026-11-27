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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #457: adjacent car commands (kind="param") must be paced 150 ms apart so the climate ECU does
 * not drop commands in a burst, while user "delay" steps stay unpadded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationStepPacingTest {

    private fun engineWith(actions: List<ActionDef>, onDispatch: suspend (ActionDef) -> Unit): AutomationEngine {
        val rule = RuleEntity(
            id = 1L,
            name = "climate",
            enabled = true,
            triggers = TriggerDef.listToJson(listOf(newButtonPressTrigger(1))),
            actions = ActionDef.listToJson(actions),
            cooldownSeconds = 0,
        )
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns listOf(rule)
        }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true) {
            coEvery { dispatch(any(), any()) } coAnswers {
                val action = firstArg<ActionDef>()
                onDispatch(action)
                if (action.kind == "delay") {
                    delay(action.payload!!.toLong())
                }
                DispatchResult(success = true)
            }
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = dispatcher,
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ctx,
            appStrings = com.bydmate.app.util.AppStrings(ctx),
        )
    }

    @Test fun `three consecutive param steps are dispatched at least 150 ms apart in order`() = runTest {
        val a1 = ActionDef("空调手动", "Manual")
        val a2 = ActionDef("设置温度24", "Temp 24")
        val a3 = ActionDef("外循环", "Fresh air")
        val log = mutableListOf<Pair<String, Long>>()

        val engine = engineWith(listOf(a1, a2, a3)) { action ->
            log += action.command to currentTime
        }
        val t0 = currentTime
        engine.onButtonPress(1)

        assertEquals(listOf("空调手动", "设置温度24", "外循环"), log.map { it.first })
        assertTrue("step 2 arrived at +${log[1].second - log[0].second}ms, expected >= 150ms", log[1].second >= t0 + 150L)
        assertTrue("step 3 arrived at +${log[2].second - log[1].second}ms, expected >= 150ms", log[2].second >= log[1].second + 150L)
        assertTrue("step 3 arrived at +${log[2].second - t0}ms, expected >= 300ms", log[2].second >= t0 + 300L)
    }

    @Test fun `user delay step between two param steps is not padded with extra 150 ms`() = runTest {
        val a1 = ActionDef("空调手动", "Manual")
        val pause = ActionDef("", "Pause", kind = "delay", payload = "1000")
        val a2 = ActionDef("设置温度24", "Temp 24")
        val log = mutableListOf<Pair<String, Long>>()

        val engine = engineWith(listOf(a1, pause, a2)) { action ->
            log += action.kind to currentTime
        }
        engine.onButtonPress(1)

        assertEquals(3, log.size)
        assertEquals(1000L, log[2].second - log[0].second)
    }
}
