package com.bydmate.app.data.autoservice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic of [AdbRestoreManager] against a fully faked head unit — the real one needs
 * mDNS, TLS sockets and secure settings, none of which exist on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdbRestoreManagerTest {

    private class FakePrefs(private var enabled: Boolean = true) : AdbRestorePreferences {
        var network: String? = null
        var writeAt: Long = 0L
        var writes = 0

        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun lastWriteNetwork(): String? = network
        override fun lastWriteAtMs(): Long = writeAt
        override fun recordWrite(network: String?, atMs: Long) {
            this.network = network
            this.writeAt = atMs
            writes++
        }
    }

    private class FakeSystem : AdbRestoreSystem {
        var permission = true
        var wifi: String? = "aa:bb:cc:dd:ee:ff"
        var adbWifiEnabled = 0
        /** What the setting reads back as after our write — 0 models the unconfirmed dialog. */
        var settingSticks = true
        var settingsWrites = mutableListOf<Int>()
        var writeAccepted = true
        var tlsPort: Int? = 39943
        var tcpipAnswer: String? = "restarting in TCP mode port: 5555"
        var tcpipCalls = 0
        var discoverCalls = 0
        /** Runs inside discoverTlsPort, i.e. while the attempt is suspended. */
        var onDiscover: (suspend () -> Unit)? = null
        /** Classic connect answers: consumed in order, the last value repeats. */
        var classicResults = mutableListOf(false, true)
        var classicCalls = 0
        var helperStarts = 0
        var now = 1_000_000L
        var slept = 0L
        var onAttemptStart: (suspend () -> Unit)? = null
        /** Runs inside the first sleep, i.e. while the attempt is suspended. */
        var onSleep: (suspend () -> Unit)? = null
        /** Runs inside writeAdbWifiEnabled(1), before the write is recorded. */
        var onEnableWrite: (() -> Unit)? = null

        override fun hasWriteSecureSettings(): Boolean = permission
        override fun wifiNetwork(): String? = wifi
        override fun readAdbWifiEnabled(): Int = adbWifiEnabled

        override fun writeAdbWifiEnabled(value: Int): Boolean {
            if (value == 1) onEnableWrite?.let { it(); onEnableWrite = null }
            settingsWrites += value
            if (!writeAccepted) return false
            adbWifiEnabled = if (value == 1 && !settingSticks) 0 else value
            return true
        }

        override suspend fun discoverTlsPort(timeoutMs: Long): Int? {
            discoverCalls++
            onDiscover?.let { it(); onDiscover = null }
            return tlsPort
        }

        override suspend fun restartTcpip(port: Int): String? {
            tcpipCalls++
            return tcpipAnswer
        }

        override suspend fun classicConnect(): Boolean {
            onAttemptStart?.let { it(); onAttemptStart = null }
            val result = classicResults.getOrElse(classicCalls) { classicResults.last() }
            classicCalls++
            return result
        }

        override suspend fun ensureHelperRunning(): Boolean {
            helperStarts++
            return true
        }

        override suspend fun sleep(ms: Long) {
            slept += ms
            now += ms
            onSleep?.let { it(); onSleep = null }
        }
        override fun nowMs(): Long = now
    }

    private fun manager(prefs: AdbRestorePreferences, system: AdbRestoreSystem) =
        AdbRestoreManager(prefs, system)

    @Test
    fun `toggle off short-circuits to Disabled`() = runTest {
        val system = FakeSystem()
        val m = manager(FakePrefs(enabled = false), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertEquals(0, system.classicCalls)
        assertTrue(system.settingsWrites.isEmpty())
    }

    @Test
    fun `live classic port reports NotNeeded and writes nothing`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(true) }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
        assertTrue(system.settingsWrites.isEmpty())
        assertEquals(0, system.helperStarts)
    }

    @Test
    fun `missing permission reports NeedsActivation`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            permission = false
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.NeedsActivation, m.state.value)
        assertTrue(system.settingsWrites.isEmpty())
    }

    @Test
    fun `no wifi reports WaitingWifi`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            wifi = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.WaitingWifi, m.state.value)
        assertTrue(system.settingsWrites.isEmpty())
    }

    @Test
    fun `setting reverted by system reports NeedsDialog and does not rewrite within cooldown`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val prefs = FakePrefs()
        val m = manager(prefs, system)

        m.attemptIfNeeded()
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        assertEquals(listOf(1), system.settingsWrites)

        // Same network, five minutes later: the dialog is still the user's move, not ours.
        system.now += 5 * 60 * 1000L
        m.attemptIfNeeded()
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        assertEquals(listOf(1), system.settingsWrites)
        assertEquals(1, prefs.writes)

        // Past the cooldown a fresh dialog is acceptable.
        system.now += AdbRestoreManager.WRITE_COOLDOWN_MS
        m.attemptIfNeeded()
        assertEquals(listOf(1, 1), system.settingsWrites)
    }

    @Test
    fun `another network is written immediately`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()
        system.wifi = "11:22:33:44:55:66"
        m.attemptIfNeeded()

        assertEquals(listOf(1, 1), system.settingsWrites)
    }

    @Test
    fun `full success restores the port and starts the helper once`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        val state = m.state.value
        assertTrue("expected Restored, got $state", state is AdbRestoreState.Restored)
        assertEquals(listOf(1), system.settingsWrites)
        assertEquals(1, system.helperStarts)
    }

    @Test
    fun `discovery timeout reports Failed`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            tlsPort = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("mDNS timeout"), m.state.value)
        assertEquals(0, system.helperStarts)
    }

    @Test
    fun `tcpip answer without the daemon marker reports Failed`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            tcpipAnswer = "error: closed"
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("tcpip refused"), m.state.value)
    }

    @Test
    fun `port that never comes back reports Failed after all retries`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false) }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("port 5555 did not come up"), m.state.value)
        // One initial probe plus one per retry.
        assertEquals(1 + AdbRestoreManager.CLASSIC_RETRIES, system.classicCalls)
        assertEquals(0, system.helperStarts)
    }

    @Test
    fun `a trigger arriving mid-attempt never runs concurrently`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        // Fires from inside the first classicConnect, i.e. with the mutex already held.
        var reentrantCalls = 0
        system.onAttemptStart = {
            reentrantCalls++
            m.attemptIfNeeded()
        }

        m.attemptIfNeeded()

        assertEquals(1, reentrantCalls)
        // The interleaved attempt would have written the setting a second time; the deferred
        // rerun happens only after the first one has finished restoring the port.
        assertEquals(listOf(1), system.settingsWrites)
        assertEquals(1, system.helperStarts)
        // Restore probe (2 calls) plus the rerun, which finds the port already alive.
        assertEquals(3, system.classicCalls)
        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
    }

    @Test
    fun `turning the toggle off writes zero and reports Disabled`() {
        val system = FakeSystem()
        val prefs = FakePrefs()
        val m = manager(prefs, system)

        m.setEnabled(false)

        assertEquals(listOf(0), system.settingsWrites)
        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertTrue(!prefs.isEnabled())
    }

    @Test
    fun `turning the toggle off without the permission writes nothing`() {
        val system = FakeSystem().apply { permission = false }
        val m = manager(FakePrefs(), system)

        m.setEnabled(false)

        assertTrue(system.settingsWrites.isEmpty())
    }

    @Test
    fun `toggle switched off during the port probe abandons the attempt`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val prefs = FakePrefs()
        val m = manager(prefs, system)
        // The user flips the switch while the attempt is suspended on its first probe.
        system.onAttemptStart = { m.setEnabled(false) }

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        // Only the switch-off write, never our enable-write.
        assertEquals(listOf(0), system.settingsWrites)
        assertEquals(0, system.discoverCalls)
        assertEquals(0, system.tcpipCalls)
        assertEquals(0, system.helperStarts)
    }

    @Test
    fun `toggle switched off during discovery stops before tcpip`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        system.onDiscover = { m.setEnabled(false) }

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        // Our enable-write, the switch-off write, then the abandoned attempt undoing its own.
        assertEquals(listOf(1, 0, 0), system.settingsWrites)
        assertEquals(0, system.adbWifiEnabled)
        assertEquals(0, system.tcpipCalls)
        assertEquals(0, system.helperStarts)
    }

    @Test
    fun `toggle switched off between the write and its read-back abandons the attempt`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        // sleep() is the settle wait right after the enable-write.
        system.onSleep = { m.setEnabled(false) }

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        // Our write, the switch-off write, then the abandoned attempt undoing its own write.
        assertEquals(listOf(1, 0, 0), system.settingsWrites)
        assertEquals(0, system.adbWifiEnabled)
        assertEquals(0, system.discoverCalls)
        assertEquals(0, system.tcpipCalls)
    }

    @Test
    fun `a failing tcpip leaves no state that blocks the next attempt`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            tcpipAnswer = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()
        assertEquals(AdbRestoreState.Failed("tcpip refused"), m.state.value)

        // Retry on the same network: discovery runs again from scratch, the enable-write stays
        // suppressed by the cooldown, and nothing from the first round is carried over.
        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("tcpip refused"), m.state.value)
        assertEquals(2, system.discoverCalls)
        assertEquals(2, system.tcpipCalls)
        assertEquals(listOf(1), system.settingsWrites)
    }

    @Test
    fun `toggle switched off inside the enable-write is rolled back`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        // Worst case: the switch-off lands between the last check and our write, so the
        // switch-off write is recorded FIRST and ours would otherwise be the surviving value.
        system.onEnableWrite = { m.setEnabled(false) }

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertEquals(0, system.settingsWrites.last())
        assertEquals(0, system.adbWifiEnabled)
        assertEquals(0, system.discoverCalls)
        assertEquals(0, system.tcpipCalls)
    }

    @Test
    fun `a trigger after a fast off-on restarts the work instead of being lost`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        // The user switches off and straight back on while the attempt is stuck in discovery,
        // and the trigger that follows the switch-on finds the mutex held.
        system.onDiscover = {
            m.setEnabled(false)
            m.setEnabled(true)
            m.attemptIfNeeded()
        }

        m.attemptIfNeeded()

        // The coalesced rerun ran with the new generation and probed the port again.
        assertEquals(2, system.classicCalls)
        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
        assertEquals(0, system.tcpipCalls)
    }

    @Test
    fun `a coalesced trigger runs exactly one rerun`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system)
        system.onDiscover = {
            m.attemptIfNeeded()
            m.attemptIfNeeded()
            m.attemptIfNeeded()
        }

        m.attemptIfNeeded()

        // Three coalesced triggers collapse into ONE rerun: the first attempt probes the port
        // twice (initial + after tcpip), the single rerun adds one probe. Three reruns would
        // have made it five.
        assertEquals(3, system.classicCalls)
        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
    }

    @Test
    fun `states render as short names for the log dump`() {
        // The dump is the only view we get of a car we cannot touch: an identity hash there
        // ("AdbRestoreState$NotNeeded@1a2b3c") tells nobody anything.
        assertEquals("Disabled", AdbRestoreState.Disabled.toString())
        assertEquals("NotNeeded", AdbRestoreState.NotNeeded.toString())
        assertEquals("NeedsActivation", AdbRestoreState.NeedsActivation.toString())
        assertEquals("WaitingWifi", AdbRestoreState.WaitingWifi.toString())
        assertEquals("NeedsDialog", AdbRestoreState.NeedsDialog.toString())
        assertEquals("Connecting", AdbRestoreState.Connecting.toString())
        assertEquals("Restored(atMs=42)", AdbRestoreState.Restored(42L).toString())
        assertEquals("Failed(reason=mDNS timeout)", AdbRestoreState.Failed("mDNS timeout").toString())
    }
}
