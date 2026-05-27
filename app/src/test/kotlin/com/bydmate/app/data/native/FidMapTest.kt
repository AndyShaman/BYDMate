package com.bydmate.app.data.native

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.reflect.full.memberProperties

class FidMapTest {

    /**
     * Intentionally ignored until Task 19 fills the full validated map.
     * FidMap baseline covers only 7 of 47 DiParsData fields; the remaining
     * entries are added field-by-field as Phase 1a validation graduates each
     * fid-candidates.yaml entry to `status: validated`.
     */
    @Ignore("Will pass after Task 19 fills full validated map")
    @Test fun `every DiParsData field has a FidMap entry`() {
        val dataFields = com.bydmate.app.data.remote.DiParsData::class
            .memberProperties.map { it.name }.toSet()
        val mapped = FidMap.entries.map { it.field }.toSet()
        val missing = dataFields - mapped
        assertTrue("Missing FidMap entries for: $missing", missing.isEmpty())
    }

    @Test fun `no duplicate device-fid pairs`() {
        val pairs = FidMap.entries.map { it.device to it.fid }
        assertTrue("Duplicate (device, fid) pairs found", pairs.size == pairs.toSet().size)
    }

    @Test fun `every entry has decoder and transact in set 5 7`() {
        FidMap.entries.forEach { e ->
            assertNotNull("Decoder null for ${e.field}", e.decoder)
            assertTrue("Bad transact ${e.transact} for ${e.field}", e.transact in listOf(5, 7))
        }
    }
}
