package com.bydmate.app.service

/**
 * Keeps a service started by a cold boot ([AutostartTrace.isColdBoot]) only while the screen is on.
 * After reboot,bydcloud on a parked car the screen can still read as awake when BOOT_COMPLETED
 * arrives; the service then held its wake lock and GPS until the driver came back (#441, #491,
 * case 197). Any other start (a car wake or the user opening the app) or driving disarms the guard,
 * so a normal drive or a mid-drive head-unit restart is never cut short by a dark screen.
 * Written on the main thread only; [armed] may be read from any thread.
 */
internal class ColdBootGuard {
    companion object {
        const val DRIVING_KMH = 10.0
    }

    @Volatile var armed = false
        private set

    /** [first] is the start that created the service. Returns true when the service should stop now. */
    fun onStart(coldBoot: Boolean, first: Boolean, interactive: Boolean): Boolean {
        if (!coldBoot) armed = false else if (first) armed = true
        return armed && !interactive
    }

    /** Driving disarms the guard; null or lower speed does nothing. */
    fun onSpeed(kmh: Double?) {
        if (kmh != null && kmh >= DRIVING_KMH) armed = false
    }

    /** Returns true when the service should stop now. */
    fun onScreenOff(interactive: Boolean): Boolean = armed && !interactive
}
