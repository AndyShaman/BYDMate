package com.bydmate.app.data.vehicle

import android.content.SharedPreferences
import android.os.Build

/** Remembered window write-channel generation for this head unit. */
enum class WindowChannel { UNKNOWN, PERCENT, CTRL }

/** Persists which window channel this firmware generation actually exposes. */
interface WindowChannelStore {
    fun winner(): WindowChannel
    fun setWinner(channel: WindowChannel)

    /**
     * When the first probe saw the "no percent family" signature, in wall-clock millis
     * (0 = no pending candidate). CTRL is only fixed by a SECOND such probe far enough
     * away in time, so neither a single cold-start link error nor a burst of them inside
     * one boot can cement the wrong channel. Reset to 0 on any other probe outcome.
     */
    fun ctrlCandidateAtMs(): Long
    fun setCtrlCandidateAtMs(ts: Long)
}

/**
 * SharedPreferences-backed store with the same guards as [SeatChannelStorePrefs]:
 * a schema-version bump (window fids changed in an app update) or a Build.FINGERPRINT
 * change (firmware OTA may add the percent family) discards the remembered channel so
 * a stale choice cannot be cemented. The candidate streak lives behind the same guards.
 */
class WindowChannelStorePrefs(private val prefs: SharedPreferences) : WindowChannelStore {
    override fun winner(): WindowChannel {
        if (!guardsPass()) return WindowChannel.UNKNOWN
        return runCatching { WindowChannel.valueOf(prefs.getString(KEY_WINNER, null) ?: return WindowChannel.UNKNOWN) }
            .getOrDefault(WindowChannel.UNKNOWN)
    }

    override fun setWinner(channel: WindowChannel) {
        prefs.edit().putInt(KEY_VERSION, SCHEMA_VERSION).putString(KEY_FP, currentFingerprint())
            .putString(KEY_WINNER, channel.name).putLong(KEY_CANDIDATE_TS, 0L).apply()
    }

    override fun ctrlCandidateAtMs(): Long = if (guardsPass()) prefs.getLong(KEY_CANDIDATE_TS, 0L) else 0L

    override fun setCtrlCandidateAtMs(ts: Long) {
        val stale = !guardsPass()
        val edit = prefs.edit().putInt(KEY_VERSION, SCHEMA_VERSION).putString(KEY_FP, currentFingerprint())
            .putLong(KEY_CANDIDATE_TS, ts)
        // Stamping the guards here must not resurrect a winner remembered under the old
        // schema/fingerprint, which winner() would start trusting again.
        if (stale) edit.remove(KEY_WINNER)
        edit.apply()
    }

    private fun guardsPass(): Boolean =
        prefs.getInt(KEY_VERSION, -1) == SCHEMA_VERSION && prefs.getString(KEY_FP, "") == currentFingerprint()

    // Build.FINGERPRINT is non-null on a device but null under a plain JVM unit test —
    // normalize defensively, same as SeatChannelStorePrefs.currentFingerprint().
    private fun currentFingerprint(): String = Build.FINGERPRINT ?: ""

    companion object {
        // Bump when the window fids / channel mapping change, to auto-reset stored winners.
        const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "window_channel_schema_version"
        private const val KEY_WINNER = "window_channel_winner"
        private const val KEY_FP = "window_channel_fp"
        private const val KEY_CANDIDATE_TS = "window_channel_ctrl_candidate_ts"
    }
}
