package com.bydmate.app.data.vehicle

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent ring of the last [MAX_ENTRIES] seat command attempts, printed in the
 * diagnostic dump. The seat reports (#74/#98/#109) arrive hours after the attempt and
 * DiLink's logcat buffer holds only minutes, so what the car answered has to survive on
 * disk to be readable at all.
 *
 * Two lines per attempt, written next to each other:
 *   W = the raw write — action, channel address, autoservice status, classification
 *   A = the actuation verdict — group, level, channel, readback result
 * Together they carry every field a field diagnosis needs; splitting them keeps the write
 * address next to the status that address returned.
 */
class SeatCommandJournal(private val prefs: SharedPreferences) {

    fun appendWrite(action: String, dev: Int, fid: Int, value: Int, status: Int?, outcome: WriteOutcome) {
        append(
            "W $action ch=${channelOf(action)} dev=$dev fid=$fid val=$value " +
                "status=${status ?: "null"} outcome=$outcome"
        )
    }

    /** [readback] is the rendered verify() result, or "n/a" when no readback was attempted. */
    fun appendActuation(
        group: SeatGroup, level: Int, channel: SeatChannel, outcome: WriteOutcome, readback: String,
    ) {
        append("A $group lvl=$level ch=${channel.name.lowercase()} outcome=$outcome rb=$readback")
    }

    /** Ring content, oldest first. */
    fun lines(): List<String> =
        prefs.getString(KEY_JOURNAL, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun append(payload: String) = synchronized(this) {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date())
        val lines = (lines() + "$ts $payload").takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY_JOURNAL, lines.joinToString("\n")).apply()
    }

    private fun channelOf(action: String) = if (action.endsWith("_fallback")) "fallback" else "primary"

    companion object {
        const val PREFS_NAME = "seat_journal"
        const val MAX_ENTRIES = 30
        private const val KEY_JOURNAL = "seat_command_journal"
        private const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }
}
