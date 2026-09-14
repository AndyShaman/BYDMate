package com.bydmate.app.ui.tech

import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.domain.battery.AvgSoc
import com.bydmate.app.domain.battery.AvgSocProvider
import com.bydmate.app.domain.battery.BatteryState
import com.bydmate.app.data.repository.SettingsRepository
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

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        savedOrder = ""
        hintSeen = false
        hintFlagWrites = 0
    }

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

    /** The two settings rows the card order lives in, kept in memory across reads and writes. */
    private var savedOrder = ""
    private var hintSeen = false
    private var hintFlagWrites = 0

    private fun settings(): SettingsRepository = mockk<SettingsRepository>(relaxed = true).also { r ->
        coEvery { r.getTechCardOrder() } answers { savedOrder }
        coEvery { r.setTechCardOrder(any()) } answers { savedOrder = firstArg() }
        coEvery { r.isTechOrderHintSeen() } answers { hintSeen }
        coEvery { r.setTechOrderHintSeen() } answers { hintFlagWrites++; hintSeen = true }
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
        return scoped(TechPanelViewModel(repo, avgProvider, settings()))
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

        val vm = scoped(TechPanelViewModel(repo, avgProvider, settings()))
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

    /** Measured on the car: the split follows the two motor currents on a shared bus. */
    @Test
    fun `motor split follows the current ratio`() {
        assertEquals(MotorSplit.Share(15, 85), motorSplitPercent(7.9f, 45.3f))
        assertEquals(MotorSplit.Share(12, 88), motorSplitPercent(0.6f, 4.5f))
        assertEquals(MotorSplit.Share(0, 100), motorSplitPercent(0.0f, 5.7f))
        assertEquals(MotorSplit.Share(100, 0), motorSplitPercent(5.7f, 0.0f))
    }

    /** Regeneration sign is unverified, so only the magnitudes decide the split. */
    @Test
    fun `motor split ignores the sign of the currents`() {
        assertEquals(MotorSplit.Share(15, 85), motorSplitPercent(-7.9f, -45.3f))
        assertEquals(MotorSplit.Share(15, 85), motorSplitPercent(7.9f, -45.3f))
    }

    /** Parked: both motors idle, so the row shows dashes instead of an invented 50/50. */
    @Test
    fun `motor split is idle while the car draws nothing`() {
        assertEquals(MotorSplit.Idle, motorSplitPercent(0.0f, 0.0f))
        assertEquals(MotorSplit.Idle, motorSplitPercent(0.2f, 0.2f))
    }

    /** A car that does not report the pair (single motor) hides the row entirely. */
    @Test
    fun `motor split is null when either current is missing`() {
        assertNull(motorSplitPercent(null, null))
        assertNull(motorSplitPercent(1.0f, null))
        assertNull(motorSplitPercent(null, 1.0f))
    }

    /** The motor currents alone are enough to draw the motors card. */
    @Test
    fun `motor currents open the motors card`() {
        assertTrue(TechPanelUiState(motorCurrentFront = 7.9f, motorCurrentRear = 45.3f).showMotors)
    }

    // --- card order ---------------------------------------------------------

    @Test
    fun `without a saved order the cards keep their factory places`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TechCardOrder.DEFAULT, vm.uiState.value.cardOrder)
        assertTrue("first open must explain the gesture", vm.uiState.value.showOrderHint)
    }

    @Test
    fun `a saved order is restored on open`() = runTest {
        savedOrder = "tyres,motors,battery_now,limits,history,climate"
        hintSeen = true
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                TechCard.TYRES, TechCard.MOTORS, TechCard.BATTERY_NOW,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.CLIMATE,
            ),
            vm.uiState.value.cardOrder,
        )
        assertFalse("the hint is retired once a drag has happened", vm.uiState.value.showOrderHint)
    }

    /** A version that adds a card must not drop it just because the stored order predates it;
     *  an id that no longer exists must not crash the screen either. */
    @Test
    fun `cards missing from the saved order are appended, unknown ids ignored`() = runTest {
        savedOrder = "tyres,ghost_card,motors"
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                TechCard.TYRES, TechCard.MOTORS, TechCard.BATTERY_NOW,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.CLIMATE,
            ),
            vm.uiState.value.cardOrder,
        )
    }

    @Test
    fun `a drag persists the new order and retires the hint`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.moveCard(TechCard.TYRES, TechCard.BATTERY_NOW)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TechCard.TYRES, vm.uiState.value.cardOrder.first())
        assertEquals("tyres,battery_now,limits,history,motors,climate", savedOrder)
        assertTrue(hintSeen)
        assertFalse(vm.uiState.value.showOrderHint)

        // The flag is already set: a second drag must not write it again.
        vm.moveCard(TechCard.CLIMATE, TechCard.TYRES)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, hintFlagWrites)
    }

    /** Hidden cards drop out of the grid but keep their place in the stored order. */
    @Test
    fun `only the cards with data are laid out`() {
        val state = TechPanelUiState(
            cardOrder = listOf(
                TechCard.TYRES, TechCard.MOTORS, TechCard.BATTERY_NOW,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.CLIMATE,
            ),
            motorRpmFront = 1200,
            soc = 43,
        )
        assertEquals(listOf(TechCard.MOTORS, TechCard.BATTERY_NOW), state.visibleCards)
    }
}
