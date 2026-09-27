package com.bydmate.app.data.telegram

import com.bydmate.app.helper.offreport.OffReportFid
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportStatus
import com.bydmate.app.helper.offreport.OffReportState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the arming loop ([PowerOffArmer]) last learned about the daemon, read by the dump
 * ([TelegramReporter.diagnosticsLines]). A holder of its own so the reporter does not depend on
 * the armer, which depends on the reporter.
 */
@Singleton
class PowerOffArmState @Inject constructor() {
    /** Wall clock of the last arm the daemon took; 0 = nothing armed. */
    @Volatile var armedAtMs: Long = 0L

    /** `ok` (the daemon took the last arm), `outdated` (alive, but without the verb), `-` (not reached). */
    @Volatile var daemon: String = DAEMON_UNKNOWN

    /** The daemon's last power-off; null until the loop has read one. */
    @Volatile var last: OffReportOutcome? = null

    /** Registration state of the daemon's power listener per fid, as last read. */
    @Volatile var fids: List<OffReportFid> = emptyList()

    /** Takes what a status reply says about the last power-off and the listener. */
    fun note(status: OffReportStatus) {
        status.last?.let { last = it }
        fids = status.fids
    }

    fun listenerLine(): String = fids.joinToString(" ") { "${it.fid}=${it.outcome}" }.ifEmpty { "-" }

    fun lastOffLine(): String {
        val o = last ?: return "-"
        val off = if (o.powerOffMs > 0L) TelegramReportBuilder.formatDateTime(o.powerOffMs) else "-"
        val lag = if (o.sentAtMs > 0L && o.powerOffMs > 0L) "${o.sentAtMs - o.powerOffMs}ms" else "-"
        return "${OffReportState.name(o.state)} id=${o.id} off=$off attempts=${o.attempts} rc=${o.rc} sent_after=$lag"
    }

    companion object {
        const val DAEMON_OK = "ok"
        const val DAEMON_OUTDATED = "outdated"
        const val DAEMON_UNKNOWN = "-"
    }
}
