package com.bydmate.app.helper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the AutoContainer privilege boundary in HelperDaemon.
 *
 * These guard the two facts that made cluster projection work (and kept it safe) on DiLink 5:
 *   1. only sendInfo values {16, 18, 0} are accepted — and 1 (which destroys the cluster display)
 *      is rejected;
 *   2. the service name is resolved DL5-first ("auto_container" before "AutoContainer") — the
 *      original bug was resolving the DL3 PascalCase name only.
 */
class HelperDaemonAutoContainerTest {

    @Test fun `whitelist accepts the three projection commands`() {
        assertTrue("16 = enable fullscreen projection", isAllowedAutoContainerInfo(16))
        assertTrue("18 = stop projection", isAllowedAutoContainerInfo(18))
        assertTrue("0 = refresh native cluster stream", isAllowedAutoContainerInfo(0))
    }

    @Test fun `whitelist rejects the destructive value 1`() {
        // sendInfo(1) disconnects Qt entirely and destroys cluster display 1 — must never pass.
        assertFalse("1 must stay rejected", isAllowedAutoContainerInfo(1))
    }

    @Test fun `whitelist rejects DL3-only and out-of-range values`() {
        // 30/35 are DiLink 3.0 screen-size / Di4.0 commands, never used on DL5.
        for (v in intArrayOf(30, 35, -1, 2, 17, 19, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse("value $v must be rejected", isAllowedAutoContainerInfo(v))
        }
    }

    @Test fun `service names try DiLink 5 snake_case before DiLink 3 PascalCase`() {
        assertArrayEquals(
            "DL5 auto_container must be tried first, then DL3 AutoContainer",
            arrayOf("auto_container", "AutoContainer"),
            AUTO_CONTAINER_SERVICE_NAMES,
        )
        assertEquals("auto_container", AUTO_CONTAINER_SERVICE_NAMES.first())
    }
}
