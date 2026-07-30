package com.bydmate.app.data.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remembered window channel (#79) must not outlive the assumptions it was decided
 * under: an app update that changes the fid table (schema version) or a firmware OTA that
 * may add the percent family (Build.FINGERPRINT) forces a fresh probe. The CTRL candidate
 * timestamp lives behind the same guards, so a half-collected verdict cannot survive either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WindowChannelStorePrefsTest {

    private companion object {
        const val CANDIDATE_MS = 1_700_000_000_000L
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("window_channel_test", Context.MODE_PRIVATE)

    @Test fun `round-trips a winner`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()

        val store = WindowChannelStorePrefs(sharedPrefs)
        assertEquals("fresh store must return UNKNOWN", WindowChannel.UNKNOWN, store.winner())

        store.setWinner(WindowChannel.CTRL)

        assertEquals(
            "winner must survive across store instances",
            WindowChannel.CTRL,
            WindowChannelStorePrefs(sharedPrefs).winner(),
        )
    }

    @Test fun `schema version mismatch resets to UNKNOWN`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit()
            .putString("window_channel_winner", WindowChannel.CTRL.name)
            .putInt("window_channel_schema_version", 0)
            .commit()

        assertEquals(WindowChannel.UNKNOWN, WindowChannelStorePrefs(sharedPrefs).winner())
    }

    @Test fun `corrupt winner string resets to UNKNOWN`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()
        val store = WindowChannelStorePrefs(sharedPrefs)
        store.setWinner(WindowChannel.CTRL)
        sharedPrefs.edit().putString("window_channel_winner", "GARBAGE").commit()

        assertEquals(WindowChannel.UNKNOWN, store.winner())
    }

    @Test fun `fingerprint mismatch resets to UNKNOWN`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()
        val store = WindowChannelStorePrefs(sharedPrefs)
        store.setWinner(WindowChannel.CTRL)
        assertEquals(WindowChannel.CTRL, store.winner())

        // Simulate an OTA: the fingerprint on disk no longer matches the running build.
        sharedPrefs.edit().putString("window_channel_fp", "stale-pre-ota-fingerprint").commit()

        assertEquals(WindowChannel.UNKNOWN, store.winner())
    }

    @Test fun `candidate timestamp round-trips and is cleared by a decided winner`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()
        val store = WindowChannelStorePrefs(sharedPrefs)
        assertEquals(0L, store.ctrlCandidateAtMs())

        store.setCtrlCandidateAtMs(CANDIDATE_MS)
        assertEquals(
            "a half-collected verdict must survive a process restart",
            CANDIDATE_MS,
            WindowChannelStorePrefs(sharedPrefs).ctrlCandidateAtMs(),
        )

        store.setWinner(WindowChannel.CTRL)
        assertEquals("deciding a winner ends the pair", 0L, store.ctrlCandidateAtMs())
    }

    @Test fun `a candidate from another firmware is not counted`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()
        val store = WindowChannelStorePrefs(sharedPrefs)
        store.setCtrlCandidateAtMs(CANDIDATE_MS)

        sharedPrefs.edit().putString("window_channel_fp", "stale-pre-ota-fingerprint").commit()

        assertEquals(0L, store.ctrlCandidateAtMs())
    }

    /** Re-stamping the guards while recording a candidate must not revive a stale winner. */
    @Test fun `recording a candidate under a new fingerprint drops the old winner`() {
        val sharedPrefs = prefs()
        sharedPrefs.edit().clear().commit()
        val store = WindowChannelStorePrefs(sharedPrefs)
        store.setWinner(WindowChannel.CTRL)

        sharedPrefs.edit().putString("window_channel_fp", "stale-pre-ota-fingerprint").commit()
        store.setCtrlCandidateAtMs(CANDIDATE_MS)

        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals(CANDIDATE_MS, store.ctrlCandidateAtMs())
    }
}
