package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandSpecTest {
    @Test fun resolves_close_all_windows() {
        assertEquals("车窗关闭", VoiceCatalog.resolve(ActionSlot.CLOSE, DeviceSlot.WINDOW_ALL, null))
    }

    @Test fun resolves_open_driver_window_to_100() {
        assertEquals("主驾打开100", VoiceCatalog.resolve(ActionSlot.OPEN, DeviceSlot.WINDOW_DRIVER, null))
    }

    @Test fun resolves_close_driver_window_to_0() {
        assertEquals("主驾打开0", VoiceCatalog.resolve(ActionSlot.CLOSE, DeviceSlot.WINDOW_DRIVER, null))
    }

    @Test fun resolves_dynamic_temperature_within_range() {
        assertEquals("设置温度23", VoiceCatalog.resolve(ActionSlot.SET, DeviceSlot.AC_TEMP, 23))
    }

    @Test fun rejects_temperature_out_of_range() {
        assertNull(VoiceCatalog.resolve(ActionSlot.SET, DeviceSlot.AC_TEMP, 40))
    }

    @Test fun resolves_lock_and_unlock() {
        assertEquals("车门上锁", VoiceCatalog.resolve(ActionSlot.ON, DeviceSlot.LOCK, null))
        assertEquals("车门解锁", VoiceCatalog.resolve(ActionSlot.OFF, DeviceSlot.LOCK, null))
    }

    @Test fun unknown_combination_returns_null() {
        assertNull(VoiceCatalog.resolve(ActionSlot.VENT, DeviceSlot.LOCK, null))
    }

    @Test fun every_catalog_command_is_dispatchable() {
        // Each produced command must be resolvable by CommandTranslator,
        // i.e. it is a real, executable command string — no typos/orphans.
        // Mirrors VehicleApiImpl.dispatch()'s order: resolveSeat() is tried
        // first (seat heat/vent), then resolve() (everything else).
        for (spec in VoiceCatalog.ALL) {
            val sample = spec.value?.min
            val cmd = spec.command(sample)
            assertTrue(
                "command not dispatchable: $cmd",
                com.bydmate.app.data.vehicle.CommandTranslator.resolveSeat(cmd) != null ||
                    com.bydmate.app.data.vehicle.CommandTranslator.resolve(cmd).isNotEmpty()
            )
        }
    }
}
