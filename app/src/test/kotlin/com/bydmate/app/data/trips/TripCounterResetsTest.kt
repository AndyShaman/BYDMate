package com.bydmate.app.data.trips

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The shared reset owner used by both the dashboard long-press and the service auto-reset.
class TripCounterResetsTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val settings = SettingsRepository(FakeSettingsDao(), mockk<LocalePreferences>(relaxed = true))

    @Test fun `reset with no active session sets excludeStraddling true and zero corrections`() = runTest {
        val resets = TripCounterResets(settings)
        // TrackingService.sessionStartedAt is null in tests (no running service): not continuous.
        resets.reset(1, now = 5_000L)

        val loaded = settings.getTripResetState(1)
        assertEquals(5_000L, loaded.resetTs)
        assertTrue(loaded.excludeStraddling)
        assertEquals(0.0, loaded.corrKm, 0.0001)
        assertEquals(0.0, loaded.corrKwh, 0.0001)
        assertEquals(0L, loaded.corrMs)
    }

    @Test fun `persisted anchor equals the emitted state and only the reset counter changes`() = runTest {
        val resets = TripCounterResets(settings)
        resets.load()
        val before2 = resets.state(2).value

        resets.reset(1, now = 7_000L)

        assertEquals(settings.getTripResetState(1), resets.state(1).value)
        assertEquals(before2, resets.state(2).value)
    }

    @Test fun `load seeds from settings and does not overwrite a newer reset`() = runTest {
        settings.setTripResetState(2, TripResetState(1_000L, 3.0, 0.5, 60_000L))
        val resets = TripCounterResets(settings)
        assertNull(resets.state(1).value)

        resets.load()
        assertEquals(0L, resets.state(1).value!!.resetTs)
        assertEquals(1_000L, resets.state(2).value!!.resetTs)

        resets.reset(2, now = 9_000L)
        resets.load()
        assertEquals(9_000L, resets.state(2).value!!.resetTs)
    }

    @Test fun `resetWholeSession persists zero corrections without straddling exclusion`() = runTest {
        val resets = TripCounterResets(settings)
        resets.resetWholeSession(1, now = 4_000L)

        val loaded = settings.getTripResetState(1)
        assertEquals(TripResetState(4_000L, 0.0, 0.0, 0L, excludeStraddling = false), loaded)
        assertFalse(loaded.excludeStraddling)
        assertEquals(loaded, resets.state(1).value)
    }

    private val acCharge = ChargeEntity(id = 42L, startTs = 1_000L, type = "AC", socStart = 30, socEnd = 80)

    @Test fun `applyAfterCharge resets only the counter whose mode matches`() = runTest {
        settings.setTripAutoResetMode(1, TripAutoResetMode.ANY)
        settings.setTripAutoResetMode(2, TripAutoResetMode.OFF)
        val resets = TripCounterResets(settings)
        resets.load()
        val before2 = resets.state(2).value

        resets.applyAfterCharge(acCharge, wholeSession = true)

        assertNotEquals(0L, resets.state(1).value!!.resetTs)
        assertEquals(settings.getTripResetState(1), resets.state(1).value)
        assertEquals(before2, resets.state(2).value)
        assertEquals(before2, settings.getTripResetState(2))
    }

    @Test fun `applyAfterCharge with both modes OFF changes nothing`() = runTest {
        val resets = TripCounterResets(settings)
        resets.load()
        val before1 = resets.state(1).value
        val before2 = resets.state(2).value

        resets.applyAfterCharge(acCharge, wholeSession = true)

        assertEquals(before1, resets.state(1).value)
        assertEquals(before2, resets.state(2).value)
    }

    @Test fun `applyAfterCharge from catch-up writes the whole-session anchor`() = runTest {
        settings.setTripAutoResetMode(2, TripAutoResetMode.AC)
        val resets = TripCounterResets(settings)

        resets.applyAfterCharge(acCharge, wholeSession = true)

        val anchor = settings.getTripResetState(2)
        assertFalse(anchor.excludeStraddling)
        assertEquals(0.0, anchor.corrKm, 0.0001)
        assertEquals(0.0, anchor.corrKwh, 0.0001)
        assertEquals(0L, anchor.corrMs)
        assertEquals(anchor, resets.state(2).value)
    }

    @Test fun `applyAfterCharge from the gun edge with no live session writes the manual anchor`() = runTest {
        settings.setTripAutoResetMode(1, TripAutoResetMode.ANY)
        val resets = TripCounterResets(settings)

        // TrackingService.sessionStartedAt is null in tests: manual reset degrades to exclusion.
        resets.applyAfterCharge(acCharge, wholeSession = false)

        val anchor = settings.getTripResetState(1)
        assertTrue(anchor.excludeStraddling)
        assertEquals(0.0, anchor.corrKm, 0.0001)
        assertEquals(anchor, resets.state(1).value)
    }
}
