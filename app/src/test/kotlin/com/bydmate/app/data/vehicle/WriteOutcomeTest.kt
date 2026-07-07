package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class WriteOutcomeTest {
    @Test fun `status 1 is REAL`() = assertEquals(WriteOutcome.REAL, WriteOutcome.fromStatus(1))
    @Test fun `status 0 is NOOP`() = assertEquals(WriteOutcome.NOOP, WriteOutcome.fromStatus(0))
    @Test fun `status -10011 is PERMANENT_DENIED`() =
        assertEquals(WriteOutcome.PERMANENT_DENIED, WriteOutcome.fromStatus(-10011))
    @Test fun `transient negatives are TRANSIENT`() {
        assertEquals(WriteOutcome.TRANSIENT, WriteOutcome.fromStatus(-10013))
        assertEquals(WriteOutcome.TRANSIENT, WriteOutcome.fromStatus(-1))
        assertEquals(WriteOutcome.TRANSIENT, WriteOutcome.fromStatus(-999))
    }
    @Test fun `null status is TRANSIENT`() = assertEquals(WriteOutcome.TRANSIENT, WriteOutcome.fromStatus(null))
    @Test fun `positive other than 1 is REAL`() = assertEquals(WriteOutcome.REAL, WriteOutcome.fromStatus(2))
}
