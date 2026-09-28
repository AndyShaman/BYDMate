package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNameMatcherTest {
    @Test fun `matches exact, case and yo insensitive`() {
        assertTrue(AgentNameMatcher.matches("Лео, стой", "Лео"))
        assertTrue(AgentNameMatcher.matches("эй лёва", "Лёва"))
        assertTrue(AgentNameMatcher.matches("лева стоп", "Лёва"))
    }
    @Test fun `matches ASR near-misses within distance 1 for length ge 4`() {
        assertTrue(AgentNameMatcher.matches("лева погоди", "Лёва"))
        assertTrue(AgentNameMatcher.matches("тёма стой", "Тема"))
    }
    @Test fun `short names require exact token, longer words do not match`() {
        assertFalse(AgentNameMatcher.matches("леонид звонил", "Лео"))
        assertFalse(AgentNameMatcher.matches("леол", "Лео"))   // len 3 -> exact only
        assertFalse(AgentNameMatcher.matches("открой окно", "Лео"))
    }
    @Test fun `blank name never matches`() {
        assertFalse(AgentNameMatcher.matches("лео стой", ""))
    }
    @Test fun `strips leading name token with punctuation`() {
        assertEquals("открой окно", AgentNameMatcher.stripLeadingName("Лео, открой окно", "Лео"))
        assertEquals("открой окно", AgentNameMatcher.stripLeadingName("открой окно", "Лео"))
    }
    @Test fun `name-only utterance is returned unchanged`() {
        assertEquals("Лео", AgentNameMatcher.stripLeadingName("Лео", "Лео"))
    }
}
