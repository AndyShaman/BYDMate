package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #457: testRun paces consecutive param steps by at least 150 ms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelStepPacingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
    private val placeRepository = mockk<PlaceRepository>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("ru")
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit()
            .putBoolean("templates_inserted", true)
            .putBoolean("templates_tg_report_inserted", true)
            .commit()
        every { ruleDao.getAll() } returns MutableStateFlow<List<RuleEntity>>(emptyList())
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { ruleLogDao.getLastPerRule() } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns MutableStateFlow<List<PlaceEntity>>(emptyList())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `testRun spaces two consecutive param steps by at least 150 ms`() {
        val dispatchedAt = mutableListOf<Pair<String, Long>>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            dispatchedAt += firstArg<ActionDef>().command to testDispatcher.scheduler.currentTime
            DispatchResult(success = true)
        }
        val vm = AutomationViewModel(
            ruleDao = ruleDao,
            ruleLogDao = ruleLogDao,
            placeRepository = placeRepository,
            vehicleApi = mockk(relaxed = true),
            actionDispatcher = dispatcher,
            settingsRepository = mockk(relaxed = true),
            context = ctx,
        ).also {
            it.ioDispatcher = testDispatcher
            testDispatcher.scheduler.advanceUntilIdle()
        }

        vm.openNewRule()
        vm.updateEditing {
            copy(
                actions = listOf(
                    ActionDef("空调手动", "Manual"),
                    ActionDef("设置温度24", "Temp 24"),
                )
            )
        }
        vm.testRun()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("空调手动", "设置温度24"), dispatchedAt.map { it.first })
        val gap = dispatchedAt[1].second - dispatchedAt[0].second
        assertTrue("second param dispatched after ${gap}ms, expected >= 150ms", gap >= 150L)
    }
}
