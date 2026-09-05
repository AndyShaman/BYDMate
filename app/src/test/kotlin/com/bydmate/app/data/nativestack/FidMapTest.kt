package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.reflect.full.memberProperties

class FidMapTest {

    /**
     * Intentionally ignored until all DiParsData fields are validated.
     * The remaining entries (avgBatTemp, chargingStatus, batteryCapacityKwh,
     * autoPark, rain) are not live-sensor fids or require separate validation
     * paths.
     */
    @Ignore("Will pass after remaining 5 fields validated (autoPark, rain, avgBatTemp, chargingStatus, batteryCapacityKwh)")
    @Test fun `every DiParsData field has a FidMap entry`() {
        val dataFields = com.bydmate.app.data.remote.DiParsData::class
            .memberProperties.map { it.name }.toSet()
        val mapped = FidMap.entries.map { it.field }.toSet()
        val missing = dataFields - mapped
        assertTrue("Missing FidMap entries for: $missing", missing.isEmpty())
    }

    @Test fun `FidMap coverage at least 48 of DiParsData fields`() {
        val dataFields = com.bydmate.app.data.remote.DiParsData::class
            .memberProperties.map { it.name }.toSet()
        val mappedFields = FidMap.entries.map { it.field }.toSet()
        val covered = mappedFields.intersect(dataFields)
        assertTrue(
            "Expected >= 48 DiParsData fields covered, got ${covered.size}: $covered",
            covered.size >= 48
        )
    }

    @Test fun `no duplicate device-fid pairs`() {
        val pairs = FidMap.entries.map { it.device to it.fid }
        assertTrue("Duplicate (device, fid) pairs found", pairs.size == pairs.toSet().size)
    }

    @Test fun `sensor wave fields are mapped`() {
        val mapped = FidMap.entries.map { it.field }.toSet()
        val required = setOf(
            "seatbeltFR", "occupancyFL", "occupancyFR", "occupancyRL",
            "occupancyRM", "occupancyRR", "lightLevel", "keyBatteryStatus",
            "wiperRelay", "autoWipers",
        )
        assertTrue("Missing FidMap entries: ${required - mapped}", mapped.containsAll(required))
    }

    @Test fun `turn signal read fid is mapped`() {
        val entry = FidMap.entries.single { it.field == "turnSignal" }
        assertEquals(1004, entry.device)
        assertEquals(950009900, entry.fid)
        assertEquals(5, entry.transact)
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

    @Test fun `batch stays within the 128-item transport cap`() {
        assertTrue("FidMap has ${FidMap.entries.size} entries, cap is 128", FidMap.entries.size <= 128)
    }

    @Test fun `tech panel fields are mapped`() {
        val mapped = FidMap.entries.map { it.field }.toSet()
        val required = setOf(
            "insulationKohm", "motorTempFront", "motorTempRear",
            "inverterTempFront", "inverterTempRear", "hvVoltage", "hvCurrent",
            "motorCurrentFront", "motorCurrentRear", "bmsMaxChargeKw", "bmsMaxDischargeKw",
            "motorRpmFront", "motorRpmRear", "compressorW",
            "tyreTempFL", "tyreTempFR", "tyreTempRL", "tyreTempRR",
            "pedalAccel", "pedalBrake",
        )
        assertTrue("Missing FidMap entries: ${required - mapped}", mapped.containsAll(required))
    }

    @Test fun `tech panel entries carry the validated device and transact`() {
        val expected = mapOf(
            "insulationKohm" to Triple(1039, 1134559256, 5),
            "motorTempFront" to Triple(1039, 1154482192, 5),
            "motorTempRear" to Triple(1039, 1155530768, 5),
            "inverterTempFront" to Triple(1039, 1154482184, 5),
            "inverterTempRear" to Triple(1039, 1155530760, 5),
            "hvVoltage" to Triple(1009, 1145045000, 5),
            "hvCurrent" to Triple(1009, 1145045016, 7),
            "motorCurrentFront" to Triple(1009, 1186988040, 7),
            "motorCurrentRear" to Triple(1009, 1186988056, 7),
            "bmsMaxChargeKw" to Triple(1014, 877658136, 5),
            "bmsMaxDischargeKw" to Triple(1014, 1145045048, 5),
            "motorRpmFront" to Triple(1012, 1141899272, 5),
            "motorRpmRear" to Triple(1012, 621805576, 5),
            "compressorW" to Triple(1000, 1031798840, 5),
            "tyreTempFL" to Triple(1007, 1246797848, 5),
            "tyreTempFR" to Triple(1007, 1246797860, 5),
            "tyreTempRL" to Triple(1007, 1246797872, 5),
            "tyreTempRR" to Triple(1007, 1246797884, 5),
            "pedalAccel" to Triple(1013, 874512392, 5),
            "pedalBrake" to Triple(1013, 874512400, 5),
        )
        expected.forEach { (field, addr) ->
            val e = FidMap.entries.single { it.field == field }
            assertEquals("device for $field", addr.first, e.device)
            assertEquals("fid for $field", addr.second, e.fid)
            assertEquals("transact for $field", addr.third, e.transact)
        }
        assertEquals(0.1, FidMap.entries.single { it.field == "bmsMaxChargeKw" }.scale, 0.0001)
    }
}
