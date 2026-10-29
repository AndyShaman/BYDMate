package com.bydmate.app.helper.dtc

import android.util.Log

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
    /** Ends the run from anywhere inside it with its terminal state. */
    private class Stop(val state: Int, val reason: String) : RuntimeException(reason)

    /** One address while it is being read; [outcome] stays skipped until its first answer. */
    private class Acc(val addr: Int) {
        var outcome = DtcWire.OUTCOME_SKIPPED
        var countUds: ByteArray? = null
        var listUds: ByteArray? = null
        var idUds: ByteArray? = null
        fun result() = DtcAddressResult(addr, outcome, countUds, listUds, idUds)
    }

    /** Counters of one run; touched by the scan thread only. */
    private class Run(val gear: () -> Int?, val startedMs: Long) {
        var registerTried = false
        var sent = 0
        var frames = 0
        var foreign = 0
        var current: Acc? = null
    }

    private val lock = Any()
    private var state = DtcWire.STATE_IDLE
    private var done = 0
    private var reason = ""
    private val results = ArrayList<DtcAddressResult>()

    /** 0 started, 1 busy. [gear] returns the live gear value, null when it could not be read. */
    fun start(gear: () -> Int?): Int {
        synchronized(lock) {
            if (state == DtcWire.STATE_RUNNING) return START_BUSY
            state = DtcWire.STATE_RUNNING
            done = 0
            reason = ""
            results.clear()
        }
        return runCatching {
            runner(Runnable { run(gear) })
            START_OK
        }.getOrElse { e ->
            publish(DtcWire.STATE_ABORTED, "exception ${e.javaClass.simpleName}")
            START_REFUSED
        }
    }

    fun status(): DtcScanStatus = synchronized(lock) {
        DtcScanStatus(state, done, DtcWire.ADDRESSES.size, reason, results.toList())
    }

    /** The scan thread: nothing escapes it, and the terminal state is published after the listener is gone. */
    private fun run(gear: () -> Int?) {
        val run = Run(gear, clock.nowMs())
        var stop = Stop(DtcWire.STATE_ABORTED, "exception")
        try {
            stop = runCatching { scan(run) }.getOrElse { e ->
                e as? Stop ?: stop(DtcWire.STATE_ABORTED, "exception ${e.javaClass.simpleName}", at = null)
            }
        } finally {
            // The listener lives for this scan only, whatever ended it.
            if (run.registerTried) {
                runCatching { device.unregister() }
                    .onSuccess { log("unregistered") }
                    .onFailure { log("unregister failed ${it.javaClass.simpleName}") }
            }
            runCatching { closeResults(run, stop) }
            publish(stop.state, stop.reason)
        }
    }

    /** A run stopped mid-address keeps what that address already answered; the rest are skipped. */
    private fun closeResults(run: Run, stop: Stop) {
        synchronized(lock) {
            run.current?.let { results += it.result() }
            val seen = results.map { it.addr }.toSet()
            DtcWire.ADDRESSES.filter { it !in seen }.forEach { results += Acc(it).result() }
        }
        val list = status().results
        log(
            "end state=${stateName(stop.state)} reason=${stop.reason} done=$done/${DtcWire.ADDRESSES.size} " +
                "ok=${list.count { it.outcome == DtcWire.OUTCOME_OK }} " +
                "nrc=${list.count { it.outcome == DtcWire.OUTCOME_NRC }} " +
                "no_answer=${list.count { it.outcome == DtcWire.OUTCOME_NO_ANSWER }} " +
                "sent=${run.sent} frames=${run.frames} foreign=${run.foreign} ms=${clock.nowMs() - run.startedMs}"
        )
    }

    /** The whole scan; returns the terminal state (DONE or no_frames) or throws [Stop]. */
    private fun scan(run: Run): Stop {
        val guard = readGuard()
        val gearValue = run.gear()
        log("start gearValue=$gearValue guard=$guard addrs=${DtcWire.ADDRESSES.size} budget_ms=$BUDGET_MS")
        gate(run, guard, gearValue, at = null)?.let { throw it }

        run.registerTried = true
        val failed = device.register()?.let { stop(DtcWire.STATE_UNAVAILABLE, it, at = null) }
            ?: device.poll(PASSIVE_MS)?.let { frame ->
                log("abort passive_traffic head=${hex(frame, 0, PASSIVE_HEAD_BYTES)}")
                Stop(DtcWire.STATE_ABORTED, "passive_traffic")
            }
        if (failed != null) throw failed
        log("passive frames=0 ms=$PASSIVE_MS")

        for (addr in DtcWire.ADDRESSES) {
            val acc = Acc(addr)
            run.current = acc
            val started = clock.nowMs()
            val requests = run.sent
            readAddress(run, acc)
            log(
                "addr=${hexAddr(addr)} outcome=${acc.outcome} requests=${run.sent - requests} " +
                    "ms=${clock.nowMs() - started}"
            )
            synchronized(lock) {
                results += acc.result()
                done++
            }
            run.current = null
        }
        return if (run.frames == 0) {
            Stop(DtcWire.STATE_UNAVAILABLE, "no_frames")
        } else {
            Stop(DtcWire.STATE_DONE, "")
        }
    }

    /**
     * `19 01 FF` first; a positive count asks for the list with the block's own mask (0x0D when it
     * supports the pending bit, else 0x09) and, on a negative answer, once more with FF; a list with
     * a row the app would show asks for the identity and the name.
     */
    private fun readAddress(run: Run, acc: Acc) {
        val addr = acc.addr
        val count = exchange(run, addr, DtcWire.countRequest(addr), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_COUNT)
        acc.outcome = outcomeOf(count)
        acc.countUds = udsOf(count, DtcWire.SERVICE_READ_DTC)
        if (count !is DtcMatch.Answer) return
        val uds = count.uds
        if (uds.size < COUNT_UDS_BYTES) return
        val records = (u(uds, 4) shl 8) or u(uds, 5)
        if (records == 0) return

        val mask = if (u(uds, 2) and STATUS_PENDING != 0) MASK_WITH_PENDING else MASK_CONFIRMED
        var list = exchange(run, addr, DtcWire.listRequest(addr, mask), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
        if (list is DtcMatch.Nrc) {
            log("list retry addr=${hexAddr(addr)} mask=${"%02X".format(mask)} nrc=0x${"%02X".format(list.code)} next=FF")
            list = exchange(run, addr, DtcWire.listRequest(addr, MASK_ALL), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
        }
        acc.outcome = outcomeOf(list)
        acc.listUds = udsOf(list, DtcWire.SERVICE_READ_DTC)
        if (list !is DtcMatch.Answer || !hasVisibleRow(list.uds)) return

        val id = exchange(
            run, addr, DtcWire.identityRequest(addr),
            DtcWire.SERVICE_READ_DID, DtcWire.DID_IDENTITY,
        )
        // The identity only names the block: no answer leaves the address's outcome as it is.
        if (id is DtcMatch.Answer) acc.idUds = id.uds

        // The block's own name, for the log only (other models): never changes the outcome.
        val name = exchange(run, addr, DtcWire.nameRequest(addr), DtcWire.SERVICE_READ_DID, DtcWire.DID_NAME)
        log("name addr=${hexAddr(addr)} ${DtcWire.nameText(name)}")
    }

    /**
     * Checks the budget, the guard property and the gear, sends [payload] and waits one window for
     * the answer to it. Null when nothing of ours came; a match is followed by a settle pause that
     * drops the duplicate frame.
     */
    private fun exchange(run: Run, addr: Int, payload: ByteArray, service: Int, sub: Int): DtcMatch? {
        val req = hex(payload, REQUEST_HEADER_BYTES, payload.size)
        val at = "at=${hexAddr(addr)} req=$req"
        val gear = run.gear()
        gate(run, readGuard(), gear, at)?.let { throw it }

        val rc = device.send(payload)
        run.sent++
        val sentAt = clock.nowMs()
        log("tx addr=${hexAddr(addr)} req=$req gear=$gear rc=$rc")
        if (rc != 0) {
            val state = if (run.sent == 1) DtcWire.STATE_UNAVAILABLE else DtcWire.STATE_ABORTED
            throw stop(state, "transport_rejected rc=$rc", at)
        }

        while (true) {
            val left = sentAt + WINDOW_MS - clock.nowMs()
            val frame = if (left > 0) device.poll(left) else null
            if (frame == null) {
                log("no_answer addr=${hexAddr(addr)} req=$req ms=${clock.nowMs() - sentAt}")
                return null
            }
            run.frames++
            val match = DtcWire.match(frame, addr, service, sub)
            when (match) {
                DtcMatch.NotMine -> {
                    run.foreign++
                    log("foreign ${head(frame)} (ignored)")
                }
                DtcMatch.Pending -> log("pending addr=${hexAddr(addr)} req=$req ms=${clock.nowMs() - sentAt}")
                else -> {
                    log(
                        "rx addr=${hexAddr(addr)} req=$req ${head(frame)} kind=${kindName(match)} " +
                            "uds=${hex(frame, FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + u(frame, 3))} " +
                            "ms=${clock.nowMs() - sentAt}" +
                            if (match is DtcMatch.Multiframe) " raw=${hex(frame, 0, frame.size)}" else ""
                    )
                    clock.sleep(SETTLE_MS)
                    device.drain()
                    return match
                }
            }
        }
    }

    /** Why the next request (or, with [at] null, the scan itself) must not go out; null when it may. */
    private fun gate(run: Run, guard: String?, gear: Int?, at: String?): Stop? = when {
        at == null && guard == null -> stop(DtcWire.STATE_UNAVAILABLE, "guard_absent", at)
        clock.nowMs() - run.startedMs >= BUDGET_MS -> stop(DtcWire.STATE_ABORTED, "budget", at)
        guard != DtcWire.GUARD_BASELINE -> stop(DtcWire.STATE_ABORTED, "diag_busy prop=$guard", at)
        gear != GEAR_PARK -> stop(DtcWire.STATE_ABORTED, "not_park gear=$gear", at)
        else -> null
    }

    private fun publish(state: Int, reason: String) = synchronized(lock) {
        this.state = state
        this.reason = reason
    }

    /** Logs the abort or unavailability line, then hands back the [Stop] to throw. */
    private fun stop(state: Int, reason: String, at: String?): Stop {
        val verb = if (state == DtcWire.STATE_UNAVAILABLE) "unavailable" else "abort"
        log("$verb $reason" + if (at == null) "" else " $at")
        return Stop(state, reason)
    }

    companion object {
        const val START_OK = 0
        const val START_BUSY = 1
        const val START_REFUSED = -1

        /** Timings of the validated probes: passive listen, answer window, duplicate settle, whole scan. */
        const val PASSIVE_MS = 2_000L
        const val WINDOW_MS = 2_500L
        const val SETTLE_MS = 400L
        const val BUDGET_MS = 90_000L

        private const val GEAR_PARK = 1
        private const val STATUS_PENDING = 0x04
        private const val MASK_WITH_PENDING = 0x0D
        private const val MASK_CONFIRMED = 0x09
        private const val MASK_ALL = 0xFF
        private const val COUNT_UDS_BYTES = 6
        private const val REQUEST_HEADER_BYTES = 4
        private const val PASSIVE_HEAD_BYTES = 16
    }
}

// Byte reading and the log text of DtcScanner.
private const val TAG = "DtcScan"
private const val NEGATIVE = 0x7F
private const val LIST_HEAD_BYTES = 3
private const val RECORD_BYTES = 4
private const val VISIBLE_STATUS = 0x0D
private const val FRAME_HEADER_BYTES = 7

private fun outcomeOf(m: DtcMatch?): String = when (m) {
    null -> DtcWire.OUTCOME_NO_ANSWER
    is DtcMatch.Answer -> DtcWire.OUTCOME_OK
    is DtcMatch.Nrc -> DtcWire.OUTCOME_NRC
    DtcMatch.GatewayTimeout -> DtcWire.OUTCOME_GW_TIMEOUT
    DtcMatch.Truncated -> DtcWire.OUTCOME_TRUNCATED
    is DtcMatch.Multiframe -> DtcWire.OUTCOME_MULTIFRAME
    DtcMatch.NotMine, DtcMatch.Pending -> DtcWire.OUTCOME_NO_ANSWER
}

/** What the app gets for one request: the answer, the negative answer as `7F sid nrc`, or the raw multi-frame. */
private fun udsOf(m: DtcMatch?, service: Int): ByteArray? = when (m) {
    is DtcMatch.Answer -> m.uds
    is DtcMatch.Nrc -> byteArrayOf(NEGATIVE.toByte(), service.toByte(), m.code.toByte())
    is DtcMatch.Multiframe -> m.raw
    else -> null
}

/** `59 02 avail` then 4-byte records (3 code bytes, status); a row counts with any of 0x0D. */
private fun hasVisibleRow(uds: ByteArray): Boolean {
    var i = LIST_HEAD_BYTES
    while (i + RECORD_BYTES <= uds.size) {
        if (u(uds, i + RECORD_BYTES - 1) and VISIBLE_STATUS != 0) return true
        i += RECORD_BYTES
    }
    return false
}

private fun head(frame: ByteArray): String =
    if (frame.size < FRAME_HEADER_BYTES) {
        "short=${hex(frame, 0, frame.size)}"
    } else {
        "rid=${hexAddr(DtcWire.replyId(frame))} len=${u(frame, 3)} fr=${u(frame, 5)}/${u(frame, 4)} res=${u(frame, 6)}"
    }

private fun kindName(m: DtcMatch): String = when (m) {
    is DtcMatch.Answer -> "answer"
    is DtcMatch.Nrc -> "nrc=0x${"%02X".format(m.code)}"
    DtcMatch.GatewayTimeout -> "gw_timeout"
    DtcMatch.Truncated -> "truncated"
    is DtcMatch.Multiframe -> "multiframe"
    DtcMatch.NotMine, DtcMatch.Pending -> "other"
}

private fun stateName(state: Int): String = when (state) {
    DtcWire.STATE_DONE -> "DONE"
    DtcWire.STATE_UNAVAILABLE -> "UNAVAILABLE"
    DtcWire.STATE_ABORTED -> "ABORTED"
    DtcWire.STATE_RUNNING -> "RUNNING"
    else -> "IDLE"
}

private fun log(line: String) {
    Log.i(TAG, line)
}

private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

private fun hexAddr(addr: Int): String = "0x%X".format(addr)

/** Hex of `[from, to)`, clipped to what the array holds. */
private fun hex(b: ByteArray, from: Int, to: Int): String {
    val sb = StringBuilder()
    for (i in from until minOf(to, b.size)) sb.append("%02X".format(b[i].toInt() and 0xFF))
    return sb.toString()
}
