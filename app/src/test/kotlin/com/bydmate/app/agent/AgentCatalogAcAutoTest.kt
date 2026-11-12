package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AC auto-mode commands (fid 501219352, live-validated 2026-07-07).
 * VALUE SEMANTICS ARE INVERTED vs ac_on: 0=enable auto, 1=disable (switch to manual).
 * A swapped pair would physically toggle the wrong way in the car.
 */
class AgentCatalogAcAutoTest {

    // catalog → translator → value → allowlist, for one direction
    private fun checkChain(id: String, chineseCommand: String, expectedValue: Int) {
        val chinese = AgentCommandCatalog.resolve(id, null)
        assertEquals("$id: catalog string", chineseCommand, chinese)
        val resolved = CommandTranslator.resolve(chinese!!)
        assertEquals("$id: must resolve to exactly one action", 1, resolved.size)
        assertEquals(id, resolved[0].actionName)
        val v = resolved[0].value
        assertEquals("$id: inverted semantics, value must be $expectedValue", expectedValue, v)

        val e = WriteAllowlist.loadProduction { "{}" }.find(resolved[0].actionName)
        assertNotNull("$id must be in allowlist", e)
        assertEquals("$id: dev must be 1000", 1000, e!!.dev)
        assertEquals("$id: writeFid must be 501219352", 501219352, e.writeFid)
        // single-value pin: both bounds equal the expected value
        assertEquals("$id: valueMin", expectedValue, e.valueMin)
        assertEquals("$id: valueMax", expectedValue, e.valueMax)
        assertTrue("$id: must be validated", e.validated)
        assertTrue(
            "value $v must be in allowlist range ${e.valueMin}..${e.valueMax}",
            v in e.valueMin..e.valueMax,
        )
    }

    @Test fun ac_auto_on_chain_writes_value_0() = checkChain("ac_auto_on", "空调自动", 0)

    @Test fun ac_auto_off_chain_writes_value_1() = checkChain("ac_auto_off", "空调手动", 1)
}
