package com.bydmate.app.helper.offreport

import android.os.Parcel

/**
 * Wire types of the power-off Telegram report (3.19, phase B), shared by the daemon (which holds
 * the armed report and sends it when the car is switched off) and the app (which arms it and asks
 * for the outcome on its next start). Kept out of both so the marshalling is tested on its own.
 */

/** Where a report id stands in the daemon. */
object OffReportState {
    /** The daemon never saw this id, or lost it (the daemon was restarted, the head unit rebooted). */
    const val UNKNOWN = 0
    /** Armed and waiting: the car has not been switched off since. */
    const val ARMED = 1
    /** The car was switched off and the send is still retrying. */
    const val SENDING = 2
    const val SENT = 3
    /** Every attempt before the deadline failed, or Telegram refused the report for good. */
    const val FAILED = 4

    fun name(state: Int): String = when (state) {
        ARMED -> "armed"
        SENDING -> "sending"
        SENT -> "sent"
        FAILED -> "failed"
        else -> "unknown"
    }
}

/**
 * One report id as the daemon sees it. [powerOffMs] and [sentAtMs] are wall clock, 0 when they
 * did not happen; [rc] is the last attempt's result (`200`, `429`, `io:UnknownHostException`),
 * "-" before any attempt.
 */
data class OffReportOutcome(
    val id: String,
    val state: Int,
    val powerOffMs: Long = 0L,
    val sentAtMs: Long = 0L,
    val attempts: Int = 0,
    val rc: String = OFF_REPORT_NO_RC,
)

/**
 * TX_OFFREPORT_STATUS reply. [queried] is the id the app asked about; [armedId] the report held
 * right now ("" = none) with its wall-clock arm time; [listening] how many power fids the daemon's
 * listener holds (-1 = not registered yet); [last] the latest power-off the daemon handled.
 */
data class OffReportStatus(
    val queried: OffReportOutcome,
    val armedId: String,
    val armedAtMs: Long,
    val listening: Int,
    val last: OffReportOutcome?,
)

const val OFF_REPORT_NO_RC = "-"

/** TX_OFFREPORT_ARM request: [String id, String token, long chatId, String text]. */
internal fun writeOffReportArm(p: Parcel, id: String, token: String, chatId: Long, text: String) {
    p.writeString(id)
    p.writeString(token)
    p.writeLong(chatId)
    p.writeString(text)
}

/** The daemon's view of an arm request; toString never shows the token, the chat or the text. */
internal class ArmRequest(val id: String, val token: String, val chatId: Long, val text: String) {
    override fun toString(): String = "ArmRequest(id=$id, len=${text.length})"
}

/** Null when a field is missing: an arm without a token, an id or a text is refused. */
internal fun readOffReportArm(p: Parcel): ArmRequest? {
    val id = p.readString()?.takeIf { it.isNotEmpty() } ?: return null
    val token = p.readString()?.takeIf { it.isNotEmpty() } ?: return null
    val chatId = p.readLong()
    val text = p.readString()?.takeIf { it.isNotEmpty() } ?: return null
    return ArmRequest(id, token, chatId, text)
}

/**
 * TX_OFFREPORT_STATUS reply after the leading status int:
 * [outcome queried, String armedId, long armedAtMs, int listening, int hasLast, (outcome last)?]
 * where an outcome is [String id, int state, long powerOffMs, long sentAtMs, int attempts, String rc].
 */
internal fun writeOffReportStatus(p: Parcel, status: OffReportStatus) {
    writeOutcome(p, status.queried)
    p.writeString(status.armedId)
    p.writeLong(status.armedAtMs)
    p.writeInt(status.listening)
    val last = status.last
    p.writeInt(if (last != null) 1 else 0)
    if (last != null) writeOutcome(p, last)
}

internal fun readOffReportStatus(p: Parcel): OffReportStatus {
    val queried = readOutcome(p)
    val armedId = p.readString().orEmpty()
    val armedAtMs = p.readLong()
    val listening = p.readInt()
    val last = if (p.readInt() == 1) readOutcome(p) else null
    return OffReportStatus(queried, armedId, armedAtMs, listening, last)
}

private fun writeOutcome(p: Parcel, o: OffReportOutcome) {
    p.writeString(o.id)
    p.writeInt(o.state)
    p.writeLong(o.powerOffMs)
    p.writeLong(o.sentAtMs)
    p.writeInt(o.attempts)
    p.writeString(o.rc)
}

private fun readOutcome(p: Parcel): OffReportOutcome = OffReportOutcome(
    id = p.readString().orEmpty(),
    state = p.readInt(),
    powerOffMs = p.readLong(),
    sentAtMs = p.readLong(),
    attempts = p.readInt(),
    rc = p.readString() ?: OFF_REPORT_NO_RC,
)
