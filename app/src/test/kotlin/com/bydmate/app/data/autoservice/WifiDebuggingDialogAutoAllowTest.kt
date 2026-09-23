package com.bydmate.app.data.autoservice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bydmate.app.data.autoservice.WifiDebuggingDialogAutoAllow.Outcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Allow wireless debugging on this network?" auto-confirm: which windows count as the dialog,
 * and what gets clicked in it. Nodes are mocks — the real ones only exist inside a bound service.
 */
class WifiDebuggingDialogAutoAllowTest {

    private fun node(checked: Boolean = false): AccessibilityNodeInfo = mockk {
        every { isChecked } returns checked
        every { performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
    }

    private fun root(checkbox: AccessibilityNodeInfo?, button: AccessibilityNodeInfo?): AccessibilityNodeInfo =
        mockk {
            every { findAccessibilityNodeInfosByViewId(ALWAYS_USE) } returns listOfNotNull(checkbox)
            every { findAccessibilityNodeInfosByViewId(BUTTON1) } returns listOfNotNull(button)
        }

    @Test
    fun `the systemui wireless debugging activity is the dialog`() {
        assertTrue(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, DIALOG_CLASS))
    }

    @Test
    fun `other packages, other classes and nulls are not the dialog`() {
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog("com.example", DIALOG_CLASS))
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, "com.android.systemui.usb.UsbDebuggingActivity"))
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog(null, DIALOG_CLASS))
        assertFalse(WifiDebuggingDialogAutoAllow.isDialog(SYSTEMUI, null))
    }

    @Test
    fun `an unticked checkbox is ticked, then Allow is pressed`() {
        val checkbox = node(checked = false)
        val button = node()

        val outcome = WifiDebuggingDialogAutoAllow.allow(root(checkbox, button))

        assertEquals(Outcome.Allowed(checkboxTicked = true), outcome)
        verify(exactly = 1) { checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `a ticked checkbox is not clicked again`() {
        val checkbox = node(checked = true)
        val button = node()

        val outcome = WifiDebuggingDialogAutoAllow.allow(root(checkbox, button))

        assertEquals(Outcome.Allowed(checkboxTicked = true), outcome)
        verify(exactly = 0) { checkbox.performAction(any()) }
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `no checkbox still presses Allow`() {
        val button = node()

        val outcome = WifiDebuggingDialogAutoAllow.allow(root(null, button))

        assertEquals(Outcome.Allowed(checkboxTicked = false), outcome)
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `no Allow button reports NoAllowButton`() {
        val outcome = WifiDebuggingDialogAutoAllow.allow(root(node(), null))

        assertEquals(Outcome.NoAllowButton, outcome)
    }

    @Test
    fun `a stale node throwing does not crash`() {
        val checkbox = mockk<AccessibilityNodeInfo> {
            every { isChecked } returns false
            every { performAction(any()) } throws IllegalStateException("stale")
        }
        val button = mockk<AccessibilityNodeInfo> {
            every { performAction(any()) } throws IllegalStateException("stale")
        }

        val outcome = WifiDebuggingDialogAutoAllow.allow(root(checkbox, button))

        assertEquals(Outcome.Allowed(checkboxTicked = false), outcome)
    }

    // --- onDialogShown: which root gets clicked, retry, persisted outcome ---

    private val outcomes = mutableListOf<String>()

    private val appContext: Context = run {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(WifiDebuggingDialogAutoAllow.KEY_LAST_OUTCOME, any()) } answers {
            outcomes += secondArg<String>()
            editor
        }
        val prefs = mockk<SharedPreferences> { every { edit() } returns editor }
        mockk { every { getSharedPreferences(AdbRestorePreferencesImpl.PREFS_NAME, any()) } returns prefs }
    }

    /** Runs the retry at once instead of 300 ms later on the main looper. */
    private val runNow: (Long, () -> Unit) -> Unit = { _, block -> block() }

    private fun window(id: Int, root: AccessibilityNodeInfo): AccessibilityWindowInfo = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.root } returns root
    }

    private fun service(vararg windowLists: List<AccessibilityWindowInfo>): AccessibilityService = mockk {
        every { windows } returnsMany windowLists.toList()
    }

    private fun event(source: AccessibilityNodeInfo?): AccessibilityEvent = mockk {
        every { windowId } returns DIALOG_WINDOW
        every { this@mockk.source } returns source
    }

    /** A dialog root as reached from the event source: top of the tree, in [windowId]. */
    private fun sourceRoot(button: AccessibilityNodeInfo, windowId: Int = DIALOG_WINDOW): AccessibilityNodeInfo {
        val r = root(null, button)
        val w = mockk<AccessibilityWindowInfo> { every { id } returns windowId }
        every { r.parent } returns null
        every { r.window } returns w
        return r
    }

    @Test
    fun `toggle off leaves the dialog alone`() {
        val button = node()
        val src = sourceRoot(button)

        WifiDebuggingDialogAutoAllow.onDialogShown(service(emptyList()), appContext, event(src), false, runNow)

        verify(exactly = 0) { button.performAction(any()) }
        assertEquals(1, outcomes.size)
        assertTrue(outcomes[0], outcomes[0].substringAfter(' ').substringAfter(' ').startsWith("skipped"))
    }

    @Test
    fun `the root from the event source in the dialog window is clicked`() {
        val button = node()

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(emptyList()), appContext, event(sourceRoot(button)), true, runNow)

        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        assertTrue(outcomes.single(), outcomes.single().endsWith("Allowed(checkbox=false) via=source"))
    }

    @Test
    fun `a source root from another window is not clicked`() {
        val button = node()

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(emptyList()), appContext, event(sourceRoot(button, windowId = OTHER_WINDOW)), true, runNow)

        verify(exactly = 0) { button.performAction(any()) }
    }

    @Test
    fun `a foreign systemui window is never clicked`() {
        val foreignButton = node()
        val foreign = window(OTHER_WINDOW, root(null, foreignButton))

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(listOf(foreign)), appContext, event(null), true, runNow)

        verify(exactly = 0) { foreignButton.performAction(any()) }
        assertTrue(outcomes[0], outcomes[0].endsWith("NoAllowButton via=window"))
        assertTrue(outcomes[1], outcomes[1].endsWith("NoAllowButton (retry)"))
    }

    @Test
    fun `no source falls back to the window with the event's id`() {
        val button = node()
        val dialog = window(DIALOG_WINDOW, root(null, button))

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(listOf(dialog)), appContext, event(null), true, runNow)

        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        assertTrue(outcomes.single(), outcomes.single().endsWith("Allowed(checkbox=false) via=window"))
    }

    @Test
    fun `the retry finds a dialog that was not there yet`() {
        val button = node()
        val dialog = window(DIALOG_WINDOW, root(null, button))

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(emptyList(), listOf(dialog)), appContext, event(null), true, runNow)

        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        assertTrue(outcomes[0], outcomes[0].endsWith("NoAllowButton via=window"))
        assertTrue(outcomes[1], outcomes[1].endsWith("Allowed(checkbox=false) via=retry"))
    }

    @Test
    fun `only one retry is pending at a time`() {
        val retries = mutableListOf<() -> Unit>()
        val capture: (Long, () -> Unit) -> Unit = { _, block -> retries += block }
        val svc = service(emptyList())

        WifiDebuggingDialogAutoAllow.onDialogShown(svc, appContext, event(null), true, capture)
        WifiDebuggingDialogAutoAllow.onDialogShown(svc, appContext, event(null), true, capture)
        retries.forEach { it() }

        assertEquals(1, retries.size)
    }

    @Test
    fun `a throwing node lookup does not crash`() {
        val broken = mockk<AccessibilityNodeInfo> {
            every { findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        }
        val dialog = window(DIALOG_WINDOW, broken)

        WifiDebuggingDialogAutoAllow.onDialogShown(
            service(listOf(dialog)), appContext, event(null), true, runNow)

        assertTrue(outcomes[0], outcomes[0].endsWith("NoAllowButton via=window"))
        assertTrue(outcomes[1], outcomes[1].endsWith("NoAllowButton (retry)"))
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val DIALOG_CLASS = "com.android.systemui.wifi.WifiDebuggingActivity"
        const val ALWAYS_USE = "com.android.internal:id/alwaysUse"
        const val BUTTON1 = "android:id/button1"
        const val DIALOG_WINDOW = 7
        const val OTHER_WINDOW = 3
    }
}
