package com.bydmate.app.data.nativestack

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DM-i hybrid fids on a firmware with the old numbering (#394): the resolver moves them only where the constant is dead. */
class HybridFidResolveTest {

    @After fun restoreConstants() = FidAddresses.resetToConstants()

    private val tangFids = mapOf(
        "mileageHevKm" to TangHybrid.MILEAGE_HEV,
        "mileageEvKm" to TangHybrid.MILEAGE_EV,
        "fuelPercent" to TangHybrid.FUEL_PERCENT,
        "fuelRangeKm" to TangHybrid.FUEL_RANGE,
        "fuelTotalL" to TangHybrid.FUEL_TOTAL,
        "evRangeKm" to TangHybrid.EV_RANGE,
    )

    private suspend fun resolveTang(probe: FidProbe) =
        FidResolver.resolve(FidMap.hybrid, TangHybrid.catalog(), probe, "test", onlyWhereConstantFails = true)

    private fun assertAllOnConstants(outcome: FidResolver.Outcome, expected: FidResolution) {
        FidMap.hybrid.forEach { entry ->
            val address = outcome.table.address(entry.field)
            assertEquals("device of ${entry.field}", entry.device, address.device)
            assertEquals("fid of ${entry.field}", entry.fid, address.fid)
        }
        outcome.table.notes.filter { it.field in tangFids }.forEach {
            assertEquals(it.field, expected, it.outcome)
        }
    }

    @Test fun `the hybrid entries carry their catalog symbols and stay out of the EV table`() {
        val symbols = FidMap.hybrid.associate { it.field to it.symbol }
        assertEquals("Statistic.STATISTIC_FUEL_PERCENTAGE", symbols["fuelPercent"])
        assertEquals("Statistic.STATISTIC_FUEL_DRIVING_RANGE", symbols["fuelRangeKm"])
        assertEquals("Statistic.STATISTIC_TOTAL_FUEL_CONSUMPTION", symbols["fuelTotalL"])
        assertEquals("Statistic.STATISTIC_MILEAGE_HEV", symbols["mileageHevKm"])
        assertEquals("Statistic.STATISTIC_MILEAGE_EV", symbols["mileageEvKm"])
        assertEquals("Statistic.STATISTIC_ELEC_DRIVING_RANGE", symbols["evRangeKm"])
        assertNull(symbols["engineRpm"])

        val allFields = FidMap.all.map { it.field }.toSet()
        FidMap.hybrid.forEach { assertFalse("${it.field} leaked into FidMap.all", it.field in allFields) }
        assertEquals(122, FidMap.all.size)
        assertEquals(94, FidMap.entries.size)
    }

    @Test fun `dead compiled addresses make the six hybrid fields follow the Tang catalog`() = runTest {
        val probe = TangHybrid.probe(TangHybrid.deadAtCompiled + TangHybrid.liveAtCatalog)
        val outcome = resolveTang(probe)

        val deviceOfStatistic = TangHybrid.catalog().deviceOf("STATISTIC")
        tangFids.forEach { (field, fid) ->
            val address = outcome.table.address(field)
            assertEquals("fid of $field", fid, address.fid)
            assertEquals("device of $field", deviceOfStatistic, address.device)
            assertEquals(field, FidResolution.CATALOG, outcome.table.notes.first { it.field == field }.outcome)
        }
        val rpm = outcome.table.address("engineRpm")
        assertEquals(1012, rpm.device)
        assertEquals(339738642, rpm.fid)
        assertEquals(FidResolution.CONST, outcome.table.notes.first { it.field == "engineRpm" }.outcome)
    }

    @Test fun `live compiled addresses keep every hybrid field on its constant`() = runTest {
        val outcome = resolveTang(TangHybrid.probe(TangHybrid.liveAtCompiled + TangHybrid.liveAtCatalog))
        assertAllOnConstants(outcome, FidResolution.REJECTED)
    }

    @Test fun `compiled addresses answering the EV absent words are alive and stay`() = runTest {
        val outcome = resolveTang(TangHybrid.probe(TangHybrid.evWordsAtCompiled + TangHybrid.liveAtCatalog))
        assertAllOnConstants(outcome, FidResolution.REJECTED)
    }

    @Test fun `a catalog address that answers an error or nothing leaves the constants`() = runTest {
        val sentinelAtCatalog = TangHybrid.liveAtCatalog.mapValues { -10011 }
        for (catalogAnswers in listOf(sentinelAtCatalog, emptyMap())) {
            val outcome = resolveTang(TangHybrid.probe(TangHybrid.deadAtCompiled + catalogAnswers))
            FidMap.hybrid.forEach { entry ->
                val address = outcome.table.address(entry.field)
                assertEquals(entry.field, entry.fid, address.fid)
                assertEquals(entry.field, entry.device, address.device)
            }
        }
    }

    @Test fun `the three known catalogs offer no hybrid candidate and the probe is never called`() = runTest {
        for (name in listOf("leopard3", "dilink4", "songplus")) {
            var calls = 0
            val probe = FidProbe { requests -> calls++; requests.map { 5 } }
            val text = checkNotNull(javaClass.classLoader?.getResource("fid-catalog-$name.txt")).readText()
            val outcome = FidResolver.resolve(
                FidMap.hybrid, FidCatalog.parse(text), probe, "test", onlyWhereConstantFails = true,
            )
            assertEquals("probe calls on $name", 0, calls)
            assertTrue(name, outcome.table.notes.all { it.outcome == FidResolution.CONST })
            FidMap.hybrid.forEach { entry ->
                assertEquals("$name ${entry.field}", entry.fid, outcome.table.fid(entry.field))
                assertEquals("$name ${entry.field}", entry.device, outcome.table.device(entry.field))
            }
        }
    }
}
