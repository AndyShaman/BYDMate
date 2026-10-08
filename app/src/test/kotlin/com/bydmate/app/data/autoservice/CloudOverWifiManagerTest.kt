package com.bydmate.app.data.autoservice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Decision logic of [CloudOverWifiManager] against a faked on-device ADB shell (#310). Every
 * scenario is about which commands reach the car: a car with its own cellular link, or one that
 * did not accept the profile, must never see a changing command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudOverWifiManagerTest {

    private class FakePrefs(private var enabled: Boolean = true) : CloudOverWifiPreferences {
        var saved: String? = null
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun savedProfile(): String? = saved
        override fun setSavedProfile(profile: String?) { saved = profile }
    }

    private class FakeSystem : CloudOverWifiSystem {
        var wifiInternet = true
        var onWifiCheck: () -> Unit = {}
        override fun hasValidatedWifi(): Boolean {
            onWifiCheck()
            return wifiInternet
        }
        override suspend fun sleep(ms: Long) = delay(ms)
    }

    /**
     * The car's shell: answers come from [props] (getprop) and [cloud7], and the two changing
     * commands update them the way a car that accepts them would.
     */
    private class FakeAdb(
        val props: MutableMap<String, String> = mutableMapOf(),
        var cloud7: String? = CLOUD_OFF,
    ) : AdbOnDeviceClient {
        var connectOk = true
        var profileAccepted = true
        var cloudComesUp = true
        val execs = mutableListOf<String>()

        /** getprop names whose read fails (exec returns null). */
        val failing = mutableSetOf<String>()

        /** Runs after each command is answered — lets a scenario change the car mid-attempt. */
        var onExec: (String) -> Unit = {}

        override suspend fun connect(): Result<Unit> =
            if (connectOk) Result.success(Unit) else Result.failure(IOException("refused"))
        override suspend fun isConnected(): Boolean = connectOk
        override fun lastConnectFailure(): AdbConnectFailure? = null
        override suspend fun exec(cmd: String): String? {
            if (!connectOk) return null
            execs += cmd
            return answer(cmd).also { onExec(cmd) }
        }

        private fun answer(cmd: String): String? {
            if (cmd.startsWith("getprop ") && cmd.removePrefix("getprop ") in failing) return null
            if (cmd.startsWith("getprop ")) return props[cmd.removePrefix("getprop ")] ?: ""
            if (cmd == "service call cloudmanager 7") return cloud7
            if (cmd == "service call cloudmanager 1 i32 4") {
                if (cloudComesUp) cloud7 = CLOUD_ON
                return "Result: Parcel(NULL)"
            }
            if (cmd == "service call cloudmanager 1 i32 -5") {
                cloud7 = CLOUD_OFF
                return "Result: Parcel(NULL)"
            }
            if (cmd.startsWith("am broadcast")) {
                val type = cmd.substringAfter("--es apn_type ").trim()
                if (profileAccepted) {
                    props[APN_TYPE] = type
                    props[APN1_DISABLE] = if (type == "double_apn") "1" else "0"
                }
                return "Broadcasting: Intent { ... }\nBroadcast completed: result=0"
            }
            return ""
        }
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = true
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = true
        override suspend fun spawnHelper(token: String): Boolean = true
        override suspend fun killHelper(): Boolean = true
        override suspend fun readHelperLog(): String? = null
        override suspend fun helperHeartbeat(): Boolean = true
        override suspend fun shutdown() = Unit

        fun changing(): List<String> = execs.filter { it.startsWith("am broadcast") || it.startsWith("service call cloudmanager 1") }
    }

    private fun nativeSimCar() = FakeAdb(
        props = mutableMapOf(
            APN_TYPE to "triple_apn",
            FACTORY_APN_TYPE to "triple_apn",
            APN1_DISABLE to "0",
            APN1_STATE to "disconnect",
            APN3_STATE to "disconnect",
        ),
    )

    /** Runs the work the manager launched in its (background) scope, settle waits included. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun TestScope.manager(prefs: FakePrefs, adb: FakeAdb, system: FakeSystem = FakeSystem()) =
        CloudOverWifiManager(prefs, adb, system, backgroundScope)

    @Test
    fun `own cellular link on apn3 sends no changing command`() = runTest {
        val adb = nativeSimCar().apply { props[APN3_STATE] = "connect" }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.OwnCellular, m.state.value)
        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
    }

    @Test
    fun `triple_apn without cellular switches to double_apn remembers it and starts the cloud`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertEquals(
            listOf(
                "am broadcast --user 0 -a com.byd.action.RADIO_CONFIG -p com.android.phone -f 0x01000000 " +
                    "--es opt_name set_default_data --es apn_type double_apn",
                "service call cloudmanager 1 i32 4",
            ),
            adb.changing(),
        )
        assertEquals("triple_apn", prefs.saved)
        assertEquals(CloudOverWifiState.Connected, m.state.value)
    }

    @Test
    fun `profile not accepted stops before the cloud command`() = runTest {
        val adb = nativeSimCar().apply { profileAccepted = false }
        val m = manager(FakePrefs(), adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.ProfileRejected, m.state.value)
        assertFalse(adb.execs.any { it.startsWith("service call cloudmanager 1") })
    }

    @Test
    fun `profile already double_apn is left alone and toggle off restores nothing`() = runTest {
        val adb = nativeSimCar().apply {
            props[APN_TYPE] = "double_apn"
            props[APN1_DISABLE] = "1"
        }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")
        assertFalse(adb.execs.any { it.startsWith("am broadcast") })
        assertNull(prefs.saved)

        adb.execs.clear()
        m.setEnabled(false)
        settle()

        assertTrue(adb.changing().isEmpty())
        assertEquals(CloudOverWifiState.Disabled, m.state.value)
    }

    @Test
    fun `toggle off after our switch stops the cloud and returns the remembered profile`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.execs.clear()

        m.setEnabled(false)
        settle()

        assertEquals(
            listOf(
                "service call cloudmanager 1 i32 -5",
                "am broadcast --user 0 -a com.byd.action.RADIO_CONFIG -p com.android.phone -f 0x01000000 " +
                    "--es opt_name set_default_data --es apn_type triple_apn",
            ),
            adb.changing(),
        )
        assertNull(prefs.saved)
        assertEquals("triple_apn", adb.props[APN_TYPE])
        assertEquals(CloudOverWifiState.Disabled, m.state.value)
    }

    @Test
    fun `cloud already connected does not call cloudmanager 1`() = runTest {
        val adb = nativeSimCar().apply {
            props[APN_TYPE] = "double_apn"
            props[APN1_DISABLE] = "1"
            cloud7 = CLOUD_ON
        }
        val m = manager(FakePrefs(), adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.Connected, m.state.value)
        assertFalse(adb.execs.any { it.startsWith("service call cloudmanager 1") })
    }

    @Test
    fun `no Wi-Fi internet only reports waiting`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb, FakeSystem().apply { wifiInternet = false })

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.WaitingInternet, m.state.value)
        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
    }

    @Test
    fun `cloud that does not come up is reported as silent`() = runTest {
        val adb = nativeSimCar().apply { cloudComesUp = false }
        val m = manager(FakePrefs(), adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.CloudSilent, m.state.value)
    }

    @Test
    fun `cloudmanager 7 parcel is parsed into connected, not connected or unknown`() {
        assertEquals(true, CloudOverWifiManager.parseCloudConnected(CLOUD_ON))
        assertEquals(false, CloudOverWifiManager.parseCloudConnected(CLOUD_OFF))
        assertNull(CloudOverWifiManager.parseCloudConnected(null))
        assertNull(CloudOverWifiManager.parseCloudConnected(""))
        assertNull(CloudOverWifiManager.parseCloudConnected("service cloudmanager does not exist"))
        assertNull(CloudOverWifiManager.parseCloudConnected("Result: Parcel(fffffffc 00000001 '........')"))
        assertNull(CloudOverWifiManager.parseCloudConnected("Result: Parcel(00000000    '....')"))
    }

    @Test
    fun `ADB not reachable reports NoAdb without throwing`() = runTest {
        val adb = nativeSimCar().apply { connectOk = false }
        val m = manager(FakePrefs(), adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.NoAdb, m.state.value)
        assertTrue(adb.execs.isEmpty())
    }

    @Test
    fun `toggle off leaves the state Disabled and sends nothing`() = runTest {
        val adb = nativeSimCar()
        val m = manager(FakePrefs(enabled = false), adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.Disabled, m.state.value)
        assertTrue(adb.execs.isEmpty())
    }

    @Test
    fun `Wi-Fi events within the settle window coalesce into one attempt 15 s later`() = runTest {
        val adb = nativeSimCar().apply { props[APN3_STATE] = "connect" }
        val m = manager(FakePrefs(), adb)

        m.onWifiValidated()
        advanceTimeBy(5_000)
        m.onWifiValidated()
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue("no attempt before the 15 s settle", adb.execs.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, adb.execs.count { it == "getprop $APN_TYPE" })
        assertEquals("wifi_validated", m.lastTrigger)
    }

    @Test
    fun `Leopard 3 rSIM fixture from 2026-10-08 is own cellular and nothing is changed`() = runTest {
        val adb = FakeAdb()
        requireNotNull(javaClass.classLoader?.getResourceAsStream("cloud/l3-rsim-cellular-20261008.txt"))
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val cmd = line.substringBefore(" =>")
                val out = line.substringAfter("=>").trim()
                if (cmd == "service call cloudmanager 7") adb.cloud7 = out
                else adb.props[cmd.removePrefix("getprop ")] = out
            }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.OwnCellular, m.state.value)
        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
        assertTrue(m.dumpLine().contains("cloud_tcp=true"))
    }

    // --- review round: guards, confirmed return, hand-off ---

    @Test
    fun `cellular that came up after our switch blocks the toggle-off return and keeps it saved`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.execs.clear()
        adb.props[APN3_STATE] = "connected"

        m.setEnabled(false)
        settle()

        assertTrue(adb.changing().isEmpty())
        assertEquals("triple_apn", prefs.saved)
    }

    @Test
    fun `cellular that came up after the cloud stop blocks the return broadcast`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.execs.clear()
        adb.onExec = { if (it == CLOUD_STOP) adb.props[APN1_STATE] = "connect" }

        m.setEnabled(false)
        settle()

        assertEquals(listOf(CLOUD_STOP), adb.changing())
        assertEquals("triple_apn", prefs.saved)
    }

    @Test
    fun `cellular that came up during the profile switch wait blocks the cloud start`() = runTest {
        val adb = nativeSimCar()
        adb.onExec = { if (it.startsWith("am broadcast")) adb.props[APN3_STATE] = "connect" }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertFalse(adb.execs.contains(CLOUD_START))
        assertEquals(CloudOverWifiState.OwnCellular, m.state.value)
        assertEquals("triple_apn", prefs.saved)
    }

    @Test
    fun `unreadable apn3 state forbids any change`() = runTest {
        val adb = nativeSimCar().apply { failing += APN3_STATE }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
        assertEquals(CloudOverWifiState.NoAdb, m.state.value)
    }

    @Test
    fun `unreadable apn1 state forbids the toggle-off return`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.execs.clear()
        adb.failing += APN1_STATE

        m.setEnabled(false)
        settle()

        assertTrue(adb.changing().isEmpty())
        assertEquals("triple_apn", prefs.saved)
    }

    @Test
    fun `return broadcast answered but not applied keeps the profile saved as pending`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.profileAccepted = false

        m.setEnabled(false)
        settle()

        assertEquals("triple_apn", prefs.saved)
        assertEquals(CloudOverWifiState.ReturnPending, m.state.value)
    }

    @Test
    fun `return with an unreadable read-back keeps the profile saved`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        m.attemptIfNeeded("test")
        adb.onExec = { if (it.startsWith("am broadcast")) adb.failing += APN_TYPE }

        m.setEnabled(false)
        settle()

        assertEquals("triple_apn", prefs.saved)
        assertTrue(m.state.value != CloudOverWifiState.Disabled)
    }

    @Test
    fun `toggle off arriving between the last rerun check and the unlock still returns the profile`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val m = manager(prefs, adb)
        var fired = false
        m.beforeUnlockForTest = {
            if (!fired) {
                fired = true
                prefs.setEnabled(false)
                m.attemptIfNeeded(CloudOverWifiManager.TRIGGER_TOGGLE_OFF)
            }
        }

        m.attemptIfNeeded("test")
        settle()

        assertTrue(fired)
        assertTrue(adb.changing().contains(CLOUD_STOP))
        assertNull(prefs.saved)
        assertEquals("triple_apn", adb.props[APN_TYPE])
    }

    @Test
    fun `toggle off before the switch broadcast persists nothing`() = runTest {
        val adb = nativeSimCar()
        val prefs = FakePrefs()
        val system = FakeSystem().apply { onWifiCheck = { prefs.setEnabled(false) } }
        val m = manager(prefs, adb, system)

        m.attemptIfNeeded("test")

        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
    }

    @Test
    fun `confirmed rejection of double_apn clears the saved profile`() = runTest {
        val adb = nativeSimCar().apply { profileAccepted = false }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.ProfileRejected, m.state.value)
        assertNull(prefs.saved)
    }

    @Test
    fun `ambiguous switch keeps the saved profile and a return finding the car on it sends nothing`() = runTest {
        val adb = nativeSimCar().apply { profileAccepted = false }
        adb.onExec = { if (it.startsWith("am broadcast")) adb.failing += APN_TYPE }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")
        assertEquals("triple_apn", prefs.saved)

        adb.failing.clear()
        adb.onExec = {}
        adb.execs.clear()
        m.setEnabled(false)
        settle()

        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
        assertEquals(CloudOverWifiState.Disabled, m.state.value)
    }

    @Test
    fun `toggle off during the guard reads before the cloud start sends no cloud start`() = runTest {
        val adb = nativeSimCar().apply {
            props[APN_TYPE] = "double_apn"
            props[APN1_DISABLE] = "1"
        }
        val prefs = FakePrefs()
        var apn1Reads = 0
        adb.onExec = {
            if (it == "getprop $APN1_STATE" && ++apn1Reads == 2) prefs.setEnabled(false)
        }
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertFalse(adb.execs.contains(CLOUD_START))
        assertNull(prefs.saved)
    }

    @Test
    fun `blank apn_type with factory fallback and a rejected switch clears the saved profile`() = runTest {
        val adb = nativeSimCar().apply {
            props[APN_TYPE] = ""
            profileAccepted = false
        }
        val prefs = FakePrefs()
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertEquals(CloudOverWifiState.ProfileRejected, m.state.value)
        assertNull(prefs.saved)
    }

    @Test
    fun `blank apn_type equal to the saved factory profile makes the return send nothing`() = runTest {
        val adb = nativeSimCar().apply { props[APN_TYPE] = "" }
        val prefs = FakePrefs(enabled = false).apply { saved = "triple_apn" }
        val m = manager(prefs, adb)

        m.attemptIfNeeded("test")

        assertTrue(adb.changing().isEmpty())
        assertNull(prefs.saved)
        assertEquals(CloudOverWifiState.Disabled, m.state.value)
    }

    private companion object {
        const val CLOUD_START = "service call cloudmanager 1 i32 4"
        const val CLOUD_STOP = "service call cloudmanager 1 i32 -5"
        const val APN_TYPE = "persist.sys.byd.apn_type"
        const val FACTORY_APN_TYPE = "ro.build.byd.apn_type"
        const val APN1_DISABLE = "persist.radio.net.lte.apn1.disable"
        const val APN1_STATE = "net.lte.apn1.state"
        const val APN3_STATE = "net.lte.apn3.state"
        const val CLOUD_ON = "Result: Parcel(00000000 00000001   '........')"
        const val CLOUD_OFF = "Result: Parcel(00000000 00000000   '........')"
    }
}
