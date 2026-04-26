package com.bydmate.app.ui.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake SettingsDao ---

    private class FakeSettingsDao(autoserviceEnabled: Boolean) : SettingsDao {
        val map = mutableMapOf<String, String>(
            SettingsRepository.KEY_AUTOSERVICE_ENABLED to autoserviceEnabled.toString()
        )
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    // --- Stub DAOs ---

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

    // --- Factory ---

    private fun buildViewModel(
        autoserviceEnabled: Boolean,
        fakeAutoservice: AutoserviceClient
    ): DashboardViewModel {
        val ctx: Context = ApplicationProvider.getApplicationContext()

        val settingsDao = FakeSettingsDao(autoserviceEnabled)
        val settingsRepo = SettingsRepository(settingsDao)

        val tripDao = StubTripDao()
        val tripPointDao = StubTripPointDao()
        val tripRepo = TripRepository(tripDao, tripPointDao)

        val idleDrainDao = StubIdleDrainDao()

        val httpClient = OkHttpClient()
        val insightsManager = InsightsManager(ctx, OpenRouterClient(httpClient), tripDao, idleDrainDao, settingsRepo)

        val batteryHealthRepo = BatteryHealthRepository(StubBatterySnapshotDao())
        val batteryStateRepo = BatteryStateRepository(fakeAutoservice, batteryHealthRepo, settingsRepo)

        return DashboardViewModel(
            tripRepository = tripRepo,
            settingsRepository = settingsRepo,
            idleDrainDao = idleDrainDao,
            insightsManager = insightsManager,
            batteryStateRepository = batteryStateRepo
        )
    }

    // --- Tests ---

    private val sampleReading = BatteryReading(
        sohPercent = 100f,
        socPercent = 91f,
        lifetimeKwh = 602f,
        lifetimeMileageKm = 2091f,
        voltage12v = 14f,
        readAtMs = 0L
    )

    @Test
    fun `adbConnected is null when autoservice disabled`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = false,
            fakeAutoservice = FakeAutoservice(sampleReading, available = true)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.adbConnected)
    }

    @Test
    fun `adbConnected is true when autoservice enabled and connected`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            fakeAutoservice = FakeAutoservice(sampleReading, available = true)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, vm.uiState.value.adbConnected)
    }

    @Test
    fun `adbConnected is false when autoservice enabled but unavailable`() = runTest {
        val vm = buildViewModel(
            autoserviceEnabled = true,
            fakeAutoservice = FakeAutoservice(battery = null, available = false)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, vm.uiState.value.adbConnected)
    }
}
