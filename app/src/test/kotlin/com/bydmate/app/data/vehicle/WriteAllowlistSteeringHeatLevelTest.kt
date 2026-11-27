package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #408 Case 8: WriteAllowlist carve-out for steering heat level (dev=1023, fid=1276256274),
 * exact-value entries 1..5, unvalidated, restricted to 1..5.
 */
class WriteAllowlistSteeringHeatLevelTest {

    private companion object {
        const val DEV = 1023
        const val LEVEL_WRITE_FID = 1276256274
        const val CONFIG_FID = 1116733496
        const val GEAR_READ_FID = 1116733465
    }

    @Test fun `steering heat level fid is carved out on dev 1023 while config and gear stay banned`() {
        assertFalse(WriteAllowlist.isBanned(DEV, LEVEL_WRITE_FID))
        assertTrue((DEV to LEVEL_WRITE_FID) in WriteAllowlist.BANNED_DEV_FID_EXCEPTIONS)
        assertTrue(WriteAllowlist.isBanned(DEV, CONFIG_FID))
        assertTrue(WriteAllowlist.isBanned(DEV, GEAR_READ_FID))
    }

    @Test fun `loadProduction keeps unvalidated exact-value entries 1 to 5 for steering heat level`() {
        val allowlist = WriteAllowlist.loadProduction { "{}" }
        val entries = allowlist.allEntries().filter { it.dev == DEV && it.writeFid == LEVEL_WRITE_FID }

        assertEquals(setOf(1, 2, 3, 4, 5), entries.map { it.valueMin }.toSet())
        for (entry in entries) {
            assertEquals(entry.actionName, entry.valueMin, entry.valueMax)
            assertEquals(entry.actionName, "climate", entry.category)
            assertFalse("${entry.actionName} must be unvalidated", entry.validated)
        }
    }

    @Test fun `valuesAllowed accepts 1 to 5 and refuses 0, 6, -1 and ranged entries`() {
        for (value in 1..5) {
            assertTrue(
                "value=$value must be allowed",
                WriteAllowlist.valuesAllowed(
                    WriteEntry("test_$value", DEV, LEVEL_WRITE_FID, null, value, value, "climate", false, "test")
                ),
            )
        }
        for (bad in listOf(0, 6, -1)) {
            assertFalse(
                "value=$bad must be refused",
                WriteAllowlist.valuesAllowed(
                    WriteEntry("test_$bad", DEV, LEVEL_WRITE_FID, null, bad, bad, "climate", false, "test")
                ),
            )
        }
        assertFalse(
            "ranged entry must be refused",
            WriteAllowlist.valuesAllowed(
                WriteEntry("test_range", DEV, LEVEL_WRITE_FID, null, 1, 5, "climate", false, "test")
            ),
        )
    }

    @Test fun `loadProduction drops 0, 6, -1 and valueless competitor entries on steering heat level fid`() {
        val fixture = """
            {
              "steer_lvl_zero":  { "featureId": $LEVEL_WRITE_FID, "deviceType": $DEV, "value": 0 },
              "steer_lvl_six":   { "featureId": $LEVEL_WRITE_FID, "deviceType": $DEV, "value": 6 },
              "steer_lvl_neg":   { "featureId": $LEVEL_WRITE_FID, "deviceType": $DEV, "value": -1 },
              "steer_lvl_any":   { "featureId": $LEVEL_WRITE_FID, "deviceType": $DEV },
              "steer_lvl_two":   { "featureId": $LEVEL_WRITE_FID, "deviceType": $DEV, "value": 2 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }

        assertNull(allowlist.find("steer_lvl_zero"))
        assertNull(allowlist.find("steer_lvl_six"))
        assertNull(allowlist.find("steer_lvl_neg"))
        assertNull(allowlist.find("steer_lvl_any"))
        assertEquals(2, allowlist.find("steer_lvl_two")?.valueMin)
    }
}
