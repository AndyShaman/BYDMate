package com.bydmate.app.ui.tech

import com.bydmate.app.domain.battery.AvgSoc
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryState
import com.bydmate.app.domain.battery.BatteryStateRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TechPanelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        battery: BatteryState? = null,
        avg: AvgSoc = AvgSoc(null, null),
    ): TechPanelViewModel {
        val repo = mockk<BatteryStateRepository>()
        coEvery { repo.refresh() } returns (
            battery ?: BatteryState(null, null, null, null, null, autoserviceAvailable = false)
            )
        val avgProvider = mockk<AvgSocProvider>()
        coEvery { avgProvider.compute(any()) } returns avg
        return TechPanelViewModel(repo, avgProvider)
    }

    // --- null mapping -------------------------------------------------------

    /** A firmware that answers nothing leaves every card hidden, which is what drives the
     *  «Машина не отдаёт эти данные» placeholder. */
    @Test
    fun `without any live value every card is hidden`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.showBatteryNow)
        assertFalse(state.showLimitsAndCells)
        assertFalse(state.showMotors)
        assertFalse(state.showClimate)
        assertFalse(state.showTyres)
        assertFalse(state.showHistory)
        assertFalse(state.hasAnyCard)
        assertEquals(false, state.autoserviceOnline)
    }

    /** SoH and the lifetime counters come from the one-shot autoservice read on open. */
    @Test
    fun `battery state read on open fills SoH, lifetime and the online flag`() = runTest {
        val vm = buildViewModel(
            battery = BatteryState(
                socNow = 43f, voltage12v = 13.9f, sohPercent = 99f,
                lifetimeKm = 3802f, lifetimeKwh = 1240f, autoserviceAvailable = true,
            ),
            avg = AvgSoc(sinceCharge = 52, allTime = 58),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(99f, state.soh)
        assertEquals(3802f, state.lifetimeKm)
        assertEquals(1240f, state.lifetimeKwh)
        assertEquals(52, state.avgSocSinceCharge)
        assertEquals(58, state.avgSocAllTime)
        assertEquals(true, state.autoserviceOnline)
        assertTrue("SoH alone must open the battery card", state.showBatteryNow)
        assertTrue(state.showHistory)
        assertTrue(state.hasAnyCard)
        // Nothing live arrived, so the vehicle-side cards stay hidden.
        assertFalse(state.showMotors)
        assertFalse(state.showTyres)
    }

    /** A failing autoservice read must not blank the screen — it just reports offline. */
    @Test
    fun `failed battery read reports offline`() = runTest {
        val repo = mockk<BatteryStateRepository>()
        coEvery { repo.refresh() } throws IllegalStateException("adb down")
        val avgProvider = mockk<AvgSocProvider>()
        coEvery { avgProvider.compute(any()) } returns AvgSoc(null, null)

        val vm = TechPanelViewModel(repo, avgProvider)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.autoserviceOnline)
    }

    // --- hints --------------------------------------------------------------

    @Test
    fun `only one hint is open at a time`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.openHint)
        vm.toggleHint("insulation")
        assertEquals("insulation", vm.uiState.value.openHint)
        vm.toggleHint("cells")
        assertEquals("cells", vm.uiState.value.openHint)
        vm.toggleHint("cells")
        assertNull("tapping the open hint closes it", vm.uiState.value.openHint)
    }

    // --- derived values -----------------------------------------------------

    /** 1% of a 72.9 kWh pack is 729 W·h, so 1458 W drains 2%/h. */
    @Test
    fun `percent per hour follows power over capacity`() {
        val state = TechPanelUiState(batteryPowerW = 1458.0, batteryCapacityKwh = 72.9)
        assertEquals(2.0, state.percentPerHour!!, 0.0001)
    }

    /** Charging is negative power (#153) and keeps its sign in %/h. */
    @Test
    fun `percent per hour keeps the charging sign`() {
        val state = TechPanelUiState(batteryPowerW = -7290.0, batteryCapacityKwh = 72.9)
        assertEquals(-10.0, state.percentPerHour!!, 0.0001)
    }

    @Test
    fun `percent per hour is unknown without a capacity or without power`() {
        assertNull(TechPanelUiState(batteryPowerW = 1458.0).percentPerHour)
        assertNull(TechPanelUiState(batteryPowerW = 1458.0, batteryCapacityKwh = 0.0).percentPerHour)
        assertNull(TechPanelUiState(batteryCapacityKwh = 72.9).percentPerHour)
    }

    @Test
    fun `cell delta needs both ends`() {
        assertEquals(0.011, TechPanelUiState(cellMin = 3.301, cellMax = 3.312).cellDelta!!, 0.0001)
        assertNull(TechPanelUiState(cellMin = 3.301).cellDelta)
        assertNull(TechPanelUiState(cellMax = 3.312).cellDelta)
    }

    /** One live reading is enough to draw its card; the others stay hidden. */
    @Test
    fun `a single live reading opens only its own card`() {
        val state = TechPanelUiState(compressorW = 576)
        assertTrue(state.showClimate)
        assertFalse(state.showBatteryNow)
        assertFalse(state.showMotors)
        assertTrue(state.hasAnyCard)
    }
}
