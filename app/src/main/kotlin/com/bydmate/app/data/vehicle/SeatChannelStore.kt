package com.bydmate.app.data.vehicle

import android.content.SharedPreferences
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/** Remembered seat write-channel for this device. */
enum class SeatChannel { UNKNOWN, PRIMARY, FALLBACK }

/**
 * Persists which seat channel actually actuated on this head unit, and owns the
 * single fallback re-probe budget that [AdaptiveSeatChannel] spends (see [claimReprobe]).
 * The budget lives here, in the singleton store, so the manual "reset seat channel"
 * action re-arms it — a flag private to the channel object would stay spent until the
 * process restarts and the reset would silently do nothing.
 */
interface SeatChannelStore {
    fun winner(): SeatChannel

    /** Records the winner. Passing [SeatChannel.UNKNOWN] means a manual reset and re-arms
     *  the fallback re-probe; the internal transitions only ever store PRIMARY/FALLBACK. */
    fun setWinner(channel: SeatChannel)

    /** True once the single fallback re-probe has been spent. */
    fun reprobeExhausted(): Boolean

    /** Atomically takes the fallback re-probe: true iff it was still unspent (and marks it
     *  spent), false if it is already gone. */
    fun claimReprobe(): Boolean
}

/**
 * SharedPreferences-backed store with a schema-version guard: if the stored schema
 * version differs from [SCHEMA_VERSION] (seat fids changed in an app update), the
 * remembered winner is discarded so a stale choice cannot be cemented. Same guard
 * applies to Build.FINGERPRINT (mirrors BatchReadGate): a firmware OTA can change
 * which seat channel actually actuates, so a fingerprint change also forces a
 * fresh probe instead of staying cemented on a pre-OTA choice (fixes #70).
 *
 * The re-probe budget is deliberately NOT persisted: "one fallback probe per process"
 * is the semantics we want, and this store is a singleton, so an in-memory flag is the
 * whole implementation.
 */
class SeatChannelStorePrefs(private val prefs: SharedPreferences) : SeatChannelStore {
    private val reprobeSpent = AtomicBoolean(false)

    override fun winner(): SeatChannel {
        if (prefs.getInt(KEY_VERSION, -1) != SCHEMA_VERSION) return SeatChannel.UNKNOWN
        if (prefs.getString(KEY_FP, "") != currentFingerprint()) return SeatChannel.UNKNOWN
        return runCatching { SeatChannel.valueOf(prefs.getString(KEY_WINNER, null) ?: return SeatChannel.UNKNOWN) }
            .getOrDefault(SeatChannel.UNKNOWN)
    }

    override fun setWinner(channel: SeatChannel) {
        if (channel == SeatChannel.UNKNOWN) reprobeSpent.set(false)
        prefs.edit().putInt(KEY_VERSION, SCHEMA_VERSION).putString(KEY_FP, currentFingerprint())
            .putString(KEY_WINNER, channel.name).apply()
    }

    override fun reprobeExhausted(): Boolean = reprobeSpent.get()

    override fun claimReprobe(): Boolean = reprobeSpent.compareAndSet(false, true)

    // Build.FINGERPRINT is a non-null platform field on a real device, but resolves to null
    // under a plain JVM unit test (no Robolectric shadow) — normalize defensively, same as
    // BatchReadGate.currentFingerprint().
    private fun currentFingerprint(): String = Build.FINGERPRINT ?: ""

    companion object {
        // Bump when seat fids / channel mapping change, to auto-reset stored winners.
        const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "seat_channel_schema_version"
        private const val KEY_WINNER = "seat_channel_winner"
        private const val KEY_FP = "seat_channel_fp"
    }
}
