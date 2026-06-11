package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override suspend fun getEnginePowerKw(): Int? = null
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
        override suspend fun setAll(settings: List<com.bydmate.app.data.local.entity.SettingEntity>) {
            settings.forEach { set(it) }
        }
        override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> =
            flowOf(emptyList())
    }

    private class FakeParsReader(
        private val data: DiParsData? = null
    ) : ParsReader {
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
        val auto: FakeAutoservice,
        val journal: CatchUpJournal
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
        prevMileageKm: Float? = null,
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
            if (prevMileageKm != null) {
                put(SettingsRepository.KEY_LAST_MILEAGE_KM, prevMileageKm.toString())
            }
            if (prevTs != null) {
                put(SettingsRepository.KEY_LAST_STATE_TS, prevTs.toString())
            }
        }
        val settingsDao = FakeSettingsDao(initialMap)
        val settings = SettingsRepository(settingsDao, mockk<LocalePreferences>(relaxed = true))
        val stateStore = ChargingStateStore(settings)
        val journal = CatchUpJournal(settings)
        val classifier = ChargingTypeClassifier()
        val detector = AutoserviceChargingDetector(
            client = auto,
            chargeRepo = chargeRepo,
            batteryHealthRepo = healthRepo,
            stateStore = stateStore,
            classifier = classifier,
            settings = settings,
            parsReader = FakeParsReader(diParsData),
            journal = journal
        )
        return TestSetup(detector, dao, snapshotDao, stateStore, auto, journal)
    }

    /** DiParsData carrying only the fields recordParkedAnchor reads (gun defaults to NONE=1). */
    private fun parkedSample(
        soc: Int?,
        mileage: Double? = null,
        gun: Int? = 1,
        power: Double? = null
    ): DiParsData = DiParsData(
        soc = soc,
        speed = null, mileage = mileage, power = power, chargeGunState = gun,
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
    fun `gate C SOC estimate respects SOH`() = runTest {
        // Issue #28: 1% of SOC on an aged pack holds nominal × SOH/100 kWh.
        val setup = build(
            battery = battery(soc = 91f, soh = 90f),
            charging = charging(capKwh = null),
            prevSoc = 80,
            prevCapacityKwh = null
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        // (91-80)/100 * 72.9 * 0.90 = 7.2171
        assertEquals(7.2171, ch.kwhCharged!!, 0.01)
        assertEquals(7.2171, ch.kwhChargedSoc!!, 0.01)
        assertEquals("autoservice_soc_estimate", ch.detectionSource)
    }

    @Test
    fun `garbage SOH falls back to nominal capacity`() = runTest {
        // Sentinel/garbage SOH (outside 50..100) must not skew the estimate.
        val setup = build(
            battery = battery(soc = 91f, soh = 0f),
            charging = charging(capKwh = null),
            prevSoc = 80,
            prevCapacityKwh = null
        )

        setup.detector.runCatchUp(now = 1500L)

        val ch = setup.chargeDao.inserted.single()
        assertEquals(8.019, ch.kwhCharged!!, 0.01)
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
        // BMS counter overruns SOC: 80→84 (4%), but cap=12 (likely stale residue).
        // socEstimate = 4/100 * 72.9 = 2.916. Ratio 12/2.916 = 4.12 → fallback.
        val setup = build(
            battery = battery(soc = 84f),
            charging = charging(capKwh = 12.0f),
            prevSoc = 80,
            prevCapacityKwh = null
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(2.916, ch.kwhCharged!!, 0.01)
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
        // socStart=87, socEnd=91, delta=4: above the charge threshold (row created)
        // but below MIN_SOC_DELTA_FOR_SNAPSHOT(5) → no snapshot.
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = 1.5f),
            prevSoc = 87,
            prevCapacityKwh = 0.0f
        )

        setup.detector.runCatchUp(now = 1500L)

        assertEquals(1, setup.chargeDao.inserted.size)
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
            prevSoc = 87,
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

    // === Sleep-charge reconstruction (two-point model) ===

    @Test
    fun `sleep charge reconstructed from parked anchor creates one session`() = runTest {
        // The exact user scenario: arrived at 10% (anchor saved during the drive,
        // before car-off), app dead the whole time, plugged in, charged to 80%
        // while asleep, gun pulled, car started → app wakes with only two points
        // (anchor 10% + current 80%) and no live data. gun=NONE, odometer
        // unchanged from the anchor, cap unknown. Expect exactly ONE COMPLETED
        // row 10→80 via SOC estimate, AC by the elapsed-time classifier.
        val fourHoursMs = 4L * 3_600_000L
        val setup = build(
            battery = battery(soc = 80f, mileage = 2091f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 10,
            prevMileageKm = 2091f,
            prevTs = 100L
        )

        val result = setup.detector.runCatchUp(now = 100L + fourHoursMs)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(10, ch.socStart)
        assertEquals(80, ch.socEnd)
        // (80-10)/100 * 72.9 = 51.03
        assertEquals(51.03, ch.kwhCharged!!, 0.1)
        assertEquals("autoservice_soc_estimate", ch.detectionSource)
        // 51.03 kWh over 4h = 12.76 kW < 15 kW threshold → AC
        assertEquals("AC", ch.type)
        // baseline rolled forward to the new SOC
        assertEquals(80, setup.stateStore.load().socPercent)
    }

    @Test
    fun `1 percent rise with odometer moved skips reconstruction`() = runTest {
        // The odometer gate applies ONLY to the 1% tier: a 1% rise after a
        // drive could be regen or a BMS recalculation, not a charge. Skip the
        // row; roll the baseline forward to current.
        val setup = build(
            battery = battery(soc = 80f, mileage = 2200f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 79,
            prevMileageKm = 2091f,
            prevTs = 100L
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        assertEquals(80, setup.stateStore.load().socPercent)
    }

    @Test
    fun `2 percent rise with odometer moved still creates a row`() = runTest {
        // SOC_DELTA_TRUSTED_CHARGE: regen-while-parked and BMS recalibration
        // never reach 2%, so a >=2% rise is a charge no matter what the
        // odometer says — the anchor's mileage can be stale (it used to freeze
        // while SOC was flat, field report 2026-06-11).
        val fourHoursMs = 4L * 3_600_000L
        val setup = build(
            battery = battery(soc = 80f, mileage = 2200f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 78,
            prevMileageKm = 2091f,
            prevTs = 100L
        )

        val result = setup.detector.runCatchUp(now = 100L + fourHoursMs)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(78, ch.socStart)
        assertEquals(80, ch.socEnd)
    }

    @Test
    fun `dacha charge with stale anchor mileage is recorded (field report 2026-06-11)`() = runTest {
        // VadimV, Song, v3.1.3: drove 7 km to the dacha at constant SOC — the
        // old anchor froze its mileage at 47934.7 while the car reached 47941.6.
        // Charged 10 kWh asleep (75→86), woke up → odometerMoved=true ate the
        // session. With the trusted-delta tier an 11% rise is recorded.
        val fourHoursMs = 4L * 3_600_000L
        val setup = build(
            battery = battery(soc = 86f, mileage = 47941.6f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 75,
            prevMileageKm = 47934.7f,
            prevTs = 100L
        )

        val result = setup.detector.runCatchUp(now = 100L + fourHoursMs)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(75, ch.socStart)
        assertEquals(86, ch.socEnd)
        assertEquals("autoservice_soc_estimate", ch.detectionSource)
    }

    @Test
    fun `1 percent SOC rise creates a row`() = runTest {
        // Daily AC top-ups on short-trip cars add only 1-2% — these are real
        // sessions, not noise (field report 2026-06-11). The gate is 1%.
        val setup = build(
            battery = battery(soc = 80f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 79
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(79, ch.socStart)
        assertEquals(80, ch.socEnd)
        assertEquals(80, setup.stateStore.load().socPercent)
    }

    @Test
    fun `runCatchUp writes journal entries for outcomes`() = runTest {
        // SESSION_CREATED and the follow-up NO_DELTA must both leave traces in
        // the persistent journal (field diagnosis — logcat rotates out).
        val setup = build(
            battery = battery(soc = 91f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 80
        )

        setup.detector.runCatchUp(now = 1500L)   // SESSION_CREATED
        setup.detector.runCatchUp(now = 2500L)   // NO_DELTA (soc unchanged)

        val lines = setup.journal.read().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("SESSION_CREATED"))
        assertTrue(lines[0].contains("soc=80->91"))
        assertTrue(lines[1].contains("NO_DELTA soc=91 prev=91"))
    }

    @Test
    fun `2 percent SOC rise creates a row`() = runTest {
        // Regression guard for the old MIN_SOC_DELTA_FOR_CHARGE=3 gate that
        // silently dropped small overnight charges.
        val setup = build(
            battery = battery(soc = 80f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 78
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
        assertEquals(80, setup.stateStore.load().socPercent)
    }

    // === Gun-glitch narrowing (static fids don't vouch for the gun fid) ===

    @Test
    fun `gun null with only static batteryType readable does NOT defer`() = runTest {
        // batteryType is a constant (LFP) readable on firmwares where the gun
        // fid is simply unsupported. Counting it as "charging device alive"
        // turned every catch-up into STILL_CHARGING and permanently blocked
        // charge logging on such cars. Only dynamic charging fids vouch.
        val setup = build(
            battery = battery(soc = 91f),
            charging = ChargingReading(
                gunConnectState = null,
                chargingType = null, chargeBatteryVoltV = null,
                batteryType = 1, chargingCapacityKwh = null,
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
    fun `fourth consecutive gun glitch falls through to cascade`() = runTest {
        // A transient glitch clears within a read or two; a gun fid that stays
        // silent across many runs while siblings answer is unsupported firmware.
        // Without an escape the detector would defer forever and never log a
        // charge on such cars.
        val glitched = ChargingReading(
            gunConnectState = null,
            chargingType = 2, chargeBatteryVoltV = 0,
            batteryType = 1, chargingCapacityKwh = 8.0f,
            bmsState = null, readAtMs = 1000L
        )
        val setup = build(
            battery = battery(soc = 91f),
            charging = glitched,
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        repeat(3) {
            assertEquals(CatchUpOutcome.STILL_CHARGING, setup.detector.runCatchUp(now = 1500L).outcome)
        }
        val fourth = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, fourth.outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
    }

    @Test
    fun `successful gun read resets the glitch counter`() = runTest {
        val glitched = ChargingReading(
            gunConnectState = null,
            chargingType = 2, chargeBatteryVoltV = 0,
            batteryType = 1, chargingCapacityKwh = 8.0f,
            bmsState = null, readAtMs = 1000L
        )
        val setup = build(
            battery = battery(soc = 91f),
            charging = glitched,
            prevSoc = 80,
            prevCapacityKwh = 0.0f
        )

        // Two glitches, then a real gun read (connected) resets the streak.
        repeat(2) { setup.detector.runCatchUp(now = 1500L) }
        setup.auto.charging = charging(capKwh = 8.0f, gunState = 2)
        setup.detector.runCatchUp(now = 1500L)

        // Three more glitches are counts 1..3 of a fresh streak — all defer.
        setup.auto.charging = glitched
        repeat(3) {
            assertEquals(CatchUpOutcome.STILL_CHARGING, setup.detector.runCatchUp(now = 1500L).outcome)
        }
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    // === Pending charge (gun seen connected → never silently lose the session) ===

    @Test
    fun `STILL_CHARGING with gun connected sets persistent chargePending`() = runTest {
        // A brief wake mid-charge proves a session is in progress. Persist that
        // evidence so a later catch-up can not silently discard the session.
        val setup = build(
            battery = battery(soc = 35f),
            charging = charging(capKwh = 2.0f, gunState = 2),
            prevSoc = 20,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertTrue(setup.stateStore.loadChargePending())
    }

    @Test
    fun `gun glitch STILL_CHARGING also sets chargePending`() = runTest {
        val setup = build(
            battery = battery(soc = 35f),
            charging = ChargingReading(
                gunConnectState = null,
                chargingType = 2, chargeBatteryVoltV = 0,
                batteryType = 1, chargingCapacityKwh = 2.0f,
                bmsState = null, readAtMs = 1000L
            ),
            prevSoc = 20,
            prevCapacityKwh = 0.0f
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.STILL_CHARGING, result.outcome)
        assertTrue(setup.stateStore.loadChargePending())
    }

    @Test
    fun `pending 1 percent charge with odometer moved still creates a row and clears pending`() = runTest {
        // Morning race lost: a wake during the charge set pending, the final
        // wake-up window failed, the user drove off. SOC can only RISE via
        // charging, so with pending evidence even a 1% rise (the only tier the
        // odometer gate still applies to) must become a row instead of a
        // silent drop.
        val fourHoursMs = 4L * 3_600_000L
        val setup = build(
            battery = battery(soc = 80f, mileage = 2200f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 79,
            prevMileageKm = 2091f,
            prevTs = 100L
        )
        setup.stateStore.setChargePending(true)

        val result = setup.detector.runCatchUp(now = 100L + fourHoursMs)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        val ch = setup.chargeDao.inserted.single()
        assertEquals(79, ch.socStart)
        assertEquals(80, ch.socEnd)
        assertFalse(setup.stateStore.loadChargePending())
    }

    @Test
    fun `1 percent rise with odometer moved WITHOUT pending still skips reconstruction`() = runTest {
        val setup = build(
            battery = battery(soc = 80f, mileage = 2200f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 79,
            prevMileageKm = 2091f,
            prevTs = 100L
        )

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
    }

    @Test
    fun `unchanged SOC clears chargePending without a row`() = runTest {
        // Gun was seen connected but SOC did not rise (charger never delivered).
        // Roll forward and drop the stale pending flag.
        val setup = build(
            battery = battery(soc = 80f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 80
        )
        setup.stateStore.setChargePending(true)

        val result = setup.detector.runCatchUp(now = 1500L)

        assertEquals(CatchUpOutcome.NO_DELTA, result.outcome)
        assertEquals(0, setup.chargeDao.inserted.size)
        assertFalse(setup.stateStore.loadChargePending())
    }

    @Test
    fun `normal reconstruction clears chargePending`() = runTest {
        val setup = build(
            battery = battery(soc = 80f, mileage = 2091f),
            charging = charging(capKwh = null, gunState = 1),
            prevSoc = 10,
            prevMileageKm = 2091f,
            prevTs = 100L
        )
        setup.stateStore.setChargePending(true)

        val result = setup.detector.runCatchUp(now = 100L + 4L * 3_600_000L)

        assertEquals(CatchUpOutcome.SESSION_CREATED, result.outcome)
        assertFalse(setup.stateStore.loadChargePending())
    }

    // === recordParkedAnchor ===

    @Test
    fun `recordParkedAnchor rolls the anchor forward on a SOC drop`() = runTest {
        val setup = build(battery = battery(soc = 50f), prevSoc = 50)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 48, mileage = 2100.0, gun = 1), now = 2000L)

        assertFalse(rearm)
        val state = setup.stateStore.load()
        assertEquals(48, state.socPercent)
        assertEquals(2100f, state.mileageKm!!, 0.01f)
    }

    @Test
    fun `recordParkedAnchor does NOT overwrite the anchor on a SOC rise and signals re-arm`() = runTest {
        // A higher SOC than the stored anchor means a charge happened while we
        // were away. Keep the low anchor so runCatchUp can reconstruct the
        // session, and return true so the caller re-arms catch-up (covers the
        // stale-read-at-wake NO_DELTA — audit 2026-06-11).
        val setup = build(battery = battery(soc = 80f), prevSoc = 10)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 80, gun = 1), now = 2000L)

        assertTrue(rearm)
        assertEquals(10, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor skips while the gun is connected`() = runTest {
        // Live charge in progress — never move the start anchor.
        val setup = build(battery = battery(soc = 50f), prevSoc = 50)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 48, gun = 2), now = 2000L)

        assertFalse(rearm)
        assertEquals(50, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor never signals re-arm while power is negative (gun-null live charge)`() = runTest {
        // Song reports chargeGunState=null even during a live charge. SOC then
        // climbs above the anchor mid-charge — re-arming there would create a
        // partial session and split the charge. Negative power = energy is
        // flowing IN = live charge; block the re-arm (codex audit 2026-06-11).
        val setup = build(battery = battery(soc = 80f), prevSoc = 10)

        val rearm = setup.detector.recordParkedAnchor(
            parkedSample(soc = 80, gun = null, power = -5.0), now = 2000L
        )

        assertFalse(rearm)
        assertEquals(10, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor signals re-arm on gun-null SOC rise once power is non-negative`() = runTest {
        // Same Song gun-null case after the charge ended: power settled to
        // null/zero — now the rise above the anchor is a missed charge.
        val setup = build(battery = battery(soc = 80f), prevSoc = 10)

        assertTrue(setup.detector.recordParkedAnchor(parkedSample(soc = 80, gun = null, power = null), now = 2000L))
        assertTrue(setup.detector.recordParkedAnchor(parkedSample(soc = 80, gun = null, power = 0.0), now = 3000L))
        assertEquals(10, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor never signals re-arm while the gun is connected`() = runTest {
        // Mid-charge ticks have SOC above the anchor by definition — the gun
        // gate must win, otherwise live charging would trigger phantom re-arms.
        val setup = build(battery = battery(soc = 80f), prevSoc = 10)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 80, gun = 2), now = 2000L)

        assertFalse(rearm)
        assertEquals(10, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor does not signal re-arm on unchanged SOC`() = runTest {
        val setup = build(battery = battery(soc = 50f), prevSoc = 50)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 50, gun = 1), now = 2000L)

        assertFalse(rearm)
        assertEquals(50, setup.stateStore.load().socPercent)
    }

    @Test
    fun `recordParkedAnchor keeps the mileage rolling while SOC is flat`() = runTest {
        // A 7 km drive at constant SOC used to freeze the anchor's mileage
        // (only SOC drops refreshed it) — the stale value then tripped the
        // Step-5 odometer gate and a real dacha charge was dropped (field
        // report 2026-06-11, Song). Flat SOC must still update mileage + ts.
        val setup = build(battery = battery(soc = 50f), prevSoc = 50, prevMileageKm = 47934.7f)

        val rearm = setup.detector.recordParkedAnchor(
            parkedSample(soc = 50, mileage = 47941.6, gun = 1), now = 2000L
        )

        assertFalse(rearm)
        val state = setup.stateStore.load()
        assertEquals(50, state.socPercent)
        assertEquals(47941.6f, state.mileageKm!!, 0.01f)
        assertEquals(2000L, state.ts)
    }

    @Test
    fun `recordParkedAnchor seeds the anchor when the store is empty`() = runTest {
        val setup = build(battery = battery(soc = 50f), prevSoc = null)

        val rearm = setup.detector.recordParkedAnchor(parkedSample(soc = 50, gun = 1), now = 2000L)

        assertFalse(rearm)
        assertEquals(50, setup.stateStore.load().socPercent)
    }

    @Test
    fun `re-arm scenario - stale NO_DELTA then fresh data creates the session`() = runTest {
        // Full stale-read-at-wake recovery: catch-up first sees yesterday's SOC
        // (equal to the anchor) and resolves NO_DELTA; later a live tick brings
        // the real post-charge SOC → recordParkedAnchor signals re-arm → the
        // re-run reconstructs the charge from the preserved low anchor.
        val setup = build(
            battery = battery(soc = 60f, mileage = 2100f),
            prevSoc = 60,
            prevMileageKm = 2100f,
        )

        // Stale read: SOC equals the anchor → terminal NO_DELTA, no row.
        assertEquals(CatchUpOutcome.NO_DELTA, setup.detector.runCatchUp(now = 1000L).outcome)
        assertEquals(0, setup.chargeDao.inserted.size)

        // Fresh tick: real SOC is 85, gun out → re-arm signal, anchor preserved.
        assertTrue(setup.detector.recordParkedAnchor(parkedSample(soc = 85, gun = 1), now = 2000L))
        assertEquals(60, setup.stateStore.load().socPercent)

        // Re-armed catch-up now sees the fresh SOC and reconstructs the charge.
        setup.auto.battery = battery(soc = 85f, mileage = 2100f)
        assertEquals(CatchUpOutcome.SESSION_CREATED, setup.detector.runCatchUp(now = 3000L).outcome)
        assertEquals(1, setup.chargeDao.inserted.size)
    }
}
