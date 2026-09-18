package com.bydmate.app.camera

/**
 * Error backoff for the blind-spot camera pipeline.
 *
 * A fresh turn-signal request (an edge from another side, or from none) cancels the backoff
 * once: the driver is asking for the view now. A held blinker is a level, not an edge, and
 * must keep waiting. The requested side is remembered here and only here — a teardown does
 * not touch it. Song DiLink 4.0, 2026-09-18: teardown reset the remembered side, so a held
 * blinker read as a new edge on the next tick, the 3 s backoff never held, and the windows
 * flashed on and off every ~350 ms (569 open attempts in one dump).
 */
internal class BlindSpotBackoff {
    private var retryAt = 0L
    private var lastRequestedSide = BlindSpotSide.NONE

    /** The side the decision asks for on this tick; NONE when the blinker is off. */
    fun request(side: BlindSpotSide) {
        if (side == lastRequestedSide) return
        if (side != BlindSpotSide.NONE) retryAt = 0L
        lastRequestedSide = side
    }

    fun fail(now: Long, delayMs: Long) {
        retryAt = now + delayMs
    }

    fun blocked(now: Long): Boolean = now < retryAt
}
