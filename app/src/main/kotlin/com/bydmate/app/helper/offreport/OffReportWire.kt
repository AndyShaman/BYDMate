package com.bydmate.app.helper.offreport

import android.os.Parcel

/**
 * Wire types of the power-off Telegram report (3.19, phase B), shared by the daemon (which holds
 * the armed report and sends it when the car is switched off) and the app (which arms it and asks
 * for the outcome on its next start). Kept out of both so the marshalling is tested on its own.
 *
 * Every reader checks what is left in the parcel and the length of each string: a truncated or
 * oversized parcel reads as null (refused), never as a half-filled value.
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

/** Registration state of one power fid in the daemon: `OK`, `sent`, `pending` or the error. */
data class OffReportFid(val dev: Int, val fid: Int, val outcome: String)

/**
 * TX_OFFREPORT_STATUS reply. [queried] is the id the app asked about; [armedId] the report held
 * right now ("" = none) with its wall-clock arm time; [listening] how many power fids the daemon's
 * listener holds (-1 = not registered yet) and [fids] each one's state; [last] the latest power-off
 * the daemon handled, whatever its id.
 */
data class OffReportStatus(
    val queried: OffReportOutcome,
    val armedId: String,
    val armedAtMs: Long,
    val listening: Int,
    val last: OffReportOutcome?,
    val fids: List<OffReportFid> = emptyList(),
)

const val OFF_REPORT_NO_RC = "-"

/** Wire bounds: a report id is 8 chars, a bot token under 50, a Telegram message at most 4096. */
internal const val OFF_REPORT_MAX_ID = 64
internal const val OFF_REPORT_MAX_TOKEN = 256
internal const val OFF_REPORT_MAX_TEXT = 8192
internal const val OFF_REPORT_MAX_RC = 128
internal const val OFF_REPORT_MAX_FIDS = 8

private const val INT_BYTES = 4
private const val LONG_BYTES = 8

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

/** Null when a field is missing, empty, oversized or the parcel ends early. */
internal fun readOffReportArm(p: Parcel): ArmRequest? {
    val id = p.boundedString(OFF_REPORT_MAX_ID)?.takeIf { it.isNotEmpty() } ?: return null
    val token = p.boundedString(OFF_REPORT_MAX_TOKEN)?.takeIf { it.isNotEmpty() } ?: return null
    if (p.dataAvail() < LONG_BYTES) return null
    val chatId = p.readLong()
    val text = p.boundedString(OFF_REPORT_MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: return null
    return ArmRequest(id, token, chatId, text)
}

/**
 * TX_OFFREPORT_STATUS reply after the leading status int:
 * [outcome queried, String armedId, long armedAtMs, int listening, int hasLast, (outcome last)?,
 *  int fidCount, fidCount × (int dev, int fid, String outcome)]
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
    val fids = status.fids.take(OFF_REPORT_MAX_FIDS)
    p.writeInt(fids.size)
    fids.forEach {
        p.writeInt(it.dev)
        p.writeInt(it.fid)
        p.writeString(it.outcome.take(OFF_REPORT_MAX_RC))
    }
}

/** Null on a truncated or oversized reply. */
internal fun readOffReportStatus(p: Parcel): OffReportStatus? {
    val queried = readOutcome(p) ?: return null
    val armedId = p.boundedString(OFF_REPORT_MAX_ID) ?: return null
    if (p.dataAvail() < LONG_BYTES + 2 * INT_BYTES) return null
    val armedAtMs = p.readLong()
    val listening = p.readInt()
    val last = when (p.readInt()) {
        0 -> null
        1 -> readOutcome(p) ?: return null
        else -> return null
    }
    if (p.dataAvail() < INT_BYTES) return null
    val count = p.readInt()
    if (count !in 0..OFF_REPORT_MAX_FIDS) return null
    val fids = ArrayList<OffReportFid>(count)
    repeat(count) {
        if (p.dataAvail() < 2 * INT_BYTES) return null
        val dev = p.readInt()
        val fid = p.readInt()
        val outcome = p.boundedString(OFF_REPORT_MAX_RC) ?: return null
        fids += OffReportFid(dev, fid, outcome)
    }
    return OffReportStatus(queried, armedId, armedAtMs, listening, last, fids)
}

private fun writeOutcome(p: Parcel, o: OffReportOutcome) {
    p.writeString(o.id)
    p.writeInt(o.state)
    p.writeLong(o.powerOffMs)
    p.writeLong(o.sentAtMs)
    p.writeInt(o.attempts)
    p.writeString(o.rc.take(OFF_REPORT_MAX_RC))
}

private fun readOutcome(p: Parcel): OffReportOutcome? {
    val id = p.boundedString(OFF_REPORT_MAX_ID) ?: return null
    if (p.dataAvail() < 2 * INT_BYTES + 2 * LONG_BYTES) return null
    val state = p.readInt()
    val powerOffMs = p.readLong()
    val sentAtMs = p.readLong()
    val attempts = p.readInt()
    val rc = p.boundedString(OFF_REPORT_MAX_RC) ?: return null
    return OffReportOutcome(id, state, powerOffMs, sentAtMs, attempts, rc)
}

/** A string of at most [max] chars; null when the parcel ends early, holds null or a longer one. */
private fun Parcel.boundedString(max: Int): String? {
    if (dataAvail() < INT_BYTES) return null
    return readString()?.takeIf { it.length <= max }
}
