package com.bydmate.app.data.charging

import com.bydmate.app.data.repository.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent ring buffer of runCatchUp decisions, surfaced in the diagnostic
 * dump. Logcat on DiLink rotates out the service-startup window within
 * minutes (observed 2026-06-11: buffer held ~4.5 min), so by the time a user
 * shares a dump the catch-up lines that explain a lost charge are gone.
 *
 * Consecutive identical payloads collapse into one line with an `(xN)`
 * counter and the latest timestamp — a connected gun makes the tick retry
 * emit STILL_CHARGING every ~30 s, which would otherwise flush the whole
 * ring within half an hour.
 */
@Singleton
class CatchUpJournal @Inject constructor(
    private val settings: SettingsRepository
) {
    companion object {
        const val MAX_ENTRIES = 50
        private const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
        // "yyyy-MM-dd HH:mm:ss " prefix length — payload starts here.
        private const val TS_PREFIX_LEN = 20
        private val REPEAT_SUFFIX = Regex(""" \(x(\d+)\)$""")
    }

    suspend fun append(payload: String, now: Long = System.currentTimeMillis()) {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date(now))
        val lines = read().lines().filter { it.isNotBlank() }.toMutableList()

        val last = lines.lastOrNull()
        if (last != null && last.length > TS_PREFIX_LEN) {
            val match = REPEAT_SUFFIX.find(last)
            val lastPayload =
                (if (match != null) last.removeRange(match.range) else last).substring(TS_PREFIX_LEN)
            if (lastPayload == payload) {
                val count = (match?.groupValues?.get(1)?.toIntOrNull() ?: 1) + 1
                lines[lines.size - 1] = "$ts $payload (x$count)"
                settings.setString(SettingsRepository.KEY_CATCHUP_JOURNAL, lines.joinToString("\n"))
                return
            }
        }

        lines.add("$ts $payload")
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        settings.setString(SettingsRepository.KEY_CATCHUP_JOURNAL, lines.joinToString("\n"))
    }

    suspend fun read(): String =
        settings.getString(SettingsRepository.KEY_CATCHUP_JOURNAL, "")
}
