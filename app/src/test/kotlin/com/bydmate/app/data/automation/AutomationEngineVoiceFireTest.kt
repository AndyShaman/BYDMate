package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SDK 33: lower levels reject ContextCompat.registerReceiver (RECEIVER_NOT_EXPORTED)
// thrown from the engine's init — same as AutomationEngineEdgeTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineVoiceFireTest {

    private fun rule(
        id: Long = 7,
        enabled: Boolean = true,
        actions: List<ActionDef>,
        requirePark: Boolean = false,
        confirm: Boolean = false,
    ) = RuleEntity(
        id = id, name = "voice rule", enabled = enabled, triggerLogic = "AND",
        triggers = """[{"param":"Voice","chineseName":"语音","operator":"==","value":"навигатор","displayName":"навигатор","kind":"voice"}]""",
        actions = ActionDef.listToJson(actions),
        requirePark = requirePark, confirmBeforeExecute = confirm,
    )

    private fun engine(ruleDao: RuleDao, dispatcher: ActionDispatcher): AutomationEngine {
        val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
        val placeRepo = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() }
        val netMon = mockk<NetworkAvailableMonitor> {
            every { lastAvailableAt } returns 0L
            every { probePending } returns false
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return AutomationEngine(ruleDao, ruleLogDao, dispatcher, placeRepo, netMon, ctx)
    }

    private val appAction = ActionDef(
        command = "", displayName = "Навигатор", kind = "app_launch",
        payload = """{"packageName":"ru.yandex.yandexnavi"}"""
    )

    @Test fun `missing rule returns NotFound`() = runBlocking {
        val ruleDao = mockk<RuleDao> { coEvery { getById(7) } returns null }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        assertEquals(VoiceFireResult.NotFound, engine(ruleDao, dispatcher).fireVoiceRule(7, null))
    }

    @Test fun `app_launch fires and dispatches`() = runBlocking {
        val r = rule(actions = listOf(appAction))
        val ruleDao = mockk<RuleDao> {
            coEvery { getById(7) } returns r
            coJustRun { updateLastTriggered(any(), any()) }
        }
        val dispatcher = mockk<ActionDispatcher> {
            coEvery { dispatch(any(), any()) } returns DispatchResult(true, null)
        }
        val res = engine(ruleDao, dispatcher).fireVoiceRule(7, diParsData())
        assertEquals(VoiceFireResult.Fired(true), res)
        coVerify { dispatcher.dispatch(match { it.kind == "app_launch" }, any()) }
    }

    @Test fun `requirePark and not parked returns ParkRequired`() = runBlocking {
        val r = rule(actions = listOf(appAction), requirePark = true)
        val ruleDao = mockk<RuleDao> { coEvery { getById(7) } returns r }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val driving = diParsData(gear = 4) // D
        assertEquals(VoiceFireResult.ParkRequired, engine(ruleDao, dispatcher).fireVoiceRule(7, driving))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun `window-open action with null snapshot returns SpeedUnknown`() = runBlocking {
        val windowAction = ActionDef(command = "车窗全开", displayName = "Открыть окна", kind = "param")
        val r = rule(actions = listOf(windowAction))
        val ruleDao = mockk<RuleDao> { coEvery { getById(7) } returns r }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        assertEquals(VoiceFireResult.SpeedUnknown, engine(ruleDao, dispatcher).fireVoiceRule(7, null))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // After the T12 predicate split 天窗 is no longer an isWindowOpenCommand subject —
    // the fail-closed guard must also check isSunroofOpenCommand.
    @Test fun `sunroof-open action with null snapshot returns SpeedUnknown`() = runBlocking {
        val sunroofAction = ActionDef(command = "天窗打开100", displayName = "Открыть люк", kind = "param")
        val r = rule(actions = listOf(sunroofAction))
        val ruleDao = mockk<RuleDao> { coEvery { getById(7) } returns r }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        assertEquals(VoiceFireResult.SpeedUnknown, engine(ruleDao, dispatcher).fireVoiceRule(7, null))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }
}
