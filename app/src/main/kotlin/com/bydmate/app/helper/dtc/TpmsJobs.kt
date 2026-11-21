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
        /** A write run: its answers count only with result byte 1 and the exact length (C3). */
        val writeJob = plan.isNotEmpty()
        /** `10 03` reached the device (answered or not); run thread only. */
        var sessionSent = false
        /** A `10 01` reached the device after it; run thread only. */
        var exitSent = false
        /** That `10 01` got exactly the default-session answer; run thread only. */
        var exitConfirmed = false

        /** The block may still be in the extended session when the run ends: the next job waits out S3. */
        val exitUnconfirmed: Boolean get() = sessionSent && !exitConfirmed

        fun <T> edit(block: Run.() -> T): T = synchronized(this) { block() }

        fun status(state: Int, mode: Int, reason: String): TpmsStatus = synchronized(this) {
            TpmsStatus(
                state, mode, reason, idUds, dtcBefore, dtcAfter,
                TpmsWire.DIDS.map { TpmsSlotResult(it, before[it], after[it], verdicts[it]) }, stoppedAt,
            )
        }
    }

    /** Stops after which nothing more is sent, not even the exit (owner's rule): the guard, a foreign frame. */
    private val NO_EXIT_STOPS = listOf("diag_busy", "foreign")
    /** Verdict of a `2E` answered `6E 33xx`, until the re-read decides. */
    private val SENT_OK = TpmsVerdict.UNVERIFIED
    private const val WRITE_ANSWER_BYTES = 3
    private const val NEGATIVE = 0x7F
    /** The block drops the extended session on its own after about 5 s (S3). */
    const val S3_WAIT_MS = 6_000L

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
     * the exit `10 01`, the re-read and the stored codes. The guard counts the attempt in
     * [security] as the key goes out; an exact `67 02` clears the count, an NRC 36/37 starts its
     * lockout and stops the run. Any stop once `10 03` went out, other than the guard or a foreign
     * frame, still sends one `10 01` and nothing after it.
     */
    @Suppress("TooGenericExceptionCaught") // whatever ends the run inside the session gets the exit, then goes on
    fun write(run: Run, sessionPolicy: LinkPolicy, security: TpmsSecurity): DiagStop {
        log("tpms write plan=${run.plan.joinToString(",") { "${hexAddr(it.did)}:${id8(it.expectedOld)}>${id8(it.newId)}" }}")
        run.link.open("tpms write", 1)
        try {
            val entries = casStep(run, readShape(run)) ?: return DiagStop(DtcWire.STATE_DONE, "nothing_to_write")
            enterSession(run, sessionPolicy)
            val failure = unlock(run, security) ?: writeEntries(run, entries)
            leave(run)
            readBack(run)
            return failure ?: DiagStop(DtcWire.STATE_DONE, "")
        } catch (e: Exception) {
            val reason = (e as? DiagStop)?.reason ?: "exception ${e.javaClass.simpleName}"
            if (run.sessionSent && !run.exitSent && NO_EXIT_STOPS.none { reason.startsWith(it) }) exitAfterStop(run, reason)
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
                "session_sent=${run.sessionSent} exit_sent=${run.exitSent} exit_confirmed=${run.exitConfirmed} " +
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
        val idMatch = ask(run, TpmsWire.readRequest(DtcWire.DID_IDENTITY), DtcWire.SERVICE_READ_DID, DtcWire.DID_IDENTITY)
        val id = udsOf(idMatch, DtcWire.SERVICE_READ_DID)
        run.edit { idUds = id }
        if (positive(run, idMatch) == null || !TpmsWire.l3Identity(id)) {
            log("tpms shape fail rule=f194 uds=${hexOrNull(id)}")
            throw stop(DtcWire.STATE_ABORTED, "not_l3 f194", at(TpmsWire.readRequest(DtcWire.DID_IDENTITY)))
        }
        log("tpms shape f194 ok")
        val dtc = readList(run)
        run.edit { dtcBefore = dtc }
        val current = HashMap<Int, Int>()
        for (did in TpmsWire.DIDS) {
            val m = ask(run, TpmsWire.readRequest(did), DtcWire.SERVICE_READ_DID, did)
            val uds = udsOf(m, DtcWire.SERVICE_READ_DID)
            run.edit { before[did] = uds }
            val slot = positive(run, m)?.let { TpmsWire.parseSlot(did, it.uds) }
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

    /**
     * Step 5: `10 03` must get exactly the L3 answer; then the session policy applies. From this
     * request on only plain frames count as ours ([DiagLink.plainOnly]).
     */
    private fun enterSession(run: Run, sessionPolicy: LinkPolicy) {
        val req = TpmsWire.sessionRequest(TpmsWire.SESSION_EXTENDED)
        run.link.plainOnly = true
        val m = sent(run, { run.sessionSent = true }) { ask(run, req, TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_EXTENDED) }
        val answer = positive(run, m)
        val refused = when {
            answer == null -> "session_refused ${outcome(m)}"
            !answer.uds.contentEquals(TpmsWire.EXTENDED_ANSWER) -> "not_l3 session=${hex(answer.uds, 0, answer.uds.size)}"
            else -> null
        }
        if (refused != null) throw stop(DtcWire.STATE_ABORTED, refused, at(req))
        run.link.policy = sessionPolicy
        log("tpms session extended settle_ms=${sessionPolicy.settleMs} busy_retries=${sessionPolicy.busyRetries}")
    }

    /**
     * Steps 6-7: `27 01`, then `27 02 <key>` unless the seed is zero; null when unlocked, else the
     * stop to end with. The attempt counted as the key went out is cleared by exactly `67 02` only;
     * an NRC 36/37 to either request throws the lockout stop (the exit follows, nothing else).
     */
    private fun unlock(run: Run, security: TpmsSecurity): DiagStop? {
        val seedReq = TpmsWire.seedRequest()
        val m = ask(run, seedReq, TpmsWire.SERVICE_SECURITY, TpmsWire.SECURITY_SEED)
        lockoutCheck(run, m, security, seedReq)
        val seedAnswer = positive(run, m)
        val seed = seedAnswer?.let { TpmsWire.seedOf(it.uds) }
        if (seed == null) {
            return securityStop(seedAnswer?.let { "seed_len=${it.uds.size - 2}" } ?: outcome(m), seedReq)
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
        lockoutCheck(run, k, security, keyReq)
        if (positive(run, k)?.uds?.contentEquals(TpmsWire.KEY_ANSWER) == true) {
            security.unlocked()
            log("tpms security unlocked")
            return null
        }
        return securityStop(outcome(k) + ((k as? DtcMatch.Answer)?.let { " uds=${hex(it.uds, 0, it.uds.size)}" } ?: ""), keyReq)
    }

    /** NRC 36 or 37 to a `27` request: the lockout starts and the run stops here. */
    private fun lockoutCheck(run: Run, m: DtcMatch?, security: TpmsSecurity, req: ByteArray) {
        if (m !is DtcMatch.Nrc || m.code !in TpmsSecurity.LOCKOUT_NRCS) return
        security.lockout(run.link.clock.nowMs(), m.code)
        throw stop(DtcWire.STATE_ABORTED, "security_lockout nrc=0x%02X".format(m.code), at(req))
    }

    private fun securityStop(detail: String, req: ByteArray): DiagStop {
        log("tpms security failed $detail")
        return DiagStop(DtcWire.STATE_ABORTED, "security_rejected $detail", at(req))
    }

    /**
     * Step 8: each entry's `2E` once, DID order; the first one without exactly `6E 33xx` ends the
     * writes. A `2E` that went out before the run stopped is unverified: the re-read, if any, decides.
     */
    private fun writeEntries(run: Run, entries: List<TpmsPlanEntry>): DiagStop? {
        for (e in entries) {
            val req = TpmsWire.writeRequest(e)
            val sentBefore = run.link.sent
            val m = sent(run, { run.edit { verdicts[e.did] = SENT_OK } }) { ask(run, req, TpmsWire.SERVICE_WRITE_DID, e.did) }
            val verdict = when {
                positive(run, m)?.uds?.size == WRITE_ANSWER_BYTES -> SENT_OK
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

    /**
     * Step 9: `10 01`; without the exact default-session answer the block's own S3 timeout is awaited.
     * Like the exit after a stop it reads no gear or speed first (only the guard and the allowlist):
     * a blocked autoservice read must not hold the block in the extended session (release audit E1).
     */
    private fun leave(run: Run) {
        val m = sent(run, { run.exitSent = true }) {
            run.link.exit(
                TpmsWire.ADDR, TpmsWire.frame(TpmsWire.sessionRequest(TpmsWire.SESSION_DEFAULT)),
                TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_DEFAULT,
            )
        }
        run.exitConfirmed = defaultAnswer(run, m)
        run.link.policy = run.normalPolicy
        if (!run.exitConfirmed) {
            log("tpms exit not confirmed answer=${outcome(m)}, waiting $S3_WAIT_MS ms")
            run.link.clock.sleep(S3_WAIT_MS)
        }
        run.link.plainOnly = false
    }

    /** After a stop once `10 03` went out (not the guard, not a foreign frame): the one `10 01`, nothing after it. */
    private fun exitAfterStop(run: Run, reason: String) {
        log("tpms exit after stop $reason")
        runCatching {
            sent(run, { run.exitSent = true }) {
                run.link.exit(
                    TpmsWire.ADDR, TpmsWire.frame(TpmsWire.sessionRequest(TpmsWire.SESSION_DEFAULT)),
                    TpmsWire.SERVICE_SESSION, TpmsWire.SESSION_DEFAULT,
                )
            }
        }
            .onSuccess {
                run.exitConfirmed = defaultAnswer(run, it)
                log("tpms exit answer ${outcome(it)} confirmed=${run.exitConfirmed}")
            }
            .onFailure { log("tpms exit not sent ${(it as? DiagStop)?.reason ?: it.javaClass.simpleName} sent=${run.exitSent}") }
    }

    private fun defaultAnswer(run: Run, m: DtcMatch?): Boolean = positive(run, m)?.uds?.contentEquals(TpmsWire.DEFAULT_ANSWER) == true

    /**
     * Steps 10 and 11: the four slots again and the stored codes. A slot answer whose frame result
     * byte is not 1 is not a read-back (D1): that slot stays unread, so its verdict stays as the `2E` left it.
     */
    private fun readBack(run: Run) {
        for (did in TpmsWire.DIDS) {
            val m = ask(run, TpmsWire.readRequest(did), DtcWire.SERVICE_READ_DID, did)
            val uds = if (m is DtcMatch.Answer && positive(run, m) == null) {
                log("tpms reread did=${hexAddr(did)} ${outcome(m)} uds=${hex(m.uds, 0, m.uds.size)} not taken")
                null
            } else {
                udsOf(m, DtcWire.SERVICE_READ_DID)
            }
            run.edit { after[did] = uds }
        }
        val dtc = readList(run)
        run.edit { dtcAfter = dtc }
    }

    /** One request to 0x720 through the link (its gates, policy and allowlist). */
    private fun ask(run: Run, uds: ByteArray, service: Int, sub: Int): DtcMatch? =
        run.link.request(TpmsWire.ADDR, TpmsWire.frame(uds), service, sub)

    /** Runs the one request of [block]; [mark] when it reached the device, also when the wait for its answer then threw. */
    private inline fun <T> sent(run: Run, mark: () -> Unit, block: () -> T): T {
        val before = run.link.sent
        try {
            return block()
        } finally {
            if (run.link.sent > before) mark()
        }
    }

    /** The positive answer as the write takes it: result byte 1 only (C3); the read job takes any. */
    private fun positive(run: Run, m: DtcMatch?): DtcMatch.Answer? =
        (m as? DtcMatch.Answer)?.takeIf { !run.writeJob || it.result == DtcWire.RESULT_ANSWER }

    private fun readDid(run: Run, did: Int): ByteArray? =
        udsOf(ask(run, TpmsWire.readRequest(did), DtcWire.SERVICE_READ_DID, did), DtcWire.SERVICE_READ_DID)

    private fun readList(run: Run): ByteArray? =
        udsOf(ask(run, TpmsWire.listRequest(), DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST), DtcWire.SERVICE_READ_DTC)

    private fun at(uds: ByteArray) = "at=${hexAddr(TpmsWire.ADDR)} req=${hex(uds, 0, uds.size)}"

    private fun id8(v: Int) = "%08X".format(v)

    private fun hexOrNull(b: ByteArray?) = b?.let { hex(it, 0, it.size) }

    private fun outcome(m: DtcMatch?): String = when {
        m == null -> "no_answer"
        m is DtcMatch.Answer && m.result != DtcWire.RESULT_ANSWER -> "answer res=${m.result}"
        else -> kindName(m)
    }

    /** The answer as the app gets it: positive UDS, `7F sid nrc`, or the raw multi-frame; else null. */
    private fun udsOf(m: DtcMatch?, service: Int): ByteArray? = when (m) {
        is DtcMatch.Answer -> m.uds
        is DtcMatch.Nrc -> byteArrayOf(NEGATIVE.toByte(), service.toByte(), m.code.toByte())
        is DtcMatch.Multiframe -> m.raw
        else -> null
    }
}
