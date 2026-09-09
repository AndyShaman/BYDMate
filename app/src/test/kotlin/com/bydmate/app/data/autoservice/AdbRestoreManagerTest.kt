package com.bydmate.app.data.autoservice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** `service.adb.tls.port`; null models a firmware that does not publish it. */
        var propPort: Int? = null
        var propPortReads = 0
        var tcpipAnswer: String? = "restarting in TCP mode port: 5555"
        var tcpipCalls = 0
        /**
         * Models a real socket call blocking on I/O: cancelling the coroutine does not interrupt
         * it, the exception only surfaces once the call itself returns — same as the real
         * `restartTcpip`, which is a blocking read cancellation cannot reach mid-call.
         */
        var tcpipGate: CompletableDeferred<Unit>? = null
        /** Same as [tcpipGate], for ensureHelperRunning — models the helper bootstrap blocking. */
        var helperGate: CompletableDeferred<Unit>? = null
        var discoverCalls = 0
        /** Runs inside discoverTlsPort, i.e. while the attempt is suspended. */
        var onDiscover: (suspend () -> Unit)? = null
        /** Classic connect answers: consumed in order, the last value repeats. */
        var classicResults = mutableListOf(false, true)
        var classicCalls = 0
        var helperStarts = 0
        var now = 1_000_000L
        var slept = 0L
        val sleeps = mutableListOf<Long>()
        var onAttemptStart: (suspend () -> Unit)? = null
        /** Runs inside the first sleep, i.e. while the attempt is suspended. */
        var onSleep: (suspend () -> Unit)? = null
        /** Runs inside writeAdbWifiEnabled(1), before the write is recorded. */
        var onEnableWrite: (() -> Unit)? = null
        /** Runs on every wifiNetwork() call, not consumed — attemptLocked and afterAttempt both call it. */
        var onWifiNetworkQuery: (() -> Unit)? = null

        override fun hasWriteSecureSettings(): Boolean = permission
        override fun wifiNetwork(): String? {
            onWifiNetworkQuery?.invoke()
            return wifi
        }
        override fun readAdbWifiEnabled(): Int = adbWifiEnabled

        override fun writeAdbWifiEnabled(value: Int): Boolean {
            if (value == 1) onEnableWrite?.let { it(); onEnableWrite = null }
            settingsWrites += value
            if (!writeAccepted) return false
            adbWifiEnabled = if (value == 1 && !settingSticks) 0 else value
            return true
        }

        override fun tlsPortFromProperty(): Int? {
            propPortReads++
            return propPort
        }

        override suspend fun discoverTlsPort(timeoutMs: Long): Int? {
            discoverCalls++
            onDiscover?.let { it(); onDiscover = null }
            return tlsPort
        }

        override suspend fun restartTcpip(port: Int): String? {
            tcpipCalls++
            tcpipGate?.let {
                withContext(NonCancellable) { it.await() }
                // The blocking call itself is immune to cancellation (above), but the real
                // restartTcpip returns onto a cancellable dispatcher — this is where a
                // cancellation that arrived while it was blocked actually surfaces.
                currentCoroutineContext().ensureActive()
            }
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
            helperGate?.let {
                withContext(NonCancellable) { it.await() }
                currentCoroutineContext().ensureActive()
            }
            return true
        }

        override suspend fun sleep(ms: Long) {
            // The real sleep is delay(), which is a cancellation point; without this the fake
            // would keep running inside a cancelled wave and prove nothing about rollback.
            currentCoroutineContext().ensureActive()
            slept += ms
            sleeps += ms
            now += ms
            onSleep?.let { it(); onSleep = null }
        }
        override fun nowMs(): Long = now
    }

    private fun manager(
        prefs: AdbRestorePreferences,
        system: AdbRestoreSystem,
        scope: CoroutineScope = TestScope(),
    ) = AdbRestoreManager(prefs, system, scope)

    /** Retry waits, told apart from the settle and poll sleeps by their distinct durations. */
    private fun FakeSystem.retryWaits(): List<Long> =
        sleeps.filter { it in AdbRestoreManager.RETRY_DELAYS_MS }

    /**
     * The manager's own serializing lock, for a test that has to observe it is still held at a
     * given point — real concurrent triggers land on real threads, which a single deterministic
     * test coroutine cannot race against, so this checks the invariant that makes the race
     * impossible instead of trying to reproduce the race itself.
     */
    private fun AdbRestoreManager.internalMutex(): Mutex {
        val field = AdbRestoreManager::class.java.getDeclaredField("mutex")
        field.isAccessible = true
        return field.get(this) as Mutex
    }

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
    fun `tcpip answer without the daemon marker still polls and restores`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false, true)
            tcpipAnswer = "error: closed"
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        val state = m.state.value
        assertTrue("expected Restored, got $state", state is AdbRestoreState.Restored)
    }

    @Test
    fun `null tcpip answer with the port coming alive on a later poll reports Restored`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false, false, false, true)
            tcpipAnswer = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        val state = m.state.value
        assertTrue("expected Restored, got $state", state is AdbRestoreState.Restored)
        assertEquals(1, system.helperStarts)
    }

    @Test
    fun `port that never comes back reports Failed after all polls`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            tcpipAnswer = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("classic port silent after tcpip"), m.state.value)
        val pollAttempts = AdbRestoreManager.TCPIP_POLL_TIMEOUT_MS / AdbRestoreManager.TCPIP_POLL_INTERVAL_MS
        // One initial probe plus one per poll.
        assertEquals(1 + pollAttempts.toInt(), system.classicCalls)
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
        assertEquals(AdbRestoreState.Failed("classic port silent after tcpip"), m.state.value)

        // Retry on the same network: discovery runs again from scratch, the enable-write stays
        // suppressed by the cooldown, and nothing from the first round is carried over.
        m.attemptIfNeeded()

        assertEquals(AdbRestoreState.Failed("classic port silent after tcpip"), m.state.value)
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
    fun `a pending dialog is retried on the 2-5-10-15 cadence`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        // The wave runs on the test dispatcher, so nothing has moved until it is let go.
        assertTrue(system.retryWaits().isEmpty())

        runCurrent()

        // 2 s, 5 s, 10 s, then 15 s forever — the tail of the wave is all 15s.
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 15_000L), system.retryWaits().take(4))
        assertTrue(system.retryWaits().drop(4).all { it == 15_000L })
        assertTrue("expected more than four retries", system.retryWaits().size > 4)
    }

    @Test
    fun `the retry wave gives up after five minutes`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)
        val startedAt = system.now

        m.attemptIfNeeded("wifi_validated")
        runCurrent()

        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        // The budget is counted from the first retry, so the first attempt's own sleeps sit
        // outside it; a few seconds of slack covers them.
        assertTrue(
            "wave outlived its budget: ${system.now - startedAt} ms",
            system.now - startedAt <= AdbRestoreManager.RETRY_BUDGET_MS + 10_000L,
        )
        assertTrue(system.retryWaits().sum() <= AdbRestoreManager.RETRY_BUDGET_MS)
        assertEquals(system.retryWaits().size, m.retryCount)
    }

    @Test
    fun `a dialog confirmed between retries ends the wave with a restore`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        // The user taps "Allow": the system itself leaves adb_wifi_enabled on, so the retry has
        // nothing left to write, and the port comes back.
        system.settingSticks = true
        system.adbWifiEnabled = 1
        system.classicResults = mutableListOf(false, true)
        system.classicCalls = 0

        runCurrent()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        // One retry was enough, so the cadence never reached the 5 s step.
        assertEquals(listOf(2_000L), system.retryWaits())
        assertEquals(1, system.helperStarts)
    }

    @Test
    fun `a retry rewrites the setting after the short interval, not the long cooldown`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        assertEquals(listOf(1), system.settingsWrites)

        runCurrent()

        // Retries at 2 s and 5 s fall inside the 15 s interval; the write only repeats past it.
        assertTrue("expected repeated enable-writes, got ${system.settingsWrites}", system.settingsWrites.size > 1)
        val writesInBudget =
            AdbRestoreManager.RETRY_BUDGET_MS / AdbRestoreManager.WRITE_RETRY_INTERVAL_MS
        assertTrue(
            "one dialog per retry would be spam: ${system.settingsWrites.size} writes",
            system.settingsWrites.size <= writesInBudget + 2,
        )
    }

    @Test
    fun `a verdict other than NeedsDialog schedules nothing`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(false, true) }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("service_start")
        runCurrent()

        assertTrue(system.retryWaits().isEmpty())
        assertEquals(0, m.retryCount)
    }

    @Test
    fun `switching the toggle off stops the wave`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        m.setEnabled(false)
        runCurrent()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertTrue("a cancelled wave must not sleep: ${system.retryWaits()}", system.retryWaits().isEmpty())
    }

    @Test
    fun `the port from the system property is tried before mDNS`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false, true)
            propPort = 41111
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        assertEquals(0, system.discoverCalls)
        assertEquals(1, system.tcpipCalls)
    }

    @Test
    fun `a property port that does not restore the port falls back to mDNS`() = runTest {
        // Silent on the property port, alive once mDNS finds the real one.
        val propPolls = (AdbRestoreManager.TCPIP_POLL_TIMEOUT_MS /
            AdbRestoreManager.TCPIP_POLL_INTERVAL_MS).toInt()
        val system = FakeSystem().apply {
            classicResults = MutableList(1 + propPolls) { false }.also { it += true }
            propPort = 41111
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        assertEquals(1, system.discoverCalls)
        assertEquals(2, system.tcpipCalls)
    }

    @Test
    fun `a garbage property port is ignored and mDNS runs as before`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false, true)
            propPort = 0
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        assertEquals(1, system.discoverCalls)
        assertEquals(1, system.tcpipCalls)
    }

    @Test
    fun `cancelling the wave inside the enable-write still rolls the setting back`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)

        // Past the short interval, so the first retry writes again — and this time it sticks.
        system.now += AdbRestoreManager.WRITE_RETRY_INTERVAL_MS
        system.settingSticks = true
        // The user flips the toggle off exactly while the retry is writing: the switch-off write
        // lands first, ours second, and the settle sleep after it dies with the cancelled wave.
        system.onEnableWrite = { m.setEnabled(false) }

        runCurrent()

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertEquals(0, system.adbWifiEnabled)
        assertEquals(0, system.settingsWrites.last())
        assertEquals(0, system.tcpipCalls)
    }

    @Test
    fun `the property port wins even when 5555 takes eight seconds`() = runTest {
        val slowPolls = 8
        val system = FakeSystem().apply {
            classicResults = MutableList(1 + slowPolls) { false }.also { it += true }
            propPort = 41111
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        assertEquals(0, system.discoverCalls)
        assertEquals(1, system.tcpipCalls)
        assertEquals(1, system.helperStarts)
    }

    @Test
    fun `a live 5555 is never reported as an mDNS timeout`() = runTest {
        val propPolls = (AdbRestoreManager.TCPIP_POLL_TIMEOUT_MS /
            AdbRestoreManager.TCPIP_POLL_INTERVAL_MS).toInt()
        val system = FakeSystem().apply {
            // Silent through the whole property-port poll, alive by the time mDNS gives up.
            classicResults = MutableList(1 + propPolls) { false }.also { it += true }
            propPort = 41111
            tlsPort = null
        }
        val m = manager(FakePrefs(), system)

        m.attemptIfNeeded()

        assertTrue("expected Restored, got ${m.state.value}", m.state.value is AdbRestoreState.Restored)
        assertEquals(1, system.discoverCalls)
        assertEquals(1, system.tcpipCalls)
        assertEquals(1, system.helperStarts)
    }

    @Test
    fun `the last trigger is recorded for the dump`() = runTest {
        val system = FakeSystem().apply { classicResults = mutableListOf(true) }
        val m = manager(FakePrefs(), system)

        assertEquals("none", m.lastTrigger)
        m.attemptIfNeeded("user_present")

        assertEquals("user_present", m.lastTrigger)
        assertFalse(m.state.value is AdbRestoreState.Failed)
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

    @Test
    fun `a suppressed-write NeedsDialog from a watchdog trigger with no prior wave does not start a wave`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        // A write already recorded moments ago on the current network — e.g. left over from
        // before this boot — puts the very first watchdog check inside the cooldown, with no
        // wave ever having run.
        val prefs = FakePrefs().apply {
            network = system.wifi
            writeAt = system.now - 1_000L
        }
        val m = manager(prefs, system, backgroundScope)

        m.attemptIfNeeded("watchdog")
        runCurrent()

        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        assertTrue(system.settingsWrites.isEmpty())
        assertTrue(system.retryWaits().isEmpty())
        assertEquals(0, m.retryCount)
    }

    @Test
    fun `a watchdog trigger after wave exhaustion writes once past cooldown but does not restart the wave`() =
        runTest {
            val system = FakeSystem().apply {
                classicResults = mutableListOf(false)
                settingSticks = false
            }
            val m = manager(FakePrefs(), system, backgroundScope)

            m.attemptIfNeeded("wifi_validated")
            runCurrent()
            assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
            val writesFromWave = system.settingsWrites.size

            // Past the cooldown from the wave's last write: a passive trigger may still send its
            // own single write, but the network is remembered as exhausted, so no new wave follows.
            system.now += AdbRestoreManager.WRITE_COOLDOWN_MS
            m.attemptIfNeeded("watchdog")
            runCurrent()

            assertEquals(writesFromWave + 1, system.settingsWrites.size)
            assertEquals(AdbRestoreState.NeedsDialog, m.state.value)

            // If a wave had restarted, it would keep writing on the retry cadence; nothing does,
            // even across a whole further budget's worth of time.
            val writesAfterWatchdog = system.settingsWrites.size
            system.now += AdbRestoreManager.RETRY_BUDGET_MS
            runCurrent()
            assertEquals(writesAfterWatchdog, system.settingsWrites.size)
        }

    @Test
    fun `a settings trigger after wave exhaustion writes again and restarts the wave`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)

        m.attemptIfNeeded("wifi_validated")
        runCurrent()
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)

        // Past the cooldown too, or the write itself would still be suppressed regardless of
        // the trigger.
        system.now += AdbRestoreManager.WRITE_COOLDOWN_MS
        m.attemptIfNeeded("settings")
        runCurrent()

        // A fresh wave ran its own 2 s / 5 s / ... cadence instead of staying dormant: two waves,
        // two initial 2 s waits.
        assertEquals(2, system.retryWaits().count { it == 2_000L })
    }

    @Test
    fun `a settings trigger within cooldown after wave exhaustion still writes and restarts the wave`() =
        runTest {
            val system = FakeSystem().apply {
                classicResults = mutableListOf(false)
                settingSticks = false
            }
            val m = manager(FakePrefs(), system, backgroundScope)

            m.attemptIfNeeded("wifi_validated")
            runCurrent()
            assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
            val writesFromWave = system.settingsWrites.size

            // Only a minute later — well inside the 10-minute cooldown from the wave's last write.
            system.now += 60_000L

            // A passive trigger must still respect the cooldown: no write, no new wave.
            m.attemptIfNeeded("watchdog")
            runCurrent()
            assertEquals(writesFromWave, system.settingsWrites.size)

            // The user acting from Settings bypasses the cooldown: a write happens right away, and
            // it starts its own retry wave (2 s / 5 s / ... cadence) instead of waiting out the
            // remaining ~9 minutes.
            m.attemptIfNeeded("settings")
            runCurrent()

            assertTrue(system.settingsWrites.size > writesFromWave)
            assertEquals(2, system.retryWaits().count { it == 2_000L })
        }

    @Test
    fun `afterAttempt judges the verdict while the attempt's own lock is still held`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)
        val mutex = m.internalMutex()
        val lockedOnEachQuery = mutableListOf<Boolean>()
        system.onWifiNetworkQuery = { lockedOnEachQuery += mutex.isLocked }

        m.attemptIfNeeded("wifi_validated")

        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        // wifiNetwork() is queried once inside attemptLocked (trivially under the lock) and once
        // more inside afterAttempt, reached only because the write really did get reverted — a
        // second, unrelated attempt racing in right after this one releases its lock could reset
        // writeWasReverted before that second query runs, which is exactly what judging the
        // verdict under the same lock that produced it rules out.
        assertEquals(2, lockedOnEachQuery.size)
        assertTrue("afterAttempt must run before the lock is released", lockedOnEachQuery.last())
    }

    @Test
    fun `a coalesced rerun landing on the write cooldown still starts the wave for an earlier revert`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)
        // The "wifi" trigger arrives while the first pass is inside its settle sleep, right after
        // the real write — same lock, so it coalesces into a second pass of the SAME attempt. That
        // second pass finds the write cooldown already active and comes back with "write
        // suppressed" instead of the real "setting reverted by system" the first pass saw.
        system.onSleep = { m.attemptIfNeeded("wifi") }

        m.attemptIfNeeded("service_start")
        runCurrent()

        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)
        // The wave must start on the strength of the first pass's real revert, not be silenced by
        // the coalesced pass's suppressed one.
        assertEquals(listOf(2_000L), system.retryWaits().take(1))
        assertTrue("expected more than one retry, got ${system.retryWaits()}", system.retryWaits().size > 1)
        // ...and it keeps writing on the retry cadence, past the short interval.
        assertTrue(
            "expected a repeated enable-write from the wave, got ${system.settingsWrites}",
            system.settingsWrites.size > 1,
        )
    }

    @Test
    fun `a rerun requested while cancellation is unwinding a blocking call is not dropped`() = runTest {
        val system = FakeSystem().apply {
            classicResults = mutableListOf(false, false, true)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        system.tcpipGate = gate

        m.attemptIfNeeded("wifi_validated")
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)

        // The dialog gets confirmed before the first retry: the retry proceeds past the write
        // straight into discovery and tcpip, where it parks on the gate — mutex held, deep inside
        // what models a blocking socket call.
        system.settingSticks = true
        system.adbWifiEnabled = 1
        runCurrent()
        assertEquals(1, system.tcpipCalls)

        // The user flips the toggle off — this cancels the retry wave while it is parked on the
        // gate — and straight back on, then asks for an attempt from a caller with no scope of its
        // own, exactly like the Settings screen. The mutex is still held, so this coalesces.
        m.setEnabled(false)
        m.setEnabled(true)
        m.requestAttempt("settings")
        runCurrent()
        assertEquals(AdbRestoreState.Disabled, m.state.value)

        // The blocking call finally returns: cancellation lands only now. Without the fix the
        // coalesced request above is dropped here and the toggle stays stuck on Disabled.
        gate.complete(Unit)
        runCurrent()

        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
    }

    @Test
    fun `a cancellation swallowed by the helper bootstrap still hands off a coalesced rerun`() = runTest {
        val system = FakeSystem().apply {
            // false, false, true: initial attempt, retry#1's own probe, then the poll that finds
            // the port back and triggers markRestored. A second false forces the eventual rerun
            // past the immediate "port already up" shortcut and into a real write + settle sleep,
            // where a lingering cancellation would actually resurface; the last true lets it finish.
            classicResults = mutableListOf(false, false, true, false, true)
            settingSticks = false
        }
        val m = manager(FakePrefs(), system, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        system.helperGate = gate

        m.attemptIfNeeded("wifi_validated")
        assertEquals(AdbRestoreState.NeedsDialog, m.state.value)

        // The dialog gets confirmed before the first retry: the retry proceeds past the write,
        // finds the port already back and calls markRestored, which parks inside the helper
        // bootstrap — mutex still held, deep inside what models a blocking daemon handshake.
        system.settingSticks = true
        system.adbWifiEnabled = 1
        runCurrent()
        assertEquals(1, system.helperStarts)
        assertEquals(AdbRestoreState.Restored(system.now), m.state.value)

        // The user flips the toggle off — cancelling the retry wave while it is parked in the
        // helper bootstrap — and straight back on, then asks for an attempt from a caller with no
        // scope of its own, exactly like the Settings screen. The mutex is still held, so this
        // coalesces. Time also moves well past the write interval, so a write attempted after the
        // bootstrap returns is not itself suppressed by the cooldown.
        m.setEnabled(false)
        m.setEnabled(true)
        m.requestAttempt("settings")
        system.now += AdbRestoreManager.WRITE_COOLDOWN_MS
        runCurrent()
        assertEquals(AdbRestoreState.Disabled, m.state.value)

        // The bootstrap finally returns: cancellation lands only now, inside markRestored's
        // runCatching. Without rethrowing it there, the still-cancelled coroutine's own while loop
        // consumes the coalesced rerun itself, then dies for good the next time it genuinely
        // suspends (the settle sleep after its own write) — by then the rerun flag is already
        // spent, so nothing hands the request off, and the toggle stays stuck on Disabled.
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, system.helperStarts)
    }
}
