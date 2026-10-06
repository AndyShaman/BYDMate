package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCountersTest {

    @Test fun `nothing received reads as zeros`() {
        assertEquals("keys: rx=0 matched=0 passed=0 by_code={}", KeyCounters().line())
    }

    @Test fun `presses are counted by code in arrival order with their outcome`() {
        val counters = KeyCounters()

        counters.onPress(25); counters.onPassed()
        counters.onPress(24); counters.onPassed()
        counters.onPress(25); counters.onPassed()
        counters.onPress(291); counters.onMatched()

        assertEquals("keys: rx=4 matched=1 passed=3 by_code={25:2,24:1,291:1}", counters.line())
    }

    @Test fun `a key that types text is counted without its code`() {
        val counters = KeyCounters()

        counters.onPress(29) // KEYCODE_A
        counters.onPress(8)  // KEYCODE_1
        counters.onPress(24)

        assertEquals("keys: rx=3 matched=0 passed=0 by_code={24:1,text:2}", counters.line())
    }

    @Test fun `codes past the cap are lumped together`() {
        val counters = KeyCounters(maxCodes = 2)

        counters.onPress(24)
        counters.onPress(25)
        counters.onPress(300)
        counters.onPress(301)
        counters.onPress(24)

        assertEquals("keys: rx=5 matched=0 passed=0 by_code={24:2,25:1,other:2}", counters.line())
    }
}
