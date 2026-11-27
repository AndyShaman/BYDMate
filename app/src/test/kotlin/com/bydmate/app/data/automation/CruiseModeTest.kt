package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.push.FidPushApplier
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushResult
import com.bydmate.app.helper.push.FidPushSub
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #383: ADAS ACC mode (dev=1038 fid=852492340) fills DiParsData.cruiseMode on poll and push,
 * and drives the boolean-style `cruise_active` rule condition (true when cruiseMode in 3..7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CruiseModeTest {

    @Before fun setUp() {
        FidAddresses.resetToConstants()
    }

    @After fun tearDown() {
        FidAddresses.resetToConstants()
    }

    private fun cruiseTrigger(operator: String = "==", value: String = "1") = TriggerDef(
        param = "cruise_active",
        chineseName = "",
        operator = operator,
        value = value,
        displayName = "cruise_active",
    )

    private fun setupEngine(trigger: TriggerDef): Pair<AutomationEngine, RuleDao> {
        val rule = RuleEntity(
            id = 1L,
            name = "cruise",
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
            cooldownSeconds = 0,
        )
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns listOf(rule)
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ctx,
            appStrings = com.bydmate.app.util.AppStrings(ctx),
        )
        engine.interactiveProvider = { true }
        return engine to ruleDao
    }

    private fun snapshotWithCruise(mode: Int?): DiParsData =
        diParsData().copy(cruiseMode = mode)

    @Test fun `cruise_active is true for cruiseMode 3, 5 and 7`() = runBlocking {
        for (activeMode in listOf(3, 5, 7)) {
            val (engine, ruleDao) = setupEngine(cruiseTrigger("==", "1"))
            engine.evaluate(snapshotWithCruise(2), null)
            engine.evaluate(snapshotWithCruise(activeMode), null)
            coVerify(exactly = 1) { ruleDao.updateLastTriggered(1L, any()) }
        }
    }

    @Test fun `cruise_active is false for cruiseMode 2, 0 and 8`() = runBlocking {
        for (inactiveMode in listOf(2, 0, 8)) {
            val (engine1, ruleDao1) = setupEngine(cruiseTrigger("==", "1"))
            engine1.evaluate(snapshotWithCruise(2), null)
            engine1.evaluate(snapshotWithCruise(inactiveMode), null)
            coVerify(exactly = 0) { ruleDao1.updateLastTriggered(any(), any()) }

            // Seed with inactiveMode: if it were true, SEED_FIRST_CHECK would block the transition to 3.
            val (engine2, ruleDao2) = setupEngine(cruiseTrigger("==", "1"))
            engine2.evaluate(snapshotWithCruise(inactiveMode), null)
            engine2.evaluate(snapshotWithCruise(3), null)
            coVerify(exactly = 1) { ruleDao2.updateLastTriggered(1L, any()) }
        }
    }

    @Test fun `cruise_active treats null cruiseMode as missing data (not satisfied)`() = runBlocking {
        val (engineOn, ruleDaoOn) = setupEngine(cruiseTrigger("==", "1"))
        engineOn.evaluate(snapshotWithCruise(2), null)
        engineOn.evaluate(snapshotWithCruise(null), null)
        coVerify(exactly = 0) { ruleDaoOn.updateLastTriggered(any(), any()) }

        val (engineOff, ruleDaoOff) = setupEngine(cruiseTrigger("==", "0"))
        engineOff.evaluate(snapshotWithCruise(3), null)
        engineOff.evaluate(snapshotWithCruise(null), null)
        coVerify(exactly = 0) { ruleDaoOff.updateLastTriggered(any(), any()) }
    }

    @Test fun `push event for fid 852492340 with intValue 3 sets cruiseMode 3`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val subs = slot<List<FidPushSub>>()
        coEvery { helper.pushSubscribe(any(), capture(subs)) } answers {
            subs.captured.map { FidPushResult(it.fid, it.device, FID_PUSH_OK) }
        }
        val channel = FidPushChannel(helper)
        channel.resubscribe("binder accepted") { null }

        val field = checkNotNull(channel.fieldFor(852492340)) {
            "fid 852492340 must be mapped in FidMap for push subscription"
        }
        val patched = FidPushApplier.apply(diParsData(), field, intValue = 3, doubleValue = 0.0)
        assertEquals(3, patched?.cruiseMode)
    }
}
