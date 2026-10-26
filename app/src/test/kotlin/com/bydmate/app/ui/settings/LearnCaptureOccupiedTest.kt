package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R1/F2: the voice and projection learn dialogs must reject a capture whose primary code OR any
 * companion code is the split key. New pure function the implementer creates in this package:
 * `internal fun captureOccupiedReason(keyCode: Int, companions: Set<Int>, occupiedReason: (Int) -> String?): String?`
 */
class LearnCaptureOccupiedTest {
    private val splitKey = 351
    private val reason: (Int) -> String? = { if (it == splitKey) "split" else null }

    @Test fun `free primary with the split key as companion is rejected`() {
        assertEquals("split", captureOccupiedReason(320, setOf(splitKey), reason))
    }
    @Test fun `split key as primary is rejected`() {
        assertEquals("split", captureOccupiedReason(splitKey, emptySet(), reason))
    }
    @Test fun `neither primary nor companions are the split key`() {
        assertNull(captureOccupiedReason(320, setOf(327), reason))
    }
}
