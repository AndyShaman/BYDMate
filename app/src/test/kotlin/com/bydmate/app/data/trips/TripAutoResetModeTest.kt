package com.bydmate.app.data.trips

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Decision matrix for the TRIP auto-reset modes (#235) plus the settings round-trip.
class TripAutoResetModeTest {

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

    private fun charge(type: String?, socEnd: Int?) =
        ChargeEntity(id = 1, startTs = 0L, socStart = 40, socEnd = socEnd, type = type)

    private val types = listOf("AC", "DC", "ac", null)
    private val socEnds = listOf(99, 100, null)

    private fun expected(mode: TripAutoResetMode, type: String?, socEnd: Int?): Boolean = when (mode) {
        TripAutoResetMode.OFF -> false
        TripAutoResetMode.ANY -> true
        TripAutoResetMode.AC -> type == "AC" || type == "ac"
        TripAutoResetMode.DC -> type == "DC"
        TripAutoResetMode.FULL -> socEnd == 100
    }

    @Test fun `decision matrix over mode x type x socEnd`() {
        for (mode in TripAutoResetMode.entries) for (type in types) for (soc in socEnds) {
            assertEquals("mode=$mode type=$type socEnd=$soc",
                expected(mode, type, soc), mode.shouldReset(charge(type, soc)))
        }
    }

    @Test fun `FULL treats socEnd above 100 as full`() {
        assertEquals(true, TripAutoResetMode.FULL.shouldReset(charge("AC", 101)))
    }

    @Test fun `fromKey maps unknown and absent values to OFF`() {
        assertEquals(TripAutoResetMode.OFF, TripAutoResetMode.fromKey(null))
        assertEquals(TripAutoResetMode.OFF, TripAutoResetMode.fromKey(""))
        assertEquals(TripAutoResetMode.OFF, TripAutoResetMode.fromKey("bogus"))
        TripAutoResetMode.entries.forEach { assertEquals(it, TripAutoResetMode.fromKey(it.key)) }
    }

    @Test fun `settings round-trip per counter, absent and unknown default to OFF`() = runTest {
        val dao = FakeSettingsDao()
        val repo = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

        assertEquals(TripAutoResetMode.OFF, repo.getTripAutoResetMode(1))
        assertEquals(TripAutoResetMode.OFF, repo.observeTripAutoResetMode(2).first())

        repo.setTripAutoResetMode(1, TripAutoResetMode.DC)
        repo.setTripAutoResetMode(2, TripAutoResetMode.FULL)
        assertEquals("dc", dao.map["trip1_auto_reset"])
        assertEquals(TripAutoResetMode.DC, repo.getTripAutoResetMode(1))
        assertEquals(TripAutoResetMode.FULL, repo.getTripAutoResetMode(2))
        assertEquals(TripAutoResetMode.DC, repo.observeTripAutoResetMode(1).first())
        assertEquals(TripAutoResetMode.FULL, repo.observeTripAutoResetMode(2).first())

        dao.map["trip1_auto_reset"] = "weekly"
        assertEquals(TripAutoResetMode.OFF, repo.getTripAutoResetMode(1))
        assertEquals(TripAutoResetMode.OFF, repo.observeTripAutoResetMode(1).first())
    }
}
