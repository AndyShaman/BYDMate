package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #408 Case 11: AgentCommandCatalog exposes fixed command ids
 * steering_heat_level_1 .. steering_heat_level_5 with no value parameter.
 */
class AgentCommandCatalogSteeringHeatLevelTest {

    @Test fun `AgentCommandCatalog has fixed ids steering_heat_level_1 to steering_heat_level_5`() {
        val resolvedCommands = mutableSetOf<String>()
        val idsDoc = AgentCommandCatalog.idsDoc()

        for (level in 1..5) {
            val id = "steering_heat_level_$level"
            val cmd = AgentCommandCatalog.ALL.firstOrNull { it.id == id }
            assertNotNull("$id must be present in AgentCommandCatalog.ALL", cmd)
            assertNull("$id must not take a value parameter", cmd!!.value)

            val resolved = AgentCommandCatalog.resolve(id, null)
            assertNotNull("$id must resolve without a value parameter", resolved)
            resolvedCommands += resolved!!
            assertTrue("$id must appear in idsDoc()", idsDoc.lines().any { it.startsWith("$id ") })
        }

        assertEquals(5, resolvedCommands.size)
        assertFalse("idsDoc() must contain no Chinese characters", idsDoc.any { it.code > 0x2E80 })
    }
}
