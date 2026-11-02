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
 * The daemon's scan and erase jobs: one run at a time (scan or erase), on its own thread
 * ([runner] starts it), talking only to the whitelisted addresses with the fixed requests of
 * [DtcWire]. Checks the guard property, the gear and the budget before every request; an erase
 * run also checks the speed ([readSpeed], km/h, null = unknown) and stops on any foreign frame.
 */
@Suppress("TooManyFunctions") // one scan job: each step of the run is its own small function
class DtcScanner(
    private val device: DtcDevice,
    private val clock: DtcClock,
    private val readGuard: () -> String?,
    private val runner: (Runnable) -> Unit = { Thread(it, "dtc-scan").start() },
    private val readSpeed: () -> Int? = { null },
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

    /** Counters of one run; touched by the run thread only. */
    private class Run(val gear: () -> Int?, val startedMs: Long, val erase: Boolean) {
        val budgetMs = if (erase) ERASE_BUDGET_MS else BUDGET_MS
        var registerTried = false
        var sent = 0
        var frames = 0
        var foreign = 0
        var current: Acc? = null
        /** Blocks with a visible row, whose F197 name is asked after the whole scan. */
        val named = ArrayList<Int>()
        /** (addr, code) of the visible rows, at most [SNAPSHOT_CAP], asked after the names. */
        val snapshots = ArrayList<Pair<Int, Int>>()
        /** Erase: index of the block being cleared and [sent] when its clear started. */
        var eraseIndex = -1
        var eraseSentBefore = 0
        /** Erase: every frame matched as ours, so their duplicates are not taken for foreign traffic. */
        val ours = ArrayList<ByteArray>()
    }

    private val lock = Any()
    private var state = DtcWire.STATE_IDLE
    private var mode = DtcWire.MODE_SCAN
    private var done = 0
    private var reason = ""
    private val results = ArrayList<DtcAddressResult>()
    private val erase = ArrayList<DtcEraseResult>()

    /** 0 started, 1 busy. [gear] returns the live gear value, null when it could not be read. */
    fun start(gear: () -> Int?): Int = launch(DtcWire.MODE_SCAN, IntArray(0)) {
        execute(Run(gear, clock.nowMs(), erase = false), ::scan, ::closeResults)
    }

    /**
     * Clears the stored codes of [addrs] (`14 FF FF FF` each, in this order) and reads them again:
     * 0 started, 1 busy (a scan or an erase runs), -1 refused (empty, repeated or outside
     * [DtcWire.ADDRESSES]; nothing is sent).
     */
    fun startErase(addrs: IntArray, gear: () -> Int?): Int {
        if (addrs.isEmpty() || addrs.any { it !in DtcWire.ADDRESSES } || addrs.distinct().size != addrs.size) {
            log("erase refused addrs=${addrs.joinToString(",") { hexAddr(it) }}")
            return START_REFUSED
        }
        return launch(DtcWire.MODE_ERASE, addrs) {
            execute(Run(gear, clock.nowMs(), erase = true), ::eraseAll, ::closeErase)
        }
    }

    private fun launch(mode: Int, addrs: IntArray, job: () -> Unit): Int {
        synchronized(lock) {
            if (state == DtcWire.STATE_RUNNING) return START_BUSY
            state = DtcWire.STATE_RUNNING
            this.mode = mode
            done = 0
            reason = ""
            results.clear()
            erase.clear()
            addrs.forEach { erase += DtcEraseResult(it, DtcWire.CONFIRM_NOT_RUN, null, "", null) }
        }
        return runCatching {
            runner(Runnable { job() })
            START_OK
        }.getOrElse { e ->
            publish(DtcWire.STATE_ABORTED, "exception ${e.javaClass.simpleName}")
            START_REFUSED
        }
    }

    fun status(): DtcScanStatus = synchronized(lock) {
        val total = if (mode == DtcWire.MODE_ERASE) erase.size else DtcWire.ADDRESSES.size
        DtcScanStatus(state, done, total, reason, results.toList(), erase.toList(), mode)
    }

    /** The run thread: nothing escapes it, and the terminal state is published after the listener is gone. */
    private fun execute(run: Run, body: (Run) -> Stop, close: (Run, Stop) -> Unit) {
        var stop = Stop(DtcWire.STATE_ABORTED, "exception")
        try {
            stop = runCatching { body(run) }.getOrElse { e ->
                e as? Stop ?: stop(DtcWire.STATE_ABORTED, "exception ${e.javaClass.simpleName}", at = null)
            }
        } finally {
            // The listener lives for this run only, whatever ended it.
            if (run.registerTried) {
                runCatching { device.unregister() }
                    .onSuccess { log("unregistered") }
                    .onFailure { log("unregister failed ${it.javaClass.simpleName}") }
            }
            runCatching { close(run, stop) }
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

    /**
     * The block whose clear was out when the run stopped is unconfirmed, the blocks after it were
     * not run; both carry the stop reason. Finished blocks keep their confirmation.
     */
    private fun closeErase(run: Run, stop: Stop) {
        val list = synchronized(lock) {
            for (i in erase.indices) {
                val e = erase[i]
                if (e.confirm != DtcWire.CONFIRM_NOT_RUN) continue
                val inFlight = i == run.eraseIndex && run.sent > run.eraseSentBefore
                val confirm = if (inFlight) DtcWire.CONFIRM_UNCONFIRMED else DtcWire.CONFIRM_NOT_RUN
                erase[i] = DtcEraseResult(e.addr, confirm, null, stop.reason, null)
            }
            erase.toList()
        }
        list.forEach { log("erase result addr=${hexAddr(it.addr)} confirm=${it.confirm} reason=${it.reason.ifEmpty { "-" }}") }
        log(
            "erase end state=${stateName(stop.state)} reason=${stop.reason} done=$done/${list.size} " +
                "confirmed=${list.count { it.confirm == DtcWire.CONFIRM_CONFIRMED }} " +
                "refused=${list.count { it.confirm == DtcWire.CONFIRM_REFUSED }} " +
                "unconfirmed=${list.count { it.confirm == DtcWire.CONFIRM_UNCONFIRMED }} " +
                "not_run=${list.count { it.confirm == DtcWire.CONFIRM_NOT_RUN }} " +
                "sent=${run.sent} frames=${run.frames} ms=${clock.nowMs() - run.startedMs}"
        )
    }

    /** Gate before the run, listener, then the passive listen: any frame there stops the run. */
    private fun open(run: Run, what: String, addrs: Int) {
        val guard = readGuard()
        val gearValue = run.gear()
        val speed = if (run.erase) readSpeed() else null
        log("$what start gearValue=$gearValue speed=$speed guard=$guard addrs=$addrs budget_ms=${run.budgetMs}")
        gate(run, guard, gearValue, speed, at = null)?.let { throw it }

        run.registerTried = true
        val failed = device.register()?.let { stop(DtcWire.STATE_UNAVAILABLE, it, at = null) }
            ?: device.poll(PASSIVE_MS)?.let { frame ->
                log("abort passive_traffic head=${hex(frame, 0, PASSIVE_HEAD_BYTES)}")
                Stop(DtcWire.STATE_ABORTED, "passive_traffic")
            }
        if (failed != null) throw failed
        log("passive frames=0 ms=$PASSIVE_MS")
    }

    /** The whole scan; returns the terminal state (DONE or no_frames) or throws [Stop]. */
    private fun scan(run: Run): Stop {
        open(run, "scan", DtcWire.ADDRESSES.size)
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
        readExtras(run)
        return if (run.frames == 0) {
            Stop(DtcWire.STATE_UNAVAILABLE, "no_frames")
        } else {
            Stop(DtcWire.STATE_DONE, "")
        }
    }

    /**
     * The erase: `14 FF FF FF` per block with a gap between blocks, then a settle pause and the
     * codes of every block whose clear went out read again. Never resends a clear whose answer
     * was lost (only a busy gateway, which did not forward it, gets it again).
     */
    private fun eraseAll(run: Run): Stop {
        val addrs = synchronized(lock) { erase.map { it.addr } }
        open(run, "erase", addrs.size)
        val cleared = ArrayList<Int>()
        for ((i, addr) in addrs.withIndex()) {
            if (i > 0) clock.sleep(ERASE_GAP_MS)
            run.eraseIndex = i
            run.eraseSentBefore = run.sent
            eraseBlock(run, i, addr)
            cleared += i
            synchronized(lock) { done++ }
        }
        run.eraseIndex = -1
        log("erase settle ms=$ERASE_SETTLE_MS before re-reading ${cleared.size} blocks")
        clock.sleep(ERASE_SETTLE_MS)
        for (i in cleared) {
            val acc = Acc(addrs[i])
            readCodes(run, acc)
            log(
                "reread addr=${hexAddr(acc.addr)} outcome=${acc.outcome} " +
                    "count=${acc.countUds?.let { hex(it, 0, it.size) }} list=${acc.listUds?.let { hex(it, 0, it.size) }}"
            )
            synchronized(lock) {
                val e = erase[i]
                erase[i] = DtcEraseResult(e.addr, e.confirm, e.nrc, e.reason, acc.result())
            }
        }
        return Stop(DtcWire.STATE_DONE, "")
    }

    private fun eraseBlock(run: Run, i: Int, addr: Int) {
        val started = clock.nowMs()
        val m = request(run, addr, DtcWire.clearRequest(addr), DtcWire.SERVICE_CLEAR, -1)
        val result = when (m) {
            is DtcMatch.Answer -> DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", null)
            is DtcMatch.Nrc -> DtcEraseResult(addr, DtcWire.CONFIRM_REFUSED, m.code, DtcWire.OUTCOME_NRC, null)
            else -> DtcEraseResult(addr, DtcWire.CONFIRM_UNCONFIRMED, null, outcomeOf(m), null)
        }
        log(
            "erase addr=${hexAddr(addr)} confirm=${result.confirm} nrc=${result.nrc?.let { "0x%02X".format(it) } ?: "-"} " +
                "reason=${result.reason.ifEmpty { "-" }} sends=${run.sent - run.eraseSentBefore} ms=${clock.nowMs() - started}"
        )
        synchronized(lock) { erase[i] = result }
    }

    /**
     * `19 01 FF` first; a positive count asks for the list with the block's own mask (0x0D when it
     * supports the pending bit, else 0x09) and, on a negative answer, once more with FF. Returns
     * the codes of the rows the app would show (empty when there is no list).
     */
    private fun readCodes(run: Run, acc: Acc): List<Int> {
        val addr = acc.addr
        val count = request(run, addr, DtcWire.countRequest(addr), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_COUNT)
        acc.outcome = outcomeOf(count)
        acc.countUds = udsOf(count, DtcWire.SERVICE_READ_DTC)
        if (count !is DtcMatch.Answer) return emptyList()
        val uds = count.uds
        if (uds.size < COUNT_UDS_BYTES) return emptyList()
        val records = (u(uds, 4) shl 8) or u(uds, 5)
        if (records == 0) return emptyList()

        val mask = if (u(uds, 2) and STATUS_PENDING != 0) MASK_WITH_PENDING else MASK_CONFIRMED
        var list = request(run, addr, DtcWire.listRequest(addr, mask), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
        if (list is DtcMatch.Nrc) {
            log("list retry addr=${hexAddr(addr)} mask=${"%02X".format(mask)} nrc=0x${"%02X".format(list.code)} next=FF")
            list = request(run, addr, DtcWire.listRequest(addr, MASK_ALL), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
        }
        acc.outcome = outcomeOf(list)
        acc.listUds = udsOf(list, DtcWire.SERVICE_READ_DTC)
        return if (list is DtcMatch.Answer) visibleCodes(list.uds) else emptyList()
    }

    /**
     * The codes ([readCodes]); a list with a row the app would show asks for the identity (the
     * name and the snapshots come after the scan, [readExtras]).
     */
    private fun readAddress(run: Run, acc: Acc) {
        val codes = readCodes(run, acc)
        if (codes.isEmpty()) return
        val addr = acc.addr
        codes.forEach { if (run.snapshots.size < SNAPSHOT_CAP) run.snapshots += addr to it }
        val id = request(
            run, addr, DtcWire.identityRequest(addr),
            DtcWire.SERVICE_READ_DID, DtcWire.DID_IDENTITY,
        )
        // The identity only names the block: no answer leaves the address's outcome as it is.
        if (id is DtcMatch.Answer) acc.idUds = id.uds
        run.named += addr
    }

    /**
     * For the log only, once every address is read: the blocks' own F197 names (other models),
     * then the snapshot records of the shown codes. A stop here (budget, gear, guard, rejected
     * send) or any exception ends these, never the scan. The gate still runs before each request,
     * so nothing is sent outside P.
     */
    @Suppress("TooGenericExceptionCaught") // an optional log step: whatever it throws, the scan stays
    private fun readExtras(run: Run) {
        try {
            for (addr in run.named) {
                val name = request(run, addr, DtcWire.nameRequest(addr), DtcWire.SERVICE_READ_DID, DtcWire.DID_NAME)
                log("name addr=${hexAddr(addr)} ${DtcWire.nameText(name)}")
            }
            for ((addr, code) in run.snapshots) {
                val snap = request(
                    run, addr, DtcWire.snapshotRequest(addr, code),
                    DtcWire.SERVICE_READ_DTC, DtcWire.SUB_SNAPSHOT,
                )
                log("snapshot addr=${hexAddr(addr)} dtc=${"%06X".format(code)} kind=${snap?.let(::kindName) ?: "none"}")
            }
        } catch (e: Stop) {
            log("extras stopped ${e.reason}, the scan result stays")
        } catch (e: Exception) {
            log("extras stopped exception ${e.javaClass.simpleName}, the scan result stays")
        }
    }

    /**
     * [exchange], and again after a growing pause while the gateway answers busy (it did not
     * forward the request), at most [BUSY_RETRIES] times; the gates run before every send.
     */
    private fun request(run: Run, addr: Int, payload: ByteArray, service: Int, sub: Int): DtcMatch? {
        var attempt = 0
        while (true) {
            val m = exchange(run, addr, payload, service, sub)
            if (m != DtcMatch.Busy || attempt == BUSY_RETRIES) return m
            attempt++
            val wait = BUSY_BACKOFF_MS * attempt
            log("busy addr=${hexAddr(addr)} req=${hex(payload, REQUEST_HEADER_BYTES, payload.size)} retry=$attempt wait_ms=$wait")
            clock.sleep(wait)
        }
    }

    /**
     * Checks the gates, sends [payload] and waits one window for the answer to it (the clear's own
     * longer window for `14`). Null when nothing of ours came; a match is followed by a settle
     * pause that drops the duplicate frame (an erase run checks what came in that pause instead).
     */
    private fun exchange(run: Run, addr: Int, payload: ByteArray, service: Int, sub: Int): DtcMatch? {
        val req = hex(payload, REQUEST_HEADER_BYTES, payload.size)
        val at = "at=${hexAddr(addr)} req=$req"
        val guard = readGuard()
        val gear = run.gear()
        val speed = if (run.erase) readSpeed() else null
        gate(run, guard, gear, speed, at)?.let { throw it }
        if (run.erase) quiet(run, at)

        val rc = device.send(payload)
        run.sent++
        val sentAt = clock.nowMs()
        log("tx addr=${hexAddr(addr)} req=$req gear=$gear" + (if (run.erase) " speed=$speed guard=$guard" else "") + " rc=$rc")
        if (rc != 0) {
            val state = if (run.sent == 1) DtcWire.STATE_UNAVAILABLE else DtcWire.STATE_ABORTED
            throw stop(state, "transport_rejected rc=$rc", at)
        }

        val windowMs = if (service == DtcWire.SERVICE_CLEAR) ERASE_WINDOW_MS else WINDOW_MS
        val (match, frame) = await(run, sentAt + windowMs, at) { DtcWire.match(it, addr, service, sub) } ?: run {
            log("no_answer addr=${hexAddr(addr)} req=$req ms=${clock.nowMs() - sentAt}")
            return null
        }
        log(
            "rx addr=${hexAddr(addr)} req=$req ${head(frame)} kind=${kindName(match)} " +
                "uds=${udsHex(frame)} ms=${clock.nowMs() - sentAt}" +
                if (match is DtcMatch.Multiframe) " raw=${hex(frame, 0, frame.size)}" else ""
        )
        clock.sleep(SETTLE_MS)
        if (run.erase) quiet(run, at) else device.drain()
        return match
    }

    /** Frames until [match] finds the answer (null at [deadline]); others go to [other], pending waits on. */
    private fun await(run: Run, deadline: Long, at: String, match: (ByteArray) -> DtcMatch): Pair<DtcMatch, ByteArray>? {
        while (true) {
            val left = deadline - clock.nowMs()
            val frame = (if (left > 0) device.poll(left) else null) ?: return null
            run.frames++
            when (val m = match(frame)) {
                DtcMatch.NotMine -> other(run, frame, at)
                DtcMatch.Pending -> {
                    if (run.erase) run.ours += frame
                    log("pending $at ms_left=${deadline - clock.nowMs()}")
                }
                else -> {
                    if (run.erase) run.ours += frame
                    return m to frame
                }
            }
        }
    }

    /** A frame that is not the answer awaited: skipped in a scan; in an erase only a duplicate of ours is. */
    private fun other(run: Run, frame: ByteArray, at: String) {
        if (run.erase && run.ours.none { sameFrame(it, frame) }) {
            run.foreign++
            log("foreign ${head(frame)} uds=${udsHex(frame)}")
            throw stop(DtcWire.STATE_ABORTED, "foreign " + head(frame).substringBefore(' '), at)
        }
        run.foreign++
        log("foreign ${head(frame)} uds=${udsHex(frame)} (ignored)")
    }

    /** Erase: whatever is queued now must be a duplicate of an answer of ours, else the run stops. */
    private fun quiet(run: Run, at: String) {
        while (true) {
            val frame = device.poll(0) ?: return
            run.frames++
            other(run, frame, at)
        }
    }

    /** The `len` UDS bytes of a frame, hex. */
    private fun udsHex(frame: ByteArray): String =
        if (frame.size < FRAME_HEADER_BYTES) "" else hex(frame, FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + u(frame, 3))

    /** Header and `len` UDS bytes equal: the gateway's second copy of a frame (the tail may differ). */
    private fun sameFrame(a: ByteArray, b: ByteArray): Boolean {
        if (a.size < FRAME_HEADER_BYTES || b.size < FRAME_HEADER_BYTES) return a.contentEquals(b)
        val n = FRAME_HEADER_BYTES + u(a, 3)
        return a.size >= n && b.size >= n && a.copyOf(n).contentEquals(b.copyOf(n))
    }

    /** Why the next request (or, with [at] null, the run itself) must not go out; null when it may. */
    private fun gate(run: Run, guard: String?, gear: Int?, speed: Int?, at: String?): Stop? = when {
        at == null && guard == null -> stop(DtcWire.STATE_UNAVAILABLE, "guard_absent", at)
        clock.nowMs() - run.startedMs >= run.budgetMs -> stop(DtcWire.STATE_ABORTED, "budget", at)
        guard != DtcWire.GUARD_BASELINE -> stop(DtcWire.STATE_ABORTED, "diag_busy prop=$guard", at)
        gear != GEAR_PARK -> stop(DtcWire.STATE_ABORTED, "not_park gear=$gear", at)
        run.erase && speed != 0 -> stop(DtcWire.STATE_ABORTED, "speed kmh=$speed", at)
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

        /** Erase: answer window of one clear, pause between blocks, pause before the re-reads, whole run. */
        const val ERASE_WINDOW_MS = 6_000L
        const val ERASE_GAP_MS = 1_000L
        const val ERASE_SETTLE_MS = 3_000L
        const val ERASE_BUDGET_MS = 120_000L

        /** A busy gateway: resends of one request, pause before resend n = n x this. */
        const val BUSY_RETRIES = 3
        const val BUSY_BACKOFF_MS = 1_000L

        /** Snapshot requests (`19 04`, log only) per scan. */
        const val SNAPSHOT_CAP = 30

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
    DtcMatch.Busy -> DtcWire.OUTCOME_BUSY
    DtcMatch.ManagerTimeout -> DtcWire.OUTCOME_MANAGER_TIMEOUT
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

/** `59 02 avail` then 4-byte records (3 code bytes, status); the codes of the rows with any of 0x0D, in list order. */
private fun visibleCodes(uds: ByteArray): List<Int> {
    val codes = ArrayList<Int>()
    var i = LIST_HEAD_BYTES
    while (i + RECORD_BYTES <= uds.size) {
        if (u(uds, i + RECORD_BYTES - 1) and VISIBLE_STATUS != 0) {
            codes += (u(uds, i) shl 16) or (u(uds, i + 1) shl 8) or u(uds, i + 2)
        }
        i += RECORD_BYTES
    }
    return codes
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
    DtcMatch.Busy -> "busy"
    DtcMatch.ManagerTimeout -> "manager_timeout"
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
