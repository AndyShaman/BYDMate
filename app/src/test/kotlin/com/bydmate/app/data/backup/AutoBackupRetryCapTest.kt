package com.bydmate.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupRetryCapTest {

    @Test fun `retry passes through below the cap`() {
        assertEquals(RunOutcome.RETRY, capRetries(RunOutcome.RETRY, 0))
        assertEquals(RunOutcome.RETRY, capRetries(RunOutcome.RETRY, MAX_RETRY_ATTEMPT - 1))
    }

    @Test fun `retry becomes success from attempt index 2, three attempts in all`() {
        assertEquals(RunOutcome.RETRY, capRetries(RunOutcome.RETRY, 1))
        assertEquals(RunOutcome.SUCCESS, capRetries(RunOutcome.RETRY, 2))
        assertEquals(RunOutcome.SUCCESS, capRetries(RunOutcome.RETRY, MAX_RETRY_ATTEMPT + 5))
        assertEquals(2, MAX_RETRY_ATTEMPT)
    }

    @Test fun `only the first attempt of a manual run exports afresh`() {
        assertTrue(forceExport(manual = true, attempt = 0))
        assertFalse(forceExport(manual = true, attempt = 1))
        assertFalse(forceExport(manual = false, attempt = 0))
    }

    @Test fun `success and failure are never changed`() {
        for (attempt in listOf(0, MAX_RETRY_ATTEMPT, 10)) {
            assertEquals(RunOutcome.SUCCESS, capRetries(RunOutcome.SUCCESS, attempt))
            assertEquals(RunOutcome.FAILURE, capRetries(RunOutcome.FAILURE, attempt))
        }
    }
}
