package com.bydmate.app.data.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SeatChannelStorePrefsTest {

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("seat_channel_test", Context.MODE_PRIVATE)

    // ── Guard T6-1: round-trip winner persists across store instances ─────────
    @Test fun `round-trips a winner`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()

        val store1 = SeatChannelStorePrefs(sharedPrefs)
        assertEquals("fresh store must return UNKNOWN", SeatChannel.UNKNOWN, store1.winner())

        store1.setWinner(SeatChannel.PRIMARY)

        // Construct a second instance over the same prefs to verify persistence
        val store2 = SeatChannelStorePrefs(sharedPrefs)
        assertEquals("winner must survive across store instances", SeatChannel.PRIMARY, store2.winner())
    }

    // ── Guard T6-2: schema version mismatch resets winner to UNKNOWN ──────────
    @Test fun `schema version mismatch resets to UNKNOWN`() {
        val sharedPrefs = prefs()
        // Pre-seed a stale schema version (0 != SCHEMA_VERSION 1) with a valid winner name
        sharedPrefs.edit()
            .putString("seat_channel_winner", SeatChannel.PRIMARY.name)
            .putInt("seat_channel_schema_version", 0)
            .commit()

        val store = SeatChannelStorePrefs(sharedPrefs)
        assertEquals(
            "stale schema version must reset winner to UNKNOWN",
            SeatChannel.UNKNOWN,
            store.winner(),
        )
    }

    // ── Guard T6-3: corrupt winner string returns UNKNOWN via runCatching ─────
    // SeatChannelStorePrefs.winner() wraps SeatChannel.valueOf in runCatching and
    // returns .getOrDefault(UNKNOWN) — so an unparseable string maps to UNKNOWN.
    @Test fun `corrupt winner string resets to UNKNOWN`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit()
            .putString("seat_channel_winner", "GARBAGE")
            .putInt("seat_channel_schema_version", SeatChannelStorePrefs.SCHEMA_VERSION)
            .commit()

        val store = SeatChannelStorePrefs(sharedPrefs)
        assertEquals(
            "corrupt winner string must map to UNKNOWN via runCatching",
            SeatChannel.UNKNOWN,
            store.winner(),
        )
    }
}
