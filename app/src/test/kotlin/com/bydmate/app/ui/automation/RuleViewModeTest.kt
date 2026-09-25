package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.PlaceRepository
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Списком» / «Карточками» in the Automation header: remembered across app restarts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class RuleViewModeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prefs.edit().clear().putBoolean("templates_inserted", true).commit()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): AutomationViewModel {
        val ruleDao = mockk<RuleDao>(relaxed = true)
        val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
        val placeRepository = mockk<PlaceRepository>(relaxed = true)
        every { ruleDao.getAll() } returns MutableStateFlow(emptyList())
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns MutableStateFlow<List<PlaceEntity>>(emptyList())
        return AutomationViewModel(
            ruleDao = ruleDao,
            ruleLogDao = ruleLogDao,
            placeRepository = placeRepository,
            vehicleApi = mockk(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true),
            context = ctx,
        ).also { testDispatcher.scheduler.advanceUntilIdle() }
    }

    @Test fun `a fresh install starts as a list`() {
        assertEquals(RuleViewMode.LIST, vm().uiState.value.viewMode)
    }

    @Test fun `the chosen view survives a new view model, as after an app restart`() {
        val first = vm()
        first.setViewMode(RuleViewMode.GRID)
        assertEquals(RuleViewMode.GRID, first.uiState.value.viewMode)

        assertEquals(RuleViewMode.GRID, vm().uiState.value.viewMode)

        vm().setViewMode(RuleViewMode.LIST)
        assertEquals(RuleViewMode.LIST, vm().uiState.value.viewMode)
    }

    @Test fun `an unknown or wrongly typed stored value falls back to the list`() {
        prefs.edit().putString(RuleViewMode.KEY, "TILES").commit()
        assertEquals(RuleViewMode.LIST, RuleViewMode.read(prefs))
        prefs.edit().remove(RuleViewMode.KEY).putInt(RuleViewMode.KEY, 1).commit()
        assertEquals(RuleViewMode.LIST, RuleViewMode.read(prefs))
    }

    @Test fun `the stored value goes through the backup prefs round trip unchanged`() {
        vm().setViewMode(RuleViewMode.GRID)
        val json = BackupManager.serializePrefs(mapOf("automation" to prefs.all))
        val restored = BackupManager.deserializePrefs(json)["automation"]!!
        assertEquals("GRID", restored[RuleViewMode.KEY])
    }
}
