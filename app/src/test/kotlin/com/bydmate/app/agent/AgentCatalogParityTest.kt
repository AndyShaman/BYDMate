package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.ui.automation.ACTION_COMMANDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift net: every id the LLM sees in vehicle_control MUST produce a Chinese
 * command the engine can actually dispatch (CommandTranslator table/composite/
 * dynamic, or the seat parser). A catalog id that fails here is a dead button
 * the agent would confidently "press" with no effect.
 */
class AgentCatalogParityTest {

    @Test fun every_catalog_id_is_dispatchable() {
        AgentCommandCatalog.ALL.forEach { cmd ->
            // first+1 dodges the fixed composite presets (fridge presets sit on
            // -6/-3/0/3/6 and 35/40/45/50), forcing the dynamic-resolution path.
            val samples: List<Int?> =
                cmd.value?.let { r -> listOf(r.first, r.last, r.first + 1).filter { it in r }.distinct() }
                    ?: listOf(null)
            samples.forEach { v ->
                val chinese = AgentCommandCatalog.resolve(cmd.id, v)
                assertNotNull("id=${cmd.id} value=$v did not resolve", chinese)
                val dispatchable = CommandTranslator.resolve(chinese!!).isNotEmpty() ||
                    CommandTranslator.resolveSeat(chinese) != null
                assertTrue("id=${cmd.id} -> $chinese is not dispatchable", dispatchable)
            }
        }
    }

    /**
     * The other direction: an action a driver can pick in a rule must be known to the agent too,
     * or it answers "I cannot do that" for something the app does (the spoiler of 4.4.1 and the
     * rear logo light were both added to automation only). The fridge's two temperature actions
     * take a number in the agent catalog, so they match by their command prefix.
     */
    @Test fun every_automation_action_is_known_to_the_agent() {
        val fixed = AgentCommandCatalog.ALL.filter { it.value == null }.map { it.chinese(null) }.toSet()
        val ranged = AgentCommandCatalog.ALL.filter { it.value != null }.map { it.chinese(it.value!!.first) }
        val missing = ACTION_COMMANDS.map { it.command }
            .filter { it.isNotEmpty() }
            .filter { command -> command !in fixed && ranged.none { it.startsWith(command) } }
        assertEquals("automation actions the agent catalog lacks", emptyList<String>(), missing)
    }

    @Test fun catalog_ids_are_unique() {
        val ids = AgentCommandCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
