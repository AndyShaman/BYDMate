package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiPlusDbReader
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.service.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake DAOs ---

    private class FakeSettingsDao(autoserviceEnabled: Boolean = false) : SettingsDao {
        val map = mutableMapOf<String, String>(
            SettingsRepository.KEY_AUTOSERVICE_ENABLED to autoserviceEnabled.toString(),
            SettingsRepository.KEY_CHARGING_PROMPT_ENABLED to "true"
        )
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private class StubTripDao : TripDao {
        override suspend fun insert(trip: TripEntity): Long = 0L
        override suspend fun update(trip: TripEntity) {}
        override fun getAll(): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): TripEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary = TripSummary(0.0, 0.0)
        override fun getLastTrip(): Flow<TripEntity?> = flowOf(null)
        override fun getRecent(limit: Int): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getCount(): Int = 0
        override suspend fun getByBydId(bydId: Long): TripEntity? = null
        override suspend fun getTripsWithoutSoc(): List<TripEntity> = emptyList()
        override suspend fun getTripsWithoutCost(): List<TripEntity> = emptyList()
        override suspend fun getPeriodSummary(from: Long, to: Long): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getLiveTrips(): List<TripEntity> = emptyList()
        override suspend fun getByStartTsRange(minTs: Long, maxTs: Long): TripEntity? = null
        override suspend fun getAllSnapshot(): List<TripEntity> = emptyList()
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteZeroKmTrips(): Int = 0
        override suspend fun getTripsForCapacityEstimate(minSocDelta: Int, limit: Int): List<TripEntity> = emptyList()
        override suspend fun getRecentSummary(maxTrips: Int): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getRecentForEma(limit: Int): List<TripEntity> = emptyList()
        override suspend fun getForEmaSince(fromTs: Long): List<TripEntity> = emptyList()
        override suspend fun getRecentForEmaFiltered(minKm: Double, limit: Int): List<TripEntity> = emptyList()
    }

    private class StubTripPointDao : TripPointDao {
        override suspend fun insertAll(points: List<TripPointEntity>) {}
        override suspend fun getByTripId(tripId: Long): List<TripPointEntity> = emptyList()
        override suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
        override suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity> = emptyList()
        override suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int = 0
        override suspend fun insert(point: TripPointEntity): Long = 0L
    }

    private class StubChargeDao : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary = ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = null
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> = emptyList()
        override suspend fun hasLegacyCharges(): Boolean = false
    }

    private class StubChargePointDao : ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private class StubIdleDrainDao : IdleDrainDao {
        override suspend fun insert(drain: IdleDrainEntity): Long = 0L
        override suspend fun update(drain: IdleDrainEntity) {}
        override fun getByDateRange(from: Long, to: Long): Flow<List<IdleDrainEntity>> = flowOf(emptyList())
        override suspend fun getTodayDrainKwh(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getCount(): Int = 0
        override suspend fun getTotalKwh(): Double = 0.0
        override suspend fun deleteAll() {}
        override suspend fun getTodayDrainHours(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getKwhSince(since: Long): Double = 0.0
        override suspend fun getHoursSince(since: Long): Double = 0.0
        override suspend fun getKwhBetween(from: Long, to: Long): Double = 0.0
    }

    private class StubBatterySnapshotDao : BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0L
        override suspend fun getLast(): BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = 0
    }

    // --- Fake AutoserviceClient ---

    private class FakeAutoservice(
        private val battery: BatteryReading?,
        private val available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = null
    }

    private class FakeAdbClient : AdbOnDeviceClient {
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = false
        override suspend fun exec(cmd: String): String? = null
        override suspend fun shutdown() {}
    }

    // --- Factory ---

    private fun buildViewModel(
        autoserviceEnabled: Boolean,
        fakeAutoservice: AutoserviceClient
    ): SettingsViewModel {
        val ctx: Context = ApplicationProvider.getApplicationContext()

        val settingsDao = FakeSettingsDao(autoserviceEnabled)
        val settingsRepo = SettingsRepository(settingsDao)

        val tripDao = StubTripDao()
        val tripPointDao = StubTripPointDao()
        val tripRepo = TripRepository(tripDao, tripPointDao)

        val chargeRepo = ChargeRepository(StubChargeDao(), StubChargePointDao())
        val idleDrainDao = StubIdleDrainDao()

        val httpClient = OkHttpClient()
        val updateChecker = UpdateChecker(httpClient)

        val energyReader = EnergyDataReader(ctx)
        val historyImporter = HistoryImporter(
            ctx, energyReader, tripRepo, tripDao, tripPointDao, idleDrainDao,
            DiPlusDbReader(), settingsRepo
        )

        val openRouterClient = OpenRouterClient(httpClient)
        val insightsManager = InsightsManager(ctx, openRouterClient, tripDao, idleDrainDao, settingsRepo)

        val batteryHealthRepo = BatteryHealthRepository(StubBatterySnapshotDao())
        val batteryStateRepo = BatteryStateRepository(fakeAutoservice, batteryHealthRepo, settingsRepo)

        return SettingsViewModel(
            appContext = ctx,
            settingsRepository = settingsRepo,
            tripRepository = tripRepo,
            chargeRepository = chargeRepo,
            updateChecker = updateChecker,
            historyImporter = historyImporter,
            energyDataReader = energyReader,
            diParsClient = DiParsClient(httpClient),
            idleDrainDao = idleDrainDao,
            insightsManager = insightsManager,
            adbOnDeviceClient = FakeAdbClient(),
            batteryStateRepository = batteryStateRepo
        )
    }

    // --- Tests ---

    @Test
    fun `loadAutoserviceState_toggleOff_returnsNotEnabled`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = false,
            fakeAutoservice = FakeAutoservice(
                BatteryReading(100f, 91f, 602f, 2091f, 14f, 0L), available = true
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AutoserviceStatus.NotEnabled, vm.uiState.value.autoserviceStatus)
    }

    @Test
    fun `loadAutoserviceState_toggleOnDisconnected_returnsDisconnected`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            fakeAutoservice = FakeAutoservice(battery = null, available = false)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AutoserviceStatus.Disconnected, vm.uiState.value.autoserviceStatus)
    }

    @Test
    fun `loadAutoserviceState_toggleOnAllNull_returnsAllSentinel`() = runTest {
        // socNow, lifetimeKm, lifetimeKwh all null — sentinel response
        val vm = buildViewModel(
            autoserviceEnabled = true,
            fakeAutoservice = FakeAutoservice(
                BatteryReading(
                    sohPercent = null,
                    socPercent = null,
                    lifetimeKwh = null,
                    lifetimeMileageKm = null,
                    voltage12v = 14f,
                    readAtMs = 0L
                ),
                available = true
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AutoserviceStatus.AllSentinel, vm.uiState.value.autoserviceStatus)
    }

    @Test
    fun `loadAutoserviceState_toggleOnWithData_returnsConnected`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            fakeAutoservice = FakeAutoservice(
                BatteryReading(100f, 91f, 602f, 2091f, 14f, 0L), available = true
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val status = vm.uiState.value.autoserviceStatus
        assertTrue(status is AutoserviceStatus.Connected)
        val connected = status as AutoserviceStatus.Connected
        assertEquals(91f, connected.socNow!!, 0.01f)
        assertEquals(100f, connected.sohPercent!!, 0.01f)
        assertEquals(2091f, connected.lifetimeKm!!, 0.01f)
        assertEquals(602f, connected.lifetimeKwh!!, 0.01f)
    }

    @Test
    fun `enableAutoservice_persistsAndReloads`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = false,
            fakeAutoservice = FakeAutoservice(
                BatteryReading(100f, 91f, 602f, 2091f, 14f, 0L), available = true
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AutoserviceStatus.NotEnabled, vm.uiState.value.autoserviceStatus)
        assertFalse(vm.uiState.value.autoserviceEnabled)

        vm.enableAutoservice(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.autoserviceEnabled)
        // After enable, BatteryStateRepository sees autoservice enabled = true and returns data
        assertTrue(vm.uiState.value.autoserviceStatus is AutoserviceStatus.Connected)
    }
}
