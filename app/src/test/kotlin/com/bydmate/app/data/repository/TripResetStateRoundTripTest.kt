package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.FakeSettingsDao
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.trips.TripResetState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Verifies that all 5 keys of TripResetState survive a get/set round-trip through
// SettingsRepository (including the new excludeStraddling / trip${n}_corr_excl key).
class TripResetStateRoundTripTest {

    private fun repo() = SettingsRepository(FakeSettingsDao(), mockk<LocalePreferences>(relaxed = true))

    @Test fun `round-trip with excludeStraddling true persists all 5 keys`() = runTest {
        val r = repo()
        val state = TripResetState(
            resetTs = 1_000_000L,
            corrKm = 12.5,
            corrKwh = 2.3,
            corrMs = 45_000L,
            excludeStraddling = true,
        )
        r.setTripResetState(1, state)
        val loaded = r.getTripResetState(1)

        assertEquals(state.resetTs, loaded.resetTs)
        assertEquals(state.corrKm, loaded.corrKm, 0.0001)
        assertEquals(state.corrKwh, loaded.corrKwh, 0.0001)
        assertEquals(state.corrMs, loaded.corrMs)
        assertTrue(loaded.excludeStraddling)
    }
}
