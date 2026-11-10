package com.bydmate.app.data.automation

import com.bydmate.app.data.automation.ActionDispatcher.BlockReason
import org.junit.Assert.assertEquals
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

    private class OpenCase(val label: String, val isOpen: (String) -> Boolean, val command: String, val expected: Boolean)

    private class GateCase(val label: String, val command: String, val speed: Int?, val expected: BlockReason?)

    private val window: (String) -> Boolean = { ActionDispatcher.isWindowOpenCommand(it) }
    private val sunroof: (String) -> Boolean = { ActionDispatcher.isSunroofOpenCommand(it) }
    private val sunshade: (String) -> Boolean = { ActionDispatcher.isSunshadeOpenCommand(it) }

    private val openCases by lazy {
        listOf(
            // isWindowOpenCommand: close-to-0 commands are NOT opens
            OpenCase("driver window to 0 percent is a close not an open", window, "主驾打开0", false),
            OpenCase("passenger window to 0 percent is a close", window, "副驾打开0", false),
            OpenCase("rear-left window to 0 percent is a close", window, "后左打开0", false),
            OpenCase("rear-right window to 0 percent is a close", window, "后右打开0", false),
            // 天窗 is no longer in isWindowOpenCommand subjects — checked via isSunroofOpenCommand
            OpenCase("sunroof to 0 percent is not a window open", window, "天窗打开0", false),
            // isWindowOpenCommand: genuine opens still classified as open
            OpenCase("driver window to 100 percent is an open", window, "主驾打开100", true),
            OpenCase("all windows full open is an open", window, "车窗全开", true),
            OpenCase("all windows half open is an open", window, "车窗半开", true),
            OpenCase("rear windows full open is an open", window, "后排车窗全开", true),
            OpenCase("windows vent is an open", window, "车窗通风", true),
            OpenCase("per-window half is an open: driver", window, "主驾半开", true),
            OpenCase("per-window half is an open: passenger", window, "副驾半开", true),
            OpenCase("per-window half is an open: rear left", window, "后左半开", true),
            OpenCase("per-window half is an open: rear right", window, "后右半开", true),
            OpenCase("front pair half is an open", window, "前排车窗半开", true),
            OpenCase("front pair vent is an open", window, "前排车窗通风", true),
            OpenCase("rear pair half is an open", window, "后排车窗半开", true),
            OpenCase("rear pair vent is an open", window, "后排车窗通风", true),
            // isWindowOpenCommand: 天窗 and 遮阳帘 are NOT in window subjects
            OpenCase("sunroof vent is not a window open", window, "天窗通风", false),
            OpenCase("sunshade open is not a window open", window, "遮阳帘打开", false),
            // isWindowOpenCommand: explicit close words
            OpenCase("all windows close is not an open", window, "车窗关闭", false),
            OpenCase("rear windows close is not an open", window, "后排车窗关闭", false),
            // isWindowOpenCommand: non-window commands
            OpenCase("climate command is not a window open", window, "自动空调", false),
            // isWindowOpenCommand: seat heat/vent shares subject+keyword but is NOT a window
            OpenCase("driver seat vent is not a window open", window, "主驾座椅通风1档", false),
            OpenCase("passenger seat vent level 5 is not a window open", window, "副驾座椅通风5档", false),
            OpenCase("driver seat heat is not a window open", window, "主驾座椅加热3档", false),
            // isSunroofOpenCommand
            OpenCase("sunroof full open is a sunroof open", sunroof, "天窗全开", true),
            OpenCase("sunroof vent is a sunroof open", sunroof, "天窗通风", true),
            OpenCase("sunroof comfort open is a sunroof open", sunroof, "天窗舒适打开", true),
            OpenCase("sunroof to 50 percent is a sunroof open", sunroof, "天窗打开50", true),
            OpenCase("sunroof to 0 percent is not a sunroof open", sunroof, "天窗打开0", false),
            OpenCase("sunroof stop is not a sunroof open", sunroof, "天窗停止", false),
            OpenCase("sunroof close is not a sunroof open", sunroof, "天窗关闭", false),
            OpenCase("sunshade is not a sunroof open", sunroof, "遮阳帘打开", false),
            OpenCase("side window full open is not a sunroof open", sunroof, "车窗全开", false),
            // isSunshadeOpenCommand (early-fire guard only — never speed-gated)
            OpenCase("sunshade open is a sunshade open", sunshade, "遮阳帘打开", true),
            OpenCase("sunshade close is not a sunshade open", sunshade, "遮阳帘关闭", false),
            OpenCase("sunroof open is not a sunshade open", sunshade, "天窗打开100", false),
            OpenCase("side window open is not a sunshade open", sunshade, "车窗全开", false),
        )
    }

    private val gateCases by lazy {
        listOf(
            // sunshade always null (ungated interior shade)
            GateCase("sunshade open at high speed returns null", "遮阳帘打开", 200, null),
            GateCase("sunshade open at unknown speed returns null", "遮阳帘打开", null, null),
            // sunroof gate at 80 km/h
            GateCase("sunroof open at 81 returns block reason", "天窗全开", 81, BlockReason.SunroofSpeed(81)),
            GateCase("sunroof open at 80 returns null", "天窗全开", 80, null),
            GateCase("sunroof open at 79 returns null", "天窗全开", 79, null),
            GateCase("sunroof open at unknown speed returns block reason", "天窗全开", null, BlockReason.SpeedUnknown),
            // window gate at 120 km/h
            GateCase("window open at 100 returns null", "车窗全开", 100, null),
            GateCase("window open at 120 returns null", "车窗全开", 120, null),
            GateCase("window open at 121 returns block reason", "车窗全开", 121, BlockReason.WindowsSpeed(121)),
            GateCase("window open at unknown speed returns block reason", "车窗全开", null, BlockReason.SpeedUnknown),
            // close commands and non-apertures are never gated
            GateCase("sunroof close at high speed returns null", "天窗关闭", 200, null),
            GateCase("window close at high speed returns null", "车窗关闭", 200, null),
            GateCase("seat vent at high speed returns null", "主驾座椅通风1档", 200, null),
        )
    }

    @Test fun `each command is or is not a window, sunroof or sunshade open`() {
        for (c in openCases) assertEquals("case: ${c.label} (${c.command})", c.expected, c.isOpen(c.command))
    }

    @Test fun `each aperture command at each speed gets its block reason`() {
        for (c in gateCases) {
            assertEquals("case: ${c.label}", c.expected, ActionDispatcher.speedGateBlockReason(c.command, c.speed))
        }
    }
}
