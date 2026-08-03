package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionDiagnosticsTest {

    @Test
    fun `formats channel lines and null-safe when manager absent`() {
        assertEquals(listOf("unavailable"), SubscriptionDiagnostics.format(null))
        assertEquals(listOf("a", "b"), SubscriptionDiagnostics.format(listOf("a", "b")))
    }
}
