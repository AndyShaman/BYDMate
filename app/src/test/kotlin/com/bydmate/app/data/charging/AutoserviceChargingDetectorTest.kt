package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoserviceChargingDetectorTest {

    // --- fakes ---

    private class FakeAutoservice(
        var battery: BatteryReading?,
        var charging: ChargingReading?,
        var available: Boolean = true
    ) : AutoserviceClient {
        override suspend fun isAvailable(): Boolean = available
        override suspend fun getInt(dev: Int, fid: Int): Int? = null
        override suspend fun getFloat(dev: Int, fid: Int): Float? = null
        override suspend fun readBatterySnapshot(): BatteryReading? = battery
        override suspend fun readChargingSnapshot(): ChargingReading? = charging
    }

    private class RecordingDao : ChargeDao {
        val inserted = mutableListOf<ChargeEntity>()
        var nextId: Long = 1
        override suspend fun insert(charge: ChargeEntity): Long {
            val withId = charge.copy(id = nextId++)
            inserted += withId
            return withId.id
        }
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(inserted.toList())
        override suspend fun getById(id: Long): ChargeEntity? = inserted.find { it.id == id }
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary = ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(inserted.lastOrNull())
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = inserted.lastOrNull()
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = null
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> =
            inserted.filter { it.detectionSource?.startsWith("autoservice_") == true }
        override suspend fun hasLegacyCharges(): Boolean =
            inserted.any { it.detectionSource == null || it.detectionSource?.startsWith("autoservice_") != true }
        override suspend fun deleteEmpty(): Int {
            val before = inserted.size
            inserted.removeAll { it.kwhCharged == null || (it.kwhCharged ?: 0.0) < 0.05 }
            return before - inserted.size
        }
        override suspend fun deletePhantomAutoserviceRows(): Int {
            val before = inserted.size
            inserted.removeAll { ch ->
                ch.detectionSource?.startsWith("autoservice_") == true &&
                        Math.abs((ch.socStart ?: 0) - (ch.socEnd ?: 0)) < 1 &&
                        (ch.kwhCharged ?: 0.0) > 1.0
            }
            return before - inserted.size
        }
        override suspend fun delete(charge: ChargeEntity) {
            inserted.removeAll { it.id == charge.id }
        }
    }

    private object NullChargePointDao : com.bydmate.app.data.local.dao.ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private class FakeSettingsDao(initial: Map<String, String> = emptyMap()) :
        com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>().also { it.putAll(initial) }
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) {
            map[entity.key] = entity.value ?: ""
        }
        override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> =
            flowOf(emptyList())
    }

    private class FakeDiParsClient(
        private val data: DiParsData? = null
    ) : DiParsClient(okhttp3.OkHttpClient()) {
        override suspend fun fetch(): DiParsData? = data
    }

    private class RecordingBatterySnapshotDao : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        val inserted = mutableListOf<com.bydmate.app.data.local.entity.BatterySnapshotEntity>()
        override suspend fun insert(snapshot: com.bydmate.app.data.local.entity.BatterySnapshotEntity): Long {
            inserted += snapshot
            return inserted.size.toLong()
        }
        override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> =
            flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> =
            flowOf(emptyList())
        override suspend fun getLast(): com.bydmate.app.data.local.entity.BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = inserted.size
    }

    private data class TestSetup(
        val detector: AutoserviceChargingDetector,
        val chargeDao: RecordingDao,
        val snapshotDao: RecordingBatterySnapshotDao,
        val stateStore: ChargingStateStore,
        val auto: FakeAutoservice
    )

    /**
     * Builds a detector with the given configuration.
     *
     * @param prevSoc          previously stored SOC (null = cold start / empty store)
     * @param prevCapacityKwh  previously stored chargingCapacityKwh (null if unknown)
     */
    private fun build(
        battery: BatteryReading?,
        charging: ChargingReading? = ChargingReading(
            gunConnectState = 1, chargingType = 1, chargeBatteryVoltV = 0,
            batteryType = 1, chargingCapacityKwh = null, bmsState = null, readAtMs = 0L
        ),
        prevSoc: Int? = null,
        prevCapacityKwh: Float? = null,
        autoserviceAvailable: Boolean = true,
        homeTariff: Double = 0.20,
        dcTariff: Double = 0.73
    ): TestSetup {
        val auto = FakeAutoservice(battery, charging, autoserviceAvailable)
        val dao = RecordingDao()
        val chargeRepo = ChargeRepository(dao, NullChargePointDao)
        val snapshotDao = RecordingBatterySnapshotDao()
        val healthRepo = BatteryHealthRepository(snapshotDao)

        val initialMap = buildMap {
            put(SettingsRepository.KEY_HOME_TARIFF, homeTariff.toString())
            put(SettingsRepository.KEY_DC_TARIFF, dcTariff.toString())
            if (prevSoc != null) {
                put(SettingsRepository.KEY_CHARGING_BASELINE_SOC, prevSoc.toString())
            }
            if (prevCapacityKwh != null) {
                put(SettingsRepository.KEY_LAST_CAPACITY_KWH, prevCapacityKwh.toString())
            }
        }
        val settingsDao = FakeSettingsDao(initialMap)
        val settings = SettingsRepository(settingsDao)
        val stateStore = ChargingStateStore(settings)
        val classifier = ChargingTypeClassifier()
        val detector = AutoserviceChargingDetector(
            client = auto,
            chargeRepo = chargeRepo,
            batteryHealthRepo = healthRepo,
            stateStore = stateStore,
            classifier = classifier,
            settings = settings,
            diParsClient = FakeDiParsClient()
        )
        return TestSetup(detector, dao, snapshotDao, stateStore, auto)
    }

    // --- helpers ---

    private fun battery(
        soc: Float,
        soh: Float = 100f,
        mileage: Float = 2091f
    ) = BatteryReading(
        sohPercent = soh,
        socPercent = soc,
        lifetimeKwh = null,          // no longer used for detection
        lifetimeMileageKm = mileage,
        voltage12v = 14.0f,
        readAtMs = 1000L
    )

    private fun charging(
        capKwh: Float? = null,
        gunState: Int = 1,
        bmsState: Int? = null
    ) = ChargingReading(
        gunConnectState = gunState,
        chargingType = 1,
        chargeBatteryVoltV = 0,
        batteryType = 1,
        chargingCapacityKwh = capKwh,
        bmsState = bmsState,
        readAtMs = 1000L
    )

    // === Test cases ===

    @Test
    fun `first run seeds state without a row`() = runTest {
        val setup = build(battery = battery(soc = 80f), prevSoc = null)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.BASELINE_INITIALIZED, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        val state = setup.stateStore.load()
        assertEquals(80, state.socPercent)
    }

    @Test
    fun `gate A SOC up and cap delta within plausible window`() = runTest {
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 8.0f),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(8.0, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_delta", ch.detectionSource)
        assertEquals(80, ch.socStart)
        assertEquals(91, ch.socEnd)
        assertNull(ch.lifetimeKwhAtStart)
        assertNull(ch.lifetimeKwhAtFinish)
    }

    @Test
    fun `gate B SOC up and cap counter reset to current`() = runTest {
        // prevCap=8.0 (last session), currentCap=6.0 → delta=-2.0 (BMS reset)
        // Gate A skipped; Gate B uses currentCap=6.0 (in range)
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 6.0f),
            prevSoc = 80,
            prevCapacityKwh = 8.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(6.0, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_session", ch.detectionSource)
    }

    @Test
    fun `gate C SOC up and cap unreliable`() = runTest {
        // Both currentCap and prevCap are null → Gate C SOC estimate
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = null),
            prevSoc = 80,
            prevCapacityKwh = null
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        // (91-80)/100 * 72.9 = 8.019
        assertEquals(8.019, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_soc_estimate", ch.detectionSource)
    }

    @Test
    fun `SOC unchanged NEVER creates a row even with cap delta`() = runTest {
        // Regression test: 91→91 SOC, cap shows 5.6 kWh — the original phantom bug
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 5.6f),
            prevSoc = 91,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `SOC went DOWN NEVER creates a row`() = runTest {
        val setup = build(
            battery = battery(soc = 88f),
            charging = charging(capKwh = 3.0f),
            prevSoc = 91,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `gate A cap delta below MIN_DELTA_KWH falls through to gate B`() = runTest {
        // prevCap=7.0, currentCap=7.2, delta=0.2 < 0.5 → Gate A skipped
        // Gate B: currentCap=7.2 in range → row with kwhCharged=7.2
        val setup = build(
            battery = battery(soc = 65f),
            charging = charging(capKwh = 7.2f),
            prevSoc = 60,
            prevCapacityKwh = 7.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(7.2, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_session", ch.detectionSource)
    }

    @Test
    fun `gate A cap delta above 200 falls through to gate C`() = runTest {
        // prevCap=0.5, currentCap=250 → delta=249.5 > 200 → Gate A fails plausibility
        // Gate B: currentCap=250 > 200 → Gate B also fails plausibility
        // Gate C: SOC estimate (91-80)/100 * 72.9 = 8.019
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 250.0f),
            prevSoc = 80,
            prevCapacityKwh = 0.5f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(8.019, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_soc_estimate", ch.detectionSource)
    }

    @Test
    fun `autoservice down returns AUTOSERVICE_UNAVAILABLE`() = runTest {
        val setup = build(battery = null, autoserviceAvailable = false)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `SOC sentinel returns SENTINEL and state NOT updated`() = runTest {
        // battery.socPercent == null
        val batteryNoSoc = BatteryReading(
            sohPercent = 100f, socPercent = null,
            lifetimeKwh = 600f, lifetimeMileageKm = 2091f,
            voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery = batteryNoSoc, prevSoc = 80)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SENTINEL, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        // State should NOT have rolled forward — prevSoc stays at 80
        val state = setup.stateStore.load()
        assertEquals(80, state.socPercent)
    }

    @Test
    fun `gate A gun DC overrides AC heuristic`() = runTest {
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = 3,   // DC gun still inserted
                chargingType = 4, chargeBatteryVoltV = 700,
                batteryType = 1, chargingCapacityKwh = 8.0f, bmsState = 1, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f,
            dcTariff = 0.73
        )

        setup.detector.runCatchUp(now = 1500L)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("DC", ch.type)
        assertEquals(3, ch.gunState)
        // cost = 8.0 * 0.73 = 5.84
        assertEquals(5.84, ch.cost!!, 0.01)
        assertEquals("autoservice_cap_delta", ch.detectionSource)
    }

    @Test
    fun `BatterySnapshot recorded when SOC delta is 5 or more`() = runTest {
        // socStart=80, socEnd=91, delta=11 >= 5 → snapshot inserted
        val setup = build(
            battery = battery(soc = 91f, soh = 95f),
            charging = charging(capKwh = 8.0f),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        setup.detector.runCatchUp(now = 1500L)

        assertEquals(1, setup.snapshotDao.inserted.size)
        val snap = setup.snapshotDao.inserted.single()
        assertEquals(80, snap.socStart)
        assertEquals(91, snap.socEnd)
        assertEquals(8.0, snap.kwhCharged, 0.01)
        // calculateCapacity = 8.0 / (11) * 100 = 72.7
        assertEquals(72.7, snap.calculatedCapacityKwh!!, 0.5)
    }

    @Test
    fun `BatterySnapshot NOT recorded when SOC delta is less than 5`() = runTest {
        // socStart=89, socEnd=91, delta=2 < 5 → no snapshot
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 1.5f),
            prevSoc = 89,
            prevCapacityKwh = 0.0f
        )

        setup.detector.runCatchUp(now = 1500L)

        assertEquals(0, setup.snapshotDao.inserted.size)
    }

    @Test
    fun `subsequent catch-up state rolls forward`() = runTest {
        // First session: prevCap=0.0 → currentCap=8.0, socDelta=80→91 → Gate A: delta=8.0
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 8.0f),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val r1 = setup.detector.runCatchUp(now = 1500L)
        assertEquals(CatchUpOutcome.SESSION_CREATED, r1.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        assertEquals(8.0, r1.deltaKwh!!, 0.01)

        // After first session, state is (soc=91, cap=8.0)
        // Second session: SOC 91→95, cap 8.0→10.0 → Gate A: delta=2.0
        setup.auto.battery = battery(soc = 95f)
        setup.auto.charging = charging(capKwh = 10.0f)

        val r2 = setup.detector.runCatchUp(now = 2500L)
        assertEquals(CatchUpOutcome.SESSION_CREATED, r2.outcome)
        assertEquals(2, setup.chargeDao.inserted.size)
        val second = setup.chargeDao.inserted[1]
        assertEquals(2.0, second.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_delta", second.detectionSource)
        // State should now be (soc=95, cap=10.0)
        val state = setup.stateStore.load()
        assertEquals(95, state.socPercent)
        assertEquals(10.0f, state.capacityKwh!!, 0.01f)
    }

    @Test
    fun `observed power 1_8 kW classifies as AC`() = runTest {
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 0.8f, gunState = 1),
            prevSoc = 90,
            prevCapacityKwh = 0.0f
        )

        setup.detector.runCatchUp(now = 1500L, observedKwAbs = 1.8)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("AC", ch.type)
    }

    @Test
    fun `observed power 50 kW classifies as DC`() = runTest {
        val setup = build(
            battery = battery(soc = 75f),
            charging = charging(capKwh = 30.0f, gunState = 1),
            prevSoc = 30,
            prevCapacityKwh = 0.0f
        )

        setup.detector.runCatchUp(now = 1500L, observedKwAbs = 50.0)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("DC", ch.type)
    }
}
