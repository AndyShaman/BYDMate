package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FidMapTest {

    @Test fun `no duplicate device-fid pairs`() {
        val pairs = FidMap.entries.map { it.device to it.fid }
        assertTrue("Duplicate (device, fid) pairs found", pairs.size == pairs.toSet().size)
    }

    // Audit 3.19 items 10, 11: the passenger belt reads the Instrument fid (Setting 1004 answered
    // a constant), the rear belts sit next to it; the Setting rear fids ride along for the probe.
    @Test fun `belt fids read the Instrument belt states`() {
        val expected = mapOf(
            "seatbeltFR" to 638582811, "seatbeltRL" to 638582812,
            "seatbeltRM" to 638582813, "seatbeltRR" to 638582814,
        )
        for ((field, fid) in expected) {
            val entry = FidMap.entries.single { it.field == field }
            assertEquals(field, 1007, entry.device)
            assertEquals(field, fid, entry.fid)
        }
        val probe = FidMap.entries.filter { it.field.startsWith("rearBeltSetting") }
            .associate { it.field to (it.device to it.fid) }
        assertEquals(
            mapOf(
                "rearBeltSettingRL" to (1023 to 824180746),
                "rearBeltSettingRM" to (1023 to 824180750),
                "rearBeltSettingRR" to (1023 to 824180748),
            ),
            probe,
        )
    }

    // Audit 3.19 item 4: dev 1006 reads 3 on normal and on every terrain mode.
    @Test fun `the target drive mode fid is mapped`() {
        val entry = FidMap.entries.single { it.field == "driveModeTarget" }
        assertEquals(1023, entry.device)
        assertEquals(255852712, entry.fid)
        assertEquals("Setting.SETTING_TARGET_DRIVING_MODE", entry.symbol)
    }

    @Test fun `front trunk position fid is mapped for the toggle action`() {
        val entry = FidMap.entries.single { it.field == "frontTrunk" }
        assertEquals(1001, entry.device)
        assertEquals(1156579344, entry.fid)
        assertEquals(5, entry.transact)
        assertEquals("Bodywork.BODYWORK_FRONT_HATCH_CURRENT_POSITION", entry.symbol)
    }

    @Test fun `BMS remaining-energy fid is mapped as a float read`() {
        val entry = FidMap.entries.single { it.field == "batteryRemainKwh" }
        assertEquals(1005, entry.device)
        assertEquals(882901008, entry.fid)
        assertEquals(7, entry.transact)
        assertEquals(Decoder.FLOAT_KWH, entry.decoder)
        assertEquals("Power.POWER_BATTERY_REMAIN_ELECTRICITY", entry.symbol)
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
            "bmsMaxDischargeAllowKw" to Triple(1014, 877658120, 5),
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
        assertEquals(0.1, FidMap.entries.single { it.field == "bmsMaxDischargeAllowKw" }.scale, 0.0001)
    }

    /** The addresses moved out of FidRegistry must still be the Leopard 3 constants it carried. */
    @Test fun `extra entries carry the constants they took over`() {
        val registry = mapOf(
            "soh" to (1014 to 1145045032),
            "lifetimeAvgPhm" to (1014 to 1246761008),
            "chargingType" to (1009 to 876609592),
            "chargeBatteryVolt" to (1009 to -1442840491),
            "batteryType" to (1009 to -1728053169),
            "chargingCapacity" to (1009 to 666894360),
            "soc" to (1014 to 1246777400),
            "mileage" to (1014 to 1246765072),
            "totalElecConsumption" to (1014 to 1032871984),
            "voltage12v" to (1001 to 1128267816),
            "power" to (1012 to 339738656),
            "chargeGunState" to (1009 to 876609586),
            "bmsState" to (1009 to 876609560),
        )
        registry.forEach { (field, address) ->
            val entry = FidMap.byField.getValue(field)
            assertEquals("device of $field", address.first, entry.device)
            assertEquals("fid of $field", address.second, entry.fid)
        }
    }

    /**
     * Readers ask for addresses by field name, so a rename would only show up at runtime.
     * These are the names looked up outside the poll loop.
     */
    @Test fun `field names the readers ask for all exist`() {
        val asked = listOf(
            "soh", "soc", "mileage", "totalElecConsumption", "voltage12v", "power",
            "chargeGunState", "chargingType", "chargeBatteryVolt", "batteryType",
            "chargingCapacity", "bmsState",
            "turnSignal", "gear", "bsdLeft", "bsdRight",
            "windowFL", "windowFR", "windowRL", "windowRR", "windowRRGen3",
            // Sensor wave fields the dashboard, agent and push evaluation read.
            "seatbeltFR", "occupancyFL", "occupancyFR", "occupancyRL",
            "occupancyRM", "occupancyRR", "lightLevel", "keyBatteryStatus",
            "wiperRelay", "autoWipers",
        ) + com.bydmate.app.ui.settings.SeatsDiagnostics.FIDS.map { it.field }
        val missing = asked.filterNot { it in FidMap.byField }
        assertTrue("FidMap has no entry for $missing", missing.isEmpty())
    }

    @Test fun `every entry carries a catalog symbol`() {
        // windowRRGen3 used to be the one exception; it now shares windowRR's symbol as an
        // alias fid so the collision guard treats both as the same field (#216).
        val withoutSymbol = FidMap.all.filter { it.symbol == null }.map { it.field }
        assertEquals(emptyList<String>(), withoutSymbol)
    }

    @Test fun `field names are unique across the polled and extra tables`() {
        assertEquals(FidMap.all.size, FidMap.byField.size)
    }
}
