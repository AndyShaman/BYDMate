package com.bydmate.app.helper.dtc

import android.util.Log

/** Ends a run from anywhere inside it with its terminal state. */
internal class DiagStop(val state: Int, val reason: String, val at: String = "") : RuntimeException(reason)

/**
 * How one job talks through [DiagLink]: its whole budget, whether the speed must read exactly 0
 * before every request ([speedGate], unknown = refuse), whether any frame that is not ours or a
 * duplicate of ours stops the run ([strictTraffic]), the pause after an answer, how often a
 * busy gateway gets the request again, whether a `7F xx 78` answer restarts the window
 * ([pendingWindowMs] > 0, at most [MAX_PENDINGS] times) and the outgoing allowlist [allow],
 * checked right before every send.
 */
@Suppress("LongParameterList") // one field per knob of the policy
internal class LinkPolicy(
    val budgetMs: Long,
    val speedGate: Boolean,
    val strictTraffic: Boolean,
    val settleMs: Long = DtcScanner.SETTLE_MS,
    val busyRetries: Int = DtcScanner.BUSY_RETRIES,
    val pendingWindowMs: Long = 0,
    val allow: (addr: Int, uds: ByteArray) -> Boolean = { _, _ -> true },
) {
    companion object {
        const val MAX_PENDINGS = 2
    }
}

/**
 * One run's request core on the vendor device: the gates before the run and before every request
 * (guard property, budget, gear P, and the speed under [LinkPolicy.speedGate]), the passive
 * listen, request / answer matching and the handling of frames that are not the answer awaited.
 * Touched by the run thread only.
 */
@Suppress("TooManyFunctions") // one request core: each step of an exchange is its own small function
internal class DiagLink(
    private val device: DtcDevice,
    val clock: DtcClock,
    private val readGuard: () -> String?,
    private val readSpeed: () -> Int?,
    val gear: () -> Int?,
    var policy: LinkPolicy,
) {
    val startedMs = clock.nowMs()
    var registerTried = false
    var sent = 0
    var frames = 0
    var foreign = 0
    /** Strict traffic: every frame matched as ours, so their duplicates are not taken for foreign traffic. */
    private val ours = ArrayList<ByteArray>()
    /** New `7F xx 78` answers in the current wait. */
    private var pendings = 0

    /** Gate before the run, listener, then the passive listen: any frame there stops the run. */
    fun open(what: String, addrs: Int) {
        val guard = readGuard()
        val gearValue = gear()
        val speed = if (policy.speedGate) readSpeed() else null
        log("$what start gearValue=$gearValue speed=$speed guard=$guard addrs=$addrs budget_ms=${policy.budgetMs}")
        gate(guard, gearValue, speed, at = null)?.let { throw it }

        registerTried = true
        val failed = device.register()?.let { stop(DtcWire.STATE_UNAVAILABLE, it, at = null) }
            ?: device.poll(DtcScanner.PASSIVE_MS)?.let { frame ->
                log("abort passive_traffic head=${hex(frame, 0, PASSIVE_HEAD_BYTES)}")
                DiagStop(DtcWire.STATE_ABORTED, "passive_traffic")
            }
        if (failed != null) throw failed
        log("passive frames=0 ms=${DtcScanner.PASSIVE_MS}")
    }

    /**
     * [exchange], and again after a growing pause while the gateway answers busy (it did not
     * forward the request), at most [LinkPolicy.busyRetries] times; the gates run before every send.
     */
    fun request(addr: Int, payload: ByteArray, service: Int, sub: Int): DtcMatch? {
        var attempt = 0
        while (true) {
            val m = exchange(addr, payload, service, sub)
            if (m != DtcMatch.Busy || attempt == policy.busyRetries) return m
            attempt++
            val wait = DtcScanner.BUSY_BACKOFF_MS * attempt
            log("busy addr=${hexAddr(addr)} req=${hex(payload, REQUEST_HEADER_BYTES, payload.size)} retry=$attempt wait_ms=$wait")
            clock.sleep(wait)
        }
    }

    /**
     * Checks the gates, sends [payload] and waits one window for the answer to it (the clear's own
     * longer window for `14`). Null when nothing of ours came; a match is followed by a settle
     * pause that drops the duplicate frame (strict traffic checks what came in that pause instead).
     */
    fun exchange(addr: Int, payload: ByteArray, service: Int, sub: Int, gated: Boolean = true): DtcMatch? {
        val req = hex(payload, REQUEST_HEADER_BYTES, payload.size)
        val at = "at=${hexAddr(addr)} req=$req"
        val guard = readGuard()
        val gearValue = gear()
        val speed = if (policy.speedGate) readSpeed() else null
        val refused = if (gated) gate(guard, gearValue, speed, at) else exitGate(guard, at)
        refused?.let { throw it }
        if (policy.strictTraffic) quiet(at)
        val sentAt = send(addr, payload, at, "gear=$gearValue" + if (policy.speedGate) " speed=$speed guard=$guard" else "")

        val windowMs = if (service == DtcWire.SERVICE_CLEAR) DtcScanner.ERASE_WINDOW_MS else DtcScanner.WINDOW_MS
        val (match, frame) = await(sentAt + windowMs, at) { DtcWire.match(it, addr, service, sub) } ?: run {
            log("no_answer addr=${hexAddr(addr)} req=$req ms=${clock.nowMs() - sentAt}")
            return null
        }
        log(
            "rx addr=${hexAddr(addr)} req=$req ${head(frame)} kind=${kindName(match)} " +
                "uds=${udsHex(frame)} ms=${clock.nowMs() - sentAt}" +
                if (match is DtcMatch.Multiframe) " raw=${hex(frame, 0, frame.size)}" else ""
        )
        clock.sleep(policy.settleMs)
        if (policy.strictTraffic) quiet(at) else device.drain()
        return match
    }

    /** The allowlist right before the send, the send, its log line; the time it went out. */
    private fun send(addr: Int, payload: ByteArray, at: String, values: String): Long {
        val req = hex(payload, REQUEST_HEADER_BYTES, payload.size)
        if (!policy.allow(addr, payload.copyOfRange(REQUEST_HEADER_BYTES, payload.size))) {
            throw stop(DtcWire.STATE_ABORTED, "not_allowed", at)
        }
        val rc = device.send(payload)
        sent++
        val sentAt = clock.nowMs()
        log("tx addr=${hexAddr(addr)} req=$req $values rc=$rc")
        if (rc != 0) {
            val state = if (sent == 1) DtcWire.STATE_UNAVAILABLE else DtcWire.STATE_ABORTED
            throw stop(state, "transport_rejected rc=$rc", at)
        }
        return sentAt
    }

    /**
     * The one request a job still sends after a gate stop (the exit from the extended session):
     * only the guard property, strict traffic and the allowlist are checked, not the budget, the
     * gear or the speed. No busy resend.
     */
    fun exit(addr: Int, payload: ByteArray, service: Int, sub: Int): DtcMatch? =
        exchange(addr, payload, service, sub, gated = false)

    /**
     * Frames until [match] finds the answer (null at [deadline]); others go to [other], pending
     * waits on (and, under [LinkPolicy.pendingWindowMs], restarts the window a few times).
     */
    private fun await(start: Long, at: String, match: (ByteArray) -> DtcMatch): Pair<DtcMatch, ByteArray>? {
        var deadline = start
        pendings = 0
        while (true) {
            val left = deadline - clock.nowMs()
            val frame = (if (left > 0) device.poll(left) else null) ?: return null
            frames++
            when (val m = match(frame)) {
                DtcMatch.NotMine -> other(frame, at)
                DtcMatch.Pending -> deadline = pending(frame, at, deadline) ?: return null
                else -> {
                    if (policy.strictTraffic) ours += frame
                    return m to frame
                }
            }
        }
    }

    /**
     * A `7F xx 78`: keep waiting, and under [LinkPolicy.pendingWindowMs] a new one (not the
     * gateway's copy) restarts the window; the deadline, or null after too many.
     */
    private fun pending(frame: ByteArray, at: String, deadline: Long): Long? {
        val repeat = ours.any { sameFrame(it, frame) }
        if (policy.strictTraffic) ours += frame
        var next = deadline
        if (policy.pendingWindowMs > 0 && !repeat) {
            if (++pendings > LinkPolicy.MAX_PENDINGS) {
                log("pending $at count=$pendings, too many")
                return null
            }
            next = clock.nowMs() + policy.pendingWindowMs
        }
        log("pending $at ms_left=${next - clock.nowMs()}")
        return next
    }

    /** A frame that is not the answer awaited: skipped, or under strict traffic only a duplicate of ours is. */
    private fun other(frame: ByteArray, at: String) {
        if (policy.strictTraffic && ours.none { sameFrame(it, frame) }) {
            foreign++
            log("foreign ${head(frame)} uds=${udsHex(frame)}")
            throw stop(DtcWire.STATE_ABORTED, "foreign " + head(frame).substringBefore(' '), at)
        }
        foreign++
        log("foreign ${head(frame)} uds=${udsHex(frame)} (ignored)")
    }

    /** Strict traffic: whatever is queued now must be a duplicate of an answer of ours, else the run stops. */
    private fun quiet(at: String) {
        while (true) {
            val frame = device.poll(0) ?: return
            frames++
            other(frame, at)
        }
    }

    /** The exit after a gate stop goes out only while the diagnostic manager is idle. */
    private fun exitGate(guard: String?, at: String): DiagStop? =
        if (guard == DtcWire.GUARD_BASELINE) null else stop(DtcWire.STATE_ABORTED, "diag_busy prop=$guard", at)

    /** Why the next request (or, with [at] null, the run itself) must not go out; null when it may. */
    private fun gate(guard: String?, gear: Int?, speed: Int?, at: String?): DiagStop? = when {
        at == null && guard == null -> stop(DtcWire.STATE_UNAVAILABLE, "guard_absent", at)
        clock.nowMs() - startedMs >= policy.budgetMs -> stop(DtcWire.STATE_ABORTED, "budget", at)
        guard != DtcWire.GUARD_BASELINE -> stop(DtcWire.STATE_ABORTED, "diag_busy prop=$guard", at)
        gear != GEAR_PARK -> stop(DtcWire.STATE_ABORTED, "not_park gear=$gear", at)
        policy.speedGate && speed != 0 -> stop(DtcWire.STATE_ABORTED, "speed kmh=$speed", at)
        else -> null
    }

    private companion object {
        const val GEAR_PARK = 1
        const val REQUEST_HEADER_BYTES = 4
        const val PASSIVE_HEAD_BYTES = 16
    }
}

/** Logs the abort or unavailability line, then hands back the [DiagStop] to throw. */
internal fun stop(state: Int, reason: String, at: String?): DiagStop {
    val verb = if (state == DtcWire.STATE_UNAVAILABLE) "unavailable" else "abort"
    log("$verb $reason" + if (at == null) "" else " $at")
    return DiagStop(state, reason, at.orEmpty())
}

// Byte reading and the log text of the diagnostic jobs.
private const val TAG = "DtcScan"
private const val FRAME_HEADER_BYTES = 7

/** The `len` UDS bytes of a frame, hex. */
private fun udsHex(frame: ByteArray): String =
    if (frame.size < FRAME_HEADER_BYTES) "" else hex(frame, FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + u(frame, 3))

/** Header and `len` UDS bytes equal: the gateway's second copy of a frame (the tail may differ). */
private fun sameFrame(a: ByteArray, b: ByteArray): Boolean {
    if (a.size < FRAME_HEADER_BYTES || b.size < FRAME_HEADER_BYTES) return a.contentEquals(b)
    val n = FRAME_HEADER_BYTES + u(a, 3)
    return a.size >= n && b.size >= n && a.copyOf(n).contentEquals(b.copyOf(n))
}

private fun head(frame: ByteArray): String =
    if (frame.size < FRAME_HEADER_BYTES) {
        "short=${hex(frame, 0, frame.size)}"
    } else {
        "rid=${hexAddr(DtcWire.replyId(frame))} len=${u(frame, 3)} fr=${u(frame, 5)}/${u(frame, 4)} res=${u(frame, 6)}"
    }

internal fun kindName(m: DtcMatch): String = when (m) {
    is DtcMatch.Answer -> "answer"
    is DtcMatch.Nrc -> "nrc=0x${"%02X".format(m.code)}"
    DtcMatch.GatewayTimeout -> "gw_timeout"
    DtcMatch.Busy -> "busy"
    DtcMatch.ManagerTimeout -> "manager_timeout"
    DtcMatch.Truncated -> "truncated"
    is DtcMatch.Multiframe -> "multiframe"
    DtcMatch.NotMine, DtcMatch.Pending -> "other"
}

internal fun log(line: String) {
    Log.i(TAG, line)
}

internal fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

internal fun hexAddr(addr: Int): String = "0x%X".format(addr)

/** Hex of `[from, to)`, clipped to what the array holds. */
internal fun hex(b: ByteArray, from: Int, to: Int): String {
    val sb = StringBuilder()
    for (i in from until minOf(to, b.size)) sb.append("%02X".format(b[i].toInt() and 0xFF))
    return sb.toString()
}
