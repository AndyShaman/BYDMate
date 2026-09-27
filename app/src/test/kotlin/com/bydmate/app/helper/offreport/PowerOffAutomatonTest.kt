package com.bydmate.app.helper.offreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerOffAutomatonTest {

    private val level = 315621418
    private val powerState = 315621408

    @Test fun `a drop to 0 after a non-zero value is the power-off`() {
        val a = PowerOffAutomaton()
        assertFalse(a.onEvent(level, 2))
        assertTrue(a.onEvent(level, 0))
    }

    @Test fun `both fids dropping together fire once per cycle`() {
        val a = PowerOffAutomaton()
        a.onEvent(level, 2)
        a.onEvent(powerState, 1)
        assertTrue(a.onEvent(level, 0))
        assertFalse(a.onEvent(powerState, 0))
        assertFalse(a.onEvent(level, 0))
    }

    @Test fun `a daemon that starts with the car off never fires`() {
        val a = PowerOffAutomaton()
        a.seed(level, 0)
        a.seed(powerState, 0)
        assertFalse(a.onEvent(level, 0))
        assertFalse(a.onEvent(powerState, 0))
    }

    @Test fun `power coming back re-arms it for the next cycle`() {
        val a = PowerOffAutomaton()
        a.onEvent(level, 2)
        assertTrue(a.onEvent(level, 0))
        assertFalse(a.primed)
        a.onEvent(level, 2)
        assertTrue(a.primed)
        assertTrue(a.onEvent(level, 0))
    }

    @Test fun `a seed from a direct read primes it but never fires`() {
        val a = PowerOffAutomaton()
        a.seed(level, 2)
        assertTrue(a.primed)
        assertTrue(a.onEvent(level, 0))
    }

    @Test fun `a seed of 0 never unprimes, so a pending 0 push still fires`() {
        val a = PowerOffAutomaton()
        a.seed(level, 2)
        a.seed(level, 0) // read raced ahead of the queued push
        assertTrue(a.primed)
        assertTrue(a.onEvent(level, 0))
    }

    @Test fun `a powerState stuck at 0 on a hybrid leaves the power level in charge`() {
        val a = PowerOffAutomaton()
        a.seed(powerState, 0)
        a.seed(level, 2)
        assertFalse(a.onEvent(powerState, 0))
        assertTrue(a.onEvent(level, 0))
    }

    @Test fun `negative sentinels are ignored`() {
        val a = PowerOffAutomaton()
        a.onEvent(level, 2)
        assertFalse(a.onEvent(level, -1))
        assertTrue(a.primed)
        assertEquals(true, a.onEvent(level, 0))
    }

    @Test fun `every pushed event moves the generation, a seed does not`() {
        val a = PowerOffAutomaton()
        val g0 = a.generation
        a.seed(level, 2)
        assertEquals(g0, a.generation)
        a.onEvent(level, 2)
        a.onEvent(level, -1)
        assertEquals(g0 + 2, a.generation)
    }
}
