package com.bydmate.app.split

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hotfix 390-2: the backdrop re-raises the panes only when it actually resurfaced — i.e. it was
 * stopped (covered by a fullscreen Activity of our package) and came back while a session runs.
 */
class SplitStageResurfaceTest {

    @Test fun `stopped then resumed with a live session is a resurface`() {
        assertTrue(shouldReassertPanes(wasStopped = true, sessionActive = true))
    }

    @Test fun `first resume of a fresh session is not a resurface`() {
        assertFalse(shouldReassertPanes(wasStopped = false, sessionActive = true))
    }

    @Test fun `resume without a session never re-raises`() {
        assertFalse(shouldReassertPanes(wasStopped = true, sessionActive = false))
        assertFalse(shouldReassertPanes(wasStopped = false, sessionActive = false))
    }
}
