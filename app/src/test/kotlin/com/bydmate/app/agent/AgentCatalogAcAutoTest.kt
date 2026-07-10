package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AC auto-mode commands (fid 501219352, live-validated 2026-07-07).
 * VALUE SEMANTICS ARE INVERTED vs ac_on: 0=enable auto, 1=disable (switch to manual).
 */
class AgentCatalogAcAutoTest {

    @Test fun ac_auto_on_resolves_from_catalog() {
        assertEquals("空调自动", AgentCommandCatalog.resolve("ac_auto_on", null))
    }

    @Test fun ac_auto_off_resolves_from_catalog() {
        assertEquals("空调手动", AgentCommandCatalog.resolve("ac_auto_off", null))
    }

    @Test fun translator_maps_ac_auto_on_to_action_and_value_0() {
        val r = CommandTranslator.resolve("空调自动")
        assertEquals("must resolve to exactly one action", 1, r.size)
        assertEquals("ac_auto_on", r[0].actionName)
        // INVERTED SEMANTICS: fid 501219352 value 0 = enable auto mode
        assertEquals(0, r[0].value)
    }

    @Test fun translator_maps_ac_auto_off_to_action_and_value_1() {
        val r = CommandTranslator.resolve("空调手动")
        assertEquals("must resolve to exactly one action", 1, r.size)
        assertEquals("ac_auto_off", r[0].actionName)
        // INVERTED SEMANTICS: fid 501219352 value 1 = disable auto mode (switch to manual)
        assertEquals(1, r[0].value)
    }

    // Pins the inverted semantics: a swapped pair would physically toggle the wrong way in the car
    @Test fun inverted_semantics_ac_auto_on_value_must_be_zero_not_one() {
        val r = CommandTranslator.resolve("空调自动")
        assertFalse(
            "ac_auto_on must dispatch value 0 (not 1) — inverted vs ac_on fid",
            r.any { it.value == 1 },
        )
    }

    @Test fun inverted_semantics_ac_auto_off_value_must_be_one_not_zero() {
        val r = CommandTranslator.resolve("空调手动")
        assertFalse(
            "ac_auto_off must dispatch value 1 (not 0) — inverted vs ac_off fid",
            r.any { it.value == 0 },
        )
    }

    @Test fun allowlist_ac_auto_on_has_correct_fid_dev_range() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val e = al.find("ac_auto_on")
        assertNotNull("ac_auto_on must be in allowlist", e)
        assertEquals("dev must be 1000", 1000, e!!.dev)
        assertEquals("writeFid must be 501219352", 501219352, e.writeFid)
        // value 0 = enable auto mode — both bounds pinned to 0
        assertEquals("valueMin must be 0 (enable auto)", 0, e.valueMin)
        assertEquals("valueMax must be 0 (single value pin)", 0, e.valueMax)
        assertEquals("climate", e.category)
        assertTrue("must be validated", e.validated)
        assertEquals("live-leopard3-2026-07-07", e.source)
    }

    @Test fun allowlist_ac_auto_off_has_correct_fid_dev_range() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val e = al.find("ac_auto_off")
        assertNotNull("ac_auto_off must be in allowlist", e)
        assertEquals("dev must be 1000", 1000, e!!.dev)
        assertEquals("writeFid must be 501219352", 501219352, e.writeFid)
        // value 1 = disable auto mode — both bounds pinned to 1
        assertEquals("valueMin must be 1 (disable auto)", 1, e.valueMin)
        assertEquals("valueMax must be 1 (single value pin)", 1, e.valueMax)
        assertEquals("climate", e.category)
        assertTrue("must be validated", e.validated)
        assertEquals("live-leopard3-2026-07-07", e.source)
    }

    // End-to-end: catalog → translator → allowlist — the full dispatch chain
    @Test fun end_to_end_ac_auto_on_chain() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val chinese = AgentCommandCatalog.resolve("ac_auto_on", null)
        assertNotNull(chinese)
        val resolved = CommandTranslator.resolve(chinese!!)
        assertEquals(1, resolved.size)
        val entry = al.find(resolved[0].actionName)
        assertNotNull("ac_auto_on must be in allowlist", entry)
        val v = resolved[0].value
        assertTrue(
            "value $v must be in allowlist range ${entry!!.valueMin}..${entry.valueMax}",
            v in entry.valueMin..entry.valueMax,
        )
    }

    @Test fun end_to_end_ac_auto_off_chain() {
        val al = WriteAllowlist.loadProduction { "{}" }
        val chinese = AgentCommandCatalog.resolve("ac_auto_off", null)
        assertNotNull(chinese)
        val resolved = CommandTranslator.resolve(chinese!!)
        assertEquals(1, resolved.size)
        val entry = al.find(resolved[0].actionName)
        assertNotNull("ac_auto_off must be in allowlist", entry)
        val v = resolved[0].value
        assertTrue(
            "value $v must be in allowlist range ${entry!!.valueMin}..${entry.valueMax}",
            v in entry.valueMin..entry.valueMax,
        )
    }
}
