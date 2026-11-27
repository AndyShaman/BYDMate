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
 * #486: Bluetooth device connected / disconnected state triggers evaluated during normal polls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineBluetoothTest {

    private fun btTrigger(kind: String, address: String, name: String = "OBD Adapter") = TriggerDef(
        param = "BtDevice",
        chineseName = "",
        operator = "==",
        value = address,
        displayName = name,
        kind = kind,
    )

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param,
        chineseName = "",
        operator = op,
        value = value,
        displayName = param,
    )

    private fun rule(id: Long, trigger: TriggerDef) = RuleEntity(
        id = id,
        name = "r$id",
        triggers = TriggerDef.listToJson(listOf(trigger)),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    private fun setup(
        rule: RuleEntity,
        connectedProvider: () -> Set<String>,
    ): Pair<AutomationEngine, RuleDao> {
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
        engine.interactiveProvider = { true }
        engine.connectedBtAddresses = connectedProvider
        return engine to ruleDao
    }

    @Test fun `bt_connected seeds on first poll, fires once on connect, does not repeat while connected`() = runBlocking {
        var connected = emptySet<String>()
        val (engine, ruleDao) = setup(rule(1, btTrigger("bt_connected", "AA:BB:CC:DD:EE:FF"))) { connected }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        connected = setOf("AA:BB:CC:DD:EE:FF")
        engine.evaluate(diParsData(), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `bt_disconnected seeds while connected and fires once on disconnect`() = runBlocking {
        var connected = setOf("AA:BB:CC:DD:EE:FF")
        val (engine, ruleDao) = setup(rule(1, btTrigger("bt_disconnected", "AA:BB:CC:DD:EE:FF"))) { connected }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        connected = emptySet()
        engine.evaluate(diParsData(), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }

        engine.evaluate(diParsData(), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `bt address comparison ignores letter case`() = runBlocking {
        var connected = emptySet<String>()
        val (engine, ruleDao) = setup(rule(1, btTrigger("bt_connected", "AA:BB:CC:DD:EE:FF"))) { connected }

        engine.evaluate(diParsData(), null)
        connected = setOf("aa:bb:cc:dd:ee:ff")
        engine.evaluate(diParsData(), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `existing non-bluetooth rule evaluates unchanged with empty connected set`() = runBlocking {
        val (engine, ruleDao) = setup(rule(1, paramTrigger("TurnSignal", "==", "2"))) { emptySet() }

        engine.evaluate(diParsData(turnSignal = 1), null)
        engine.evaluate(diParsData(turnSignal = 2), null)
        engine.evaluate(diParsData(turnSignal = 2), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }
}
