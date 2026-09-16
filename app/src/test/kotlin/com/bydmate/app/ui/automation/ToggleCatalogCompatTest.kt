package com.bydmate.app.ui.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Переключить» moved from its own kind entry in the add-action list into the catalog,
 * next to each on/off pair. The stored row must not have moved with it: a rule saved by
 * v3.16 and a rule created by the new catalog have to be the same four fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ToggleCatalogCompatTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** What the retired kind picker wrote for a target. */
    private fun legacyToggle(target: String) = ActionDef(
        command = "",
        displayName = toggleDisplayName(app, target),
        kind = "toggle",
        payload = target,
    )

    private val catalogToggles = ACTION_COMMANDS.filter { it.toggleTarget != null }

    @Test fun `every catalog toggle entry stores the legacy action verbatim`() {
        assertTrue(catalogToggles.isNotEmpty())
        catalogToggles.forEach { option ->
            val target = option.toggleTarget!!
            assertEquals(legacyToggle(target), actionDefFor(option, app))
            // Byte-identical down to the serialized row, not just structurally equal.
            assertEquals(
                legacyToggle(target).toJson().toString(),
                actionDefFor(option, app).toJson().toString(),
            )
        }
    }

    @Test fun `the default trunk toggle of the old picker is one of the catalog entries`() {
        val fromCatalog = catalogToggles.first { it.toggleTarget == ActionDispatcher.TOGGLE_TRUNK }
        assertEquals(newToggleAction(app).toJson().toString(), actionDefFor(fromCatalog, app).toJson().toString())
    }

    @Test fun `all targets are reachable — catalog plus the two control rows`() {
        // Sentry and cluster projection have their own on/off rows, where «Переключить»
        // is the third state instead of a catalog entry.
        val inCatalog = catalogToggles.mapNotNull { it.toggleTarget }.toSet()
        val onControlRows = setOf(ActionDispatcher.TOGGLE_SENTRY, ActionDispatcher.TOGGLE_CLUSTER)
        assertEquals(ActionDispatcher.TOGGLE_TARGETS.toSet(), inCatalog + onControlRows)
        assertTrue(inCatalog.intersect(onControlRows).isEmpty())
    }

    @Test fun `a v3_16 rule keeps its display name after the picker change`() {
        ActionDispatcher.TOGGLE_TARGETS.forEach { target ->
            val stored = ActionDef.listToJson(listOf(legacyToggle(target)))
            val parsed = ActionDef.listFromJson(stored).single()
            assertEquals("toggle", parsed.kind)
            assertEquals(target, parsed.payload)
            assertEquals(toggleDisplayName(app, target), parsed.displayName)
        }
    }
}
