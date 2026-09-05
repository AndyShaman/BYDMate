package com.bydmate.app.ui.tech

import com.bydmate.app.domain.battery.AvgSoc
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryState
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.TrackingService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Owns every view model built here so @After cancels their collectors on the static
     *  TrackingService flows — resetMain() alone would leave them running across tests. */
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After fun tearDown() {
        store.clear()
        connectedFlow().value = true
        Dispatchers.resetMain()
    }

    /** The service publishes its transport flag from inside the service; tests reach the
     *  backing MutableStateFlow directly, the way the cluster tests reach their singletons. */
    private fun connectedFlow(): MutableStateFlow<Boolean> {
        @Suppress("UNCHECKED_CAST")
        return TrackingService::class.java
            .getDeclaredField("_vehicleDataConnected")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Boolean>
    }

    /** Scoped so [store] can clear it; a bare constructor call leaks its viewModelScope. */
    private fun scoped(vm: TechPanelViewModel): TechPanelViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        return ViewModelProvider(store, factory)[TechPanelViewModel::class.java]
    }

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
        return scoped(TechPanelViewModel(repo, avgProvider))
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
    }

    /** The header cue follows the live transport flag, both ways — it must not stay on the
     *  value the on-open autoservice read happened to see. */
    @Test
    fun `online flag follows the live connection flag`() = runTest {
        val vm = buildViewModel(
            battery = BatteryState(
                socNow = 43f, voltage12v = 13.9f, sohPercent = 99f,
                lifetimeKm = 3802f, lifetimeKwh = 1240f, autoserviceAvailable = true,
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.autoserviceOnline)

        connectedFlow().value = false
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, vm.uiState.value.autoserviceOnline)

        connectedFlow().value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.autoserviceOnline)
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

    /**
     * A failing autoservice read must not blank the screen. It reports offline, and once the
     * live transport flag speaks that flag has the last word — a read that failed while the
     * bus is up must not leave the header stuck on «offline».
     */
    @Test
    fun `failed battery read reports offline while the transport is down`() = runTest {
        connectedFlow().value = false
        val repo = mockk<BatteryStateRepository>()
        coEvery { repo.refresh() } throws IllegalStateException("adb down")
        val avgProvider = mockk<AvgSocProvider>()
        coEvery { avgProvider.compute(any()) } returns AvgSoc(null, null)

        val vm = scoped(TechPanelViewModel(repo, avgProvider))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.autoserviceOnline)

        connectedFlow().value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, vm.uiState.value.autoserviceOnline)
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

    /** Idle reads -1 from the front motor fid; reverse really is negative and must survive. */
    @Test
    fun `idle rpm shows as zero and reverse keeps its sign`() {
        assertEquals("0", rpmForDisplay(-1)?.toString())
        assertEquals("0", rpmForDisplay(0)?.toString())
        assertEquals("-370", rpmForDisplay(-370)?.toString())
        assertEquals("1200", rpmForDisplay(1200)?.toString())
        assertNull(rpmForDisplay(null))
    }
}
