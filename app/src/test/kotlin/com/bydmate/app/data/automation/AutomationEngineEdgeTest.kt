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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edge-semantics regressions from issue #51: rules firing right after the user
 * saves an edit, deferred fires after cooldown / park gating, and service_start
 * racing cold-start nulls in the very first poll tick.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver
// (RECEIVER_NOT_EXPORTED permission fallback) thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineEdgeTest {

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param
    )

    private fun serviceStartTrigger() = TriggerDef(
        param = "", chineseName = "", operator = "", value = "",
        displayName = "start", kind = "service_start"
    )

    private fun rule(
        id: Long,
        triggers: List<TriggerDef>,
        logic: String = "AND",
        cooldown: Int = 0,
        requirePark: Boolean = false,
        lastTriggeredAt: Long? = null,
    ) = RuleEntity(
        id = id, name = "r$id",
        triggerLogic = logic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification_silent"))),
        cooldownSeconds = cooldown,
        requirePark = requirePark,
        lastTriggeredAt = lastTriggeredAt,
    )

    private fun setup(rulesProvider: () -> List<RuleEntity>): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } answers { rulesProvider() }
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
        )
        return engine to ruleDao
    }

    @Test fun `editing rule triggers reseeds edge state instead of firing`() = runBlocking {
        var current = rule(1, listOf(paramTrigger("Speed", ">", "40")))
        val (engine, ruleDao) = setup { listOf(current) }

        engine.evaluate(diParsData(speed = 10), null)  // seed: false under OLD triggers

        // User edits the rule: condition is now instantly true. Must reseed, not fire.
        current = current.copy(triggers = TriggerDef.listToJson(listOf(paramTrigger("ExtTemp", ">", "20"))))
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        // A genuine front after the reseed still fires.
        engine.evaluate(diParsData(exteriorTemp = 10), null)
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `front while park-gated is consumed, not deferred until parked`() = runBlocking {
        val r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23")), requirePark = true)
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(gear = 1, exteriorTemp = 10), null)  // parked, cold: seed false
        engine.evaluate(diParsData(gear = 4, exteriorTemp = 25), null)  // front happens while driving
        engine.evaluate(diParsData(gear = 1, exteriorTemp = 25), null)  // parked again, still hot

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `front during cooldown is consumed, not deferred until cooldown expires`() = runBlocking {
        var r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23")), cooldown = 600)
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(exteriorTemp = 10), null)  // seed false, no cooldown yet

        r = r.copy(lastTriggeredAt = System.currentTimeMillis())  // cooldown engaged
        engine.evaluate(diParsData(exteriorTemp = 25), null)      // front during cooldown

        r = r.copy(lastTriggeredAt = System.currentTimeMillis() - 700_000)  // cooldown over
        engine.evaluate(diParsData(exteriorTemp = 25), null)      // still true, no NEW front

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start AND-rule fires when param warms up on a later tick`() = runBlocking {
        val r = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")), logic = "AND")
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(exteriorTemp = null), null)  // first tick: data still cold
        engine.evaluate(diParsData(exteriorTemp = 25), null)    // within start window: must fire

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires exactly once per rule`() = runBlocking {
        val r = rule(1, listOf(serviceStartTrigger()))
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(soc = 50), null)
        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }
}
