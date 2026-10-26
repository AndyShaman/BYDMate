package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restart enqueued by onTaskRemoved / onDestroy must not bring the service (and its wake lock)
 * back on a parked car: the screen is off then. Every other source starts as before.
 */
class ServiceStartWorkerGateTest {

    @Test fun `task_removed with the screen off is skipped`() {
        assertFalse(ServiceStartWorker.shouldStart(AutostartTrace.SOURCE_TASK_REMOVED, interactive = false))
    }

    @Test fun `service_destroyed with the screen off is skipped`() {
        assertFalse(ServiceStartWorker.shouldStart(AutostartTrace.SOURCE_SERVICE_DESTROYED, interactive = false))
    }

    @Test fun `task_removed and service_destroyed start when the screen is on`() {
        assertTrue(ServiceStartWorker.shouldStart(AutostartTrace.SOURCE_TASK_REMOVED, interactive = true))
        assertTrue(ServiceStartWorker.shouldStart(AutostartTrace.SOURCE_SERVICE_DESTROYED, interactive = true))
    }

    @Test fun `other sources start whether the screen is on or off`() {
        val others = listOf(
            "BOOT_COMPLETED", "USER_PRESENT", "MY_PACKAGE_REPLACED",
            "RECOVER_START", BootReceiver.ACTION_RECOVER_START, "something_new", "", null,
        )
        for (source in others) {
            assertTrue("$source off", ServiceStartWorker.shouldStart(source, interactive = false))
            assertTrue("$source on", ServiceStartWorker.shouldStart(source, interactive = true))
        }
    }
}
