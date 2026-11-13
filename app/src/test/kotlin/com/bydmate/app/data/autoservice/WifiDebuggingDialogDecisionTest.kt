package com.bydmate.app.data.autoservice

import com.bydmate.app.data.autoservice.WifiDebuggingDialogAutoAllow.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1-A6, A8, A9: the pure "may we press Allow" decision, so it is testable without Android nodes.
 * Needs the new `WifiDebuggingDialogAutoAllow.decide` and `Decision`.
 */
class WifiDebuggingDialogDecisionTest {

    private val bssid = "02:00:00:aa:bb:cc"
    private val dialogText =
        "Allow wireless debugging on this network? Network name (SSID) Dummy Device's Wi-Fi address (BSSID) " +
            "02:00:00:AA:BB:CC Always allow on this network"
    private val usbText = "Allow USB debugging? The computer's RSA key fingerprint is: 00:11:22:33"

    @Suppress("LongParameterList") // one parameter per input of the decision
    private fun decide(
        pkg: String? = SYSTEMUI,
        cls: String? = PLAIN_DIALOG,
        hasAllowButton: Boolean = true,
        windowText: String? = dialogText,
        currentBssid: String? = bssid,
        restoreEnabled: Boolean = true,
        recentWrite: Boolean = true,
    ) = WifiDebuggingDialogAutoAllow.decide(
        pkg, cls, hasAllowButton, windowText, currentBssid, restoreEnabled, recentWrite)

    private fun assertSkipped(d: Decision, reasonPart: String) {
        assertTrue("expected Skip, got $d", d is Decision.Skip)
        val reason = (d as Decision.Skip).reason
        assertTrue("reason '$reason' should mention '$reasonPart'", reason.contains(reasonPart, ignoreCase = true))
    }

    @Test
    fun `A1 systemui Dialog with the BSSID, toggle on and a recent write is pressed, case-insensitive`() {
        assertEquals(Decision.Press, decide())
    }

    @Test
    fun `A2 the USB debugging look-alike without the BSSID is skipped, with the reason`() {
        assertSkipped(decide(windowText = usbText), "bssid")
        assertSkipped(decide(windowText = null), "bssid")
    }

    @Test
    fun `A3 no recent write of ours is skipped`() {
        assertSkipped(decide(recentWrite = false), "write")
    }

    @Test
    fun `A4 toggle off is skipped`() {
        assertSkipped(decide(restoreEnabled = false), "toggle")
    }

    @Test
    fun `A5 unknown current BSSID still presses the wireless dialog, told apart by its text`() {
        // Android hides the BSSID from apps while location is off: null or the masked placeholder.
        // The wireless dialog shows one network address of six pairs; that is enough with our write.
        assertEquals(Decision.Press, decide(currentBssid = null))
        assertEquals(Decision.Press, decide(currentBssid = "02:00:00:00:00:00"))
        assertEquals(Decision.Press, decide(currentBssid = "  "))
    }

    @Test
    fun `A5 unknown current BSSID never presses a window without a lone network address`() {
        // The USB debugging dialog shows a key fingerprint: a chain longer than six pairs.
        val md5 = "Allow USB debugging? The computer's RSA key fingerprint is: " +
            "0A:1B:2C:3D:4E:5F:60:71:82:93:A4:B5:C6:D7:E8:F9 Always allow from this computer"
        for (bssid in listOf(null, "02:00:00:00:00:00")) {
            assertSkipped(decide(windowText = md5, currentBssid = bssid), "bssid")
            assertSkipped(decide(windowText = usbText, currentBssid = bssid), "bssid")
            assertSkipped(decide(windowText = "Allow?", currentBssid = bssid), "bssid")
            assertSkipped(decide(windowText = null, currentBssid = bssid), "bssid")
            // A fingerprint next to an address is still not ours to press.
            assertSkipped(decide(windowText = "$dialogText $md5", currentBssid = bssid), "bssid")
        }
    }

    @Test
    fun `A5 unknown current BSSID keeps the other conditions`() {
        assertSkipped(decide(currentBssid = null, recentWrite = false), "write")
        assertSkipped(decide(currentBssid = null, restoreEnabled = false), "toggle")
        assertEquals(Decision.NoAllowButton, decide(currentBssid = null, hasAllowButton = false))
    }

    @Test
    fun `A5 a known BSSID inside a key fingerprint is not the wireless dialog`() {
        // The six pairs of the network address may also sit inside a longer chain.
        val usb = "Allow USB debugging? The computer's RSA key fingerprint is: " +
            "00:11:$bssid:22:33:44:55:66:77:88:99 Always allow from this computer"
        assertSkipped(decide(windowText = usb), "lone network address")
    }

    @Test
    fun `A5 a window with two network addresses is never pressed`() {
        val two = "$dialogText 0A:1B:2C:3D:4E:5F"
        assertSkipped(decide(windowText = two), "lone network address")
        assertSkipped(decide(windowText = two, currentBssid = null), "lone network address")
    }

    @Test
    fun `A5 a known BSSID still has to be the one in the window`() {
        assertSkipped(decide(currentBssid = "02:00:00:11:22:33"), "bssid")
    }

    @Test
    fun `A6 the old class keeps today's rule - pressed without BSSID or recent write when the toggle is on`() {
        val old = "com.android.systemui.wifi.WifiDebuggingActivity"
        assertEquals(Decision.Press, decide(cls = old, windowText = usbText, currentBssid = null, recentWrite = false))
        assertSkipped(decide(cls = old, restoreEnabled = false), "toggle")
        assertEquals(Decision.NoAllowButton, decide(cls = old, hasAllowButton = false))
    }

    @Test
    fun `A8 systemui Dialog without button1 is NoAllowButton`() {
        assertEquals(Decision.NoAllowButton, decide(hasAllowButton = false))
    }

    @Test
    fun `A9 other packages with the Dialog class are not candidates`() {
        assertEquals(Decision.NotCandidate, decide(pkg = "com.example"))
        assertEquals(Decision.NotCandidate, decide(pkg = null))
        assertEquals(Decision.NotCandidate, decide(cls = "com.android.systemui.usb.UsbDebuggingActivity"))
        assertEquals(Decision.NotCandidate, decide(cls = null))
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val PLAIN_DIALOG = "android.app.Dialog"
    }
}
