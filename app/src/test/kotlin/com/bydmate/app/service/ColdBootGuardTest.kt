package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After reboot,bydcloud the screen can still read as awake when the service starts; the service
 * then held its wake lock and GPS until morning. A cold-boot service stops when the screen goes
 * off; a car wake or the user opening the app keeps it like any other.
 */
class ColdBootGuardTest {

    @Test fun `a cold-boot service stops when the screen goes off`() {
        val guard = ColdBootGuard()
        assertFalse(guard.onStart(coldBoot = true, first = true, interactive = true))
        assertTrue(guard.onScreenOff(interactive = false))
    }

    @Test fun `a cold-boot service that starts on a dark screen stops at once`() {
        assertTrue(ColdBootGuard().onStart(coldBoot = true, first = true, interactive = false))
    }

    @Test fun `a service started any other way keeps running when the screen goes off`() {
        val guard = ColdBootGuard()
        assertFalse(guard.onStart(coldBoot = false, first = true, interactive = false))
        assertFalse(guard.onScreenOff(interactive = false))
    }

    @Test fun `a car wake or the app opening after a cold boot disarms the guard`() {
        val guard = ColdBootGuard()
        guard.onStart(coldBoot = true, first = true, interactive = true)
        assertFalse(guard.onStart(coldBoot = false, first = false, interactive = true))
        assertFalse(guard.onScreenOff(interactive = false))
    }

    @Test fun `a cold boot reaching an already running service does not arm it`() {
        val guard = ColdBootGuard()
        guard.onStart(coldBoot = false, first = true, interactive = true)
        assertFalse(guard.onStart(coldBoot = true, first = false, interactive = false))
        assertFalse(guard.onScreenOff(interactive = false))
    }

    @Test fun `driving speed disarms the guard`() {
        assertEquals(10.0, ColdBootGuard.DRIVING_KMH, 0.0)

        val fast = ColdBootGuard()
        fast.onStart(coldBoot = true, first = true, interactive = true)
        fast.onSpeed(20.0)
        assertFalse(fast.onScreenOff(interactive = false))

        val threshold = ColdBootGuard()
        threshold.onStart(coldBoot = true, first = true, interactive = true)
        threshold.onSpeed(10.0)
        assertFalse(threshold.onScreenOff(interactive = false))
    }

    @Test fun `gps jitter or missing speed does not disarm the guard`() {
        val guard = ColdBootGuard()
        guard.onStart(coldBoot = true, first = true, interactive = true)
        guard.onSpeed(3.0)
        guard.onSpeed(null)
        assertTrue(guard.onScreenOff(interactive = false))
    }

    @Test fun `a late screen off while the screen is already on does not stop`() {
        val guard = ColdBootGuard()
        guard.onStart(coldBoot = true, first = true, interactive = true)
        assertFalse(guard.onScreenOff(interactive = true))
        assertTrue(guard.onScreenOff(interactive = false))
    }
}
