package com.bydmate.app.split

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent ring of split-session lifecycle transitions, surfaced in the diagnostic dump.
 *
 * The DiLink logcat ring (256 KiB) rotates the split window out within minutes, so a field
 * report about panes that never came up after a reboot, or about a picker that opens on
 * every tap, arrives with nothing left to read. Same shape as
 * [com.bydmate.app.data.charging.CatchUpJournal]: timestamped lines in SharedPreferences,
 * oldest dropped first.
 *
 * Only transitions and decisions are recorded — never a plain watchdog tick.
 */
interface SplitJournal {
    fun append(payload: String)
    fun read(): List<String>
}

/** No-op default for call sites (and tests) with no journal wired. */
object NoSplitJournal : SplitJournal {
    override fun append(payload: String) {}
    override fun read(): List<String> = emptyList()
}

/**
 * Ring-buffer implementation over a plain string [Store].
 *
 * Consecutive identical payloads collapse into one line with an `(xN)` counter and the
 * latest timestamp: a retrying watchdog decision (e.g. a cluster calibration that keeps
 * failing) fires every tick and would otherwise flush the whole ring within a minute.
 */
class SplitJournalImpl(
    private val store: Store,
    private val now: () -> Long = System::currentTimeMillis,
) : SplitJournal {

    /** Where the ring lives; a seam so the ring logic stays testable without Android. */
    interface Store {
        fun read(): String
        fun write(value: String)
    }

    @Synchronized
    override fun append(payload: String) {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date(now()))
        val lines = read().toMutableList()

        val last = lines.lastOrNull()
        if (last != null && last.length > TS_PREFIX_LEN) {
            val match = REPEAT_SUFFIX.find(last)
            val lastPayload =
                (if (match != null) last.removeRange(match.range) else last).substring(TS_PREFIX_LEN)
            if (lastPayload == payload) {
                val count = (match?.groupValues?.get(1)?.toIntOrNull() ?: 1) + 1
                lines[lines.size - 1] = "$ts $payload (x$count)"
                store.write(lines.joinToString("\n"))
                return
            }
        }

        lines.add("$ts $payload")
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        store.write(lines.joinToString("\n"))
    }

    @Synchronized
    override fun read(): List<String> = store.read().lines().filter { it.isNotBlank() }

    companion object {
        const val MAX_ENTRIES = 40
        private const val TS_FORMAT = "yyyy-MM-dd HH:mm:ss"
        // "yyyy-MM-dd HH:mm:ss " prefix length — payload starts here.
        private const val TS_PREFIX_LEN = 20
        private val REPEAT_SUFFIX = Regex(""" \(x(\d+)\)$""")
    }
}

/** Production store: the split settings prefs file. */
class PrefsSplitJournalStore(context: Context) : SplitJournalImpl.Store {
    private val prefs =
        context.getSharedPreferences(SplitPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String = prefs.getString(KEY_JOURNAL, "").orEmpty()

    override fun write(value: String) {
        prefs.edit().putString(KEY_JOURNAL, value).apply()
    }

    private companion object {
        const val KEY_JOURNAL = "journal"
    }
}
