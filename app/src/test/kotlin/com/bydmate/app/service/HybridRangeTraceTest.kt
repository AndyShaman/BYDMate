package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRangeTraceTest {

    @Test fun `due logs once on first odometer reading, every 1 km after, and on odometer reset`() {
        assertFalse(HybridRangeTrace.due(null, null))
        assertTrue(HybridRangeTrace.due(null, 1000.0))
        assertFalse(HybridRangeTrace.due(1000.0, 1000.9))
        assertTrue(HybridRangeTrace.due(1000.0, 1001.0))
        assertTrue(HybridRangeTrace.due(1000.0, 999.0))
    }
}
