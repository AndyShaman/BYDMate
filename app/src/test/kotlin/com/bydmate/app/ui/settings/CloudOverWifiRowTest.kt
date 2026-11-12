package com.bydmate.app.ui.settings

import com.bydmate.app.data.autoservice.CloudOverWifiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #330: on a firmware without the data-profile property (DiLink 3/4) the cloud over Wi-Fi cannot
 * work. The row says so and its switch cannot be turned on; a switch already on can still go off.
 * On every car with the property the switch behaves as before.
 */
class CloudOverWifiRowTest {

    @Test fun `no profile property locks the switch off`() {
        assertFalse(cloudOverWifiSwitchEnabled(CloudOverWifiState.Unavailable, enabled = false, adbReady = true))
        assertFalse(cloudOverWifiSwitchEnabled(CloudOverWifiState.Unavailable, enabled = false, adbReady = false))
    }

    @Test fun `no profile property still lets a switch that is on go off`() {
        assertTrue(cloudOverWifiSwitchEnabled(CloudOverWifiState.Unavailable, enabled = true, adbReady = true))
        assertTrue(cloudOverWifiSwitchEnabled(CloudOverWifiState.Unavailable, enabled = true, adbReady = false))
    }

    @Test fun `with the property the switch follows ADB as today`() {
        val states = listOf(
            CloudOverWifiState.Disabled, CloudOverWifiState.NoAdb, CloudOverWifiState.OwnCellular,
            CloudOverWifiState.WaitingInternet, CloudOverWifiState.ProfileRejected, CloudOverWifiState.CloudSilent,
            CloudOverWifiState.ServerRefused, CloudOverWifiState.Connected, CloudOverWifiState.ReturnPending,
        )
        for (state in states) {
            assertTrue("$state off, ADB ready", cloudOverWifiSwitchEnabled(state, enabled = false, adbReady = true))
            assertFalse("$state off, no ADB", cloudOverWifiSwitchEnabled(state, enabled = false, adbReady = false))
            assertTrue("$state on, no ADB", cloudOverWifiSwitchEnabled(state, enabled = true, adbReady = false))
        }
    }
}
