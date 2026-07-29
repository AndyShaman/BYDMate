package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Validator cases for kind="split_screen": empty narrow, empty wide, same package, bad side, valid. */
class RuleDraftValidatorSplitScreenTest {

    private fun splitAction(
        narrow: String = "com.example.narrow",
        wide: String = "com.example.wide",
        side: String = "right",
    ) = ActionDef(
        command = "",
        displayName = "Split Screen",
        kind = "split_screen",
        payload = """{"narrow":"$narrow","wide":"$wide","side":"$side"}""",
    )

    @Test fun `valid split_screen action passes validation`() {
        assertNull(RuleDraftValidator.validateActions(listOf(splitAction())))
    }

    @Test fun `side left is also valid`() {
        assertNull(RuleDraftValidator.validateActions(listOf(splitAction(side = "left"))))
    }

    @Test fun `empty narrow package returns SplitScreenNarrowEmpty`() {
        val err = RuleDraftValidator.validateActions(listOf(splitAction(narrow = "")))
        assertEquals(ActionValidationError.SplitScreenNarrowEmpty(1), err)
    }

    @Test fun `empty wide package returns SplitScreenWideEmpty`() {
        val err = RuleDraftValidator.validateActions(listOf(splitAction(wide = "")))
        assertEquals(ActionValidationError.SplitScreenWideEmpty(1), err)
    }

    @Test fun `same narrow and wide package returns SplitScreenSamePackage`() {
        val err = RuleDraftValidator.validateActions(listOf(splitAction(narrow = "com.same", wide = "com.same")))
        assertEquals(ActionValidationError.SplitScreenSamePackage(1), err)
    }

    @Test fun `unknown side returns SplitScreenInvalidSide`() {
        val err = RuleDraftValidator.validateActions(listOf(splitAction(side = "center")))
        assertEquals(ActionValidationError.SplitScreenInvalidSide(1), err)
    }

    @Test fun `blank side returns SplitScreenInvalidSide`() {
        val err = RuleDraftValidator.validateActions(listOf(splitAction(side = "")))
        assertEquals(ActionValidationError.SplitScreenInvalidSide(1), err)
    }

    @Test fun `second split_screen action failing reports index 2`() {
        val err = RuleDraftValidator.validateActions(listOf(
            splitAction(),
            splitAction(narrow = ""),
        ))
        assertEquals(ActionValidationError.SplitScreenNarrowEmpty(2), err)
    }

    // ── Payload-less split kinds (W6-F5) ────────────────────────────────────────

    private fun bareAction(kind: String) =
        ActionDef(command = "", displayName = kind, kind = kind, payload = null)

    @Test fun `split_screen_close needs no payload`() {
        assertNull(RuleDraftValidator.validateActions(listOf(bareAction("split_screen_close"))))
    }

    @Test fun `split_screen_toggle needs no payload`() {
        assertNull(RuleDraftValidator.validateActions(listOf(bareAction("split_screen_toggle"))))
    }

    @Test fun `payload-less split kinds stay valid next to a configured split_screen`() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            splitAction(),
            bareAction("split_screen_close"),
            bareAction("split_screen_toggle"),
        )))
    }
}
