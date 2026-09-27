package com.bydmate.app.data.telegram

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.offreport.OffReportFid
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the arming loop ([PowerOffArmer]) last learned about the daemon, read by the dump
 * ([TelegramReporter.diagnosticsLines]). A holder of its own so the reporter does not depend on
 * the armer, which depends on the reporter. Delivery outcomes and the pending count are the
 * daemon's: [daemonStatus] asks it.
 */
@Singleton
class PowerOffArmState @Inject constructor(private val helper: HelperClient) {
    /** Wall clock of the last arm the daemon took; 0 = nothing armed. */
    @Volatile var armedAtMs: Long = 0L

    /** `ok` (the daemon took the last arm), `outdated` (alive, but without the verb), `-` (not reached). */
    @Volatile var daemon: String = DAEMON_UNKNOWN

    /** The daemon's pending count, last delivery outcome and listener; null when it does not answer. */
    suspend fun daemonStatus(): OffReportStatus? = helper.offReportStatus("")

    companion object {
        const val DAEMON_OK = "ok"
        const val DAEMON_OUTDATED = "outdated"
        const val DAEMON_UNKNOWN = "-"

        fun lastOffLine(o: OffReportOutcome?): String {
            if (o == null) return "-"
            val off = if (o.powerOffMs > 0L) TelegramReportBuilder.formatDateTime(o.powerOffMs) else "-"
            val lag = if (o.sentAtMs > 0L && o.powerOffMs > 0L) "${o.sentAtMs - o.powerOffMs}ms" else "-"
            return "${OffReportState.name(o.state)} id=${o.id} off=$off attempts=${o.attempts} rc=${o.rc} sent_after=$lag"
        }

        fun listenerLine(fids: List<OffReportFid>): String =
            fids.joinToString(" ") { "${it.fid}=${it.outcome}" }.ifEmpty { "-" }
    }
}
