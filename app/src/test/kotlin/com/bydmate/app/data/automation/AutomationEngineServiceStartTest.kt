package com.bydmate.app.data.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** The service_start session of [AutomationEngine] (#177), split out of [AutomationEngineEdgeTest]. */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver
// (RECEIVER_NOT_EXPORTED permission fallback) thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Suppress("LargeClass") // one scenario suite over shared helpers
class AutomationEngineServiceStartTest {

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param
    )

    private fun serviceStartTrigger() = TriggerDef(
        param = "", chineseName = "", operator = "", value = "",
        displayName = "start", kind = "service_start"
    )

    private fun rule(id: Long, triggers: List<TriggerDef>, logic: String = "AND") = RuleEntity(
        id = id, name = "r$id",
        triggerLogic = logic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    private fun setup(
        context: Context = ApplicationProvider.getApplicationContext(),
        dispatcher: ActionDispatcher = mockk(relaxed = true),
        rulesProvider: () -> List<RuleEntity>,
    ): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } answers { rulesProvider() }
        }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = dispatcher,
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = context,
            appStrings = com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()),
        )
        engine.interactiveProvider = { true }  // screen on unless a test says otherwise
        return engine to ruleDao
    }

    // #177: the service_start session follows the head unit screen. A tick with the screen on
    // after more than a minute without a heartbeat (car off: screen dark and/or process dead)
    // is a new car start and fires once. A process restart mid-drive (fresh heartbeat) does
    // not, and a process started with the screen off (restart after ACC_OFF) waits for the
    // screen. A reboot (new boot id, elapsed going backwards) fires. Wall-clock time takes
    // no part.
    private val t0 = 1_700_000_000_000L
    private val bootId = "boot-a"
    private val e0 = 3_600_000L  // elapsedRealtime of the stored heartbeat

    private fun automationPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("automation", Context.MODE_PRIVATE)

    private fun storeServiceStartState(bootId: String, lastSeenElapsed: Long) {
        automationPrefs().edit()
            .putString("service_start_boot_id", bootId)
            .putLong("service_start_last_seen_elapsed", lastSeenElapsed)
            .commit()
    }

    private fun storedHeartbeat() = automationPrefs().getLong("service_start_last_seen_elapsed", -1L)

    private fun engineLogs() = ShadowLog.getLogsForTag("AutomationEngine").map { it.msg }

    private class HeadUnit(var elapsed: Long, var wall: Long, var screenOn: Boolean = true) {
        fun advance(ms: Long) { elapsed += ms; wall += ms }
    }

    private fun serviceStartEngine(
        bootId: String,
        unit: HeadUnit,
        r: RuleEntity = rule(1, listOf(serviceStartTrigger())),
    ): Pair<AutomationEngine, RuleDao> {
        val (engine, dao) = setup { listOf(r) }
        engine.bootIdProvider = { bootId }
        engine.elapsedMs = { unit.elapsed }
        engine.nowMs = { unit.wall }
        engine.interactiveProvider = { unit.screenOn }
        return engine to dao
    }

    private suspend fun startProcess(bootId: String, elapsed: Long, wall: Long = t0): RuleDao {
        val (engine, dao) = serviceStartEngine(bootId, HeadUnit(elapsed, wall))
        engine.evaluate(diParsData(soc = 50), null)
        engine.evaluate(diParsData(soc = 50), null)
        return dao
    }

    @Test fun `service_start fires on the first process of a fresh boot`() = runBlocking {
        val dao = startProcess(bootId, e0)                                  // nothing stored
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not fire on a restart mid-session`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val dao = startProcess(bootId, e0 + 20_000L)                        // heartbeat 20 s old
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
        assertTrue(engineLogs().contains("service_start: process restarted mid-session, not re-arming gap=20s"))
    }

    @Test fun `service_start fires once after the car was off 15 minutes and the process was killed`() =
        runBlocking {
            storeServiceStartState(bootId, lastSeenElapsed = e0)
            val dao = startProcess(bootId, e0 + 900_000L)                   // heartbeat 15 min old
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertTrue(engineLogs().contains("service_start: new session reason=wake gap=900s"))
        }

    @Test fun `service_start fires once when the screen comes back after 15 minutes off in a live process`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            engine.evaluate(diParsData(soc = 50), null)                     // fires, rule consumed
            unit.advance(60_000L)
            engine.evaluate(diParsData(soc = 50), null)                     // window over
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            val heartbeat = storedHeartbeat()

            unit.screenOn = false                                           // car off 15 min
            repeat(30) {
                unit.advance(30_000L)
                engine.evaluate(diParsData(soc = 50), null)
            }
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertEquals(heartbeat, storedHeartbeat())
            assertEquals(1, engineLogs().count { it == "service_start: screen off, heartbeat paused" })

            unit.screenOn = true                                            // car on
            unit.advance(5_000L)
            engine.evaluate(diParsData(soc = 50), null)
            repeat(3) {
                unit.advance(5_000L)
                engine.evaluate(diParsData(soc = 50), null)
            }
            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        }

    @Test fun `service_start waits for the screen when the process starts with the screen off`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)                // last tick of the drive
        val unit = HeadUnit(e0 + 12_000L, t0, screenOn = false)            // restart 12 s after ACC_OFF
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)
        repeat(4) {                                                         // 2 min with the screen off
            unit.advance(30_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
        assertTrue(engineLogs().contains("service_start: screen off at start, waiting for wake-up"))

        unit.screenOn = true                                                // car on
        unit.advance(5_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(5_000L)
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not re-arm after a 20 s screen blink`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        unit.advance(60_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(10_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    private fun storedCarOff() = automationPrefs().getBoolean("service_start_car_off", false)

    @Test fun `service_start fires once on the car off marker after 30 s dark in a live process`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        engine.onCarOff()
        assertTrue(storedCarOff())
        unit.screenOn = false
        repeat(3) {                                                         // 30 s off, under the gap
            unit.advance(10_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: ACC_OFF, car off marked"))
        assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=30s"))
        assertFalse(storedCarOff())
        repeat(5) {
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires once on a restarted process after ACC_OFF with a 30 s old heartbeat`() =
        runBlocking {
            storeServiceStartState(bootId, lastSeenElapsed = e0)
            val (dying, _) = serviceStartEngine(bootId, HeadUnit(e0 + 5_000L, t0))
            dying.onCarOff()                                                // then force-stopped
            val unit = HeadUnit(e0 + 30_000L, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            engine.evaluate(diParsData(soc = 50), null)                     // first tick, screen on
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=30s"))
            assertFalse(storedCarOff())
        }

    @Test fun `service_start waits for a dark screen when the screen stays lit after ACC_OFF`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        engine.onCarOff()
        repeat(3) {                                                         // screen still lit at car off
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(10_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start ignores a car off marker within a minute of the session start`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        unit.advance(3_000L)
        engine.onCarOff()
        unit.screenOn = false
        unit.advance(3_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.screenOn = true
        repeat(4) {
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertEquals(1, engineLogs().count { it == "service_start: car off marker within session, ignored gap=9s" })
        assertFalse(storedCarOff())
    }

    @Test fun `service_start window closes on ACC_OFF with the screen still lit`() = runBlocking {
        val r = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")), logic = "AND")
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit, r)
        engine.evaluate(diParsData(exteriorTemp = null), null)              // window armed, data cold
        unit.advance(3_000L)
        engine.onCarOff()
        engine.evaluate(diParsData(exteriorTemp = null), null)              // screen still lit
        unit.advance(3_000L)
        engine.evaluate(diParsData(exteriorTemp = 25), null)                // second condition true
        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        assertEquals(1, engineLogs().count { it == "service_start: ACC_OFF, window closed" })
    }

    @Test fun `service_start opens no wake session on a lit tick after ACC_OFF and a 90 s pause`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        unit.advance(90_000L)                                               // evaluate paused
        engine.onCarOff()
        engine.evaluate(diParsData(soc = 50), null)                         // screen still lit
        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().none { it.startsWith("service_start: new session reason=wake") })
        assertTrue(storedCarOff())
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(10_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=20s"))
    }

    @Test fun `service_start keeps the marker ready through a repeated ACC_OFF after the dark tick`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        engine.onCarOff()
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        engine.onCarOff()                                                   // repeated broadcast
        unit.advance(10_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // car on
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: ACC_OFF repeated, marker kept"))
    }

    @Test fun `service_start keeps the marker of an ACC_OFF 50 s into a session for the next start`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, _) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // t=0: session, fires
        unit.advance(50_000L)
        engine.onCarOff()                                                   // t=50
        unit.advance(1_000L)
        engine.evaluate(diParsData(soc = 50), null)                         // t=51, lit: marker kept
        assertTrue(storedCarOff())
        unit.screenOn = false
        repeat(3) {
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        // The process dies; the car is back at t=100 with the heartbeat of t=51 (49 s old).
        val (restarted, dao) = serviceStartEngine(bootId, HeadUnit(e0 + 100_000L, t0))
        restarted.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start car off marker expires when the screen stays on`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        engine.onCarOff()
        repeat(44) {                                                        // 132 s, screen on
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        assertTrue(engineLogs().contains("service_start: car off marker expired, screen stayed on"))
        assertFalse(storedCarOff())
        unit.screenOn = false                                               // 20 s blink mid-drive
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(10_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `onCarOff commits the marker while an evaluate holds the lock`() = runBlocking {
        val (engine, _) = serviceStartEngine(bootId, HeadUnit(e0, t0))
        engine.evaluateMutex.lock()
        try {
            // A blocking call would hang here: bound it on its own thread.
            val receiver = thread { engine.onCarOff() }
            receiver.join(1_000L)
            assertFalse(receiver.isAlive)
            assertTrue(storedCarOff())
        } finally {
            engine.evaluateMutex.unlock()
        }
    }

    /** Prefs that run [hook] once when [key] is read or put, in the middle of a tick. */
    private class HookedPrefs(
        private val real: SharedPreferences,
        var hook: (() -> Unit)?,
        private val key: String = "service_start_boot_id",
    ) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            if (key == this.key) hook?.also { hook = null }?.invoke()
            return real.getBoolean(key, defValue)
        }

        private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (key == this@HookedPrefs.key) hook?.also { hook = null }?.invoke()
                e.putString(key, value)
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                e.putLong(key, value)
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                e.putBoolean(key, value)
                return this
            }
        }
    }

    private fun contextWith(prefs: SharedPreferences) =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "automation") prefs else super.getSharedPreferences(name, mode)
        }

    private fun AutomationEngine.wire(unit: HeadUnit) {
        bootIdProvider = { bootId }
        elapsedMs = { unit.elapsed }
        nowMs = { unit.wall }
        interactiveProvider = { unit.screenOn }
    }

    @Test fun `ACC_OFF arriving during a tick keeps its marker and opens no session`() = runBlocking {
        val prefs = HookedPrefs(automationPrefs(), null)
        val (engine, dao) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
        val unit = HeadUnit(e0, t0)
        engine.wire(unit)
        // The receiver thread commits the marker after the tick read it, before the session write.
        prefs.hook = { engine.onCarOff() }

        engine.evaluate(diParsData(soc = 50), null)                         // reason=first, conflicts

        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        assertTrue(storedCarOff())
        assertTrue(engineLogs().contains("service_start: ACC_OFF arrived during the tick, marker kept"))
        assertTrue(engineLogs().contains("service_start: ACC_OFF arrived during the tick, session not opened"))
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(60_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=70s"))
        assertFalse(storedCarOff())
    }

    @Test fun `ACC_OFF during the rule scan keeps the later service_start rules from firing`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val first = rule(1, listOf(serviceStartTrigger()))
        val second = rule(2, listOf(serviceStartTrigger()))
        val (engine, dao) = serviceStartEngine(bootId, unit)
        coEvery { dao.getEnabled() } returns listOf(first, second)
        var carOffPending = true
        coEvery { dao.updateLastTriggered(1, any()) } answers {
            if (carOffPending) {                                            // car off as rule 1 fires
                carOffPending = false
                engine.onCarOff()
            }
        }

        engine.evaluate(diParsData(soc = 50), null)                         // reason=first

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        coVerify(exactly = 0) { dao.updateLastTriggered(2, any()) }
        assertTrue(storedCarOff())
        assertTrue(engineLogs().contains("service_start: ACC_OFF, window closed"))
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(60_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        coVerify(exactly = 1) { dao.updateLastTriggered(2, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=70s"))
    }

    @Test fun `ACC_OFF landing while a tick reads the marker waits for the dark screen`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val prefs = HookedPrefs(automationPrefs(), null, key = "service_start_car_off")
        val (engine, dao) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
        val unit = HeadUnit(e0 + 20_000L, t0)
        engine.wire(unit)
        engine.evaluate(diParsData(soc = 50), null)                         // restart mid-drive: silent
        unit.advance(3_000L)
        prefs.hook = { engine.onCarOff() }                                  // lands as the tick reads it

        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 0) { dao.updateLastTriggered(1, any()) }
        assertTrue(storedCarOff())
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(60_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=car_off gap=70s"))
    }

    @Test fun `a repeated ACC_OFF retries a marker commit that failed`() = runBlocking {
        val prefs = RecordingPrefs(automationPrefs())
        val (engine, _) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
        engine.wire(HeadUnit(e0, t0))
        prefs.failNextCommit = true

        engine.onCarOff()                                                   // disk write fails
        engine.onCarOff()                                                   // repeated broadcast
        engine.onCarOff()

        val markerCommits = prefs.committed.filter { "service_start_car_off" in it }
        assertEquals(2, markerCommits.size)
        val logs = engineLogs()
        assertTrue(logs.contains("service_start: car off marker commit failed"))
        assertTrue(logs.contains("service_start: ACC_OFF repeated, marker retried"))
        assertTrue(logs.contains("service_start: ACC_OFF repeated, marker kept"))
        assertTrue(storedCarOff())
    }

    @Test fun `service_start fires once per car start with ACC_OFF 15 minutes apart`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val unit = HeadUnit(e0 + 20_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.onCarOff()                                                   // previous drive ended
        unit.screenOn = false
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(20_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // first car start
        repeat(10) {
            unit.advance(3_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        engine.onCarOff()
        unit.screenOn = false
        repeat(30) {                                                        // car off 15 min
            unit.advance(30_000L)
            engine.evaluate(diParsData(soc = 50), null)
        }
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // second car start
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        assertEquals(2, engineLogs().count { it.startsWith("service_start: new session reason=car_off") })
    }

    @Test fun `service_start fires on a new boot even with a fresh heartbeat`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val dao = startProcess("boot-b", e0 + 20_000L)                      // quick reboot
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires on a reboot without boot id when elapsed goes backwards`() = runBlocking {
        storeServiceStartState("", lastSeenElapsed = e0)
        val dao = startProcess("", 40_000L)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not fire on a restart without boot id and a fresh heartbeat`() = runBlocking {
        storeServiceStartState("", lastSeenElapsed = e0)
        val dao = startProcess("", e0 + 20_000L)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start re-arms when the same process survives a suspend with the screen off`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            engine.evaluate(diParsData(soc = 50), null)                     // fires, rule consumed
            unit.advance(60_000L)
            engine.evaluate(diParsData(soc = 50), null)                     // window over
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }

            unit.advance(3_600_000L)                                        // 1 h suspended, no ticks
            engine.evaluate(diParsData(soc = 50), null)
            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        }

    @Test fun `service_start does not re-arm when telemetry pauses 40 s with the car on`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        unit.advance(40_000L)                                               // 40 s without data
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires once over five minutes of 30 s ticks`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        repeat(11) {
            engine.evaluate(diParsData(soc = 50), null)
            unit.advance(30_000L)
        }
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start ignores a wall clock correction between processes`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        // NTP/GPS moved the wall clock a minute; same boot, heartbeat 20 s old by elapsed.
        val dao = startProcess(bootId, e0 + 20_000L, wall = t0 + 60_000L)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start window ignores a wall clock correction while it is open`() = runBlocking {
        val warm = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")), logic = "AND")
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit, warm)
        engine.evaluate(diParsData(exteriorTemp = null), null)              // window armed, data cold
        unit.elapsed += 5_000L
        unit.wall += 3_600_000L                                             // GPS moved the clock an hour
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start window closes when the screen goes off before the rule matched`() = runBlocking {
        val warm = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")), logic = "AND")
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit, warm)
        engine.evaluate(diParsData(exteriorTemp = null), null)              // window armed, data cold
        unit.advance(5_000L)
        unit.screenOn = false                                               // car off inside the window
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        unit.advance(5_000L)
        unit.screenOn = true                                                // back on, same session
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
        assertTrue(engineLogs().contains("service_start: screen off, window closed"))
    }

    /** Keys of every committed and every applied edit of the prefs it wraps. */
    private class RecordingPrefs(private val real: SharedPreferences) : SharedPreferences by real {
        val committed = mutableListOf<Set<String>>()
        val applied = mutableListOf<Set<String>>()
        // The next commit fails on disk: the edit reaches memory only, as SharedPreferences do.
        var failNextCommit = false

        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
            private val keys = mutableSetOf<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                keys += key
                e.putString(key, value)
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                keys += key
                e.putLong(key, value)
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                keys += key
                e.putBoolean(key, value)
                return this
            }
            override fun commit(): Boolean {
                committed += keys.toSet()
                if (!failNextCommit) return e.commit()
                failNextCommit = false
                e.apply()
                return false
            }
            override fun apply() { applied += keys.toSet(); e.apply() }
        }
    }

    @Test fun `service_start new session stores boot id and heartbeat in one synchronous write`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val prefs = RecordingPrefs(automationPrefs())
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "automation") prefs else super.getSharedPreferences(name, mode)
        }
        val r = rule(1, listOf(serviceStartTrigger()))
        val (engine, dao) = setup(context) { listOf(r) }
        engine.bootIdProvider = { bootId }
        engine.elapsedMs = { e0 + 900_000L }                                // car off 15 min, process killed

        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        // A process killed right after the fire must find the new heartbeat on disk.
        val session = setOf("service_start_boot_id", "service_start_last_seen_elapsed", "service_start_car_off")
        assertTrue(prefs.committed.toString(), prefs.committed.any { it.containsAll(session) })
        assertTrue(prefs.applied.toString(), prefs.applied.none { "service_start_last_seen_elapsed" in it })
        assertEquals(e0 + 900_000L, storedHeartbeat())
    }

    @Test fun `service_start dump line shows the window, consumption, heartbeat and boot id`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, _) = serviceStartEngine(bootId, unit)
        assertEquals(
            "service_start: interactive=true window=not armed consumed=0 last_heartbeat_age=- boot_id=- car_off=- " +
                "car_off_lit=- heartbeat=none",
            engine.serviceStartDumpLine(),
        )
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        val until = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(t0 + AutomationEngine.SERVICE_START_WINDOW_MS))
        assertEquals(
            "service_start: interactive=true window=armed until $until consumed=1 last_heartbeat_age=0s boot_id=boot-a " +
                "car_off=- car_off_lit=- heartbeat=none",
            engine.serviceStartDumpLine(),
        )
        unit.advance(45_000L)
        engine.onCarOff()                                                   // screen still lit
        unit.advance(5_000L)
        unit.screenOn = false
        assertEquals(
            "service_start: interactive=false window=spent consumed=1 last_heartbeat_age=50s boot_id=boot-a " +
                "car_off=pending car_off_lit=5s heartbeat=none",
            engine.serviceStartDumpLine(),
        )
    }

    // The heartbeat timer: a lit screen keeps the session alive while evaluate() is stalled.
    // [ms] pass on the head unit and on the timer's clock together, one second at a time.
    private fun TestScope.pass(unit: HeadUnit, ms: Long) {
        repeat((ms / 1_000L).toInt()) {
            unit.advance(1_000L)
            testScheduler.advanceTimeBy(1_000L)
            testScheduler.runCurrent()
        }
    }

    @Test fun `service_start heartbeat timer keeps the session through a 120 s evaluate stall`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        timer.pass(unit, 120_000L)                                          // no tick, screen on
        val beaten = storedHeartbeat()

        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertFalse(engineLogs().any { it.startsWith("service_start: new session reason=wake") })
        assertEquals(e0 + 120_000L, beaten)
        assertTrue(engineLogs().contains("service_start: heartbeat timer started period=30s"))
        engine.stopServiceStartHeartbeat()
    }

    @Test fun `service_start heartbeat timer writes nothing with the screen dark`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        val heartbeat = storedHeartbeat()

        unit.screenOn = false                                               // car off, no tick
        timer.pass(unit, 120_000L)
        assertEquals(heartbeat, storedHeartbeat())
        unit.screenOn = true                                                // car on, a beat before the tick
        timer.pass(unit, 30_000L)
        assertEquals(heartbeat, storedHeartbeat())

        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=wake gap=150s"))
        engine.stopServiceStartHeartbeat()
    }

    @Test fun `service_start heartbeat timer waits for the first tick of a restarted process`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)                // car off 15 min, process killed
        val unit = HeadUnit(e0 + 900_000L, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        timer.pass(unit, 60_000L)                                           // slow start, no tick yet
        assertEquals(e0, storedHeartbeat())

        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: new session reason=wake gap=960s"))
        engine.stopServiceStartHeartbeat()
    }

    @Test fun `service_start heartbeat timer stops on teardown`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, _) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        engine.evaluate(diParsData(soc = 50), null)
        assertTrue(engine.serviceStartDumpLine().endsWith(" heartbeat=timer"))
        timer.pass(unit, 30_000L)
        assertEquals(e0 + 30_000L, storedHeartbeat())

        engine.stopServiceStartHeartbeat()
        timer.pass(unit, 120_000L)
        assertEquals(e0 + 30_000L, storedHeartbeat())
        assertTrue(engine.serviceStartDumpLine().endsWith(" heartbeat=none"))
    }

    // The next elapsed read of evaluate() lets the due timer beat run first, 1 ms ahead of the
    // elapsed the tick gets: the beat thread won the race to the session state.
    private fun beatAheadOfNextTick(engine: AutomationEngine, unit: HeadUnit, timer: TestScope) {
        var armed = true
        engine.elapsedMs = {
            if (armed) {
                armed = false
                timer.pass(unit, 1_000L)                                    // the beat reads unit.elapsed
                unit.elapsed - 1L
            } else {
                unit.elapsed
            }
        }
    }

    @Test fun `service_start heartbeat beat 1 ms ahead of the tick opens no session`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        timer.pass(unit, 29_000L)
        beatAheadOfNextTick(engine, unit, timer)
        engine.evaluate(diParsData(soc = 50), null)                         // tick at e0 + 29 999

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertFalse(engineLogs().any { it.startsWith("service_start: new session reason=elapsed_back") })
        assertEquals(e0 + 30_000L, storedHeartbeat())
        engine.stopServiceStartHeartbeat()
    }

    @Test fun `service_start session opened by a tick older than a beat keeps the newer heartbeat`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val (engine, dao) = serviceStartEngine(bootId, unit)
        val timer = TestScope()
        engine.startServiceStartHeartbeat(timer)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        timer.pass(unit, 20_000L)
        engine.onCarOff()                                                   // screen still lit
        unit.screenOn = false
        engine.evaluate(diParsData(soc = 50), null)                         // dark tick, marker ready
        unit.screenOn = true
        timer.pass(unit, 69_000L)                                           // beats at 30 s and 60 s
        beatAheadOfNextTick(engine, unit, timer)
        engine.evaluate(diParsData(soc = 50), null)                         // tick at e0 + 89 999

        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().any { it.startsWith("service_start: new session reason=car_off") })
        assertEquals(e0 + 90_000L, storedHeartbeat())
        engine.stopServiceStartHeartbeat()
    }

    @Test fun `service_start dark heartbeat beat confirms the ACC_OFF marker while evaluate is stalled`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            engine.evaluate(diParsData(soc = 50), null)                     // fires, rule consumed
            timer.pass(unit, 20_000L)
            engine.onCarOff()                                               // screen still lit, no tick after
            unit.screenOn = false
            timer.pass(unit, 20_000L)                                       // one dark beat at 30 s
            unit.screenOn = true                                            // car on again
            timer.pass(unit, 120_000L)                                      // lit beats keep the heartbeat fresh
            engine.evaluate(diParsData(soc = 50), null)                     // evaluate resumes

            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
            assertTrue(engineLogs().contains("service_start: screen off seen by heartbeat timer"))
            assertTrue(engineLogs().any { it.startsWith("service_start: new session reason=car_off") })
            engine.stopServiceStartHeartbeat()
        }

    // Real threads below: every wait is bounded, a hang fails the test instead of blocking it.
    private fun CountDownLatch.awaitOrFail(what: String) = assertTrue(what, await(5, TimeUnit.SECONDS))

    private fun Thread.joinOrFail(what: String) {
        join(5_000L)
        assertFalse(what, isAlive)
    }

    private fun Thread.awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (state != Thread.State.BLOCKED) {
            assertTrue("thread never blocked on the lock", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    /**
     * Prefs that record every stored heartbeat in the order it is put, and hold the next commit or
     * apply of an edit touching [gateKey] (with the writer's locks) until [release].
     */
    private class GatedPrefs(private val real: SharedPreferences) : SharedPreferences by real {
        val heartbeats: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        @Volatile var gateKey: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
            private val keys = mutableSetOf<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                keys += key
                e.putString(key, value)
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                keys += key
                if (key == "service_start_last_seen_elapsed") heartbeats += value
                e.putLong(key, value)
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                keys += key
                e.putBoolean(key, value)
                return this
            }
            override fun commit(): Boolean {
                gate()
                return e.commit()
            }
            override fun apply() {
                gate()
                e.apply()
            }
            private fun gate() {
                val key = gateKey ?: return
                if (key !in keys) return
                gateKey = null
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun `service_start beat and tick entering the lock together open no elapsed_back session and keep the heartbeat monotonic`() =
        runBlocking {
            val prefs = GatedPrefs(automationPrefs())
            val (engine, dao) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
            val clock = AtomicLong(e0)
            engine.bootIdProvider = { bootId }
            engine.nowMs = { t0 }
            engine.interactiveProvider = { true }
            engine.elapsedMs = { clock.incrementAndGet() }                  // every reading 1 ms after the last
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            engine.evaluate(diParsData(soc = 50), null)                     // fires, rule consumed

            // Each round lets a tick and a beat go at once, 10 s after the previous round.
            val rounds = 20
            val barrier = CyclicBarrier(2) { clock.addAndGet(10_000L) }
            val failures = ConcurrentLinkedQueue<Throwable>()
            fun racer(step: () -> Unit) = thread {
                try {
                    repeat(rounds) {
                        barrier.await(5, TimeUnit.SECONDS)
                        step()
                    }
                } catch (e: Throwable) {
                    failures += e
                }
            }
            val ticks = racer { runBlocking { engine.evaluate(diParsData(soc = 50), null) } }
            val beats = racer {
                timer.testScheduler.advanceTimeBy(30_000L)
                timer.testScheduler.runCurrent()
            }
            ticks.joinOrFail("ticks did not finish")
            beats.joinOrFail("beats did not finish")
            engine.stopServiceStartHeartbeat()

            assertTrue(failures.toString(), failures.isEmpty())
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertEquals(1, engineLogs().count { it.startsWith("service_start: new session") })
            assertFalse(engineLogs().any { it.startsWith("service_start: new session reason=elapsed_back") })
            val heartbeats = prefs.heartbeats.toList()
            assertTrue(heartbeats.toString(), heartbeats.size > rounds)     // every beat wrote
            assertEquals(heartbeats.sorted(), heartbeats)
            assertEquals(heartbeats.last(), storedHeartbeat())
        }

    @Test fun `service_start stopHeartbeat after a beat passed its generation check lets that write finish and returns at once`() =
        runBlocking {
            val prefs = GatedPrefs(automationPrefs())
            val (engine, _) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
            val unit = HeadUnit(e0, t0)
            engine.wire(unit)
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            engine.evaluate(diParsData(soc = 50), null)
            timer.pass(unit, 29_000L)
            prefs.gateKey = "service_start_last_seen_elapsed"               // the beat's write hangs in the lock
            val beat = thread { timer.pass(unit, 1_000L) }
            prefs.entered.awaitOrFail("the beat never wrote")

            val stop = thread { engine.stopServiceStartHeartbeat() }
            stop.joinOrFail("stop waited for the beat's write")
            assertEquals(e0, storedHeartbeat())                             // the write is still held
            prefs.release.countDown()
            beat.joinOrFail("the beat did not finish")

            assertEquals(e0 + 30_000L, storedHeartbeat())                   // its one write went through
            timer.pass(unit, 120_000L)
            assertEquals(e0 + 30_000L, storedHeartbeat())                   // and no later one
        }

    @Test fun `service_start beat waiting for the lock when its timer stops writes nothing and stop skips the tick commit`() =
        runBlocking {
            val prefs = GatedPrefs(automationPrefs())
            val (engine, _) = setup(contextWith(prefs)) { listOf(rule(1, listOf(serviceStartTrigger()))) }
            val unit = HeadUnit(e0, t0)
            engine.wire(unit)
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            prefs.gateKey = "service_start_boot_id"                         // the session commit hangs in the lock
            val tick = thread { runBlocking { engine.evaluate(diParsData(soc = 50), null) } }
            prefs.entered.awaitOrFail("the tick never committed its session")
            val beat = thread { timer.pass(unit, 30_000L) }
            beat.awaitBlocked()                                             // the due beat waits for the tick

            val stop = thread { engine.stopServiceStartHeartbeat() }
            stop.joinOrFail("stop waited for the tick's commit")
            prefs.release.countDown()
            tick.joinOrFail("the tick did not finish")
            beat.joinOrFail("the beat did not finish")

            assertEquals(e0, storedHeartbeat())                             // the session's heartbeat only
            timer.pass(unit, 120_000L)
            assertEquals(e0, storedHeartbeat())
        }

    @Test fun `service_start dark tick read before an ACC_OFF leaves its marker waiting for the dark screen`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            engine.evaluate(diParsData(soc = 50), null)                     // t=0: session, fires
            unit.advance(10_000L)
            val observed = CountDownLatch(1)
            val resume = CountDownLatch(1)
            engine.interactiveProvider = {                                  // t=10: dark, then the tick stalls
                observed.countDown()
                resume.await(5, TimeUnit.SECONDS)
                false
            }
            val tick = thread { runBlocking { engine.evaluate(diParsData(soc = 50), null) } }
            observed.awaitOrFail("the tick never read the screen")
            unit.advance(1_000L)
            engine.onCarOff()                                               // t=11: receiver, screen still lit
            resume.countDown()
            tick.joinOrFail("the tick did not finish")
            engine.interactiveProvider = { unit.screenOn }

            assertTrue(engine.serviceStartDumpLine().contains(" car_off=pending car_off_lit=0s "))
            unit.advance(1_000L)
            engine.evaluate(diParsData(soc = 50), null)                     // t=12, lit: waits for dark
            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertTrue(storedCarOff())
            assertFalse(engineLogs().any { it.startsWith("service_start: car off marker within session") })
            unit.screenOn = false
            unit.advance(10_000L)
            engine.evaluate(diParsData(soc = 50), null)
            unit.advance(60_000L)
            unit.screenOn = true
            engine.evaluate(diParsData(soc = 50), null)                     // next car start
            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
            assertTrue(engineLogs().any { it.startsWith("service_start: new session reason=car_off") })
        }

    @Test fun `service_start tick reads the clock and the screen inside the lock a timer beat waits for`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val (engine, dao) = serviceStartEngine(bootId, unit)
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            engine.evaluate(diParsData(soc = 50), null)                     // t=0: session, fires
            timer.pass(unit, 29_000L)
            val reading = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val tickThread = AtomicReference<Thread>()
            engine.interactiveProvider = {
                if (Thread.currentThread() == tickThread.get()) {           // the tick stalls on its reading
                    reading.countDown()
                    resume.await(5, TimeUnit.SECONDS)
                }
                unit.screenOn
            }
            val tick = thread(start = false) { runBlocking { engine.evaluate(diParsData(soc = 50), null) } }
            tickThread.set(tick)
            tick.start()
            reading.awaitOrFail("the tick never read the screen")
            val beat = thread { timer.pass(unit, 1_000L) }
            beat.join(1_000L)                                               // no beat between reading and judging
            assertTrue("a beat ran while the tick was reading", beat.isAlive)
            assertEquals(e0, storedHeartbeat())
            resume.countDown()
            tick.joinOrFail("the tick did not finish")
            beat.joinOrFail("the beat did not finish")
            engine.stopServiceStartHeartbeat()

            coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
            assertEquals(e0 + 30_000L, storedHeartbeat())
        }

    @Test fun `service_start evaluate stalled from 50 s to 160 s through ACC_OFF and a dark beat opens one car_off session`() =
        runBlocking {
            val unit = HeadUnit(e0, t0)
            val stalled = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val stall = AtomicBoolean(false)
            val r = rule(1, listOf(serviceStartTrigger()))
            val (engine, dao) = setup {
                if (stall.compareAndSet(true, false)) {                    // the rule read hangs
                    stalled.countDown()
                    resume.await(5, TimeUnit.SECONDS)
                }
                listOf(r)
            }
            engine.wire(unit)
            val timer = TestScope()
            engine.startServiceStartHeartbeat(timer)
            engine.evaluate(diParsData(soc = 50), null)                     // t=0: session, fires
            timer.pass(unit, 50_000L)                                       // beat at 30
            stall.set(true)
            val evaluate = thread { runBlocking { engine.evaluate(diParsData(soc = 50), null) } }
            stalled.awaitOrFail("evaluate never reached the rules")         // t=50: evaluate stalls

            timer.pass(unit, 30_000L)                                       // lit beat at 60
            engine.onCarOff()                                               // t=80
            unit.screenOn = false
            timer.pass(unit, 20_000L)                                       // dark beat at 90
            unit.screenOn = true                                            // t=100: car on
            timer.pass(unit, 60_000L)                                       // lit beats at 120 and 150
            resume.countDown()                                              // t=160: evaluate resumes
            evaluate.joinOrFail("evaluate did not finish")
            engine.stopServiceStartHeartbeat()

            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
            assertTrue(engineLogs().contains("service_start: screen off seen by heartbeat timer"))
            assertFalse(engineLogs().any { it.startsWith("service_start: car off marker within session") })
            assertEquals(1, engineLogs().count { it.startsWith("service_start: new session reason=car_off") })
            assertFalse(storedCarOff())
            repeat(3) {
                unit.advance(3_000L)
                engine.evaluate(diParsData(soc = 50), null)
            }
            coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        }

    @Test fun `ACC_OFF while the first service_start rule marks its fire skips that rule's own action`() = runBlocking {
        val unit = HeadUnit(e0, t0)
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val (engine, dao) = setup(dispatcher = dispatcher) { listOf(rule(1, listOf(serviceStartTrigger()))) }
        engine.wire(unit)
        var carOffPending = true
        coEvery { dao.updateLastTriggered(1, any()) } answers {
            if (carOffPending) {                                            // car off as rule 1 fires
                carOffPending = false
                engine.onCarOff()
            }
        }

        engine.evaluate(diParsData(soc = 50), null)                         // reason=first

        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertTrue(engineLogs().contains("service_start: window closed before dispatch"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
        unit.screenOn = false
        unit.advance(10_000L)
        engine.evaluate(diParsData(soc = 50), null)
        unit.advance(60_000L)
        unit.screenOn = true
        engine.evaluate(diParsData(soc = 50), null)                         // next car start
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
        coVerify(timeout = 5_000L, exactly = 1) { dispatcher.dispatch(any(), any()) }
        assertEquals(1, engineLogs().count { it == "service_start: window closed before dispatch" })
    }
}
