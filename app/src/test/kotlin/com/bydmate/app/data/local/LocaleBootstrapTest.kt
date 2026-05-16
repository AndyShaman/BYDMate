package com.bydmate.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleBootstrapTest {

    @Test
    fun `upgrader gets ru`() {
        assertEquals("ru", decideLanguage(setupCompleted = true))
    }

    @Test
    fun `fresh install gets en`() {
        assertEquals("en", decideLanguage(setupCompleted = false))
    }
}
