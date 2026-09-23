package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The verdict table, first match wins. */
class AdbVerdictTest {

    @Suppress("LongParameterList")
    private fun eval(
        healthy: Boolean = false,
        connected: Boolean = false,
        failure: AdbConnectFailure? = null,
        restore: AdbRestoreState = AdbRestoreState.Disabled,
        wifi: Int = 0,
        everAlive: Boolean = false,
    ) = evaluateAdbVerdict(healthy, connected, failure, restore, wifi, everAlive)

    @Test
    fun `row 1 - healthy daemon is OK`() {
        assertEquals(AdbVerdict.OK, eval(healthy = true))
    }

    @Test
    fun `healthy wins over everything`() {
        assertEquals(
            AdbVerdict.OK,
            eval(
                healthy = true, connected = true, failure = AdbConnectFailure.AUTH_REJECTED,
                restore = AdbRestoreState.Connecting, wifi = 0, everAlive = false,
            ),
        )
    }

    @Test
    fun `row 2 - socket up with a dead daemon is HELPER_DOWN`() {
        assertEquals(AdbVerdict.HELPER_DOWN, eval(connected = true))
        assertEquals(AdbVerdict.HELPER_DOWN, eval(connected = true, restore = AdbRestoreState.Connecting))
    }

    @Test
    fun `row 3 - Connecting hides the verdict only when neither healthy nor connected`() {
        assertNull(eval(restore = AdbRestoreState.Connecting))
        assertNull(eval(restore = AdbRestoreState.Connecting, failure = AdbConnectFailure.AUTH_REJECTED))
    }

    @Test
    fun `row 4 - rejected key is NO_ACCESS`() {
        assertEquals(AdbVerdict.NO_ACCESS, eval(failure = AdbConnectFailure.AUTH_REJECTED, wifi = 1, everAlive = true))
    }

    @Test
    fun `row 4 - NeedsDialog is NO_ACCESS even with adb_wifi_enabled 0`() {
        assertEquals(AdbVerdict.NO_ACCESS, eval(restore = AdbRestoreState.NeedsDialog, wifi = 0, everAlive = false))
    }

    @Test
    fun `row 5 - wireless debugging off on an install that never had a daemon is NOT_ENABLED`() {
        assertEquals(AdbVerdict.NOT_ENABLED, eval(failure = AdbConnectFailure.UNREACHABLE, wifi = 0, everAlive = false))
    }

    @Test
    fun `row 6 - wireless debugging on but never alive is OFF_AFTER_REBOOT`() {
        assertEquals(AdbVerdict.OFF_AFTER_REBOOT, eval(wifi = 1, everAlive = false))
    }

    @Test
    fun `row 6 - daemon was alive before, port closed now is OFF_AFTER_REBOOT`() {
        assertEquals(AdbVerdict.OFF_AFTER_REBOOT, eval(wifi = 0, everAlive = true))
    }
}
