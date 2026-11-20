package com.bydmate.app.helper.dtc

/**
 * The firmware check (CONTEXT.md «Прошивки блоков»): per address of [DtcWire.ADDRESSES] `22 F1 94`,
 * then `22 F1 95` only when F194 got an answer. Read gates as for a scan, run on the scanner's
 * thread through a [DiagLink] whose policy carries [EcuFirmwareWire.allowed].
 */
internal object FirmwareJob {
    /** One run: its [link] and the answers so far. Written by the run thread, read by the status verb. */
    class Run(val link: DiagLink) {
        private val results = ArrayList<FirmwareAddressResult>()

        fun add(r: FirmwareAddressResult) = synchronized(this) { results += r }

        fun status(state: Int, reason: String): FirmwareStatus = synchronized(this) {
            FirmwareStatus(state, results.size, DtcWire.ADDRESSES.size, reason, results.toList())
        }

        /** A stopped run lists every address: the ones not reached as skipped. */
        fun fillSkipped() = synchronized(this) {
            val seen = results.map { it.addr }.toSet()
            DtcWire.ADDRESSES.filter { it !in seen }.forEach { results += FirmwareAddressResult(it, DtcWire.OUTCOME_SKIPPED, null, null) }
        }
    }

    fun read(run: Run): DiagStop {
        run.link.open("firmware", DtcWire.ADDRESSES.size)
        for (addr in DtcWire.ADDRESSES) {
            val started = run.link.clock.nowMs()
            val sentBefore = run.link.sent
            val id = run.link.request(addr, DtcWire.identityRequest(addr), DtcWire.SERVICE_READ_DID, DtcWire.DID_IDENTITY)
            var outcome = outcomeOf(id)
            val f194 = udsOf(id, DtcWire.SERVICE_READ_DID)
            var f195: ByteArray? = null
            if (id is DtcMatch.Answer) {
                val sw = run.link.request(addr, EcuFirmwareWire.softwareRequest(addr), DtcWire.SERVICE_READ_DID, EcuFirmwareWire.DID_SOFTWARE)
                outcome = outcomeOf(sw)
                f195 = udsOf(sw, DtcWire.SERVICE_READ_DID)
            }
            val fw = EcuFirmwareWire.parse(f195)
            log(
                "fw addr=${hexAddr(addr)} outcome=$outcome f194=${hexOrNull(f194)} f195=${hexOrNull(f195)} " +
                    "version=${fw?.version ?: "-"} date=${fw?.date ?: "-"} requests=${run.link.sent - sentBefore} " +
                    "ms=${run.link.clock.nowMs() - started}"
            )
            run.add(FirmwareAddressResult(addr, outcome, f194, f195))
        }
        return if (run.link.frames == 0) {
            DiagStop(DtcWire.STATE_UNAVAILABLE, "no_frames")
        } else {
            DiagStop(DtcWire.STATE_DONE, "")
        }
    }

    fun close(run: Run, stop: DiagStop) {
        run.fillSkipped()
        val list = run.status(stop.state, stop.reason).results
        log(
            "fw end state=${stateName(stop.state)} reason=${stop.reason} " +
                "f194=${list.count { it.f194Uds != null }} f195=${list.count { EcuFirmwareWire.parse(it.f195Uds) != null }} " +
                "skipped=${list.count { it.outcome == DtcWire.OUTCOME_SKIPPED }}/${list.size} " +
                "sent=${run.link.sent} frames=${run.link.frames} foreign=${run.link.foreign} " +
                "ms=${run.link.clock.nowMs() - run.link.startedMs}"
        )
    }

    private fun hexOrNull(b: ByteArray?) = b?.let { hex(it, 0, it.size) } ?: "-"
}
