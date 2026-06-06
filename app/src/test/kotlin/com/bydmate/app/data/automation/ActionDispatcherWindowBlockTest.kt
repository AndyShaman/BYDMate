package com.bydmate.app.data.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the >80 km/h window-open safety predicate.
 *
 * Regression: "打开N" sets a window/sunroof to N%. N==0 is a CLOSE, which is safe
 * at speed and must NOT be blocked. The earlier predicate matched the substring
 * "打开" without parsing N, so 主驾打开0 / 后左打开0 etc. were wrongly blocked.
 * Genuine opens (打开>0, 全开, 半开, 通风) must still be reported as open.
 */
class ActionDispatcherWindowBlockTest {

    private fun isOpen(cmd: String) = ActionDispatcher.isWindowOpenCommand(cmd)

    // ── close-to-0 commands are NOT opens (must not be blocked at speed) ───────
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

    @Test fun `sunroof to 0 percent is a close`() {
        assertFalse(isOpen("天窗打开0"))
    }

    // ── genuine opens still classified as open (still blocked at speed) ───────
    @Test fun `driver window to 100 percent is an open`() {
        assertTrue(isOpen("主驾打开100"))
    }

    @Test fun `sunroof to 50 percent is an open`() {
        assertTrue(isOpen("天窗打开50"))
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

    @Test fun `sunshade open (no percentage) is treated as open`() {
        assertTrue(isOpen("遮阳帘打开"))
    }

    // ── explicit close words are not opens ────────────────────────────────────
    @Test fun `all windows close is not an open`() {
        assertFalse(isOpen("车窗关闭"))
    }

    @Test fun `rear windows close is not an open`() {
        assertFalse(isOpen("后排车窗关闭"))
    }

    // ── non-window commands are never window-opens ────────────────────────────
    @Test fun `climate command is not a window open`() {
        assertFalse(isOpen("自动空调"))
    }
}
