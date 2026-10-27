package com.bydmate.app.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPartsRecycledFlagTest {

    @Test fun `energy_ids_recycled travels with the trip tables`() {
        assertTrue("energy_ids_recycled" in BackupParts.TABLES_SETTINGS_KEYS)
        val (tablesFilter, tablesArgs) = BackupParts.settingsKeyFilter(BackupPart.TABLES)
        assertTrue(tablesFilter.startsWith("key IN"))
        assertTrue("energy_ids_recycled" in tablesArgs)
        val (settingsFilter, settingsArgs) = BackupParts.settingsKeyFilter(BackupPart.SETTINGS)
        assertTrue(settingsFilter.startsWith("key NOT IN"))
        assertTrue("energy_ids_recycled" in settingsArgs)
        assertFalse(BackupParts.settingsKeyFilter(BackupPart.KEYS).second.contains("energy_ids_recycled"))
    }
}
