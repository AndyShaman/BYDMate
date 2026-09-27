package com.bydmate.app.ui.components

import com.bydmate.app.R
import com.bydmate.app.data.autoservice.AdbVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbDialogPrimaryActionTest {

    @Test
    fun `off after reboot on Android 10 offers Check, never enable restore`() {
        assertEquals(
            AdbDialogAction.CHECK,
            adbDialogPrimaryAction(AdbVerdict.OFF_AFTER_REBOOT, restoreEnabled = false, restoreSupported = false),
        )
        assertEquals(
            AdbDialogAction.CHECK,
            adbDialogPrimaryAction(AdbVerdict.OFF_AFTER_REBOOT, restoreEnabled = true, restoreSupported = false),
        )
    }

    @Test
    fun `off after reboot on Android 11 offers enable restore while it is off`() {
        assertEquals(
            AdbDialogAction.ENABLE_RESTORE,
            adbDialogPrimaryAction(AdbVerdict.OFF_AFTER_REBOOT, restoreEnabled = false, restoreSupported = true),
        )
        assertEquals(
            AdbDialogAction.CHECK,
            adbDialogPrimaryAction(AdbVerdict.OFF_AFTER_REBOOT, restoreEnabled = true, restoreSupported = true),
        )
    }

    @Test
    fun `other verdicts keep their action`() {
        assertEquals(AdbDialogAction.CHECK, adbDialogPrimaryAction(AdbVerdict.NOT_ENABLED, false, false))
        assertEquals(AdbDialogAction.CHECK, adbDialogPrimaryAction(AdbVerdict.NO_ACCESS, false, true))
        assertEquals(AdbDialogAction.OPEN_DIAGNOSTICS, adbDialogPrimaryAction(AdbVerdict.HELPER_DOWN, false, true))
        assertNull(adbDialogPrimaryAction(AdbVerdict.OK, false, true))
    }

    @Test
    fun `off after reboot body warns Android 10 users instead of promising an automatic restore`() {
        assertEquals(
            R.string.adb_dialog_off_after_reboot_body_unsupported,
            adbDialogOffAfterRebootBody(restoreSupported = false),
        )
        assertEquals(
            R.string.adb_dialog_off_after_reboot_body,
            adbDialogOffAfterRebootBody(restoreSupported = true),
        )
    }
}
