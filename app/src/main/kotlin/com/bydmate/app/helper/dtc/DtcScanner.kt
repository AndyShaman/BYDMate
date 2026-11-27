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
 * The daemon's diagnostic jobs: one run at a time (scan, erase, tyre-sensor read or write,
 * firmware check), on its own thread ([runner] starts it), talking only to the whitelisted
 * addresses with the fixed requests of [DtcWire], [TpmsWire] and [EcuFirmwareWire]. Checks the guard property, the gear and the budget before
 * every request; an erase and a tyre write also check the speed ([readSpeed], km/h, null =
 * unknown) and stop on any foreign frame.
 */
@Suppress("TooManyFunctions") // one scan job: each step of the run is its own small function
class DtcScanner(
    private val device: DtcDevice,
    private val clock: DtcClock,
    private val readGuard: () -> String?,
    private val runner: (Runnable) -> Unit = { Thread(it, "dtc-scan").start() },
    private val readSpeed: () -> Int? = { null },
) {
    /** One address while it is being read; [outcome] stays skipped until its first answer. */
    private class Acc(val addr: Int) {
        var outcome = DtcWire.OUTCOME_SKIPPED
        var countUds: ByteArray? = null
        var listUds: ByteArray? = null
        var idUds: ByteArray? = null
        fun result() = DtcAddressResult(addr, outcome, countUds, listUds, idUds)
    }

    /** One scan or erase run: its [link] and the scan's own bookkeeping; touched by the run thread only. */
    private class Run(val link: DiagLink) {
        var current: Acc? = null
        /** Blocks with a visible row, whose F197 name is asked after the whole scan. */
        val named = ArrayList<Int>()
        /** (addr, code) of the visible rows, at most [SNAPSHOT_CAP], asked after the names. */
        val snapshots = ArrayList<Pair<Int, Int>>()
        /** Erase: index of the block being cleared and [sent] when its clear started. */
        var eraseIndex = -1
        var eraseSentBefore = 0
    }

    private val lock = Any()
    private var state = DtcWire.STATE_IDLE
    private var mode = DtcWire.MODE_SCAN
    private var done = 0
    private var reason = ""
    private val results = ArrayList<DtcAddressResult>()
    private val erase = ArrayList<DtcEraseResult>()
    private var tpms: TpmsJobs.Run? = null
    private var firmware: FirmwareJob.Run? = null
    /** The tyre write's security attempts: the pause after a failed key and the lockout of NRC 36/37. */
    private val security = TpmsSecurity()
    /** A tyre write that left the extended session unconfirmed: no job starts before this time (S3). */
    private var quietUntilMs = Long.MIN_VALUE

    /** 0 started, 1 busy. [gear] returns the live gear value, null when it could not be read. */
    fun start(gear: () -> Int?): Int = launch(DtcWire.MODE_SCAN, IntArray(0)) {
        val run = Run(link(gear, SCAN_POLICY))
        execute(run.link, { scan(run) }) { closeResults(run, it) }
    }

    /**
     * Clears the stored codes of [addrs] (`14 FF FF FF` each, in this order) and reads them again:
     * 0 started, 1 busy (a scan or an erase runs), -1 refused (empty, repeated or outside
     * [DtcWire.ADDRESSES]; nothing is sent). [accepted] runs once the start is taken, before
     * the job's thread starts.
     */
    fun startErase(addrs: IntArray, accepted: () -> Unit = {}, gear: () -> Int?): Int {
        if (addrs.isEmpty() || addrs.any { it !in DtcWire.ADDRESSES } || addrs.distinct().size != addrs.size) {
            log("erase refused addrs=${addrs.joinToString(",") { hexAddr(it) }}")
            return START_REFUSED
        }
        return launch(DtcWire.MODE_ERASE, addrs, accepted = accepted) {
            val run = Run(link(gear, ERASE_POLICY))
            execute(run.link, { eraseAll(run) }) { closeErase(run, it) }
        }
    }

    /**
     * Reads the four tyre-sensor slots of 0x720 (F194, 19 02 FF, 22 3305..3308; nothing else):
     * 0 started, 1 busy, -1 refused. Read gates as for a scan.
     */
    fun startTpmsRead(gear: () -> Int?): Int {
        val guard = TpmsGuard(TpmsJobKind.READ, emptyList(), false)
        val run = TpmsJobs.Run(
            link(gear, tpmsPolicy(guard, speedGate = false, strict = false, legacyGuardOk = true)),
            guard,
            emptyList(),
        )
        return launch(DtcWire.MODE_TPMS_READ, IntArray(0), run) {
            execute(run.link, { TpmsJobs.read(run) }) { TpmsJobs.close(run, it, DtcWire.MODE_TPMS_READ) }
        }
    }

    /**
     * Writes [plan] to 0x720 per design B.3: 0 started, 1 busy, -1 refused (invalid plan, or a
     * security wait of [TpmsSecurity] runs, [securityWaitMs]; nothing is sent). Gates as for an
     * erase (P, speed exactly 0, guard), strict traffic, one `27 02`, no resent `2E`. A stop for
     * stop once `10 03` went out still sends one `10 01`, except for the guard or a foreign frame,
     * after which nothing more is sent. Ended without a confirmed `50 01`, no job starts for
     * [TpmsJobs.S3_WAIT_MS]. [accepted] as for [startErase].
     */
    fun startTpmsWrite(plan: List<TpmsPlanEntry>, accepted: () -> Unit = {}, gear: () -> Int?): Int {
        val planText = plan.joinToString(",") { "${hexAddr(it.did)}:%08X>%08X".format(it.expectedOld, it.newId) }
        val wait = securityWaitMs()
        val refused = TpmsWire.validatePlan(plan) ?: "security_wait ms_left=$wait".takeIf { wait > 0 }
        if (refused != null) {
            log("tpms write refused $refused plan=$planText")
            return START_REFUSED
        }
        val guard = TpmsGuard(
            TpmsJobKind.WRITE, plan,
            securityWait = { securityWaitMs() > 0 },
            onKeySent = { security.keySent(clock.nowMs()) },
        )
        val run = TpmsJobs.Run(link(gear, tpmsPolicy(guard, speedGate = true, strict = true)), guard, plan)
        val session = tpmsPolicy(guard, speedGate = true, strict = true, inSession = true)
        return launch(DtcWire.MODE_TPMS_WRITE, IntArray(0), run, accepted = accepted) {
            execute(run.link, { TpmsJobs.write(run, session, security) }) {
                TpmsJobs.close(run, it, DtcWire.MODE_TPMS_WRITE)
                if (run.exitUnconfirmed) {
                    val until = clock.nowMs() + TpmsJobs.S3_WAIT_MS
                    synchronized(lock) { quietUntilMs = until }
                    log("tpms exit unconfirmed: no job starts before ms=$until")
                }
            }
        }
    }

    /**
     * Reads `22 F1 94` then, when the block answered it (positive or negative), `22 F1 95` of every address in [DtcWire.ADDRESSES]:
     * 0 started, 1 busy, -1 refused. Read gates as for a scan; nothing else is ever sent.
     */
    fun startFirmware(gear: () -> Int?): Int {
        val run = FirmwareJob.Run(link(gear, FIRMWARE_POLICY))
        return launch(DtcWire.MODE_FIRMWARE, IntArray(0), firmwareRun = run) {
            execute(run.link, { FirmwareJob.read(run) }) { FirmwareJob.close(run, it) }
        }
    }

    /** Milliseconds until a tyre write may start after a failed key or an NRC 36/37; 0 when it may now. */
    fun securityWaitMs(): Long = security.waitMs(clock.nowMs())

    /** A failed key at [nowMs], as a `27 02` of a write would count it. */
    internal fun recordSecurityFailure(nowMs: Long) = security.keySent(nowMs)

    /** The last firmware check's raw answers; state IDLE with no results when the last job was not one. */
    fun firmwareStatus(): FirmwareStatus {
        val (state, reason, run) = synchronized(lock) {
            Triple(state, reason, firmware.takeIf { mode == DtcWire.MODE_FIRMWARE })
        }
        return run?.status(state, reason) ?: FirmwareStatus(DtcWire.STATE_IDLE, 0, 0, "", emptyList())
    }

    /** The last tyre job's raw answers and verdicts (one slot per DID); empty slots when the last job was not one. */
    fun tpmsStatus(): TpmsStatus {
        val (base, run) = synchronized(lock) {
            val slots = TpmsWire.DIDS.map { TpmsSlotResult(it, null, null, null) }
            TpmsStatus(state, mode, reason, null, null, null, slots, "") to tpms
        }
        return run?.status(base.state, base.mode, base.reason) ?: base
    }

    private fun link(gear: () -> Int?, policy: LinkPolicy) = DiagLink(device, clock, readGuard, readSpeed, gear, policy)

    /** Tyre jobs: 0x720 only through [guard]; inside the extended session no busy resend, a short settle, pendings wait on. */
    private fun tpmsPolicy(
        guard: TpmsGuard,
        speedGate: Boolean,
        strict: Boolean,
        inSession: Boolean = false,
        legacyGuardOk: Boolean = false,
    ) =
        if (inSession) {
            LinkPolicy(
                TPMS_BUDGET_MS, speedGate, strict, settleMs = TPMS_SESSION_SETTLE_MS, busyRetries = 0,
                pendingWindowMs = TPMS_PENDING_WINDOW_MS, allow = guard::allowed, legacyGuardOk = legacyGuardOk,
            )
        } else {
            LinkPolicy(TPMS_BUDGET_MS, speedGate, strict, allow = guard::allowed, legacyGuardOk = legacyGuardOk)
        }

    @Suppress("LongParameterList") // the state one start sets, then the hook that must run before the thread
    private fun launch(
        mode: Int,
        addrs: IntArray,
        tpmsRun: TpmsJobs.Run? = null,
        firmwareRun: FirmwareJob.Run? = null,
        accepted: () -> Unit = {},
        job: () -> Unit,
    ): Int {
        synchronized(lock) {
            if (state == DtcWire.STATE_RUNNING) return START_BUSY
            val now = clock.nowMs()
            if (now < quietUntilMs) {
                log("start busy mode=$mode: the last tyre write left the session unconfirmed, ms_left=${quietUntilMs - now}")
                return START_BUSY
            }
            accepted()
            state = DtcWire.STATE_RUNNING
            this.mode = mode
            tpms = tpmsRun
            firmware = firmwareRun
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
        // A tyre job or a firmware check reports its mode with empty lists, so the scan and erase pollers see "not mine".
        val total = when (mode) {
            DtcWire.MODE_ERASE -> erase.size
            DtcWire.MODE_SCAN -> DtcWire.ADDRESSES.size
            else -> 0
        }
        DtcScanStatus(state, done, total, reason, results.toList(), erase.toList(), mode)
    }

    /** The run thread: nothing escapes it, and the terminal state is published after the listener is gone. */
    private fun execute(link: DiagLink, body: () -> DiagStop, close: (DiagStop) -> Unit) {
        var stop = DiagStop(DtcWire.STATE_ABORTED, "exception")
        try {
            stop = runCatching { body() }.getOrElse { e ->
                e as? DiagStop ?: stop(DtcWire.STATE_ABORTED, "exception ${e.javaClass.simpleName}", at = null)
            }
        } finally {
            // The listener lives for this run only, whatever ended it.
            if (link.registerTried) {
                runCatching { device.unregister() }
                    .onSuccess { log("unregistered") }
                    .onFailure { log("unregister failed ${it.javaClass.simpleName}") }
            }
            runCatching { close(stop) }
            publish(stop.state, stop.reason)
        }
    }

    /** A run stopped mid-address keeps what that address already answered; the rest are skipped. */
    private fun closeResults(run: Run, stop: DiagStop) {
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
                "sent=${run.link.sent} frames=${run.link.frames} foreign=${run.link.foreign} ms=${clock.nowMs() - run.link.startedMs}"
        )
    }

    /**
     * The block whose clear was out when the run stopped is unconfirmed, the blocks after it were
     * not run; both carry the stop reason. Finished blocks keep their confirmation.
     */
    private fun closeErase(run: Run, stop: DiagStop) {
        val list = synchronized(lock) {
            for (i in erase.indices) {
                val e = erase[i]
                if (e.confirm != DtcWire.CONFIRM_NOT_RUN) continue
                val inFlight = i == run.eraseIndex && run.link.sent > run.eraseSentBefore
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
                "sent=${run.link.sent} frames=${run.link.frames} ms=${clock.nowMs() - run.link.startedMs}"
        )
    }

    /** The whole scan; returns the terminal state (DONE or no_frames) or throws [Stop]. */
    private fun scan(run: Run): DiagStop {
        run.link.open("scan", DtcWire.ADDRESSES.size)
        for (addr in DtcWire.ADDRESSES) {
            val acc = Acc(addr)
            run.current = acc
            val started = clock.nowMs()
            val requests = run.link.sent
            readAddress(run, acc)
            log(
                "addr=${hexAddr(addr)} outcome=${acc.outcome} requests=${run.link.sent - requests} " +
                    "ms=${clock.nowMs() - started}"
            )
            synchronized(lock) {
                results += acc.result()
                done++
            }
            run.current = null
        }
        readExtras(run)
        return if (run.link.frames == 0) {
            DiagStop(DtcWire.STATE_UNAVAILABLE, "no_frames")
        } else {
            DiagStop(DtcWire.STATE_DONE, "")
        }
    }

    /**
     * The erase: `14 FF FF FF` per block with a gap between blocks, then a settle pause and the
     * codes of every block whose clear went out read again. Never resends a clear whose answer
     * was lost (only a busy gateway, which did not forward it, gets it again).
     */
    private fun eraseAll(run: Run): DiagStop {
        val addrs = synchronized(lock) { erase.map { it.addr } }
        run.link.open("erase", addrs.size)
        val cleared = ArrayList<Int>()
        for ((i, addr) in addrs.withIndex()) {
            if (i > 0) clock.sleep(ERASE_GAP_MS)
            run.eraseIndex = i
            run.eraseSentBefore = run.link.sent
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
        return DiagStop(DtcWire.STATE_DONE, "")
    }

    private fun eraseBlock(run: Run, i: Int, addr: Int) {
        val started = clock.nowMs()
        val m = run.link.request(addr, DtcWire.clearRequest(addr), DtcWire.SERVICE_CLEAR, -1)
        val result = when (m) {
            is DtcMatch.Answer -> DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", null)
            is DtcMatch.Nrc -> DtcEraseResult(addr, DtcWire.CONFIRM_REFUSED, m.code, DtcWire.OUTCOME_NRC, null)
            else -> DtcEraseResult(addr, DtcWire.CONFIRM_UNCONFIRMED, null, outcomeOf(m), null)
        }
        log(
            "erase addr=${hexAddr(addr)} confirm=${result.confirm} nrc=${result.nrc?.let { "0x%02X".format(it) } ?: "-"} " +
                "reason=${result.reason.ifEmpty { "-" }} sends=${run.link.sent - run.eraseSentBefore} ms=${clock.nowMs() - started}"
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
        val count = run.link.request(addr, DtcWire.countRequest(addr), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_COUNT)
        acc.outcome = outcomeOf(count)
        acc.countUds = udsOf(count, DtcWire.SERVICE_READ_DTC)
        if (count !is DtcMatch.Answer) return emptyList()
        val uds = count.uds
        if (uds.size < COUNT_UDS_BYTES) return emptyList()
        val records = (u(uds, 4) shl 8) or u(uds, 5)
        if (records == 0) return emptyList()

        val mask = if (u(uds, 2) and STATUS_PENDING != 0) MASK_WITH_PENDING else MASK_CONFIRMED
        var list = run.link.request(addr, DtcWire.listRequest(addr, mask), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
        if (list is DtcMatch.Nrc) {
            log("list retry addr=${hexAddr(addr)} mask=${"%02X".format(mask)} nrc=0x${"%02X".format(list.code)} next=FF")
            list = run.link.request(addr, DtcWire.listRequest(addr, MASK_ALL), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST)
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
        val id = run.link.request(
            addr, DtcWire.identityRequest(addr),
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
                val name = run.link.request(addr, DtcWire.nameRequest(addr), DtcWire.SERVICE_READ_DID, DtcWire.DID_NAME)
                log("name addr=${hexAddr(addr)} ${DtcWire.nameText(name)}")
            }
            for ((addr, code) in run.snapshots) {
                val snap = run.link.request(
                    addr, DtcWire.snapshotRequest(addr, code),
                    DtcWire.SERVICE_READ_DTC, DtcWire.SUB_SNAPSHOT,
                )
                log("snapshot addr=${hexAddr(addr)} dtc=${"%06X".format(code)} kind=${snap?.let(::kindName) ?: "none"}")
            }
        } catch (e: DiagStop) {
            log("extras stopped ${e.reason}, the scan result stays")
        } catch (e: Exception) {
            log("extras stopped exception ${e.javaClass.simpleName}, the scan result stays")
        }
    }

    private fun publish(state: Int, reason: String) = synchronized(lock) {
        this.state = state
        this.reason = reason
    }

    companion object {
        const val START_OK = 0
        const val START_BUSY = 1
        const val START_REFUSED = -1

        /** Timings of the validated probes: passive listen, answer window, duplicate settle, whole scan. */
        const val PASSIVE_MS = 2_000L
        const val WINDOW_MS = 2_500L
        const val SETTLE_MS = 400L
        const val BUDGET_MS = 180_000L

        /** Tyre jobs: whole run; inside the extended session the duplicate settle and the window after a pending. */
        const val TPMS_BUDGET_MS = 30_000L
        const val TPMS_SESSION_SETTLE_MS = 60L
        const val TPMS_PENDING_WINDOW_MS = 5_000L

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

        private const val STATUS_PENDING = 0x04
        private const val MASK_WITH_PENDING = 0x0D
        private const val MASK_CONFIRMED = 0x09
        private const val MASK_ALL = 0xFF
        private const val COUNT_UDS_BYTES = 6

        private val SCAN_POLICY = LinkPolicy(BUDGET_MS, speedGate = false, strictTraffic = false, legacyGuardOk = true)
        private val ERASE_POLICY = LinkPolicy(ERASE_BUDGET_MS, speedGate = true, strictTraffic = true)
        private val FIRMWARE_POLICY = LinkPolicy(
            BUDGET_MS, speedGate = false, strictTraffic = false, allow = EcuFirmwareWire::allowed, legacyGuardOk = true,
        )
    }
}

// Byte reading and the log text of DtcScanner.
private const val NEGATIVE = 0x7F
private const val LIST_HEAD_BYTES = 3
private const val RECORD_BYTES = 4
private const val VISIBLE_STATUS = 0x0D

internal fun outcomeOf(m: DtcMatch?): String = when (m) {
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
internal fun udsOf(m: DtcMatch?, service: Int): ByteArray? = when (m) {
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

internal fun stateName(state: Int): String = when (state) {
    DtcWire.STATE_DONE -> "DONE"
    DtcWire.STATE_UNAVAILABLE -> "UNAVAILABLE"
    DtcWire.STATE_ABORTED -> "ABORTED"
    DtcWire.STATE_RUNNING -> "RUNNING"
    else -> "IDLE"
}
