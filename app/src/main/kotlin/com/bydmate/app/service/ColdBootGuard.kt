package com.bydmate.app.service

/**
 * Keeps a service started by a cold boot ([AutostartTrace.isColdBoot]) only while the screen is on.
 * After reboot,bydcloud on a parked car the screen can still read as awake when BOOT_COMPLETED
 * arrives; the service then held its wake lock and GPS until the driver came back (#441, #491,
 * case 197). Any other start, a car wake or the user opening the app, disarms the guard, so a
 * normal drive is never cut short by a dark screen. Main thread only.
 */
internal class ColdBootGuard {
    var armed = false
        private set

    /** [first] is the start that created the service. Returns true when the service should stop now. */
    fun onStart(coldBoot: Boolean, first: Boolean, interactive: Boolean): Boolean {
        if (!coldBoot) armed = false else if (first) armed = true
        return armed && !interactive
    }

    /** Returns true when the service should stop now. */
    fun onScreenOff(): Boolean = armed
}
