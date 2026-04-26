package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
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

    private class RecordingDao(
        private var maxBaseline: Double? = null
    ) : ChargeDao {
        val inserted = mutableListOf<ChargeEntity>()
        var nextId: Long = 1
        override suspend fun insert(charge: ChargeEntity): Long {
            val withId = charge.copy(id = nextId++)
            inserted += withId
            // Roll baseline forward if this insert recorded a new finish.
            withId.lifetimeKwhAtFinish?.let { maxBaseline = it }
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
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = maxBaseline
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> =
            inserted.filter { it.detectionSource?.startsWith("autoservice_") == true }
        override suspend fun hasLegacyCharges(): Boolean =
            inserted.any { it.detectionSource == null || it.detectionSource?.startsWith("autoservice_") != true }
    }

    // Deviation #1: replaced delete/thinPointsForCharge with getCount/thinOldPoints
    private object NullChargePointDao : com.bydmate.app.data.local.dao.ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    // Deviation #2: added getAll() override; also SettingEntity.value is String? so store with ?: ""
    private class FakeSettingsDao(initial: Map<String, String> = emptyMap()) : com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>().also { it.putAll(initial) }
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: com.bydmate.app.data.local.entity.SettingEntity) { map[entity.key] = entity.value ?: "" }
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
    }

    // Deviation #3: RecordingBatterySnapshotDao + real BatteryHealthRepository (no FakeBatteryHealthRepo)
    private class RecordingBatterySnapshotDao : com.bydmate.app.data.local.dao.BatterySnapshotDao {
        val inserted = mutableListOf<com.bydmate.app.data.local.entity.BatterySnapshotEntity>()
        override suspend fun insert(snapshot: com.bydmate.app.data.local.entity.BatterySnapshotEntity): Long {
            inserted += snapshot
            return inserted.size.toLong()
        }
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override fun getRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<com.bydmate.app.data.local.entity.BatterySnapshotEntity>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun getLast(): com.bydmate.app.data.local.entity.BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = inserted.size
    }

    // Deviation #4: TestSetup data class instead of Triple
    private data class TestSetup(
        val detector: AutoserviceChargingDetector,
        val chargeDao: RecordingDao,
        val snapshotDao: RecordingBatterySnapshotDao,
        val auto: FakeAutoservice
    )

    private fun build(
        battery: BatteryReading?,
        charging: ChargingReading? = ChargingReading(1, 1, 0, 1, 0L),
        baselineKwh: Double? = null,
        baselineTs: Long = 0L,
        autoserviceAvailable: Boolean = true,
        homeTariff: Double = 0.20,
        dcTariff: Double = 0.73
    ): TestSetup {
        val auto = FakeAutoservice(battery, charging, autoserviceAvailable)
        val dao = RecordingDao(maxBaseline = baselineKwh)
        val chargeRepo = ChargeRepository(dao, NullChargePointDao)
        val snapshotDao = RecordingBatterySnapshotDao()
        val healthRepo = BatteryHealthRepository(snapshotDao)
        // Pre-populate FakeSettingsDao with tariff values so that the non-open
        // getHomeTariff()/getDcTariff() methods read them correctly via getString().
        // getAutoserviceBaseline() is open, so override it on the anonymous object.
        val initialMap = buildMap {
            put(SettingsRepository.KEY_HOME_TARIFF, homeTariff.toString())
            put(SettingsRepository.KEY_DC_TARIFF, dcTariff.toString())
            if (baselineKwh != null) {
                put(SettingsRepository.KEY_AUTOSERVICE_BASELINE_KWH, baselineKwh.toString())
                put(SettingsRepository.KEY_AUTOSERVICE_BASELINE_TS, baselineTs.toString())
            }
        }
        val settingsDao = FakeSettingsDao(initialMap)
        val settings = SettingsRepository(settingsDao)
        val baselineStore = ChargingBaselineStore(chargeRepo, settings)
        val classifier = ChargingTypeClassifier()
        val detector = AutoserviceChargingDetector(
            client = auto,
            chargeRepo = chargeRepo,
            batteryHealthRepo = healthRepo,
            baselineStore = baselineStore,
            classifier = classifier,
            settings = settings
        )
        return TestSetup(detector, dao, snapshotDao, auto)
    }

    @Test
    fun `cold start records baseline without creating a session`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 602.7f,
            lifetimeMileageKm = 2091f, voltage12v = 14.0f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = null)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.BASELINE_INITIALIZED, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `lifetime_kwh delta below threshold does NOT create a session`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 600.4f,  // baseline 600.0 → delta 0.4 < 0.5
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = 600.0)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `lifetime_kwh delta of 8 kWh creates one session marked autoservice_catchup`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,  // baseline 600 → delta 8
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val charging = ChargingReading(
            gunConnectState = 1,  // already disconnected (catch-up scenario)
            chargingType = 1, chargeBatteryVoltV = 0, batteryType = 1, readAtMs = 1000L
        )
        val setup = build(battery, charging, baselineKwh = 600.0)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        assertEquals("COMPLETED", ch.status)
        assertEquals(8.0, ch.kwhCharged!!, 0.01)
        assertEquals(600.0, ch.lifetimeKwhAtStart!!, 0.01)
        assertEquals(608.0, ch.lifetimeKwhAtFinish!!, 0.01)
        assertEquals("autoservice_catchup", ch.detectionSource)
        assertNull(ch.gunState)  // gun=1 (NONE) → null in DB per spec:130
        // Heuristic: 8 kWh in <1h would be DC, but we cannot know hours from a snapshot
        // alone. The detector uses a duration assumption (see implementation). Type may
        // be AC or DC depending on the heuristic — we only assert it's one of them.
        assertTrue(ch.type == "AC" || ch.type == "DC")
        assertNotNull(ch.cost)
        // AC heuristic → home tariff 0.20 → cost = 8.0 * 0.20 = 1.60
        assertEquals(1.60, ch.cost!!, 0.01)
    }

    @Test
    fun `gun_state DC during catch-up overrides heuristic`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        // gun still inserted as DC (rare: user comes back to plug-in DC and we catch-up before disconnect)
        val charging = ChargingReading(
            gunConnectState = 3, chargingType = 4, chargeBatteryVoltV = 700,
            batteryType = 1, readAtMs = 1000L
        )
        val setup = build(battery, charging, baselineKwh = 600.0)

        setup.detector.runCatchUp(now = 1500L)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("DC", ch.type)
        assertEquals(3, ch.gunState)
        // DC gun → dc tariff 0.73 → cost = 8.0 * 0.73 = 5.84
        assertEquals(5.84, ch.cost!!, 0.01)
    }

    @Test
    fun `session creation also records BatterySnapshot when delta SOC is 5 or more`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = 600.0)
        // Configure baseline + last seen SOC of 80 so delta = 91 - 80 = 11 >= 5.
        setup.detector.recordLastSeenSoc(80)

        setup.detector.runCatchUp(now = 1500L)

        // BatterySnapshot recorded with SOC delta and capacity calculation.
        assertEquals(1, setup.snapshotDao.inserted.size)
        val snap = setup.snapshotDao.inserted.single()
        assertEquals(80, snap.socStart)
        assertEquals(91, snap.socEnd)
        assertEquals(8.0, snap.kwhCharged, 0.01)
        // calculateCapacity = 8 / (91-80) * 100 = 72.7 kWh
        assertEquals(72.7, snap.calculatedCapacityKwh!!, 0.1)
    }

    @Test
    fun `BatterySnapshot is NOT recorded when SOC delta is below 5`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = 600.0)
        setup.detector.recordLastSeenSoc(89)  // delta = 2

        setup.detector.runCatchUp(now = 1500L)

        assertEquals(0, setup.snapshotDao.inserted.size)
    }

    @Test
    fun `runCatchUp returns AUTOSERVICE_UNAVAILABLE when client is down`() = runTest {
        val setup = build(battery = null, autoserviceAvailable = false)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `runCatchUp returns SENTINEL when lifetime_kwh comes back null`() = runTest {
        val battery = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = null,  // sentinel
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery, baselineKwh = 600.0)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SENTINEL, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `subsequent catch-up after a session uses the new baseline`() = runTest {
        // Deviation #5: no reflection — mutate FakeAutoservice.battery directly
        val battery1 = BatteryReading(
            sohPercent = 100f, socPercent = 91f, lifetimeKwh = 608.0f,
            lifetimeMileageKm = 2091f, voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(battery1, baselineKwh = 600.0)
        setup.detector.runCatchUp(now = 1500L)
        assertEquals(1, setup.chargeDao.inserted.size)

        // Next session: lifetime_kwh now 612 (was 608). delta = 4.
        // Mutate the fake's reading directly (no reflection needed).
        setup.auto.battery = battery1.copy(lifetimeKwh = 612.0f)
        setup.detector.runCatchUp(now = 2500L)

        assertEquals(2, setup.chargeDao.inserted.size)
        val second = setup.chargeDao.inserted[1]
        assertEquals(608.0, second.lifetimeKwhAtStart!!, 0.01)
        assertEquals(612.0, second.lifetimeKwhAtFinish!!, 0.01)
        assertEquals(4.0, second.kwhCharged!!, 0.01)
    }
}
