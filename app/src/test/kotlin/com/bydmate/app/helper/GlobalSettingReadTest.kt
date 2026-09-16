package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read side of TX_GET_GLOBAL_SETTING. It runs on the binder thread the app blocks on, so a
 * `settings` call that never returns must be killed instead of pinning the daemon; an unknown
 * state is answered with null and the toggle refuses.
 */
class GlobalSettingReadTest {

    @Test fun `a numeric value is parsed from the first line`() {
        assertEquals(1, parseGlobalSettingValue("1\n"))
        assertEquals(0, parseGlobalSettingValue(" 0 "))
    }

    @Test fun `null, noise and a timeout marker are unknown`() {
        assertNull(parseGlobalSettingValue("null"))
        assertNull(parseGlobalSettingValue(""))
        assertNull(parseGlobalSettingValue("Exception: no such setting"))
        assertNull(parseGlobalSettingValue("\n[timeout 2000ms]"))
    }

    @Test fun `a key outside the whitelist is not read at all`() {
        assertNull(readGlobalSetting("adb_enabled"))
        assertNull(readGlobalSetting(""))
    }

    @Test fun `a hung shell call is killed within the bound and reads as unknown`() {
        val timeoutMs = 400L
        val started = System.currentTimeMillis()
        val out = shExecBounded("sleep 30; echo 1", timeoutMs, 256)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("the call must not outlive its bound: ${elapsed}ms", elapsed < timeoutMs * 10)
        assertTrue("a killed call is marked: '$out'", out.contains("[timeout "))
        assertNull("an unfinished read is an unknown state", parseGlobalSettingValue(out))
    }

    @Test fun `the key is passed as an argument, never spliced into the script`() {
        assertEquals("sentrymode_enabled_switch", shExecBounded("echo \"\$1\"", 2000L, 256, "sentrymode_enabled_switch"))
    }
}
