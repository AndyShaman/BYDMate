package com.bydmate.app.ui.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sentry and the cluster projection have a three-state row: Вкл, Выкл, Переключить. Picking
 * «Переключить» stores a toggle action, and the row must still be the one that edits it —
 * otherwise the two other states are unreachable and the action can only be deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ToggleRowStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun toggleAction(target: String) = ActionDef(
        command = "", displayName = toggleDisplayName(app, target), kind = "toggle", payload = target,
    )

    @Test fun `a stored sentry toggle comes back to the sentry row`() {
        assertEquals(SENTRY_ROW, toggleRowSpecFor(toggleAction(ActionDispatcher.TOGGLE_SENTRY)))
        assertEquals(SENTRY_ROW, toggleRowSpecFor(newSentryAction(app)))
    }

    @Test fun `a stored cluster toggle comes back to the cluster row`() {
        assertEquals(CLUSTER_ROW, toggleRowSpecFor(toggleAction(ActionDispatcher.TOGGLE_CLUSTER)))
        assertEquals(CLUSTER_ROW, toggleRowSpecFor(newClusterAction(app)))
    }

    @Test fun `catalog toggles keep the target dropdown as their editor`() {
        assertNull(toggleRowSpecFor(toggleAction(ActionDispatcher.TOGGLE_TRUNK)))
        assertNull(toggleRowSpecFor(toggleAction(ActionDispatcher.TOGGLE_CLIMATE)))
        assertNull(toggleRowSpecFor(ActionDef("开后备箱", "Багажник", kind = "param")))
    }

    @Test fun `on then toggle then off ends up where a fresh off would`() {
        val lc = app.appLocalizedContext()
        val label = lc.getString(R.string.automation_action_sentry)
        val offName = "$label: " + lc.getString(R.string.automation_action_sentry_off)

        val fresh = rowStateAction(SENTRY_ROW, "0", offName)
        val roundTrip = rowStateAction(
            toggleRowSpecFor(toggleAction(ActionDispatcher.TOGGLE_SENTRY))!!, "0", offName,
        )
        assertEquals(fresh, roundTrip)
        // And a fresh "off" is the same row the action factory produces, just flipped.
        assertEquals(newSentryAction(app).copy(displayName = offName, payload = "0"), fresh)
    }

    @Test fun `the cluster row restores its own kind and command after a toggle`() {
        val restored = rowStateAction(CLUSTER_ROW, "1", "x")
        assertEquals("cluster_projection", restored.kind)
        assertEquals("cluster_projection", restored.command)
        assertEquals("1", restored.payload)
    }
}
