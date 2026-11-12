package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.DiParsData
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
 * Sensors wave: the new cabin-sensor params must resolve in getParamValue so
 * edge-triggered rules can fire on them. Each case seeds false then crosses
 * the front (engine edge semantics: seed tick + firing tick).
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver
// (RECEIVER_NOT_EXPORTED permission fallback) thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineSensorParamsTest {

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param
    )

    private fun rule(id: Long, trigger: TriggerDef) = RuleEntity(
        id = id, name = "r$id",
        triggers = TriggerDef.listToJson(listOf(trigger)),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    private fun setup(rule: RuleEntity): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns listOf(rule)
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
        return engine to ruleDao
    }

    private class Case(val param: String, val op: String, val value: String, val seed: DiParsData, val firing: DiParsData)

    private val cases by lazy {
        listOf(
            Case("SeatbeltFR", "==", "0", diParsData().copy(seatbeltFR = 1), diParsData().copy(seatbeltFR = 0)),
            Case("OccupancyFL", "==", "2", diParsData().copy(occupancyFL = 1), diParsData().copy(occupancyFL = 2)),
            Case("OccupancyFR", "==", "2", diParsData().copy(occupancyFR = 1), diParsData().copy(occupancyFR = 2)),
            Case("OccupancyRL", "==", "1", diParsData().copy(occupancyRL = 2), diParsData().copy(occupancyRL = 1)),
            Case("OccupancyRM", "==", "2", diParsData().copy(occupancyRM = 1), diParsData().copy(occupancyRM = 2)),
            Case("OccupancyRR", "==", "2", diParsData().copy(occupancyRR = 1), diParsData().copy(occupancyRR = 2)),
            // numeric compare
            Case("LightLevel", "<=", "2", diParsData().copy(lightLevel = 4), diParsData().copy(lightLevel = 1)),
            Case("KeyBattery", "!=", "0", diParsData().copy(keyBatteryStatus = 0), diParsData().copy(keyBatteryStatus = 1)),
            // derived values
            Case("Rain", "!=", "0", diParsData(rain = 0), diParsData(rain = 1)),
            Case("ChargingStatus", "==", "2", diParsData(chargingStatus = 1), diParsData(chargingStatus = 2)),
        )
    }

    @Test fun `each sensor param resolves and fires on its front`() = runBlocking {
        for (c in cases) {
            val (engine, ruleDao) = setup(rule(1, paramTrigger(c.param, c.op, c.value)))
            engine.evaluate(c.seed, null)    // seed edge state: condition false
            engine.evaluate(c.firing, null)  // front: condition true -> must fire
            try {
                coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
            } catch (e: AssertionError) {
                throw AssertionError("case: ${c.param} ${c.op} ${c.value}", e)
            }
        }
    }
}
