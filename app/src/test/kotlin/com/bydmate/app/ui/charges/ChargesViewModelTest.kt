package com.bydmate.app.ui.charges

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class ChargesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Fake DAOs ────────────────────────────────────────────────────────────

    private class FakeSettingsDao(
        autoserviceEnabled: Boolean = false,
        batteryCapacity: String = "72.9"
    ) : SettingsDao {
        val map = mutableMapOf(
            SettingsRepository.KEY_AUTOSERVICE_ENABLED to autoserviceEnabled.toString(),
            SettingsRepository.KEY_BATTERY_CAPACITY to batteryCapacity,
            SettingsRepository.KEY_CURRENCY to "BYN"
        )
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private class FakeChargeDao(
        private val chargesFlow: MutableStateFlow<List<ChargeEntity>> = MutableStateFlow(emptyList()),
        private val autoserviceCharges: List<ChargeEntity> = emptyList(),
        private val legacyExists: Boolean = false
    ) : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = chargesFlow
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> =
            flowOf(chargesFlow.value.filter { it.startTs in from..to })
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary =
            ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = null
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> = autoserviceCharges
        override suspend fun hasLegacyCharges(): Boolean = legacyExists
        override suspend fun deleteEmpty(): Int {
            val before = chargesFlow.value
            val after = before.filter { it.kwhCharged != null && it.kwhCharged >= 0.05 }
            chargesFlow.value = after
            return before.size - after.size
        }
        override suspend fun delete(charge: ChargeEntity) {
            chargesFlow.value = chargesFlow.value.filter { it.id != charge.id }
        }
    }

    private class StubChargePointDao : ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private class FakeBatterySnapshotDao(
        private val snapshots: List<BatterySnapshotEntity> = emptyList()
    ) : BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(snapshots)
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(snapshots.take(limit))
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0L
        override suspend fun getLast(): BatterySnapshotEntity? = snapshots.firstOrNull()
        override suspend fun getCount(): Int = snapshots.size
    }

    private class FakeAutoserviceClient(
        private val battery: BatteryReading?,
        private val available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = null
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    private fun buildViewModel(
        autoserviceEnabled: Boolean = false,
        chargesFlow: MutableStateFlow<List<ChargeEntity>> = MutableStateFlow(emptyList()),
        autoserviceCharges: List<ChargeEntity> = emptyList(),
        legacyExists: Boolean = false,
        batteryReading: BatteryReading? = null,
        autoserviceAvailable: Boolean = true,
        snapshots: List<BatterySnapshotEntity> = emptyList(),
        batteryCapacityKwh: String = "72.9"
    ): ChargesViewModel {
        val settingsDao = FakeSettingsDao(autoserviceEnabled, batteryCapacityKwh)
        val settingsRepo = SettingsRepository(settingsDao)

        val chargeDao = FakeChargeDao(chargesFlow, autoserviceCharges, legacyExists)
        val chargeRepo = ChargeRepository(chargeDao, StubChargePointDao())

        val snapshotDao = FakeBatterySnapshotDao(snapshots)
        val autoservice = FakeAutoserviceClient(batteryReading, autoserviceAvailable)
        val batteryHealthRepo = BatteryHealthRepository(snapshotDao)
        val batteryStateRepo = BatteryStateRepository(autoservice, batteryHealthRepo, settingsRepo)

        return ChargesViewModel(chargeRepo, snapshotDao, settingsRepo, batteryStateRepo)
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun makeCharge(
        id: Long,
        startTs: Long,
        kwhCharged: Double? = 10.0,
        gunState: Int? = 2,
        type: String? = when (gunState) { 2 -> "AC"; 3, 4 -> "DC"; else -> null },
        cost: Double? = 2.0,
        detectionSource: String? = "autoservice_detector"
    ) = ChargeEntity(
        id = id,
        startTs = startTs,
        endTs = startTs + 3_600_000L,
        kwhCharged = kwhCharged,
        gunState = gunState,
        type = type,
        cost = cost,
        detectionSource = detectionSource
    )

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `setPeriod_today_filtersChargesToToday`() = runTest {
        val today = startOfToday() + 3_600_000L
        val yesterdayTs = startOfToday() - 3_600_000L // 1 hour before midnight today

        val flow = MutableStateFlow(listOf(
            makeCharge(1, today),
            makeCharge(2, yesterdayTs)
        ))
        val vm = buildViewModel(chargesFlow = flow)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setPeriod(ChargesPeriod.TODAY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChargesPeriod.TODAY, vm.uiState.value.period)
        val allCharges = vm.uiState.value.months.flatMap { m -> m.days.flatMap { it.charges } }
        assertEquals(1, allCharges.size)
        assertEquals(1L, allCharges[0].id)
    }

    @Test
    fun `setPeriod_year_excludesChargesOlderThanCalendarYear`() = runTest {
        // Calendar-year boundaries: Jan 1 of current year. Charge dated 14 months ago
        // is BEFORE that boundary regardless of when in the year we run.
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -14)
        val fourteenMonthsAgo = cal.timeInMillis
        val recentTs = System.currentTimeMillis() - 1_000L

        val flow = MutableStateFlow(listOf(
            makeCharge(1, recentTs),
            makeCharge(2, fourteenMonthsAgo)
        ))
        val vm = buildViewModel(chargesFlow = flow)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setPeriod(ChargesPeriod.YEAR)
        testDispatcher.scheduler.advanceUntilIdle()

        val yearCharges = vm.uiState.value.months.flatMap { m -> m.days.flatMap { it.charges } }
        assertEquals(1, yearCharges.size)
        assertEquals(1L, yearCharges[0].id)

        vm.setPeriod(ChargesPeriod.ALL)
        testDispatcher.scheduler.advanceUntilIdle()

        val allCharges = vm.uiState.value.months.flatMap { m -> m.days.flatMap { it.charges } }
        assertEquals(2, allCharges.size)
    }

    @Test
    fun `setTypeFilter_AC_filtersOnlyAcCharges`() = runTest {
        val now = System.currentTimeMillis()
        val flow = MutableStateFlow(listOf(
            makeCharge(1, now - 1000, gunState = 2),   // AC
            makeCharge(2, now - 2000, gunState = 3),   // DC
            makeCharge(3, now - 3000, gunState = 4)    // GB_DC
        ))
        val vm = buildViewModel(chargesFlow = flow)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTypeFilter(ChargeTypeFilter.AC)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChargeTypeFilter.AC, vm.uiState.value.typeFilter)
        val months = vm.uiState.value.months
        val allCharges = months.flatMap { m -> m.days.flatMap { it.charges } }
        assertTrue(allCharges.all { it.gunState == 2 })
    }

    @Test
    fun `groupChargesByMonthDay_groupsCorrectly`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.APRIL, 25, 10, 0, 0)
        val ts1 = cal.timeInMillis
        cal.set(2026, Calendar.APRIL, 25, 22, 0, 0)
        val ts2 = cal.timeInMillis
        cal.set(2026, Calendar.MARCH, 15, 8, 0, 0)
        val ts3 = cal.timeInMillis

        val charges = listOf(
            makeCharge(1, ts1, kwhCharged = 30.0),
            makeCharge(2, ts2, kwhCharged = 20.0),
            makeCharge(3, ts3, kwhCharged = 15.0)
        )

        val groups = vm.groupChargesByMonthDay(charges)

        assertEquals(2, groups.size)
        // Most recent month first
        val aprilGroup = groups[0]
        assertEquals(2, aprilGroup.sessionCount)
        assertEquals(50.0, aprilGroup.totalKwh, 0.001)
        assertEquals(1, aprilGroup.days.size)
        assertEquals(2, aprilGroup.days[0].sessionCount)

        val marchGroup = groups[1]
        assertEquals(1, marchGroup.sessionCount)
        assertEquals(15.0, marchGroup.totalKwh, 0.001)
    }

    @Test
    fun `groupChargesByMonthDay_emptyList_returnsEmpty`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = vm.groupChargesByMonthDay(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toggleMonth_addsToExpanded`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleMonth("2026-04")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("2026-04" in vm.uiState.value.expandedMonths)
    }

    @Test
    fun `toggleMonth_removesFromExpanded`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleMonth("2026-04")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("2026-04" in vm.uiState.value.expandedMonths)

        vm.toggleMonth("2026-04")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse("2026-04" in vm.uiState.value.expandedMonths)
    }

    @Test
    fun `toggleDay_addsToExpanded`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleDay("2026-04-25")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("2026-04-25" in vm.uiState.value.expandedDays)
    }

    @Test
    fun `loadAutoserviceState_toggleOff_returnsEnabledFalse`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = false,
            batteryReading = BatteryReading(100f, 91f, 602f, 2091f, 14f, 0L),
            autoserviceAvailable = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.autoserviceEnabled)
        assertFalse(vm.uiState.value.autoserviceConnected)
    }

    @Test
    fun `loadAutoserviceState_toggleOnConnected_returnsEnabledTrueConnectedTrue`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            batteryReading = BatteryReading(100f, 91f, 602f, 2091f, 14f, 0L),
            autoserviceAvailable = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.autoserviceEnabled)
        assertTrue(vm.uiState.value.autoserviceConnected)
        assertFalse(vm.uiState.value.autoserviceAllSentinel)
    }

    @Test
    fun `loadAutoserviceState_toggleOnAllNull_returnsAllSentinelTrue`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            batteryReading = BatteryReading(
                sohPercent = null,
                socPercent = null,
                lifetimeKwh = null,
                lifetimeMileageKm = null,
                voltage12v = 14f,
                readAtMs = 0L
            ),
            autoserviceAvailable = true
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.autoserviceEnabled)
        assertTrue(vm.uiState.value.autoserviceConnected)
        assertTrue(vm.uiState.value.autoserviceAllSentinel)
    }

    @Test
    fun `loadLifetimeStats_zeroKwh_equivCyclesIsZero`() = runTest {
        val vm = buildViewModel(autoserviceCharges = emptyList())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.0, vm.uiState.value.equivCycles, 0.001)
    }

    @Test
    fun `loadLifetimeStats_kwhEqualsNominal_equivCyclesIsOne`() = runTest {
        val now = System.currentTimeMillis()
        val charges = listOf(
            makeCharge(1, now, kwhCharged = 72.9, gunState = 2)
        )
        val vm = buildViewModel(
            autoserviceCharges = charges,
            batteryCapacityKwh = "72.9"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.0, vm.uiState.value.equivCycles, 0.01)
    }

    @Test
    fun `loadLifetimeStats_acDcSplit_correct`() = runTest {
        val now = System.currentTimeMillis()
        val charges = listOf(
            makeCharge(1, now, kwhCharged = 30.0, gunState = 2),   // AC
            makeCharge(2, now - 1000, kwhCharged = 10.0, gunState = 3),  // DC
            makeCharge(3, now - 2000, kwhCharged = 5.0, gunState = 4)    // GB_DC
        )
        val vm = buildViewModel(autoserviceCharges = charges)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(30.0, vm.uiState.value.lifetimeAcKwh, 0.001)
        assertEquals(15.0, vm.uiState.value.lifetimeDcKwh, 0.001)
        assertEquals(45.0, vm.uiState.value.lifetimeTotalKwh, 0.001)
    }

    @Test
    fun `loadLifetimeStats_catchUpSessionsWithNullGunState_includedByType`() = runTest {
        val now = System.currentTimeMillis()
        val charges = listOf(
            makeCharge(1, now - 3000, kwhCharged = 25.0, gunState = null, type = "AC"),
            makeCharge(2, now - 2000, kwhCharged = 50.0, gunState = null, type = "DC"),
            makeCharge(3, now - 1000, kwhCharged = 10.0, gunState = 2, type = "AC")
        )
        val vm = buildViewModel(autoserviceEnabled = true, autoserviceCharges = charges)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(35.0, vm.uiState.value.lifetimeAcKwh, 0.01)
        assertEquals(50.0, vm.uiState.value.lifetimeDcKwh, 0.01)
        assertEquals(85.0, vm.uiState.value.lifetimeTotalKwh, 0.01)
    }

    @Test
    fun `hasLegacyCharges_onlyAutoservice_returnsFalse`() = runTest {
        val vm = buildViewModel(legacyExists = false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.hasLegacyCharges)
    }

    @Test
    fun `hasLegacyCharges_someLegacy_returnsTrue`() = runTest {
        val vm = buildViewModel(legacyExists = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.hasLegacyCharges)
    }

    @Test
    fun `sohSeries_populatedFromSnapshots`() = runTest {
        val snapshots = listOf(
            BatterySnapshotEntity(id = 1, timestamp = 1000L, socStart = 10, socEnd = 80,
                kwhCharged = 50.0, sohPercent = 100.0, calculatedCapacityKwh = 72.9),
            BatterySnapshotEntity(id = 2, timestamp = 2000L, socStart = 20, socEnd = 90,
                kwhCharged = 55.0, sohPercent = 99.5, calculatedCapacityKwh = 72.6)
        )
        val vm = buildViewModel(snapshots = snapshots)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.sohSeries.size)
        assertEquals(2, vm.uiState.value.capacitySeries.size)
    }

    @Test
    fun `defaultPeriod_isMonth`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChargesPeriod.MONTH, vm.uiState.value.period)
    }

    @Test
    fun `defaultTypeFilter_isAll`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChargeTypeFilter.ALL, vm.uiState.value.typeFilter)
    }
}
