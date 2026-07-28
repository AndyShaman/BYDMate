package com.bydmate.app.data.repository

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Radio feature ships switched off: a fresh install must show no Radio tab and no dashboard
 * strip until the driver opts in.
 */
class RadioEnabledDefaultTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(setting: SettingEntity) { map[setting.key] = setting.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val dao = FakeSettingsDao()
    private val settings = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

    @Test
    fun `radio is off on a fresh install`() = runTest {
        assertFalse(settings.isRadioEnabled())
    }

    @Test
    fun `the flag round-trips both ways`() = runTest {
        settings.setRadioEnabled(true)
        assertTrue(settings.isRadioEnabled())

        settings.setRadioEnabled(false)
        assertFalse(settings.isRadioEnabled())
    }

    @Test
    fun `a garbage stored value reads as off`() = runTest {
        dao.map[SettingsRepository.KEY_RADIO_ENABLED] = "yes"
        assertFalse(settings.isRadioEnabled())
    }
}
