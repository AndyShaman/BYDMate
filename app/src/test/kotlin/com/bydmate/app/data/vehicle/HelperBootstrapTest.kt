package com.bydmate.app.data.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperBootstrapTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whatever this Robolectric environment reports for our own package, computed the
     *  same way HelperBootstrap does. Tests drive prefs relative to this so they never
     *  depend on a hard-coded version number. */
    private fun baselineVersion(): Long =
        ctx().packageManager.getPackageInfo(ctx().packageName, 0).longVersionCode

    @Before
    fun reset() {
        prefs().edit().clear().apply()
    }

    /** Records kill/spawn calls; spawn can flip the helper alive via [onSpawn]. processAlive is
     *  the ps-level "a bydmate_helper process exists" signal that the kill-confirm wait polls;
     *  killHelper() clears it (the process dies). */
    private class FakeAdb : AdbOnDeviceClient {
        var killCalls = 0
        var spawnCalls = 0
        @Volatile var processAlive = false
        // When false, killHelper() does NOT clear processAlive — simulates a process that
        // refuses to die, so the bail-out guard can be exercised.
        var killEffective = true
        // When false, killHelper() reports a failed dispatch (no ADB connection / exec threw),
        // exercising the "kill could not be dispatched → bail" guard.
        var killSucceeds = true
        // How many killHelper() calls it takes before the process actually dies (models a first
        // kill lost on a stale ADB socket that a re-dispatched kill then clears).
        var killsNeededToDie = 1
        // Kill dispatches from this call number on report failure (models ADB dropping
        // mid-sequence: the initial kill dispatches, the retry kill cannot).
        var failDispatchFromCall = Int.MAX_VALUE
        var onSpawn: () -> Boolean = { true }
        // Daemon log contents readHelperLog() hands back; null = transport error.
        var helperLog: String? = null
        override suspend fun connect() = Result.success(Unit)
        override suspend fun isConnected() = true
        override suspend fun exec(cmd: String): String? = null
        override suspend fun grantUsageStatsAppop(packageName: String) = true
        override suspend fun spawnHelper(): Boolean { spawnCalls++; return onSpawn() }
        override suspend fun killHelper(): Boolean {
            killCalls++
            if (killCalls >= failDispatchFromCall) return false
            if (killSucceeds && killEffective && killCalls >= killsNeededToDie) processAlive = false
            return killSucceeds
        }
        override suspend fun readHelperLog(): String? = helperLog
        override suspend fun helperHeartbeat(): Boolean = processAlive
        override suspend fun shutdown() {}
    }

    /** Fakes the two methods ensureRunning exercises: isAlive() and daemonVersion().
     *  [version] defaults to null so stale/dead scenarios work without explicit setup. */
    private class FakeHelper(
        @Volatile var alive: Boolean,
        @Volatile var version: Long? = null,
    ) : HelperClientImpl() {
        override suspend fun isAlive(): Boolean = alive
        override suspend fun daemonVersion(): Long? = version
    }

    @Test
    fun `fresh daemon of current version is reused without kill or spawn`() = runTest {
        prefs().edit().putLong(KEY, baselineVersion()).apply()
        val adb = FakeAdb()
        // version matches want → daemonVersion() == want → reuse immediately
        val boot = HelperBootstrap(adb, FakeHelper(alive = true, version = baselineVersion()), ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("reuse must not kill", 0, adb.killCalls)
        assertEquals("reuse must not respawn", 0, adb.spawnCalls)
    }

    @Test
    fun `stale daemon from another version is killed and respawned`() = runTest {
        // Daemon alive but daemonVersion() returns null (old daemon, no TX_GET_VERSION) → stale.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = true                        // stale daemon process is running
        val helper = FakeHelper(alive = true)          // old daemon still answers isAlive ping
        // After spawn the fresh daemon carries the right version.
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("must kill the stale daemon", 1, adb.killCalls)
        assertEquals("must spawn a fresh daemon", 1, adb.spawnCalls)
        assertEquals("records the current version", baselineVersion(), prefs().getLong(KEY, -1L))
    }

    @Test
    fun `same version but dead daemon respawns without a needless kill`() = runTest {
        prefs().edit().putLong(KEY, baselineVersion()).apply()
        val adb = FakeAdb()
        // Dead daemon: daemonVersion() = null (no reply), isAlive() = false → no kill, spawn.
        val helper = FakeHelper(alive = false)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("clean boot must not kill", 0, adb.killCalls)
        assertEquals("must respawn", 1, adb.spawnCalls)
    }

    @Test
    fun `stale daemon that refuses to die after both kill rounds is not spawned over`() = runTest {
        // Stale version recorded; the old process stays alive through both kill rounds
        // (killEffective=false — it never dies), exercising the full retry-then-bail path.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = true
        adb.killEffective = false               // the stale process refuses to die
        val helper = FakeHelper(alive = true)   // old daemon keeps answering the ping
        adb.onSpawn = { helper.alive = true; true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("must not claim success over a live stale daemon", boot.ensureRunning())
        assertEquals("must attempt the initial kill plus one retry", 2, adb.killCalls)
        assertEquals("must NOT spawn over the live stale daemon", 0, adb.spawnCalls)
        assertEquals("must not persist a version", baselineVersion() xor 1L, prefs().getLong(KEY, -1L))
    }

    @Test
    fun `second kill round clears a stale daemon lost on the first kill`() = runTest {
        // The first kill is lost (e.g. a stale ADB socket); the re-dispatched kill in round 2
        // actually clears the process, so ensureRunning proceeds to spawn instead of bailing.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = true
        adb.killsNeededToDie = 2
        val helper = FakeHelper(alive = true)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue("the second kill round must let the spawn proceed", boot.ensureRunning())
        assertEquals("must dispatch exactly two kills", 2, adb.killCalls)
        assertEquals("must spawn once the stale daemon is actually gone", 1, adb.spawnCalls)
        assertEquals("records the current version", baselineVersion(), prefs().getLong(KEY, -1L))
    }

    @Test
    fun `failed retry kill dispatch breaks out and bails without spawning`() = runTest {
        // The first kill dispatches but the process survives round 1; the round-2 retry kill
        // then cannot be dispatched (ADB drops mid-sequence). The retry loop must break out
        // immediately and bail via the stale-alive guard, never spawning over the live daemon.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = true
        adb.killEffective = false          // round 1 kill lands but the process survives
        adb.failDispatchFromCall = 2       // the retry kill fails to dispatch
        val helper = FakeHelper(alive = true)
        adb.onSpawn = { helper.alive = true; true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("must not claim success", boot.ensureRunning())
        assertEquals("initial kill plus the failed retry dispatch", 2, adb.killCalls)
        assertEquals("must NOT spawn over the live stale daemon", 0, adb.spawnCalls)
        assertEquals("must not persist a version", baselineVersion() xor 1L, prefs().getLong(KEY, -1L))
    }

    @Test
    fun `failed kill dispatch bails without spawning or persisting`() = runTest {
        // Stale version recorded; killHelper() reports it could not be dispatched (e.g. ADB down).
        // Without evidence the old daemon is gone, we must not spawn over it nor record a version.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = true
        adb.killSucceeds = false
        val helper = FakeHelper(alive = true)
        adb.onSpawn = { helper.alive = true; true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("must not claim success when the kill could not be dispatched", boot.ensureRunning())
        assertEquals("must attempt the kill", 1, adb.killCalls)
        assertEquals("must NOT spawn after a failed kill", 0, adb.spawnCalls)
        assertEquals("must not persist a version", baselineVersion() xor 1L, prefs().getLong(KEY, -1L))
    }

    @Test
    fun `failed spawn dispatch does not persist even though a daemon answers the ping`() = runTest {
        // Stale version recorded; the old ps-level process is already gone (heartbeat false) but a
        // leftover daemon still answers the binder ping. The spawn dispatch fails — we must bail
        // BEFORE the poll so that ping does not get the new version persisted against a non-fresh daemon.
        prefs().edit().putLong(KEY, baselineVersion() xor 1L).apply()
        val adb = FakeAdb()
        adb.processAlive = false
        val helper = FakeHelper(alive = true)
        adb.onSpawn = { false }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("must not claim success on a failed spawn dispatch", boot.ensureRunning())
        assertEquals("must attempt the spawn", 1, adb.spawnCalls)
        assertEquals(
            "must NOT persist the new version on spawn failure",
            baselineVersion() xor 1L,
            prefs().getLong(KEY, -1L),
        )
    }

    @Test
    fun `first run with no record spawns and records version`() = runTest {
        // prefs cleared by @Before; dead daemon: daemonVersion() = null, isAlive() = false.
        val adb = FakeAdb()
        val helper = FakeHelper(alive = false)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("must spawn on first run", 1, adb.spawnCalls)
        assertEquals(baselineVersion(), prefs().getLong(KEY, -1L))
    }

    // -----------------------------------------------------------------------
    // Version handshake decision table (new in fix-wave 2)
    // -----------------------------------------------------------------------

    @Test
    fun `alive with right version is reused without kill or spawn`() = runTest {
        // daemonVersion() == want → reuse immediately; no kill, no spawn.
        val adb = FakeAdb()
        val helper = FakeHelper(alive = true, version = baselineVersion())
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("right version must not kill", 0, adb.killCalls)
        assertEquals("right version must not spawn", 0, adb.spawnCalls)
    }

    @Test
    fun `alive with wrong version is killed and respawned`() = runTest {
        // daemonVersion() returns a version that doesn't match want (explicit wrong non-null).
        // isAlive() = true → kill path, then spawn.
        val adb = FakeAdb()
        adb.processAlive = true
        val helper = FakeHelper(alive = true, version = baselineVersion() xor 1L)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("wrong version must trigger kill", 1, adb.killCalls)
        assertEquals("wrong version must trigger spawn", 1, adb.spawnCalls)
        assertEquals("records the current version", baselineVersion(), prefs().getLong(KEY, -1L))
    }

    @Test
    fun `alive with null version answer from old daemon is killed and respawned`() = runTest {
        // Old daemon: TX_GET_VERSION returns false (unhandled) → daemonVersion() = null.
        // isAlive() = true → kill path, then spawn.
        val adb = FakeAdb()
        adb.processAlive = true
        val helper = FakeHelper(alive = true, version = null)   // null = old daemon, no TX_GET_VERSION
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("old daemon must be killed", 1, adb.killCalls)
        assertEquals("fresh daemon must be spawned", 1, adb.spawnCalls)
        assertEquals("records the current version", baselineVersion(), prefs().getLong(KEY, -1L))
    }

    @Test
    fun `dead daemon spawns without kill`() = runTest {
        // Daemon is dead: daemonVersion() = null, isAlive() = false, processAlive = false → no kill.
        val adb = FakeAdb()
        adb.processAlive = false
        val helper = FakeHelper(alive = false, version = null)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("dead daemon must not be killed", 0, adb.killCalls)
        assertEquals("must spawn", 1, adb.spawnCalls)
    }

    @Test
    fun `process alive in ps but binder mute triggers kill`() = runTest {
        // Regression for Critical-1: after an APK reinstall the app uid changes, so the daemon's
        // UID gate rejects every transact — isAlive()=false, daemonVersion()=null — but the
        // process is still alive in ps and holds the exclusive file lock. Without helperHeartbeat
        // as a second gate, we would skip the kill and the fresh spawn would lose the lock race.
        val adb = FakeAdb()
        adb.processAlive = true                      // process holds the lock
        val helper = FakeHelper(alive = false, version = null)  // binder mute (uid mismatch)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals("ps-visible mute process must be killed", 1, adb.killCalls)
        assertEquals("must spawn after the mute process is cleared", 1, adb.spawnCalls)
    }

    @Test
    fun `fresh spawn that answers wrong version is a failure`() = runTest {
        // Spawn succeeds but the new daemon answers the wrong version — treat as failure.
        val adb = FakeAdb()
        val helper = FakeHelper(alive = false, version = null)
        // Spawn sets the version to a wrong value (simulates a corrupt or mismatched APK).
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion() xor 1L; true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("wrong version after spawn must return false", boot.ensureRunning())
        assertEquals("must attempt the spawn", 1, adb.spawnCalls)
        assertEquals("must NOT persist on wrong version", -1L, prefs().getLong(KEY, -1L))
    }

    // ── #64: keep the daemon log tail from a failed spawn for the diagnostic dump ──

    @Test
    fun `a spawn that never becomes reachable stores the daemon log tail`() = runTest {
        val adb = FakeAdb()
        // 12 numbered lines plus a blank one — only the last 10 non-blank lines are kept.
        adb.helperLog = (1..12).joinToString("\n") { "line$it" } + "\n\n"
        val helper = FakeHelper(alive = false, version = null)
        val boot = HelperBootstrap(adb, helper, ctx())

        assertFalse("spawn never answers the right version", boot.ensureRunning())

        val failure = boot.lastSpawnFailure()
        assertTrue("failure must be recorded", failure != null)
        assertTrue("timestamp must be set", failure!!.ts > 0L)
        val lines = failure.logTail.lines()
        assertEquals("only the last 10 lines are kept", 10, lines.size)
        assertEquals("line3", lines.first())
        assertEquals("line12", lines.last())
    }

    @Test
    fun `an unreadable daemon log is recorded as such`() = runTest {
        val adb = FakeAdb()   // readHelperLog() returns null
        val boot = HelperBootstrap(adb, FakeHelper(alive = false, version = null), ctx())

        assertFalse(boot.ensureRunning())
        assertEquals("(daemon log unavailable)", boot.lastSpawnFailure()?.logTail)
    }

    @Test
    fun `no failure is reported when the daemon comes up`() = runTest {
        val adb = FakeAdb()
        val helper = FakeHelper(alive = false)
        adb.onSpawn = { helper.alive = true; helper.version = baselineVersion(); true }
        val boot = HelperBootstrap(adb, helper, ctx())

        assertTrue(boot.ensureRunning())
        assertEquals(null, boot.lastSpawnFailure())
    }

    companion object {
        private const val PREFS = "helper"
        private const val KEY = "spawned_version_code"
    }
}
