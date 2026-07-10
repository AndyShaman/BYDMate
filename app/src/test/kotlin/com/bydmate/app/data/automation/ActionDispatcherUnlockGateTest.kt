package com.bydmate.app.data.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Door-unlock speed gate: unlocking ("车门解锁") is forbidden above 30 km/h and
 * when speed is unknown (fail-closed, same policy as the frunk gate). Locking
 * ("车门上锁") is never gated.
 */
class ActionDispatcherUnlockGateTest {

    private fun gate(cmd: String, speed: Int?) = ActionDispatcher.unlockGateBlockReason(cmd, speed)

    @Test fun `unlock predicate matches canonical command`() {
        assertTrue(ActionDispatcher.isDoorUnlockCommand("车门解锁"))
    }

    @Test fun `lock command is not an unlock`() {
        assertFalse(ActionDispatcher.isDoorUnlockCommand("车门上锁"))
    }

    @Test fun `unlock allowed when parked`() {
        assertNull(gate("车门解锁", 0))
    }

    @Test fun `unlock allowed at 30 kmh boundary`() {
        assertNull(gate("车门解锁", 30))
    }

    @Test fun `unlock blocked above 30 kmh`() {
        assertNotNull(gate("车门解锁", 31))
        assertNotNull(gate("车门解锁", 90))
    }

    @Test fun `unlock blocked when speed unknown`() {
        assertNotNull(gate("车门解锁", null))
    }

    @Test fun `lock is never gated even at speed or unknown speed`() {
        assertNull(gate("车门上锁", 120))
        assertNull(gate("车门上锁", null))
    }

    @Test fun `unrelated commands pass through`() {
        assertNull(gate("车窗全开", 200))
    }
}
