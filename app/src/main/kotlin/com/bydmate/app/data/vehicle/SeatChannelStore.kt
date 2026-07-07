package com.bydmate.app.data.vehicle

import android.content.SharedPreferences

/** Remembered seat write-channel for this device. */
enum class SeatChannel { UNKNOWN, PRIMARY, FALLBACK }

/** Persists which seat channel actually actuated on this head unit. */
interface SeatChannelStore {
    fun winner(): SeatChannel
    fun setWinner(channel: SeatChannel)
}

/**
 * SharedPreferences-backed store with a schema-version guard: if the stored schema
 * version differs from [SCHEMA_VERSION] (seat fids changed in an app update), the
 * remembered winner is discarded so a stale choice cannot be cemented.
 */
class SeatChannelStorePrefs(private val prefs: SharedPreferences) : SeatChannelStore {
    override fun winner(): SeatChannel {
        if (prefs.getInt(KEY_VERSION, -1) != SCHEMA_VERSION) return SeatChannel.UNKNOWN
        return runCatching { SeatChannel.valueOf(prefs.getString(KEY_WINNER, null) ?: return SeatChannel.UNKNOWN) }
            .getOrDefault(SeatChannel.UNKNOWN)
    }

    override fun setWinner(channel: SeatChannel) {
        prefs.edit().putInt(KEY_VERSION, SCHEMA_VERSION).putString(KEY_WINNER, channel.name).apply()
    }

    companion object {
        // Bump when seat fids / channel mapping change, to auto-reset stored winners.
        const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "seat_channel_schema_version"
        private const val KEY_WINNER = "seat_channel_winner"
    }
}
