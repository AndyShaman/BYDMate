package com.bydmate.app.data.nativestack

import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BatchReadGate promotion/demotion state machine. Uses the same in-memory
 * FakeSettingsDao + real SettingsRepository double as CatchUpJournalTest so
 * persistence-across-instances is exercised for real (not mocked away).
 */
class BatchReadGateTest {

    private class FakeSettingsDao :
        com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    /** Fresh SettingsRepository backed by an isolated in-memory store (nothing persisted yet). */
    private fun freshSettingsRepository(): SettingsRepository =
        SettingsRepository(FakeSettingsDao(), mockk(relaxed = true))

    /** Same pattern, but pre-seeded so tests can assert cross-instance persistence and
     *  simulate an already-stored gate state (e.g. a stale fingerprint). */
    private suspend fun seededSettingsRepository(vararg entries: Pair<String, String>): SettingsRepository {
        val repo = SettingsRepository(FakeSettingsDao(), mockk(relaxed = true))
        entries.forEach { (k, v) -> repo.setString(k, v) }
        return repo
    }

    /** Minimal DiParsData with only soc/mileage set — the two fields BatchReadGate compares. */
    private fun snapshot(soc: Int?, mileage: Double?): DiParsData = DiParsData(
        soc = soc, speed = null, mileage = mileage, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = null,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = null,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = null,
        powerState = null, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null, sunroof = null,
        trunk = null, hood = null, seatbeltFL = null, lockFL = null, tirePressFL = null,
        tirePressFR = null, tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
    )

    @Test
    fun `fresh state with no stored keys is VALIDATING`() = runTest {
        val gate = BatchReadGate(freshSettingsRepository())
        assertEquals(BatchMode.VALIDATING, gate.mode())
    }

    @Test
    fun `three clean matches promote to ACTIVE and persist across instances`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)
        val a = snapshot(soc = 50, mileage = 100.0)
        val b = snapshot(soc = 50, mileage = 100.0)

        gate.recordComparison(a, b)
        gate.recordComparison(a, b)
        assertEquals("still validating after only 2 matches", BatchMode.VALIDATING, gate.mode())
        gate.recordComparison(a, b)
        assertEquals(BatchMode.ACTIVE, gate.mode())

        // Survives a brand new instance backed by the same store.
        val gate2 = BatchReadGate(settings)
        assertEquals(BatchMode.ACTIVE, gate2.mode())
    }

    @Test
    fun `three accumulated mismatches interleaved with matches demote to OFF and persist`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)
        val match = snapshot(soc = 50, mileage = 100.0) to snapshot(soc = 50, mileage = 100.0)
        val mismatch = snapshot(soc = 50, mileage = 100.0) to snapshot(soc = 60, mileage = 100.0)

        // Sequence M m M m m: ok resets on every mismatch, mm accumulates across the whole
        // sequence (not just consecutively) until it hits MISMATCH_LIMIT=3.
        gate.recordComparison(match.first, match.second)      // ok=1 mm=0
        gate.recordComparison(mismatch.first, mismatch.second) // ok=0 mm=1
        gate.recordComparison(match.first, match.second)      // ok=1 mm=1
        gate.recordComparison(mismatch.first, mismatch.second) // ok=0 mm=2
        gate.recordComparison(mismatch.first, mismatch.second) // ok=0 mm=3 -> OFF

        assertEquals(BatchMode.OFF, gate.mode())

        val gate2 = BatchReadGate(settings)
        assertEquals(BatchMode.OFF, gate2.mode())
    }

    @Test
    fun `fingerprint change resets a persisted OFF state back to VALIDATING`() = runTest {
        // Simulate a device that was already demoted to OFF on a previous firmware build.
        val settings = seededSettingsRepository(
            BatchReadGate.KEY_STATE to "off",
            BatchReadGate.KEY_FP to "old-firmware-fingerprint-marker",
            BatchReadGate.KEY_OK to "0",
            BatchReadGate.KEY_MM to "3",
        )
        val gate = BatchReadGate(settings)
        assertEquals(BatchMode.VALIDATING, gate.mode())
    }

    @Test
    fun `null snapshot on either side moves no counters`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)
        val a = snapshot(soc = 50, mileage = 100.0)

        gate.recordComparison(null, a)
        gate.recordComparison(a, null)
        gate.recordComparison(null, null)
        assertEquals(BatchMode.VALIDATING, gate.mode())

        // Only two REAL matches follow — if the three null calls above had moved the
        // counter, this would already be ACTIVE (3 total "matches").
        gate.recordComparison(a, a)
        gate.recordComparison(a, a)
        assertEquals("null comparisons must not have advanced ok", BatchMode.VALIDATING, gate.mode())

        gate.recordComparison(a, a)
        assertEquals(BatchMode.ACTIVE, gate.mode())
    }

    @Test
    fun `soc within tolerance and mileage within tolerance count as a match`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)
        // soc diff = 1 (<= SOC_TOLERANCE), mileage diff = 0.1 (<= MILEAGE_TOLERANCE 0.15)
        val adb = snapshot(soc = 50, mileage = 100.0)
        val batch = snapshot(soc = 51, mileage = 100.1)

        gate.recordComparison(adb, batch)
        gate.recordComparison(adb, batch)
        gate.recordComparison(adb, batch)
        assertEquals(BatchMode.ACTIVE, gate.mode())
    }

    @Test
    fun `soc differing by 2 counts as a mismatch`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)
        val adb = snapshot(soc = 50, mileage = 100.0)
        val batch = snapshot(soc = 52, mileage = 100.0)

        gate.recordComparison(adb, batch)
        gate.recordComparison(adb, batch)
        gate.recordComparison(adb, batch)
        assertEquals(BatchMode.OFF, gate.mode())
    }

    @Test
    fun `five consecutive recordBatchUnavailable go OFF for this instance only, session-scoped`() = runTest {
        val settings = freshSettingsRepository()
        val gate = BatchReadGate(settings)

        repeat(5) { gate.recordBatchUnavailable() }
        assertEquals(BatchMode.OFF, gate.mode())

        // A brand new instance backed by the same store starts a new session:
        // recordBatchUnavailable's streak is in-memory only, never persisted.
        val gate2 = BatchReadGate(settings)
        assertEquals(BatchMode.VALIDATING, gate2.mode())
    }
}
