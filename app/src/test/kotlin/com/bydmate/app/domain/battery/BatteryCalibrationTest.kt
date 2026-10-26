package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.domain.battery.BatteryCalibration.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCalibrationTest {

    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun charge(
        startTs: Long,
        endTs: Long? = startTs + 1_000,
        socStart: Int? = 50,
        socEnd: Int? = 100,
        status: String = "COMPLETED"
    ) = ChargeEntity(
        startTs = startTs, endTs = endTs, socStart = socStart, socEnd = socEnd, status = status
    )

    @Test
    fun emptyList_noMarks() {
        val m = BatteryCalibration.marks(emptyList())
        assertNull(m.lastFullChargeTs)
        assertNull(m.lastCalibrationTs)
    }

    @Test
    fun chargeTo99_isNotFull() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, socStart = 5, socEnd = 99)))
        assertNull(m.lastFullChargeTs)
        assertNull(m.lastCalibrationTs)
    }

    @Test
    fun latestFullChargeWins_regardlessOfOrder() {
        val a = charge(10 * day, endTs = 10 * day + 5)
        val b = charge(30 * day, endTs = 30 * day + 5)
        val c = charge(20 * day, endTs = 20 * day + 5)
        assertEquals(30 * day + 5, BatteryCalibration.marks(listOf(a, b, c)).lastFullChargeTs)
        assertEquals(30 * day + 5, BatteryCalibration.marks(listOf(c, b, a)).lastFullChargeTs)
    }

    @Test
    fun nonCompletedRow_isIgnored() {
        val m = BatteryCalibration.marks(
            listOf(charge(10 * day, socStart = 5, status = "ACTIVE"))
        )
        assertNull(m.lastFullChargeTs)
        assertNull(m.lastCalibrationTs)
    }

    @Test
    fun endTsNull_fallsBackToStartTs() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, endTs = null)))
        assertEquals(10 * day, m.lastFullChargeTs)
    }

    @Test
    fun calibration_socStart9To100_counts() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, endTs = 10 * day + 7, socStart = 9)))
        assertEquals(10 * day + 7, m.lastCalibrationTs)
        assertEquals(10 * day + 7, m.lastFullChargeTs)
    }

    @Test
    fun calibration_socStart10To100_doesNotCount() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, socStart = 10)))
        assertNull(m.lastCalibrationTs)
        assertEquals(10 * day + 1_000, m.lastFullChargeTs)
    }

    @Test
    fun calibration_socStart5To99_doesNotCount() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, socStart = 5, socEnd = 99)))
        assertNull(m.lastCalibrationTs)
    }

    @Test
    fun calibration_nullSocStart_doesNotCount() {
        val m = BatteryCalibration.marks(listOf(charge(10 * day, socStart = null)))
        assertNull(m.lastCalibrationTs)
        assertEquals(10 * day + 1_000, m.lastFullChargeTs)
    }

    @Test
    fun fullChargeThatIsNotCalibration_setsOnlyFullCharge() {
        val cal = charge(10 * day, endTs = 10 * day + 5, socStart = 5)
        val full = charge(40 * day, endTs = 40 * day + 5, socStart = 40)
        val m = BatteryCalibration.marks(listOf(cal, full))
        assertEquals(40 * day + 5, m.lastFullChargeTs)
        assertEquals(10 * day + 5, m.lastCalibrationTs)
    }

    @Test
    fun sinceFullCharge_nullTs_null() {
        assertNull(BatteryCalibration.sinceFullChargeLevel(null, now))
    }

    @Test
    fun sinceFullCharge_boundaries() {
        fun lvl(days: Long) = BatteryCalibration.sinceFullChargeLevel(now - days * day, now)
        assertEquals(Level.GOOD, lvl(0))
        assertEquals(Level.GOOD, lvl(6))
        assertEquals(Level.WARN, lvl(7))
        assertEquals(Level.WARN, lvl(13))
        assertEquals(Level.BAD, lvl(14))
        assertEquals(Level.BAD, lvl(400))
    }

    @Test
    fun sinceFullCharge_partialDayFloors() {
        assertEquals(Level.GOOD, BatteryCalibration.sinceFullChargeLevel(now - 7 * day + 1, now))
    }

    @Test
    fun calibration_nullTs_null() {
        assertNull(BatteryCalibration.calibrationLevel(null, now))
    }

    @Test
    fun calibrationLevel_boundaries() {
        fun lvl(days: Long) = BatteryCalibration.calibrationLevel(now - days * day, now)
        assertEquals(Level.GOOD, lvl(0))
        assertEquals(Level.GOOD, lvl(89))
        assertEquals(Level.WARN, lvl(90))
        assertEquals(Level.WARN, lvl(180))
        assertEquals(Level.BAD, lvl(181))
    }

    @Test
    fun futureTs_isGood() {
        assertEquals(Level.GOOD, BatteryCalibration.sinceFullChargeLevel(now + 5 * day, now))
        assertEquals(Level.GOOD, BatteryCalibration.calibrationLevel(now + 5 * day, now))
    }
}
