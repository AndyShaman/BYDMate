package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.ui.automation.newButtonPressTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A rule that is still running (e.g. waiting in a long pause) is not started again by an
 * automatic fire; manual starts are never gated. Harness as in AutomationEngineEdgeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineRunningGateTest {

    @get:Rule val trace = TraceRecorder()

    private val stepA = ActionDef("车窗关闭", "A")
    private val pause = ActionDef("", "Пауза", kind = "delay", payload = "300000")
    private val stepB = ActionDef("车窗打开", "B")

    private fun edgeLines() = trace.events()
        .map { it.replace(Regex(" #\\d+"), "").replace(Regex(" +"), " ") }
        .filter { it.startsWith("auto rule ") }

    private val coldTrigger = TriggerDef(
        param = "ExtTemp", chineseName = "", operator = "<", value = "12", displayName = "ExtTemp"
    )

    private fun rule(triggers: List<TriggerDef>, logic: String = "AND") = RuleEntity(
        id = 1, name = "r1",
        triggerLogic = logic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(stepA, pause, stepB)),
        cooldownSeconds = 0,
    )

    private fun engineWith(r: RuleEntity, dispatcher: ActionDispatcher): AutomationEngine {
        val ruleDao = mockk<RuleDao>(relaxed = true) { coEvery { getEnabled() } returns listOf(r) }
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
            appStrings = com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()),
        )
        engine.interactiveProvider = { true }
        return engine
    }

    /** Dispatcher whose "delay" suspends on [gate]; every other action succeeds. */
    private fun gatedDispatcher(gate: CompletableDeferred<Unit>) = mockk<ActionDispatcher>(relaxed = true) {
        coEvery { dispatch(any(), any()) } coAnswers {
            if (firstArg<ActionDef>().kind == "delay") gate.await()
            DispatchResult(success = true)
        }
    }

    private suspend fun AutomationEngine.coldFront() {
        evaluate(diParsData(exteriorTemp = 25), null)
        evaluate(diParsData(exteriorTemp = 10), null)
    }

    @Test fun `automatic fire is skipped while the same rule still waits in a pause`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = gatedDispatcher(gate)
        val engine = engineWith(rule(listOf(coldTrigger)), dispatcher)

        engine.coldFront()
        coVerify(timeout = 3000, exactly = 1) { dispatcher.dispatch(stepA, any()) }

        engine.coldFront()  // second front while the first run waits
        coVerify(exactly = 1) { dispatcher.dispatch(stepA, any()) }
        assertEquals(true, edgeLines().contains("auto rule id=1 edge=skip:running src=poll"))

        gate.complete(Unit)
        coVerify(timeout = 3000, exactly = 1) { dispatcher.dispatch(stepB, any()) }
        Thread.sleep(300)  // the last step is dispatched; let the run unwind past its journal write

        engine.coldFront()  // the run is over: fires again
        coVerify(timeout = 3000, exactly = 2) { dispatcher.dispatch(stepA, any()) }
    }

    @Test fun `manual start is not gated by a running run`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = gatedDispatcher(gate)
        val engine = engineWith(rule(listOf(newButtonPressTrigger(1))), dispatcher)
        val scope = CoroutineScope(Dispatchers.Default)

        val first: Job = scope.launch { engine.onButtonPress(1) }
        coVerify(timeout = 3000, exactly = 1) { dispatcher.dispatch(stepA, any()) }
        val second: Job = scope.launch { engine.onButtonPress(1) }
        coVerify(timeout = 3000, exactly = 2) { dispatcher.dispatch(stepA, any()) }

        gate.complete(Unit)
        first.join()
        second.join()
    }

    @Test fun `a manual run also blocks the automatic path`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = gatedDispatcher(gate)
        val engine = engineWith(rule(listOf(newButtonPressTrigger(1), coldTrigger), logic = "OR"), dispatcher)
        val scope = CoroutineScope(Dispatchers.Default)

        engine.evaluate(diParsData(exteriorTemp = 25), null)  // seed
        val manual: Job = scope.launch { engine.onButtonPress(1) }
        coVerify(timeout = 3000, exactly = 1) { dispatcher.dispatch(stepA, any()) }

        engine.evaluate(diParsData(exteriorTemp = 10), null)  // front: skipped
        coVerify(exactly = 1) { dispatcher.dispatch(stepA, any()) }
        assertEquals(true, edgeLines().contains("auto rule id=1 edge=skip:running src=poll"))

        gate.complete(Unit)
        manual.join()
    }

    @Test fun `the running mark is released when a step throws`() = runBlocking {
        var first = true
        val dispatcher = mockk<ActionDispatcher>(relaxed = true) {
            coEvery { dispatch(any(), any()) } coAnswers {
                if (firstArg<ActionDef>() == stepA && first) {
                    first = false
                    error("boom")
                }
                DispatchResult(success = true)
            }
        }
        val engine = engineWith(rule(listOf(coldTrigger)), dispatcher)

        engine.coldFront()
        coVerify(timeout = 3000, exactly = 1) { dispatcher.dispatch(stepA, any()) }
        Thread.sleep(300)  // let the failed run unwind

        engine.coldFront()
        coVerify(timeout = 3000, exactly = 2) { dispatcher.dispatch(stepA, any()) }
    }
}
