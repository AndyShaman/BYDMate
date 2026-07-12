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
        )
        return engine to ruleDao
    }

    private fun assertParamFires(param: String, op: String, value: String, seed: DiParsData, firing: DiParsData) = runBlocking {
        val (engine, ruleDao) = setup(rule(1, paramTrigger(param, op, value)))
        engine.evaluate(seed, null)    // seed edge state: condition false
        engine.evaluate(firing, null)  // front: condition true -> must fire
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `SeatbeltFR resolves`() = assertParamFires(
        "SeatbeltFR", "==", "0",
        seed = diParsData().copy(seatbeltFR = 1),
        firing = diParsData().copy(seatbeltFR = 0),
    )

    @Test fun `OccupancyFL resolves`() = assertParamFires(
        "OccupancyFL", "==", "2",
        seed = diParsData().copy(occupancyFL = 1),
        firing = diParsData().copy(occupancyFL = 2),
    )

    @Test fun `OccupancyFR resolves`() = assertParamFires(
        "OccupancyFR", "==", "2",
        seed = diParsData().copy(occupancyFR = 1),
        firing = diParsData().copy(occupancyFR = 2),
    )

    @Test fun `OccupancyRL resolves`() = assertParamFires(
        "OccupancyRL", "==", "1",
        seed = diParsData().copy(occupancyRL = 2),
        firing = diParsData().copy(occupancyRL = 1),
    )

    @Test fun `OccupancyRM resolves`() = assertParamFires(
        "OccupancyRM", "==", "2",
        seed = diParsData().copy(occupancyRM = 1),
        firing = diParsData().copy(occupancyRM = 2),
    )

    @Test fun `OccupancyRR resolves`() = assertParamFires(
        "OccupancyRR", "==", "2",
        seed = diParsData().copy(occupancyRR = 1),
        firing = diParsData().copy(occupancyRR = 2),
    )

    @Test fun `LightLevel resolves with numeric compare`() = assertParamFires(
        "LightLevel", "<=", "2",
        seed = diParsData().copy(lightLevel = 4),
        firing = diParsData().copy(lightLevel = 1),
    )

    @Test fun `KeyBattery resolves`() = assertParamFires(
        "KeyBattery", "!=", "0",
        seed = diParsData().copy(keyBatteryStatus = 0),
        firing = diParsData().copy(keyBatteryStatus = 1),
    )

    @Test fun `Rain fires on derived value`() = assertParamFires(
        "Rain", "!=", "0",
        seed = diParsData(rain = 0),
        firing = diParsData(rain = 1),
    )

    @Test fun `ChargingStatus fires on derived value`() = assertParamFires(
        "ChargingStatus", "==", "2",
        seed = diParsData(chargingStatus = 1),
        firing = diParsData(chargingStatus = 2),
    )
}
