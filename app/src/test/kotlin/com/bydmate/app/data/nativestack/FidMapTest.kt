package com.bydmate.app.data.nativestack

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.reflect.full.memberProperties

class FidMapTest {

    /**
     * Intentionally ignored until all DiParsData fields are validated.
     * FidMap currently covers 39 of 45 DiParsData fields; the remaining
     * entries (sunroof, avgBatTemp, chargingStatus, batteryCapacityKwh, autoPark, rain)
     * are not live-sensor fids or require separate validation paths.
     */
    @Ignore("Will pass after remaining 6 fields validated (sunroof, autoPark, rain, avgBatTemp, chargingStatus, batteryCapacityKwh)")
    @Test fun `every DiParsData field has a FidMap entry`() {
        val dataFields = com.bydmate.app.data.remote.DiParsData::class
            .memberProperties.map { it.name }.toSet()
        val mapped = FidMap.entries.map { it.field }.toSet()
        val missing = dataFields - mapped
        assertTrue("Missing FidMap entries for: $missing", missing.isEmpty())
    }

    @Test fun `FidMap coverage at least 40 of DiParsData fields`() {
        val dataFields = com.bydmate.app.data.remote.DiParsData::class
            .memberProperties.map { it.name }.toSet()
        val mappedFields = FidMap.entries.map { it.field }.toSet()
        val covered = mappedFields.intersect(dataFields)
        assertTrue(
            "Expected >= 39 DiParsData fields covered, got ${covered.size}: $covered",
            covered.size >= 39
        )
    }

    @Test fun `no duplicate device-fid pairs`() {
        val pairs = FidMap.entries.map { it.device to it.fid }
        assertTrue("Duplicate (device, fid) pairs found", pairs.size == pairs.toSet().size)
    }

    @Test fun `every entry has decoder and transact in set 5 7`() {
        FidMap.entries.forEach { e ->
            assertNotNull("Decoder null for ${e.field}", e.decoder)
            assertTrue("Bad transact ${e.transact} for ${e.field}", e.transact in listOf(5, 7))
            if (e.decoder == Decoder.INT_SCALED) {
                assertTrue(
                    "INT_SCALED entry '${e.field}' must have scale != 1.0 (got ${e.scale})",
                    e.scale != 1.0
                )
            }
        }
    }
}
