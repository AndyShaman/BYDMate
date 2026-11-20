package com.bydmate.app.helper.dtc

/**
 * The tyre-sensor jobs on 0x720 (DIDs 3305..3308): the read and the write of design B.3, run on
 * the scanner's thread through a [DiagLink] whose policy carries the [TpmsGuard] allowlist.
 */
@Suppress("TooManyFunctions") // one tyre job: each step of the sequence is its own small function
internal object TpmsJobs {
    /**
     * One run: its [link], the raw answers and, for a write, what each planned entry got.
     * Written by the run thread, read by the status verb; every access goes through [edit] or [status].
     */
    class Run(val link: DiagLink, val guard: TpmsGuard, val plan: List<TpmsPlanEntry>) {
        /** The policy outside the extended session, restored after `10 01`. */
        val normalPolicy = link.policy
        var idUds: ByteArray? = null
        var dtcBefore: ByteArray? = null
        var dtcAfter: ByteArray? = null
        val before = HashMap<Int, ByteArray?>()
        val after = HashMap<Int, ByteArray?>()
        val verdicts = HashMap<Int, TpmsVerdict>()
        var stoppedAt = ""
        /** `10 03` was answered and no `10 01` went out since; run thread only. */
        var inSession = false

        fun <T> edit(block: Run.() -> T): T = synchronized(this) { block() }

        fun status(state: Int, mode: Int, reason: String): TpmsStatus = synchronized(this) {
            TpmsStatus(
                state, mode, reason, idUds, dtcBefore, dtcAfter,
                TpmsWire.DIDS.map { TpmsSlotResult(it, before[it], after[it], verdicts[it]) }, stoppedAt,
            )
        }
    }

    /** Stops that still send the exit from the extended session (owner's rule): not the guard, not a foreign frame. */
    private val SESSION_EXIT_STOPS = listOf("not_park", "speed", "budget")
    /** Verdict of a `2E` answered `6E 33xx`, until the re-read decides. */
    private val SENT_OK = TpmsVerdict.UNVERIFIED
    private const val WRITE_ANSWER_BYTES = 3
    private const val NEGATIVE = 0x7F
    /** The block drops the extended session on its own after about 5 s (S3). */
    private const val S3_WAIT_MS = 6_000L

    /** Read job: F194, the stored codes, the four slots; nothing else (design B.1 read context). */
    fun read(run: Run): DiagStop {
        run.link.open("tpms read", 1)
        val id = readDid(run, DtcWire.DID_IDENTITY)
        run.edit { idUds = id }
        val dtc = readList(run)
        run.edit { dtcBefore = dtc }
        for (did in TpmsWire.DIDS) {
            val uds = readDid(run, did)
            run.edit { before[did] = uds }
        }
        log("tpms read shape l3=${run.edit { TpmsWire.l3Shape(idUds, before) }}")
        return if (run.link.frames == 0) {
            DiagStop(DtcWire.STATE_UNAVAILABLE, "no_frames")
        } else {
            DiagStop(DtcWire.STATE_DONE, "")
        }
    }

    /**
     * Write job, design B.3: identity and slots must have the L3 shape, compare-and-set against
     * this fresh read, `10 03` with the exact L3 answer, one security attempt, then each `2E` once,
     * the exit `10 01`, the re-read and the stored codes. [latch] records a failed security attempt
     * for the daemon's lifetime. A gear, speed or budget stop inside the session still sends one
     * `10 01` and nothing after it.
     */
    fun write(run: Run, sessionPolicy: LinkPolicy, latch: () -> Unit): DiagStop {
        log("tpms write plan=${run.plan.joinToString(",") { "${hexAddr(it.did)}:${id8(it.expectedOld)}>${id8(it.newId)}" }}")
        run.link.open("tpms write", 1)
        try {
            val entries = casStep(run, readShape(run)) ?: return DiagStop(DtcWire.STATE_DONE, "nothing_to_write")
            enterSession(run, sessionPolicy)
            val failure = unlock(run, latch) ?: writeEntries(run, entries)
            leave(run)
            readBack(run)
            return failure ?: DiagStop(DtcWire.STATE_DONE, "")
        } catch (e: DiagStop) {
            if (run.inSession && SESSION_EXIT_STOPS.any { e.reason.startsWith(it) }) exitAfterStop(run, e)
            throw e
        }
    }

    /**
     * The verdict of each planned entry once the run ended: the re-read decides for an entry whose
     * `2E` went out; without a re-read it keeps what the `2E` got (a positive answer = unverified).
     */
    fun close(run: Run, stop: DiagStop, mode: Int) {
        run.edit {
            for (e in plan) verdicts[e.did]?.let { verdicts[e.did] = finalVerdict(e, it, after[e.did]) }
            stoppedAt = stop.at
        }
        val s = run.status(stop.state, mode, stop.reason)
        s.slots.forEach {
            log("tpms slot did=${hexAddr(it.did)} verdict=${it.verdict ?: "-"} before=${hexOrNull(it.beforeUds)} after=${hexOrNull(it.afterUds)}")
        }
        log("tpms dtc before=${hexOrNull(s.dtcBefore)} after=${hexOrNull(s.dtcAfter)}")
        log(
            "tpms end state=${stateName(stop.state)} reason=${stop.reason} stoppedAt=${stop.at.ifEmpty { "-" }} " +
                "sent=${run.link.sent} frames=${run.link.frames} foreign=${run.link.foreign} " +
                "ms=${run.link.clock.nowMs() - run.link.startedMs}"
        )
    }

    private fun finalVerdict(e: TpmsPlanEntry, v: TpmsVerdict, after: ByteArray?): TpmsVerdict {
        val sent = v == SENT_OK || v == TpmsVerdict.REFUSED || v == TpmsVerdict.UNKNOWN
        val slot = TpmsWire.parseSlot(e.did, after)
        return when {
            !sent || slot == null -> v
            slot.id == e.newId -> TpmsVerdict.WRITTEN
            slot.id == e.expectedOld -> TpmsVerdict.NOT_WRITTEN
            else -> TpmsVerdict.OTHER
        }
    }

    /** Steps 1-3: F194, the stored codes and the four slots, each in the L3 shape; the current IDs. */
    private fun readShape(run: Run): Map<Int, Int> {
        val id = readDid(run, DtcWire.DID_IDENTITY)
        run.edit { idUds = id }
        if (!TpmsWire.l3Identity(id)) {
            log("tpms shape fail rule=f194 uds=${hexOrNull(id)}")
            throw stop(DtcWire.STATE_ABORTED, "not_l3 f194", at(TpmsWire.readRequest(DtcWire.DID_IDENTITY)))
        }
        log("tpms shape f194 ok")
        val dtc = readList(run)
        run.edit { dtcBefore = dtc }
        val current = HashMap<Int, Int>()
        for (did in TpmsWire.DIDS) {
            val uds = readDid(run, did)
            run.edit { before[did] = uds }
            val slot = TpmsWire.parseSlot(did, uds)
                ?: run {
                    log("tpms shape fail rule=slot did=${hexAddr(did)} uds=${hexOrNull(uds)}")
                    throw stop(DtcWire.STATE_ABORTED, "not_l3 slot=${hexAddr(did)}", at(TpmsWire.readRequest(did)))
                }
            current[did] = slot.id
        }
        log("tpms shape slots ok")
        return current
    }

    /** Step 4: the entries to write (DID order), null when every one already holds its new ID. */
    private fun casStep(run: Run, current: Map<Int, Int>): List<TpmsPlanEntry>? {
        for (e in run.plan) {
            val cur = current[e.did]
            val decision = when (cur) {
                e.expectedOld -> "write"
                e.newId -> "already"
                else -> "changed"
            }
            log("tpms cas did=${hexAddr(e.did)} current=${cur?.let(::id8)} old=${id8(e.expectedOld)} new=${id8(e.newId)} -> $decision")
        }
        val entries = when (val cas = TpmsWire.cas(run.plan, current)) {
            is TpmsCas.Abort -> throw stop(DtcWire.STATE_ABORTED, cas.reason, null)
            TpmsCas.NothingToWrite -> emptyList()
            is TpmsCas.Write -> cas.entries
        }
        run.edit {
            plan.forEach { e -> verdicts[e.did] = if (entries.any { it.did == e.did }) TpmsVerdict.NOT_RUN else TpmsVerdict.ALREADY }
        }
        return entries.ifEmpty { null }
    }

    /** Step 5: `10 03` must get exactly the L3 answer; then the session policy applies. */
    private fun enterSession(run: Run, sessionPolicy: LinkPolicy) {
        val req = TpmsWire.sessionRequest(TpmsWire.SESSION_EXTENDED)
        val m = ask(run, req, TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_EXTENDED)
        val refused = when {
            m !is DtcMatch.Answer -> "session_refused ${outcome(m)}"
            !m.uds.contentEquals(TpmsWire.EXTENDED_ANSWER) -> "not_l3 session=${hex(m.uds, 0, m.uds.size)}"
            else -> null
        }
        if (refused != null) throw stop(DtcWire.STATE_ABORTED, refused, at(req))
        run.inSession = true
        run.link.policy = sessionPolicy
        log("tpms session extended settle_ms=${sessionPolicy.settleMs} busy_retries=${sessionPolicy.busyRetries}")
    }

    /** Steps 6-7: `27 01`, then `27 02 <key>` unless the seed is zero; null when unlocked, else the stop to end with. */
    private fun unlock(run: Run, latch: () -> Unit): DiagStop? {
        val seedReq = TpmsWire.seedRequest()
        val m = ask(run, seedReq, TpmsWire.SERVICE_SECURITY, TpmsWire.SECURITY_SEED)
        val seed = (m as? DtcMatch.Answer)?.let { TpmsWire.seedOf(it.uds) }
        if (seed == null) {
            latch()
            return securityStop((m as? DtcMatch.Answer)?.let { "seed_len=${it.uds.size - 2}" } ?: outcome(m), seedReq)
        }
        val seedValue = TpmsWire.int32(seed, 0)
        if (seedValue == 0) {
            log("tpms security seed=00000000 already unlocked")
            return null
        }
        run.guard.onSeed(seed)
        val key = TpmsWire.securityKey(seedValue)
        log("tpms security seed=${id8(seedValue)} key=${id8(key)}")
        val keyReq = TpmsWire.keyRequest(key)
        val k = ask(run, keyReq, TpmsWire.SERVICE_SECURITY, TpmsWire.SECURITY_KEY)
        if (k is DtcMatch.Answer) {
            log("tpms security unlocked")
            return null
        }
        latch()
        return securityStop(outcome(k), keyReq)
    }

    private fun securityStop(detail: String, req: ByteArray): DiagStop {
        log("tpms security failed $detail, latched for this daemon")
        return DiagStop(DtcWire.STATE_ABORTED, "security_rejected $detail", at(req))
    }

    /** Step 8: each entry's `2E` once, DID order; the first one without `6E 33xx` ends the writes. */
    private fun writeEntries(run: Run, entries: List<TpmsPlanEntry>): DiagStop? {
        for (e in entries) {
            val req = TpmsWire.writeRequest(e)
            val sentBefore = run.link.sent
            val m = ask(run, req, TpmsWire.SERVICE_WRITE_DID, e.did)
            val verdict = when {
                m is DtcMatch.Answer && m.uds.size == WRITE_ANSWER_BYTES -> SENT_OK
                m is DtcMatch.Nrc -> TpmsVerdict.REFUSED
                m == DtcMatch.Busy -> TpmsVerdict.NOT_SENT
                else -> TpmsVerdict.UNKNOWN
            }
            log("tpms write did=${hexAddr(e.did)} new=${id8(e.newId)} answer=${outcome(m)} sends=${run.link.sent - sentBefore}")
            run.edit { verdicts[e.did] = verdict }
            val reason = when (verdict) {
                SENT_OK -> continue
                TpmsVerdict.REFUSED -> "write_refused nrc=0x%02X".format((m as DtcMatch.Nrc).code)
                TpmsVerdict.NOT_SENT -> "write_busy"
                else -> "write_no_answer ${outcome(m)}"
            }
            return DiagStop(DtcWire.STATE_ABORTED, "$reason did=${hexAddr(e.did)}", at(req))
        }
        return null
    }

    /** Step 9: `10 01`; without the exact default-session answer the block's own S3 timeout is awaited. */
    private fun leave(run: Run) {
        val m = ask(run, TpmsWire.sessionRequest(TpmsWire.SESSION_DEFAULT), TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_DEFAULT)
        run.inSession = false
        run.link.policy = run.normalPolicy
        if (m is DtcMatch.Answer && m.uds.contentEquals(TpmsWire.DEFAULT_ANSWER)) return
        log("tpms exit not confirmed answer=${outcome(m)}, waiting $S3_WAIT_MS ms")
        run.link.clock.sleep(S3_WAIT_MS)
    }

    /** After a gear, speed or budget stop inside the session: the one `10 01`, nothing after it. */
    private fun exitAfterStop(run: Run, e: DiagStop) {
        log("tpms exit after stop ${e.reason}")
        run.inSession = false
        runCatching {
            run.link.exit(
                TpmsWire.ADDR, TpmsWire.frame(TpmsWire.sessionRequest(TpmsWire.SESSION_DEFAULT)),
                TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_DEFAULT,
            )
        }
            .onSuccess { log("tpms exit answer ${outcome(it)}") }
            .onFailure { log("tpms exit not sent ${(it as? DiagStop)?.reason ?: it.javaClass.simpleName}") }
    }

    /** Steps 10 and 11: the four slots again and the stored codes. */
    private fun readBack(run: Run) {
        for (did in TpmsWire.DIDS) {
            val uds = readDid(run, did)
            run.edit { after[did] = uds }
        }
        val dtc = readList(run)
        run.edit { dtcAfter = dtc }
    }

    /** One request to 0x720 through the link (its gates, policy and allowlist). */
    private fun ask(run: Run, uds: ByteArray, service: Int, sub: Int): DtcMatch? =
        run.link.request(TpmsWire.ADDR, TpmsWire.frame(uds), service, sub)

    private fun readDid(run: Run, did: Int): ByteArray? =
        udsOf(ask(run, TpmsWire.readRequest(did), DtcWire.SERVICE_READ_DID, did), DtcWire.SERVICE_READ_DID)

    private fun readList(run: Run): ByteArray? =
        udsOf(ask(run, TpmsWire.listRequest(), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST), DtcWire.SERVICE_READ_DTC)

    private fun at(uds: ByteArray) = "at=${hexAddr(TpmsWire.ADDR)} req=${hex(uds, 0, uds.size)}"

    private fun id8(v: Int) = "%08X".format(v)

    private fun hexOrNull(b: ByteArray?) = b?.let { hex(it, 0, it.size) }

    private fun outcome(m: DtcMatch?): String = m?.let(::kindName) ?: "no_answer"

    /** The answer as the app gets it: positive UDS, `7F sid nrc`, or the raw multi-frame; else null. */
    private fun udsOf(m: DtcMatch?, service: Int): ByteArray? = when (m) {
        is DtcMatch.Answer -> m.uds
        is DtcMatch.Nrc -> byteArrayOf(NEGATIVE.toByte(), service.toByte(), m.code.toByte())
        is DtcMatch.Multiframe -> m.raw
        else -> null
    }
}
