package com.bydmate.app.data.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the speed-gate predicates and speed-gate reason helper.
 *
 * After the wave-O split:
 *   - isWindowOpenCommand covers 车窗/主驾/副驾/后左/后右 only, gated at >120 km/h.
 *   - isSunroofOpenCommand covers 天窗 only, gated at >80 km/h.
 *   - 遮阳帘 (sunshade) is interior and ungated -- neither predicate matches it.
 *
 * Regression: "打开N" sets a window/sunroof to N%. N==0 is a CLOSE, which is safe
 * at speed and must NOT be blocked. The earlier predicate matched the substring
 * "打开" without parsing N, so 主驾打开0 / 后左打开0 etc. were wrongly blocked.
 * Genuine opens (打开>0, 全开, 半开, 通风) must still be reported as open.
 */
class ActionDispatcherWindowBlockTest {

    private fun isOpen(cmd: String) = ActionDispatcher.isWindowOpenCommand(cmd)
    private fun isSunroofOpen(cmd: String) = ActionDispatcher.isSunroofOpenCommand(cmd)
    private fun speedGate(cmd: String, speed: Int?) = ActionDispatcher.speedGateBlockReason(cmd, speed)

    // ── isWindowOpenCommand: close-to-0 commands are NOT opens ───────────────

    @Test fun `driver window to 0 percent is a close not an open`() {
        assertFalse(isOpen("主驾打开0"))
    }

    @Test fun `passenger window to 0 percent is a close`() {
        assertFalse(isOpen("副驾打开0"))
    }

    @Test fun `rear-left window to 0 percent is a close`() {
        assertFalse(isOpen("后左打开0"))
    }

    @Test fun `rear-right window to 0 percent is a close`() {
        assertFalse(isOpen("后右打开0"))
    }

    @Test fun `sunroof to 0 percent is not a window open`() {
        // 天窗 is no longer in isWindowOpenCommand subjects — checked via isSunroofOpenCommand
        assertFalse(isOpen("天窗打开0"))
    }

    // ── isWindowOpenCommand: genuine opens still classified as open ───────────

    @Test fun `driver window to 100 percent is an open`() {
        assertTrue(isOpen("主驾打开100"))
    }

    @Test fun `all windows full open is an open`() {
        assertTrue(isOpen("车窗全开"))
    }

    @Test fun `all windows half open is an open`() {
        assertTrue(isOpen("车窗半开"))
    }

    @Test fun `rear windows full open is an open`() {
        assertTrue(isOpen("后排车窗全开"))
    }

    @Test fun `windows vent is an open`() {
        assertTrue(isOpen("车窗通风"))
    }

    // ── isWindowOpenCommand: 天窗 and 遮阳帘 are NOT in window subjects ─────────

    @Test fun `sunroof vent is not a window open`() {
        assertFalse(isOpen("天窗通风"))
    }

    @Test fun `sunshade open is not a window open`() {
        assertFalse(isOpen("遮阳帘打开"))
    }

    // ── isWindowOpenCommand: explicit close words ─────────────────────────────

    @Test fun `all windows close is not an open`() {
        assertFalse(isOpen("车窗关闭"))
    }

    @Test fun `rear windows close is not an open`() {
        assertFalse(isOpen("后排车窗关闭"))
    }

    // ── isWindowOpenCommand: non-window commands ──────────────────────────────

    @Test fun `climate command is not a window open`() {
        assertFalse(isOpen("自动空调"))
    }

    // ── isWindowOpenCommand: seat heat/vent shares subject+keyword but is NOT a window ──

    @Test fun `driver seat vent is not a window open`() {
        assertFalse(isOpen("主驾座椅通风1档"))
    }

    @Test fun `passenger seat vent level 5 is not a window open`() {
        assertFalse(isOpen("副驾座椅通风5档"))
    }

    @Test fun `driver seat heat is not a window open`() {
        assertFalse(isOpen("主驾座椅加热3档"))
    }

    // ── isSunroofOpenCommand ──────────────────────────────────────────────────

    @Test fun `sunroof full open is a sunroof open`() {
        assertTrue(isSunroofOpen("天窗全开"))
    }

    @Test fun `sunroof vent is a sunroof open`() {
        assertTrue(isSunroofOpen("天窗通风"))
    }

    @Test fun `sunroof comfort open is a sunroof open`() {
        assertTrue(isSunroofOpen("天窗舒适打开"))
    }

    @Test fun `sunroof to 50 percent is a sunroof open`() {
        assertTrue(isSunroofOpen("天窗打开50"))
    }

    @Test fun `sunroof to 0 percent is not a sunroof open`() {
        assertFalse(isSunroofOpen("天窗打开0"))
    }

    @Test fun `sunroof stop is not a sunroof open`() {
        assertFalse(isSunroofOpen("天窗停止"))
    }

    @Test fun `sunroof close is not a sunroof open`() {
        assertFalse(isSunroofOpen("天窗关闭"))
    }

    @Test fun `sunshade is not a sunroof open`() {
        assertFalse(isSunroofOpen("遮阳帘打开"))
    }

    @Test fun `side window full open is not a sunroof open`() {
        assertFalse(isSunroofOpen("车窗全开"))
    }

    // ── isSunshadeOpenCommand (early-fire guard only — never speed-gated) ─────

    @Test fun `sunshade open is a sunshade open`() {
        assertTrue(ActionDispatcher.isSunshadeOpenCommand("遮阳帘打开"))
    }

    @Test fun `sunshade close is not a sunshade open`() {
        assertFalse(ActionDispatcher.isSunshadeOpenCommand("遮阳帘关闭"))
    }

    @Test fun `sunroof open is not a sunshade open`() {
        assertFalse(ActionDispatcher.isSunshadeOpenCommand("天窗打开100"))
    }

    @Test fun `side window open is not a sunshade open`() {
        assertFalse(ActionDispatcher.isSunshadeOpenCommand("车窗全开"))
    }

    // ── speedGateBlockReason: sunshade always null (ungated interior shade) ───

    @Test fun `sunshade open at high speed returns null`() {
        assertNull(speedGate("遮阳帘打开", 200))
    }

    @Test fun `sunshade open at unknown speed returns null`() {
        assertNull(speedGate("遮阳帘打开", null))
    }

    // ── speedGateBlockReason: sunroof gate at 80 km/h ────────────────────────

    @Test fun `sunroof open at 81 returns block reason`() {
        assertNotNull(speedGate("天窗全开", 81))
    }

    @Test fun `sunroof open at 80 returns null`() {
        assertNull(speedGate("天窗全开", 80))
    }

    @Test fun `sunroof open at 79 returns null`() {
        assertNull(speedGate("天窗全开", 79))
    }

    @Test fun `sunroof open at unknown speed returns block reason`() {
        assertNotNull(speedGate("天窗全开", null))
    }

    // ── speedGateBlockReason: window gate at 120 km/h ────────────────────────

    @Test fun `window open at 100 returns null`() {
        assertNull(speedGate("车窗全开", 100))
    }

    @Test fun `window open at 120 returns null`() {
        assertNull(speedGate("车窗全开", 120))
    }

    @Test fun `window open at 121 returns block reason`() {
        assertNotNull(speedGate("车窗全开", 121))
    }

    @Test fun `window open at unknown speed returns block reason`() {
        assertNotNull(speedGate("车窗全开", null))
    }

    // ── speedGateBlockReason: close commands and non-apertures are never gated ─

    @Test fun `sunroof close at high speed returns null`() {
        assertNull(speedGate("天窗关闭", 200))
    }

    @Test fun `window close at high speed returns null`() {
        assertNull(speedGate("车窗关闭", 200))
    }

    @Test fun `seat vent at high speed returns null`() {
        assertNull(speedGate("主驾座椅通风1档", 200))
    }
}
