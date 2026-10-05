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
import org.robolectric.shadows.ShadowLog

/**
 * The service_start trigger of [AutomationEngine] (#177): a new process is a car start. It fires
 * on the first evaluate() with the screen on, waits for the screen when the process came up dark,
 * and fires each rule once per process. A new engine is a new process.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver
// (RECEIVER_NOT_EXPORTED permission fallback) thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineServiceStartTest {

    private class HeadUnit(var elapsed: Long = 3_600_000L, var screenOn: Boolean = true)

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param
    )

    private fun serviceStartTrigger() = TriggerDef(
        param = "", chineseName = "", operator = "", value = "",
        displayName = "start", kind = "service_start"
    )

    private fun rule(id: Long, triggers: List<TriggerDef>, logic: String = "AND") = RuleEntity(
        id = id, name = "r$id",
        triggerLogic = logic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    private fun engine(
        unit: HeadUnit,
        r: RuleEntity = rule(1, listOf(serviceStartTrigger())),
    ): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns listOf(r)
        }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ApplicationProvider.getApplicationContext<Context>(),
            appStrings = com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()),
        )
        engine.elapsedMs = { unit.elapsed }
        engine.interactiveProvider = { unit.screenOn }
        return engine to ruleDao
    }

    private fun engineLogs() = ShadowLog.getLogsForTag("AutomationEngine").map { it.msg }

    @Test fun `service_start fires on the first evaluate with the screen on`() = runBlocking {
        val (engine, dao) = engine(HeadUnit())

        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertEquals(1, engineLogs().count { it == "service_start: fired (new process, screen on)" })
    }

    @Test fun `service_start waits for the screen when the process starts with the screen off`() = runBlocking {
        val unit = HeadUnit(screenOn = false)
        val (engine, dao) = engine(unit)

        engine.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 40_000L
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
        assertEquals(1, engineLogs().count { it == "service_start: screen off at start, waiting for screen on" })

        unit.elapsed += 60_000L
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not fire again in the same process`() = runBlocking {
        val unit = HeadUnit()
        val (engine, dao) = engine(unit)

        engine.evaluate(diParsData(soc = 50), null)
        engine.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 10_000L
        engine.evaluate(diParsData(soc = 50), null)
        // Screen off and on again in the same process: not a new start.
        unit.screenOn = false
        unit.elapsed += 60_000L
        engine.evaluate(diParsData(soc = 50), null)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires again in a new process`() = runBlocking {
        val (first, firstDao) = engine(HeadUnit())
        first.evaluate(diParsData(soc = 50), null)
        first.evaluate(diParsData(soc = 50), null)

        val (second, secondDao) = engine(HeadUnit())
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { firstDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { secondDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start AND-rule fires once when the param warms up within the window`() = runBlocking {
        val unit = HeadUnit()
        val r = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")))
        val (engine, dao) = engine(unit, r)

        engine.evaluate(diParsData(exteriorTemp = null), null)  // first tick: data still cold
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }

        unit.elapsed += 10_000L
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }

        unit.elapsed += 3_000L
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start rule disabled for a tick within the window does not fire twice`() = runBlocking {
        val unit = HeadUnit()
        val r = rule(1, listOf(serviceStartTrigger()))
        var enabled = listOf(r)
        val (engine, dao) = engine(unit, r)
        coEvery { dao.getEnabled() } answers { enabled }

        engine.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 3_000L
        enabled = emptyList()                                  // rule switched off
        engine.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 3_000L
        enabled = listOf(r)                                    // and back on, window still open
        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start AND-rule does not fire once the window is over`() = runBlocking {
        val unit = HeadUnit()
        val r = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")))
        val (engine, dao) = engine(unit, r)

        engine.evaluate(diParsData(exteriorTemp = null), null)
        unit.elapsed += AutomationEngine.SERVICE_START_WINDOW_MS + 1L
        engine.evaluate(diParsData(exteriorTemp = 25), null)

        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start dump line shows the screen and whether it fired`() = runBlocking {
        val unit = HeadUnit(screenOn = false)
        val (engine, _) = engine(unit)
        engine.evaluate(diParsData(soc = 50), null)
        assertEquals("service_start: interactive=false fired=false", engine.serviceStartDumpLine())

        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)
        assertEquals("service_start: interactive=true fired=true", engine.serviceStartDumpLine())
    }

    // Android 10 a11y recovery (Atto 3): the app force-stops itself and RECOVER_START brings a new
    // process 11-44 s later. That process is the same car start, not a new one.

    @Test fun `service_start does not fire in the process our a11y recovery restarted`() = runBlocking {
        val unit = HeadUnit()
        val (first, firstDao) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 13_000L
        first.markSelfRestart()

        unit.elapsed += 30_000L
        val (second, secondDao) = engine(unit)
        second.evaluate(diParsData(soc = 50), null)
        unit.elapsed += 3_000L
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { firstDao.updateLastTriggered(1, any()) }
        coVerify(exactly = 0) { secondDao.updateLastTriggered(any(), any()) }
        assertEquals(1, engineLogs().count { it.startsWith("service_start: self-restart mark written") })
        assertEquals(1, engineLogs().count { it.startsWith("service_start: suppressed, our own a11y recovery restart") })
        assertEquals("service_start: interactive=true fired=true", second.serviceStartDumpLine())
    }

    @Test fun `service_start fires when the self-restart mark is older than the bound`() = runBlocking {
        val unit = HeadUnit()
        val (first, _) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        first.markSelfRestart()

        unit.elapsed += AutomationEngine.SELF_RESTART_MAX_GAP_MS + 1L
        val (second, secondDao) = engine(unit)
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { secondDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires when the clock went backwards after the mark (real reboot)`() = runBlocking {
        val unit = HeadUnit()
        val (first, _) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        first.markSelfRestart()

        val (second, secondDao) = engine(HeadUnit(elapsed = 20_000L))
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { secondDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start self-restart mark is consumed by the first check`() = runBlocking {
        val unit = HeadUnit()
        val (first, _) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        first.markSelfRestart()

        unit.elapsed += 5_000L
        val (second, secondDao) = engine(unit)
        second.evaluate(diParsData(soc = 50), null)
        // A later process within the bound, with no recovery in between: a real start.
        unit.elapsed += 5_000L
        val (third, thirdDao) = engine(unit)
        third.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 0) { secondDao.updateLastTriggered(any(), any()) }
        coVerify(exactly = 1) { thirdDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start self-restart before any fire writes no mark`() = runBlocking {
        val unit = HeadUnit(screenOn = false)
        val (first, _) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        first.markSelfRestart()

        unit.elapsed += 20_000L
        unit.screenOn = true
        val (second, secondDao) = engine(unit)
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { secondDao.updateLastTriggered(1, any()) }
        assertEquals(0, engineLogs().count { it.startsWith("service_start: self-restart mark written") })
    }

    @Test fun `service_start fires when the recovery call returned and the process lived on`() = runBlocking {
        val unit = HeadUnit()
        val (first, _) = engine(unit)
        first.evaluate(diParsData(soc = 50), null)
        first.markSelfRestart()
        first.clearSelfRestartMark()

        unit.elapsed += 10_000L
        val (second, secondDao) = engine(unit)
        second.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { secondDao.updateLastTriggered(1, any()) }
    }
}
