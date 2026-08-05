package com.bydmate.app.split

import com.bydmate.app.helper.HelperBinderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #130: STANDARD panes are opt-in per firmware, everything else falls back to RECENTS.
 * The direction of the fallback is the point of these tests — an unknown firmware must never
 * end up on the type that kills a pane on the first tap.
 */
class PaneTypePolicyTest {

    @Test fun `Leopard 3 firmware gets STANDARD panes`() {
        val policy = PaneTypePolicy("BYD/Leopard3/eng.build.20260106:12/user/release-keys")
        assertTrue(policy.knownGood)
        assertEquals(HelperBinderProtocol.PANE_TYPE_STANDARD, policy.paneType)
    }

    @Test fun `Song L 20260320 firmware falls back to RECENTS`() {
        val policy = PaneTypePolicy("BYD/SongL/eng.build.20260320:12/user/release-keys")
        assertFalse(policy.knownGood)
        assertEquals(HelperBinderProtocol.PANE_TYPE_RECENTS, policy.paneType)
    }

    @Test fun `Han EV 20260516 firmware falls back to RECENTS`() {
        val policy = PaneTypePolicy("BYD/HanEV/eng.build.20260516:12/user/release-keys")
        assertFalse(policy.knownGood)
        assertEquals(HelperBinderProtocol.PANE_TYPE_RECENTS, policy.paneType)
    }

    @Test fun `empty fingerprint falls back to RECENTS`() {
        assertEquals(HelperBinderProtocol.PANE_TYPE_RECENTS, PaneTypePolicy("").paneType)
    }

    @Test fun `garbage fingerprint falls back to RECENTS`() {
        assertEquals(HelperBinderProtocol.PANE_TYPE_RECENTS, PaneTypePolicy("???").paneType)
    }

    @Test fun `real fingerprint carrying a build timestamp still gets STANDARD panes`() {
        val policy = PaneTypePolicy(
            "BYD/Leopard3/leopard3:12/eng.build.20260106.201352/test-keys")
        assertTrue(policy.knownGood)
    }

    @Test fun `a build id that only starts with a known-good one falls back to RECENTS`() {
        // "contains" would accept all three: a longer id is a different firmware, untested.
        assertFalse(PaneTypePolicy("BYD/X/eng.build.202601060:12/user/release-keys").knownGood)
        assertFalse(PaneTypePolicy("BYD/X/eng.build.20260106X:12/user/release-keys").knownGood)
        assertFalse(PaneTypePolicy("BYD/X/eng.build.20260106beta").knownGood)
    }

    @Test fun `a truncated build id falls back to RECENTS`() {
        assertFalse(PaneTypePolicy("BYD/X/eng.build.2026010:12/user/release-keys").knownGood)
    }

    @Test fun `label names the chosen type and the branch that chose it`() {
        assertEquals("standard(known-good)", PaneTypePolicy("eng.build.20260106").label)
        assertEquals("recents(fallback)", PaneTypePolicy("eng.build.20260320").label)
    }
}
