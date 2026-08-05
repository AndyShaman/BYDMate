package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository.ManualRangePoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualRangeCalculatorTest {

    private val calc = ManualRangeCalculator()

    private val table = listOf(
        ManualRangePoint(temperatureC = -20, consumptionKwhPer100Km = 27.2),
        ManualRangePoint(temperatureC = -10, consumptionKwhPer100Km = 25.0),
        ManualRangePoint(temperatureC = 0, consumptionKwhPer100Km = 20.6),
        ManualRangePoint(temperatureC = 10, consumptionKwhPer100Km = 18.5),
        ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 16.3),
    )

    /** Same table with the optional range column filled in on two rows only. */
    private val partiallyFilled = listOf(
        ManualRangePoint(temperatureC = -20, consumptionKwhPer100Km = 27.2),
        ManualRangePoint(temperatureC = -10, consumptionKwhPer100Km = 25.0, rangeKmAt100Soc = 180.0),
        ManualRangePoint(temperatureC = 0, consumptionKwhPer100Km = 20.6),
        ManualRangePoint(temperatureC = 10, consumptionKwhPer100Km = 18.5, rangeKmAt100Soc = 250.0),
        ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 16.3, rangeKmAt100Soc = 350.0),
    )

    /** Same table with the range column filled in on every row. */
    private val fullyFilled = listOf(
        ManualRangePoint(temperatureC = -20, consumptionKwhPer100Km = 27.2, rangeKmAt100Soc = 161.0),
        ManualRangePoint(temperatureC = -10, consumptionKwhPer100Km = 25.0, rangeKmAt100Soc = 180.0),
        ManualRangePoint(temperatureC = 0, consumptionKwhPer100Km = 20.6, rangeKmAt100Soc = 200.0),
        ManualRangePoint(temperatureC = 10, consumptionKwhPer100Km = 18.5, rangeKmAt100Soc = 250.0),
        ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 16.3, rangeKmAt100Soc = 350.0),
    )

    @Test fun `consumption interpolates midway between points`() {
        // halfway between 0 C (20.6) and 10 C (18.5)
        assertEquals(19.55, calc.interpolatedConsumption(5.0, table), 1e-9)
    }

    @Test fun `consumption clamps below table range`() {
        assertEquals(27.2, calc.interpolatedConsumption(-40.0, table), 1e-9)
    }

    @Test fun `consumption clamps above table range`() {
        assertEquals(16.3, calc.interpolatedConsumption(45.0, table), 1e-9)
    }

    @Test fun `consumption exact point hit returns that row`() {
        assertEquals(25.0, calc.interpolatedConsumption(-10.0, table), 1e-9)
    }

    @Test fun `empty table falls back to reference consumption`() {
        assertEquals(16.3, calc.interpolatedConsumption(5.0, emptyList()), 1e-9)
    }

    @Test fun `range at 100 soc is null when no row has it`() {
        assertNull(calc.interpolatedRangeAt100Soc(5.0, table))
    }

    @Test fun `range at 100 soc is null when column is only partly filled`() {
        // a lone summer entry would otherwise be clamped across the whole span, claiming
        // 350 km at -20 C too
        assertNull(calc.interpolatedRangeAt100Soc(0.0, partiallyFilled))
        assertNull(calc.interpolatedRangeAt100Soc(-20.0, partiallyFilled))
        assertNull(calc.interpolatedRangeAt100Soc(20.0, partiallyFilled))
    }

    @Test fun `range at 100 soc interpolates over a fully filled column`() {
        // halfway between 0 C (200) and 10 C (250)
        assertEquals(225.0, calc.interpolatedRangeAt100Soc(5.0, fullyFilled)!!, 1e-9)
        assertEquals(200.0, calc.interpolatedRangeAt100Soc(0.0, fullyFilled)!!, 1e-9)
        // clamped at the table's own extremes
        assertEquals(161.0, calc.interpolatedRangeAt100Soc(-40.0, fullyFilled)!!, 1e-9)
        assertEquals(350.0, calc.interpolatedRangeAt100Soc(45.0, fullyFilled)!!, 1e-9)
    }

    @Test fun `estimate uses fallback capacity when table has no range column`() {
        val est = calc.estimateDetailed(
            soc = 50,
            temperatureC = 20,
            table = table,
            fallbackCapacityKwh = 72.9,
        )!!
        assertEquals(16.3, est.avgKwhPer100, 1e-9)
        assertEquals(72.9, est.capacityKwh, 1e-9)
        assertEquals(36.45, est.remainingKwh, 1e-9)
        assertEquals(36.45 / 16.3 * 100.0, est.rangeKm, 1e-9)
    }

    @Test fun `estimate derives effective capacity from range column`() {
        val withRange = listOf(
            ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 16.3, rangeKmAt100Soc = 350.0),
        )
        val est = calc.estimateDetailed(
            soc = 100,
            temperatureC = 20,
            table = withRange,
            fallbackCapacityKwh = 72.9,
        )!!
        // 350 km at 16.3 kWh/100km implies a 57.05 kWh pack, overriding the setting
        assertEquals(57.05, est.capacityKwh, 1e-9)
        assertEquals(350.0, est.rangeKm, 1e-9)
    }

    @Test fun `estimate ignores a partially filled range column`() {
        val est = calc.estimateDetailed(
            soc = 50,
            temperatureC = 20,
            table = partiallyFilled,
            fallbackCapacityKwh = 72.9,
        )!!
        // the 350 km entry at 20 C is discarded with the column, capacity comes from the setting
        assertEquals(72.9, est.capacityKwh, 1e-9)
    }

    @Test fun `estimate uses a fully filled range column`() {
        val est = calc.estimateDetailed(
            soc = 100,
            temperatureC = 20,
            table = fullyFilled,
            fallbackCapacityKwh = 72.9,
        )!!
        assertEquals(57.05, est.capacityKwh, 1e-9)
        assertEquals(350.0, est.rangeKm, 1e-9)
    }

    @Test fun `estimate returns null on out of range soc`() {
        assertNull(calc.estimateDetailed(soc = null, temperatureC = 20, table = table, fallbackCapacityKwh = 72.9))
        assertNull(calc.estimateDetailed(soc = 0, temperatureC = 20, table = table, fallbackCapacityKwh = 72.9))
        assertNull(calc.estimateDetailed(soc = 101, temperatureC = 20, table = table, fallbackCapacityKwh = 72.9))
    }

    @Test fun `estimate returns null on unusable fallback capacity`() {
        assertNull(calc.estimateDetailed(soc = 50, temperatureC = 20, table = table, fallbackCapacityKwh = 0.0))
        assertNull(calc.estimateDetailed(soc = 50, temperatureC = 20, table = table, fallbackCapacityKwh = Double.NaN))
    }

    @Test fun `estimate returns null on capacity outside the sane bounds`() {
        assertNull(calc.estimateDetailed(soc = 50, temperatureC = 20, table = table, fallbackCapacityKwh = 1001.0))
        assertNull(calc.estimateDetailed(soc = 50, temperatureC = 20, table = table, fallbackCapacityKwh = 0.5))
    }

    @Test fun `estimate returns null when the range column implies an absurd capacity`() {
        // 200000 km at 16.3 kWh/100km would mean a 32.6 MWh pack
        val absurd = listOf(ManualRangePoint(temperatureC = 20, consumptionKwhPer100Km = 16.3, rangeKmAt100Soc = 200_000.0))
        assertNull(calc.estimateDetailed(soc = 50, temperatureC = 20, table = absurd, fallbackCapacityKwh = 72.9))
    }
}
