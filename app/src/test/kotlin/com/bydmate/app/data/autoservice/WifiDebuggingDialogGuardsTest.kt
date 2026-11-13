package com.bydmate.app.data.autoservice

import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.data.autoservice.WifiDebuggingDialogAutoAllow.Outcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recognition and click details of the dialog auto-allow. Runs on today's API. */
class WifiDebuggingDialogGuardsTest {

    private fun node(checked: Boolean = false): AccessibilityNodeInfo = mockk {
        every { isChecked } returns checked
        every { performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
    }

    @Test
    fun `A6 the old WifiDebuggingActivity class is still a dialog`() {
        assertTrue(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, OLD_CLASS))
    }

    @Test
    fun `A6 the old class still presses Allow when the button is there`() {
        val button = node()
        val root = mockk<AccessibilityNodeInfo> {
            every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
            every { findAccessibilityNodeInfosByViewId(BUTTON1) } returns listOf(button)
        }

        assertEquals(Outcome.Allowed(checkboxTicked = false), WifiDebuggingDialogAutoAllow.allow(root))
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `A9 a Dialog class from another package is not a dialog`() {
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog("com.example", PLAIN_DIALOG))
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog(null, PLAIN_DIALOG))
    }

    @Test
    fun `A9 the USB debugging activity class is still not a dialog`() {
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, "com.android.systemui.usb.UsbDebuggingActivity"))
    }

    @Test
    fun `A1 systemui android app Dialog is a candidate for the strict check`() {
        assertTrue(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, PLAIN_DIALOG))
    }

    @Test
    fun `A7 the always-allow checkbox is found by android id alwaysUse and ticked before Allow`() {
        val checkbox = node(checked = false)
        val button = node()
        val root = mockk<AccessibilityNodeInfo> {
            every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
            every { findAccessibilityNodeInfosByViewId("android:id/alwaysUse") } returns listOf(checkbox)
            every { findAccessibilityNodeInfosByViewId(BUTTON1) } returns listOf(button)
        }

        val outcome = WifiDebuggingDialogAutoAllow.allow(root)

        assertEquals(Outcome.Allowed(checkboxTicked = true), outcome)
        verifyOrder {
            checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val OLD_CLASS = "com.android.systemui.wifi.WifiDebuggingActivity"
        const val PLAIN_DIALOG = "android.app.Dialog"
        const val BUTTON1 = "android:id/button1"
    }
}
