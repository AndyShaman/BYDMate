package com.bydmate.app.data.backup

import com.bydmate.app.data.repository.SettingsRepository
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPartsBmsCounterTest {

    // The remembered BMS counter belongs to this car: another car's archive must not bring it in,
    // and restoring an older archive must not wipe it.
    @Test fun `the remembered BMS counter is live state, not a setting`() {
        assertTrue(SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST in BackupParts.RUNTIME_SETTINGS_KEYS)
        val (filter, args) = BackupParts.settingsKeyFilter(BackupPart.SETTINGS)
        assertTrue(filter.startsWith("key NOT IN"))
        assertTrue(SettingsRepository.KEY_BMS_LIFETIME_KWH_LAST in args)
    }
}
