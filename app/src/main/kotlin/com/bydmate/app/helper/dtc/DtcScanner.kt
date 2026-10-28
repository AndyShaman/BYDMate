@file:Suppress("UnusedParameter", "UnusedPrivateProperty") // compile stub, remove with the implementation

package com.bydmate.app.helper.dtc

/** Time source of the scan; the fake one in tests moves only when asked. */
interface DtcClock {
    fun nowMs(): Long
    fun sleep(ms: Long)
}

/** The vendor device the frames travel through. The real one wraps the reflective 1033 device. */
interface DtcDevice {
    /** Registers the RX listener; null on success, else the reason (`class_missing`, `register_failed <Class>`). */
    fun register(): String?
    fun unregister()
    /** Sends [payload]; the vendor return code, 0 = accepted. */
    fun send(payload: ByteArray): Int
    /** Next received frame within [timeoutMs], null when none came. */
    fun poll(timeoutMs: Long): ByteArray?
    /** Drops everything queued. */
    fun drain()
}

/**
 * The daemon's scan job: one run at a time, on its own thread ([runner] starts it), reading only
 * the whitelisted addresses with the fixed requests of [DtcWire]. Checks the guard property, the
 * gear and the budget before every request.
 */
class DtcScanner(
    private val device: DtcDevice,
    private val clock: DtcClock,
    private val readGuard: () -> String?,
    private val runner: (Runnable) -> Unit = { Thread(it, "dtc-scan").start() },
) {
    /** 0 started, 1 busy. [gear] returns the live gear value, null when it could not be read. */
    fun start(gear: () -> Int?): Int = TODO()

    fun status(): DtcScanStatus = TODO()
}
