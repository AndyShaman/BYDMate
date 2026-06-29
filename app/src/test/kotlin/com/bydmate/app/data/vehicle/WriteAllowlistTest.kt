package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WriteAllowlistTest {

    private val bannedDevs = setOf(1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032)

    // ── Test 5: WriteEntry data class construction with new fields ────────────
    @Test fun `WriteEntry data class construction with new fields works`() {
        val entry = WriteEntry(
            actionName = "test_action",
            dev = 1001,
            writeFid = 12345,
            readbackFid = 67890,
            valueMin = 0,
            valueMax = 100,
            category = "windows",
            validated = true,
            source = "live-leopard3-2026-05-28",
        )
        assertEquals("test_action", entry.actionName)
        assertEquals(1001, entry.dev)
        assertEquals(12345, entry.writeFid)
        assertEquals(67890, entry.readbackFid)
        assertEquals(0, entry.valueMin)
        assertEquals(100, entry.valueMax)
        assertEquals("windows", entry.category)
        assertTrue(entry.validated)
        assertEquals("live-leopard3-2026-05-28", entry.source)
    }

    // ── Test 1: banned dev entries are dropped; safe entries survive ──────────
    @Test fun `no production entry targets a banned dev namespace`() {
        val fixture = """
            {
              "safe_action": { "featureId": 1125122104, "deviceType": 1001, "value": 1 },
              "banned_action": { "featureId": 999999999, "deviceType": 1014, "value": 1 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }

        assertNotNull("safe_action must survive", allowlist.find("safe_action"))
        assertNull("banned_action must be dropped", allowlist.find("banned_action"))

        for (entry in allowlist.allEntries()) {
            if (entry.dev in bannedDevs &&
                (entry.dev to entry.writeFid) !in WriteAllowlist.BANNED_DEV_FID_EXCEPTIONS) {
                fail("WriteAllowlist contains banned dev=${entry.dev} for action=${entry.actionName}")
            }
        }
    }

    // ── Test 2: all 5 required categories present in LIVE_VALIDATED ──────────
    @Test fun `at least 5 validated categories cover climate windows sunroof sunshade locks`() {
        // Load with empty competitor JSON — only LIVE_VALIDATED contributes
        val allowlist = WriteAllowlist.loadProduction { "{}" }
        val validatedCategories = allowlist.allEntries()
            .filter { it.validated }
            .map { it.category }
            .toSet()
        val required = setOf("climate", "windows", "sunroof", "sunshade", "locks")
        val missing = required - validatedCategories
        assertTrue("Missing validated categories: $missing", missing.isEmpty())
    }

    // ── Test 3: production has at least 100 entries ───────────────────────────
    @Test fun `production has at least 100 entries`() {
        // Read the real competitor JSON from the project research directory.
        // The test runs on the Mac where .research/ is available at project root.
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".research/competitor-v2/pushFidConfig.json").exists() }
            ?: error("Cannot find .research/competitor-v2/pushFidConfig.json from ${File(".").canonicalPath}")
        val src = File(projectRoot, ".research/competitor-v2/pushFidConfig.json")
        val raw = src.readText()
        // Extract defaults.actions slice (same logic as extract-competitor-actions.py)
        val fullJson = org.json.JSONObject(raw)
        val actionsJson = fullJson.getJSONObject("defaults").getJSONObject("actions").toString()
        val allowlist = WriteAllowlist.loadProduction { actionsJson }
        assertTrue(
            "Expected ≥ 100 entries, got ${allowlist.size}",
            allowlist.size >= 100
        )
    }

    // ── Test 4: live-validated entry wins on name collision ───────────────────
    @Test fun `validated entries override competitor entries on name collision`() {
        // doors_lock exists in LIVE_VALIDATED (dev=1001, fid=1276141590)
        // Competitor fixture has doors_lock with a different dev/fid — live wins
        val fixture = """
            {
              "doors_lock": { "featureId": 1234, "deviceType": 9999, "value": 99 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        val entry = allowlist.find("doors_lock")
        assertNotNull(entry)
        assertEquals(
            "live-leopard3-2026-05-28 must win on collision",
            "live-leopard3-2026-05-28",
            entry!!.source,
        )
        assertEquals("live fid must be preserved", 1276141590, entry.writeFid)
    }

    // ── Legacy: EMPTY has no entries ─────────────────────────────────────────
    @Test fun `EMPTY has no entries`() {
        assertEquals(0, WriteAllowlist.EMPTY.size)
        assertNull(WriteAllowlist.EMPTY.find("anything"))
    }

    // ── Dim 6, Test 1: malformed entry missing deviceType is skipped ──────────
    @Test fun `loadProduction skips entries missing deviceType`() {
        val fixture = """
            {
              "no_dev_action": { "featureId": 12345, "value": 1 },
              "valid_action": { "featureId": 1125122104, "deviceType": 1001, "value": 1 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        assertNull("entry missing deviceType must be skipped", allowlist.find("no_dev_action"))
        assertNotNull("valid entry must survive", allowlist.find("valid_action"))
    }

    // ── Dim 6, Test 2: malformed entry missing featureId is skipped ───────────
    @Test fun `loadProduction skips entries missing featureId`() {
        val fixture = """
            {
              "no_fid_action": { "deviceType": 1001, "value": 1 },
              "valid_action2": { "featureId": 1125122107, "deviceType": 1001, "value": 2 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        assertNull("entry missing featureId must be skipped", allowlist.find("no_fid_action"))
        assertNotNull("valid entry must survive", allowlist.find("valid_action2"))
    }

    // ── Dim 6, Test 3: all-banned competitor JSON yields only LIVE_VALIDATED ──
    @Test fun `loadProduction returns LIVE only when all competitor entries are banned`() {
        // All entries target banned devs — none should survive from competitor
        val fixture = """
            {
              "banned1": { "featureId": 1, "deviceType": 1014, "value": 1 },
              "banned2": { "featureId": 2, "deviceType": 1012, "value": 1 },
              "banned3": { "featureId": 3, "deviceType": 1004, "value": 1 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        assertEquals(
            "only LIVE_VALIDATED + CANDIDATE_UNVALIDATED entries should be present",
            WriteAllowlist.LIVE_VALIDATED.size + WriteAllowlist.CANDIDATE_UNVALIDATED.size,
            allowlist.entries.size,
        )
        assertNull(allowlist.find("banned1"))
        assertNull(allowlist.find("banned2"))
        assertNull(allowlist.find("banned3"))
    }

    // ── Dim 6, Test 4: intra-competitor duplicate actionName → exactly one entry
    @Test fun `loadProduction handles intra-competitor duplicate actionName`() {
        val fixture = """
            {
              "dup_action": { "featureId": 1125122104, "deviceType": 1001, "value": 1 },
              "Dup_Action": { "featureId": 1125122107, "deviceType": 1001, "value": 2 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        // Both lowercase to "dup_action" — exactly one entry survives; no crash
        val found = allowlist.find("dup_action")
        assertNotNull("one of the two entries must survive", found)
        // Total entries = LIVE_VALIDATED + CANDIDATE_UNVALIDATED + 1 competitor (duplicate collapsed)
        val baseSize = WriteAllowlist.LIVE_VALIDATED.size + WriteAllowlist.CANDIDATE_UNVALIDATED.size
        assertEquals(
            "duplicate competitor names collapse to one entry",
            baseSize + 1,
            allowlist.entries.size,
        )
    }

    // ── Dim 6, Test 5: unknown-prefix action is tagged as category=other ──────
    @Test fun `loadProduction tags unknown-prefix actions as category=other`() {
        val fixture = """
            {
              "xyz_unknown_action": { "featureId": 1125122104, "deviceType": 1001, "value": 1 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        val entry = allowlist.find("xyz_unknown_action")
        assertNotNull(entry)
        assertEquals("other", entry!!.category)
    }

    // ── Lights / mirror / DRL live-validated on Leopard 3 2026-05-29 ──────────
    @Test fun `light mirror and drl actions are live-validated`() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val names = listOf(
            "interior_light_on", "interior_light_off",
            "ambient_light_on", "ambient_light_off",
            "drl_on", "drl_off",
            "defrost_rear_on", "defrost_rear_off",
        )
        for (name in names) {
            val e = al.find(name)
            assertNotNull("$name must be present", e)
            assertTrue("$name must be validated", e!!.validated)
        }
    }

    @Test fun `ambient light off writes raw 1 not 0`() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val e = al.find("ambient_light_off")
        assertNotNull(e)
        assertEquals(1, e!!.valueMin)
        assertEquals(1, e.valueMax)
    }

    @Test fun `drl is carved out of banned dev 1004`() {
        assertTrue(
            "drl write fid must be carved out of the dev 1004 ban",
            (1004 to 1125122118) in WriteAllowlist.BANNED_DEV_FID_EXCEPTIONS,
        )
        val al = WriteAllowlist.loadProduction { "{}" }
        val on = al.find("drl_on")
        assertNotNull(on)
        assertEquals(1004, on!!.dev)
        assertEquals(1, on.valueMin)
        assertEquals(2, al.find("drl_off")!!.valueMin)
    }

    // ── Seat heat/vent re-wired to validated dev=1000 switch+level 2026-06-29 ──
    @Test fun `seat heat and vent switch plus level entries are validated on dev 1000`() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val names = listOf(
            "driver_seat_heat_switch", "driver_seat_heat_level",
            "passenger_seat_heat_switch", "passenger_seat_heat_level",
            "driver_seat_vent_switch", "driver_seat_vent_level",
            "passenger_seat_vent_switch", "passenger_seat_vent_level",
        )
        for (name in names) {
            val e = al.find(name)
            assertNotNull("$name must be present", e)
            assertTrue("$name must be validated", e!!.validated)
            assertEquals("$name must be on dev 1000", 1000, e.dev)
        }
        val sw = al.find("driver_seat_heat_switch")!!
        assertEquals(0, sw.valueMin); assertEquals(1, sw.valueMax)
        val lvl = al.find("driver_seat_heat_level")!!
        assertEquals(1, lvl.valueMin); assertEquals(5, lvl.valueMax)
    }

    // ── Fridge carved out of banned dev 1023, validated 2026-06-29 ────────────
    @Test fun `fridge fids are carved out of banned dev 1023`() {
        assertTrue(
            "fridge mode fid must be carved out of dev 1023 ban",
            (1023 to 850427920) in WriteAllowlist.BANNED_DEV_FID_EXCEPTIONS,
        )
        assertTrue(
            "fridge temp fid must be carved out of dev 1023 ban",
            (1023 to 850427928) in WriteAllowlist.BANNED_DEV_FID_EXCEPTIONS,
        )
        val al = WriteAllowlist.loadProduction { "{}" }
        val mode = al.find("fridge_mode")
        assertNotNull(mode); assertEquals(1023, mode!!.dev); assertTrue(mode.validated)
        assertEquals(1, mode.valueMin); assertEquals(3, mode.valueMax)
        val cool = al.find("fridge_temp_cool")
        assertNotNull(cool); assertEquals(13, cool!!.valueMin); assertEquals(25, cool.valueMax)
        val heat = al.find("fridge_temp_heat")
        assertNotNull(heat); assertEquals(35, heat!!.valueMin); assertEquals(50, heat.valueMax)
    }

    // ── Dim 6, Test 6: LIVE_VALIDATED has no duplicate actionName ────────────
    @Test fun `LIVE_VALIDATED has no duplicate actionName case-insensitive`() {
        val liveKeys = WriteAllowlist.LIVE_VALIDATED.map { it.actionName.lowercase() }
        assertEquals(
            "LIVE_VALIDATED must have no duplicate actionName (case-insensitive)",
            liveKeys.distinct().size,
            liveKeys.size,
        )
    }
}
