package com.bydmate.app.data.automation

import com.bydmate.app.data.automation.ActionDispatcher.BlockReason
import com.bydmate.app.data.vehicle.DriveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drive mode speed gate: terrain modes (snow, sand, mud, mountain, rock, smart) switch only up to
 * 15 km/h and never at unknown speed; ECO, normal and sport switch at any speed.
 */
class ActionDispatcherDriveModeGateTest {

    private fun gate(cmd: String, speed: Int?) = ActionDispatcher.driveModeGateBlockReason(cmd, speed)

    private val terrain = listOf("雪地模式", "沙地模式", "泥地模式", "山地模式", "岩石模式", "智能模式")
    private val road = listOf("ECO模式", "普通模式", "运动模式")

    @Test fun `every command maps to its mode`() {
        assertEquals(DriveMode.ECO, ActionDispatcher.driveModeOf("ECO模式"))
        assertEquals(DriveMode.SMART, ActionDispatcher.driveModeOf("智能模式"))
        assertNull(ActionDispatcher.driveModeOf("车门解锁"))
    }

    @Test fun `terrain modes pass at 15 kmh`() {
        terrain.forEach { assertNull(it, gate(it, 15)) }
        terrain.forEach { assertNull(it, gate(it, 0)) }
    }

    @Test fun `terrain modes are refused at 16 kmh`() {
        terrain.forEach { assertEquals(it, BlockReason.DriveModeSpeed(16), gate(it, 16)) }
    }

    @Test fun `terrain modes are refused at unknown speed`() {
        terrain.forEach { assertEquals(it, BlockReason.DriveModeSpeedUnknown, gate(it, null)) }
    }

    @Test fun `eco normal and sport have no speed gate`() {
        road.forEach { cmd ->
            assertNull(cmd, gate(cmd, null))
            assertNull(cmd, gate(cmd, 180))
        }
    }

    @Test fun `the full safety gate refuses terrain without telemetry`() {
        assertEquals(BlockReason.DriveModeSpeedUnknown, ActionDispatcher.safetyBlockReason("雪地模式", null))
        assertNull(ActionDispatcher.safetyBlockReason("运动模式", null))
    }

    @Test fun `other commands are untouched`() {
        assertNull(gate("车门解锁", null))
        assertNull(gate("主驾打开100", 200))
    }
}
