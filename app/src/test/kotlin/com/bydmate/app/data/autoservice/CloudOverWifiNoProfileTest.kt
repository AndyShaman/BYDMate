package com.bydmate.app.data.autoservice

import com.bydmate.app.ui.settings.cloudOverWifiSwitchEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * #330, case 21: DiLink 3/4 have no data-profile property, so the switch to `double_apn` has no
 * profile to return to. That is the firmware, not a refusal of the car: the row reads
 * [CloudOverWifiState.Unavailable] instead of «Машина не приняла настройку связи». The verdict is
 * remembered with the firmware fingerprint, and only two properties actually read empty set it:
 * a car where the cloud works today must never end up "unavailable".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudOverWifiNoProfileTest {

    private class Prefs(private var enabled: Boolean = true) : CloudOverWifiPreferences {
        var saved: String? = null
        var missingFp: String? = null
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun savedProfile(): String? = saved
        override fun setSavedProfile(profile: String?) { saved = profile }
        override fun profileMissingFingerprint(): String? = missingFp
        override fun setProfileMissingFingerprint(fingerprint: String?) { missingFp = fingerprint }
    }

    private class WifiUp(private val fingerprint: String = FP) : CloudOverWifiSystem {
        override fun firmwareFingerprint(): String = fingerprint
        override fun hasValidatedWifi(): Boolean = true
        override suspend fun sleep(ms: Long) = delay(ms)
    }

    /** A DiLink 3/4 shell: both profile properties blank, no cellular link. */
    private class NoProfileCar : AdbOnDeviceClient {
        val props = mutableMapOf(
            APN_TYPE to "",
            FACTORY_APN_TYPE to "",
            "persist.radio.net.lte.apn1.disable" to "0",
            "net.lte.apn1.state" to "disconnect",
            "net.lte.apn3.state" to "disconnect",
        )

        /** getprop names whose read gets no answer (exec returns null). */
        val failing = mutableSetOf<String>()
        var throwing = false
        val execs = mutableListOf<String>()

        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = true
        override fun lastConnectFailure(): AdbConnectFailure? = null
        override suspend fun exec(cmd: String): String? {
            if (throwing) throw IOException("shell dropped")
            execs += cmd
            val name = cmd.removePrefix("getprop ")
            if (cmd.startsWith("getprop ") && name in failing) return null
            return if (cmd.startsWith("getprop ")) props[name].orEmpty() else ""
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = true
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = true
        override suspend fun spawnHelper(token: String): Boolean = true
        override suspend fun killHelper(): Boolean = true
        override suspend fun readHelperLog(): String? = null
        override suspend fun helperHeartbeat(): Boolean = true

        fun changing() = execs.filter { it.startsWith("am broadcast") || it.startsWith("service call cloudmanager 1") }
    }

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test fun `both properties read empty reads as unavailable, remembers the firmware and changes nothing`() = runTest {
        val adb = NoProfileCar()
        val prefs = Prefs()
        val m = CloudOverWifiManager(prefs, adb, WifiUp(), backgroundScope)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.Unavailable, m.state.value)
        assertEquals(FP, prefs.missingFp)
        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
    }

    @Test fun `unavailable stays after the switch goes off, and the switch could go off`() = runTest {
        val adb = NoProfileCar()
        val m = CloudOverWifiManager(Prefs(), adb, WifiUp(), backgroundScope)
        m.attemptIfNeeded("test")
        assertTrue(cloudOverWifiSwitchEnabled(m.state.value, enabled = true, adbReady = true))

        m.setEnabled(false)
        settle()

        assertEquals(CloudOverWifiState.Unavailable, m.state.value)
        assertTrue(adb.changing().isEmpty())
    }

    @Test fun `a restart on the same firmware starts unavailable`() = runTest {
        val prefs = Prefs(enabled = false).apply { missingFp = FP }

        val m = CloudOverWifiManager(prefs, NoProfileCar(), WifiUp(FP), backgroundScope)

        assertEquals(CloudOverWifiState.Unavailable, m.state.value)
    }

    @Test fun `a firmware update gives the car a fresh try`() = runTest {
        val prefs = Prefs(enabled = false).apply { missingFp = "byd/old/fp:1" }

        val m = CloudOverWifiManager(prefs, NoProfileCar(), WifiUp(FP), backgroundScope)

        assertNotEquals(CloudOverWifiState.Unavailable, m.state.value)
        assertTrue(cloudOverWifiSwitchEnabled(m.state.value, enabled = false, adbReady = true))
    }

    @Test fun `a read that gets no answer never marks the car`() = runTest {
        val adb = NoProfileCar().apply { failing += FACTORY_APN_TYPE }
        val prefs = Prefs()
        val m = CloudOverWifiManager(prefs, adb, WifiUp(), backgroundScope)

        m.attemptIfNeeded("test")

        // Today's answer to a getprop with no reply.
        assertEquals(CloudOverWifiState.NoAdb, m.state.value)
        assertNull(prefs.missingFp)
    }

    @Test fun `a shell that throws never marks the car`() = runTest {
        val adb = NoProfileCar().apply { throwing = true }
        val prefs = Prefs()
        val m = CloudOverWifiManager(prefs, adb, WifiUp(), backgroundScope)

        m.attemptIfNeeded("test")

        assertNotEquals(CloudOverWifiState.Unavailable, m.state.value)
        assertNull(prefs.missingFp)
    }

    private companion object {
        const val FP = "byd/dilink4/fp:2404260"
        const val APN_TYPE = "persist.sys.byd.apn_type"
        const val FACTORY_APN_TYPE = "ro.build.byd.apn_type"
    }
}
