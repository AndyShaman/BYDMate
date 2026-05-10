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
        prevTs: Long? = null,
        autoserviceAvailable: Boolean = true,
        homeTariff: Double = 0.20,
        dcTariff: Double = 0.73,
        diParsData: DiParsData? = null
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
            if (prevTs != null) {
                put(SettingsRepository.KEY_LAST_STATE_TS, prevTs.toString())
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
            diParsClient = FakeDiParsClient(diParsData)
        )
        return TestSetup(detector, dao, snapshotDao, stateStore, auto)
    }

    /** Minimal DiParsData with only the SOC field populated; rest is null. */
    private fun diParsSoc(soc: Int?): DiParsData = DiParsData(
        soc = soc,
        speed = null, mileage = null, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null,
        chargingStatus = null, batteryCapacityKwh = null, totalElecConsumption = null,
        voltage12v = null, maxCellVoltage = null, minCellVoltage = null,
        exteriorTemp = null, gear = null, powerState = null, insideTemp = null,
        acStatus = null, acTemp = null, fanLevel = null, acCirc = null,
        doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = null, trunk = null, hood = null, seatbeltFL = null, lockFL = null,
        tirePressFL = null, tirePressFR = null, tirePressRL = null, tirePressRR = null,
        driveMode = null, workMode = null, autoPark = null, rain = null,
        lightLow = null, drl = null
    )

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
    fun `gate A cap delta below MIN_DELTA_KWH falls back to SOC when gate B diverges`() = runTest {
        // prevCap=7.0, currentCap=7.2, delta=0.2 < 0.5 → Gate A skipped.
        // Gate B candidate=7.2 vs socEstimate=5/100*72.9=3.645 → ratio 1.97 → SOC fallback.
        val setup = build(
            battery = battery(soc = 65f),
            charging = charging(capKwh = 7.2f),
            prevSoc = 60,
            prevCapacityKwh = 7.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(3.645, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_soc_fallback", ch.detectionSource)
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
    fun `gate A cap delta underreports versus SOC falls back to SOC estimate`() = runTest {
        // Real-world v2.5.15 regression: overnight AC 48→99 (51%), BMS counter
        // shows only 21.5 kWh because the per-session counter resets across
        // BMS pause/resume sub-phases. socEstimate = 51/100 * 72.9 = 37.179.
        // Ratio 21.5/37.179 = 0.578 < SOC_SANITY_RATIO_LOW(0.70) → SOC fallback.
        val setup = build(
            battery = battery(soc = 99f),
            charging = charging(capKwh = 21.5f),
            prevSoc = 48,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(37.179, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_soc_fallback", ch.detectionSource)
        assertEquals(48, ch.socStart)
        assertEquals(99, ch.socEnd)
    }

    @Test
    fun `gate B cap session overreports versus SOC falls back to SOC estimate`() = runTest {
        // BMS counter overruns SOC: 80→82 (2%), but cap=12 (likely stale residue).
        // socEstimate = 2/100 * 72.9 = 1.458. Ratio 12/1.458 = 8.23 → fallback.
        val setup = build(
            battery = battery(soc = 82f),
            charging = charging(capKwh = 12.0f),
            prevSoc = 80,
            prevCapacityKwh = null
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(1.458, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_soc_fallback", ch.detectionSource)
    }

    @Test
    fun `gate A cap delta within sanity ratio low boundary still trusts BMS`() = runTest {
        // socEstimate = 10/100 * 72.9 = 7.29. capDelta = 7.29 * 0.71 = 5.176 → ratio 0.71
        // just above LOW(0.70) → trust BMS.
        val setup = build(
            battery = battery(soc = 90f),
            charging = charging(capKwh = 5.176f),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(5.176, ch.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_delta", ch.detectionSource)
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
        // battery.socPercent == null AND DiPars unavailable (default null)
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
    fun `autoservice SOC sentinel falls back to DiPars and seeds baseline`() = runTest {
        // Cold-start scenario: TrackingService runs catch-up before autoservice
        // has warmed its fid cache. autoservice SOC is sentinel, but DiPars
        // (separate process) returns the cached SOC=99. Baseline must seed
        // from DiPars value, no row created.
        val batteryNoSoc = BatteryReading(
            sohPercent = 100f, socPercent = null,
            lifetimeKwh = null, lifetimeMileageKm = 2091f,
            voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(
            battery = batteryNoSoc,
            prevSoc = null,
            diParsData = diParsSoc(99)
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.BASELINE_INITIALIZED, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        val state = setup.stateStore.load()
        assertEquals(99, state.socPercent)
    }

    @Test
    fun `autoservice SOC sentinel falls back to DiPars and creates session`() = runTest {
        // Reproduces the 2026-04-30 lost-charge bug: car charged overnight,
        // pistol pulled before DiLink finished booting → autoservice SOC was
        // sentinel during catch-up. With DiPars fallback, we recover the real
        // SOC=100, see the +20 jump from prevSoc=80, and create the session.
        val batteryNoSoc = BatteryReading(
            sohPercent = 100f, socPercent = null,
            lifetimeKwh = null, lifetimeMileageKm = 2091f,
            voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(
            battery = batteryNoSoc,
            charging = charging(capKwh = 14.0f),
            prevSoc = 80,
            prevCapacityKwh = 0.0f,
            diParsData = diParsSoc(100)
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(100, ch.socEnd)
        assertEquals(80, ch.socStart)
        assertEquals(14.0, ch.kwhCharged!!, 0.01)
    }

    @Test
    fun `autoservice SOC sentinel and DiPars out-of-range still returns SENTINEL`() = runTest {
        // DiPars can return garbage values (negative or >100) on some firmware
        // states. takeIf { it in 0..100 } guard must reject them and keep the
        // SENTINEL outcome so the baseline isn't poisoned.
        val batteryNoSoc = BatteryReading(
            sohPercent = 100f, socPercent = null,
            lifetimeKwh = null, lifetimeMileageKm = 2091f,
            voltage12v = 14f, readAtMs = 1000L
        )
        val setup = build(
            battery = batteryNoSoc,
            prevSoc = 80,
            diParsData = diParsSoc(-1)
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SENTINEL, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        val state = setup.stateStore.load()
        assertEquals(80, state.socPercent)
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
        // Second session: SOC 91→95, cap 8.0→10.92 → Gate A: delta=2.92
        // socEstimate=4/100*72.9=2.916 → ratio 1.001, BMS trusted.
        setup.auto.battery = battery(soc = 95f)
        setup.auto.charging = charging(capKwh = 10.92f)

        val r2 = setup.detector.runCatchUp(now = 2500L)
        assertEquals(CatchUpOutcome.SESSION_CREATED, r2.outcome)
        assertEquals(2, setup.chargeDao.inserted.size)
        val second = setup.chargeDao.inserted[1]
        assertEquals(2.92, second.kwhCharged!!, 0.01)
        assertEquals("autoservice_cap_delta", second.detectionSource)
        // State should now be (soc=95, cap=10.92)
        val state = setup.stateStore.load()
        assertEquals(95, state.socPercent)
        assertEquals(10.92f, state.capacityKwh!!, 0.01f)
    }

    @Test
    fun `gun still connected during catch-up returns STILL_CHARGING and state NOT updated`() = runTest {
        // Reproduces the user-reported bug: car woken up while still on charger.
        // SOC grew (30 → 70) while DiLink was asleep, but the gun is still
        // physically inserted (gunConnectState=2 / AC). runCatchUp must NOT
        // create a COMPLETED row and must NOT advance the baseline — we wait
        // for the live gun-disconnect edge to finalize the session.
        val setup = build(
            battery = battery(soc = 70f),
            charging = charging(capKwh = 25.0f, gunState = 2),
            prevSoc = 30,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        assertEquals(0, setup.snapshotDao.inserted.size)
        // Baseline must stay on the pre-charge anchor so the next live edge
        // captures the full delta from the original start.
        val state = setup.stateStore.load()
        assertEquals(30, state.socPercent)
        assertEquals(0.0f, state.capacityKwh!!, 0.001f)
    }

    @Test
    fun `gun connected DC during catch-up returns STILL_CHARGING`() = runTest {
        // Same gate must trigger for any non-NONE gun state (3=DC, 4=GB_DC).
        val setup = build(
            battery = battery(soc = 70f),
            charging = charging(capKwh = 25.0f, gunState = 3),
            prevSoc = 30,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `gun null but other charging fids readable returns STILL_CHARGING`() = runTest {
        // The gun fid can sentinel-out for one read while sibling fids
        // (chargingType, batteryType, capacityKwh, bmsState) keep returning
        // valid values — that's a transient glitch on the gun fid alone,
        // not a sign that the gun is physically disconnected. Letting cascades
        // run in this state would re-open the same phantom-row bug we just
        // fixed, just on a different trigger. Block it.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = null,
                chargingType = 2, chargeBatteryVoltV = 0,
                batteryType = 1, chargingCapacityKwh = 8.0f,
                bmsState = null, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        // baseline must not advance while we're still uncertain
        val state = setup.stateStore.load()
        assertEquals(80, state.socPercent)
    }

    @Test
    fun `gun null with only bmsState readable also returns STILL_CHARGING`() = runTest {
        // Even a single readable sibling fid is enough evidence that the
        // charging device is alive and the gun fid alone glitched.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = null,
                chargingType = null, chargeBatteryVoltV = null,
                batteryType = null, chargingCapacityKwh = null,
                bmsState = 5, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `gun 0 unknown with readable siblings returns STILL_CHARGING`() = runTest {
        // gun=0 is autoservice "unknown" sentinel (matches ChargingTypeClassifier).
        // Mirror null-handling so unknown gun on a working device still defers.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = 0,
                chargingType = 2, chargeBatteryVoltV = 0,
                batteryType = 1, chargingCapacityKwh = 8.0f,
                bmsState = null, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `gun 0 unknown with ALL siblings null falls back to legacy behavior`() = runTest {
        // gun=0 must not permanently block charge logging when the entire
        // charging device is silent on this firmware — same fallback as null gun.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = 0,
                chargingType = null, chargeBatteryVoltV = null,
                batteryType = null, chargingCapacityKwh = null,
                bmsState = null, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
    }

    @Test
    fun `gun null and ALL charging fids null falls back to legacy behavior`() = runTest {
        // Firmware where the entire charging device is unsupported — keep
        // catch-up working so other BYD models don't permanently lose the
        // ability to log charges. cap=8.0 + socDelta=11 → SESSION_CREATED.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = null,
                chargingType = null, chargeBatteryVoltV = null,
                batteryType = null, chargingCapacityKwh = null,
                bmsState = null, readAtMs = 1000L
            ),
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
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
    fun `overnight AC catch-up uses real elapsed time and classifies as AC`() = runTest {
        // The exact scenario reported by users: car off in the evening, plugged
        // into AC overnight, woken up in the morning at 100%, gun pulled before
        // any DiPars power sample lands in observedKwAbs. Old code divided 30 kWh
        // by the fixed 1h heuristic → 30 kW → false DC. The fix divides by the
        // real elapsed time (8h here) → ~3.75 kW → AC.
        val eightHoursMs = 8L * 3_600_000L
        val now = 100L + eightHoursMs
        val setup = build(
            battery = battery(soc = 100f),
            charging = charging(capKwh = 30.0f, gunState = 1),
            prevSoc = 60,
            prevCapacityKwh = 0.0f,
            prevTs = 100L
        )

        setup.detector.runCatchUp(now = now)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("AC", ch.type)
    }

    @Test
    fun `short DC catch-up uses real elapsed time and classifies as DC`() = runTest {
        // Inverse of the overnight scenario: 40 kWh charged in 30 minutes —
        // unmistakably DC. Real elapsed time keeps the classification correct
        // even though no live observedKwAbs is supplied.
        val halfHourMs = 30L * 60_000L
        val now = 100L + halfHourMs
        val setup = build(
            battery = battery(soc = 90f),
            charging = charging(capKwh = 40.0f, gunState = 1),
            prevSoc = 30,
            prevCapacityKwh = 0.0f,
            prevTs = 100L
        )

        setup.detector.runCatchUp(now = now)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("DC", ch.type)
    }

    @Test
    fun `clock skew with prevTs in the future falls back to 1h heuristic`() = runTest {
        // User corrected the system clock backward between sessions →
        // raw elapsed becomes negative. We must not treat that as "almost
        // zero hours" (which would force false DC); fall back to the 1h
        // baseline so behavior matches a fresh cold start.
        val setup = build(
            battery = battery(soc = 90f),
            charging = charging(capKwh = 5.0f, gunState = 1),
            prevSoc = 80,
            prevCapacityKwh = 0.0f,
            prevTs = 10_000L
        )

        // now < prevTs by 1s → rawElapsed = -1s/3.6M = negative
        setup.detector.runCatchUp(now = 9_000L)

        val ch = setup.chargeDao.inserted.single()
        // 5 kWh / 1h = 5 kW → AC. If clock-skew handling were missing this
        // would be 5 kWh / 0.05h = 100 kW → false DC.
        assertEquals("AC", ch.type)
    }

    @Test
    fun `multi-day idle then DC charge is capped at MAX_ELAPSED_HOURS`() = runTest {
        // Pathological case: car sat 48h then took a 30 kWh DC charge.
        // Without an upper clamp, 30/48 = 0.625 kW → false AC. With the
        // 16h ceiling, 30/16 = 1.875 kW → still AC by the 15 kW threshold,
        // but any DC session above ~240 kWh in 16h would correctly land
        // as DC. Conservative cap intentionally biases toward the cheaper
        // AC tariff when uncertain — the user can override via the row dialog.
        val twoDaysMs = 48L * 3_600_000L
        val now = 100L + twoDaysMs
        val setup = build(
            battery = battery(soc = 90f),
            charging = charging(capKwh = 250.0f, gunState = 1),
            prevSoc = 30,
            prevCapacityKwh = 0.0f,
            prevTs = 100L
        )

        setup.detector.runCatchUp(now = now)

        val ch = setup.chargeDao.inserted.single()
        // 200 kWh from cap delta / 16h capped = 12.5 kW → AC. Matches the
        // documented conservative bias; full DC scenario above 240 kWh in
        // <16h would still classify as DC.
        assertEquals("AC", ch.type)
    }

    @Test
    fun `cold start with no prevTs falls back to 1h heuristic`() = runTest {
        // First catch-up after a fresh install or store wipe — prev.ts = 0L.
        // We can't compute elapsed hours, so the legacy 1h fallback is used.
        // 30 kWh / 1h = 30 kW → DC. Documented edge: this is the only path
        // that can still mis-classify, but it's a one-time first-run case.
        val setup = build(
            battery = battery(soc = 90f),
            charging = charging(capKwh = 30.0f, gunState = 1),
            prevSoc = 30,
            prevCapacityKwh = 0.0f
            // prevTs intentionally null → defaults to 0L
        )

        setup.detector.runCatchUp(now = 1500L)

        val ch = setup.chargeDao.inserted.single()
        assertEquals("DC", ch.type)
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
