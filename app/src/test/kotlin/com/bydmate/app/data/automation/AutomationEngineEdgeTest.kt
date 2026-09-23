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
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Edge-semantics regressions from issue #51: rules firing right after the user
 * saves an edit, deferred fires after cooldown / park gating, and service_start
 * racing cold-start nulls in the very first poll tick.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver
// (RECEIVER_NOT_EXPORTED permission fallback) thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineEdgeTest {

    private fun paramTrigger(param: String, op: String, value: String) = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param
    )

    private fun serviceStartTrigger() = TriggerDef(
        param = "", chineseName = "", operator = "", value = "",
        displayName = "start", kind = "service_start"
    )

    private fun rule(
        id: Long,
        triggers: List<TriggerDef>,
        logic: String = "AND",
        cooldown: Int = 0,
        requirePark: Boolean = false,
        lastTriggeredAt: Long? = null,
    ) = RuleEntity(
        id = id, name = "r$id",
        triggerLogic = logic,
        triggers = TriggerDef.listToJson(triggers),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = cooldown,
        requirePark = requirePark,
        lastTriggeredAt = lastTriggeredAt,
    )

    private fun setup(
        context: Context = ApplicationProvider.getApplicationContext(),
        rulesProvider: () -> List<RuleEntity>,
    ): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } answers { rulesProvider() }
        }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
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

    @Test fun `editing rule triggers reseeds edge state instead of firing`() = runBlocking {
        var current = rule(1, listOf(paramTrigger("Speed", ">", "40")))
        val (engine, ruleDao) = setup { listOf(current) }

        engine.evaluate(diParsData(speed = 10), null)  // seed: false under OLD triggers

        // User edits the rule: condition is now instantly true. Must reseed, not fire.
        current = current.copy(triggers = TriggerDef.listToJson(listOf(paramTrigger("ExtTemp", ">", "20"))))
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }

        // A genuine front after the reseed still fires.
        engine.evaluate(diParsData(exteriorTemp = 10), null)
        engine.evaluate(diParsData(exteriorTemp = 25), null)
        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `front while park-gated is consumed, not deferred until parked`() = runBlocking {
        val r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23")), requirePark = true)
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(gear = 1, exteriorTemp = 10), null)  // parked, cold: seed false
        engine.evaluate(diParsData(gear = 4, exteriorTemp = 25), null)  // front happens while driving
        engine.evaluate(diParsData(gear = 1, exteriorTemp = 25), null)  // parked again, still hot

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `front during cooldown is consumed, not deferred until cooldown expires`() = runBlocking {
        var r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23")), cooldown = 600)
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(exteriorTemp = 10), null)  // seed false, no cooldown yet

        r = r.copy(lastTriggeredAt = System.currentTimeMillis())  // cooldown engaged
        engine.evaluate(diParsData(exteriorTemp = 25), null)      // front during cooldown

        r = r.copy(lastTriggeredAt = System.currentTimeMillis() - 700_000)  // cooldown over
        engine.evaluate(diParsData(exteriorTemp = 25), null)      // still true, no NEW front

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start AND-rule fires when param warms up on a later tick`() = runBlocking {
        val r = rule(1, listOf(serviceStartTrigger(), paramTrigger("ExtTemp", ">", "22")), logic = "AND")
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(exteriorTemp = null), null)  // first tick: data still cold
        engine.evaluate(diParsData(exteriorTemp = 25), null)    // within start window: must fire

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires exactly once per rule`() = runBlocking {
        val r = rule(1, listOf(serviceStartTrigger()))
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(soc = 50), null)
        engine.evaluate(diParsData(soc = 50), null)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
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
        repeat(4) {
            engine.evaluate(diParsData(soc = 50), null)
            unit.advance(3_000L)
        }
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
        assertEquals(1, engineLogs().count { it == "service_start: car off marker within session, ignored gap=3s" })
        assertFalse(storedCarOff())
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
            override fun commit(): Boolean { committed += keys.toSet(); return e.commit() }
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
            "service_start: interactive=true window=not armed consumed=0 last_heartbeat_age=- boot_id=- car_off=-",
            engine.serviceStartDumpLine(),
        )
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        val until = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(t0 + AutomationEngine.SERVICE_START_WINDOW_MS))
        assertEquals(
            "service_start: interactive=true window=armed until $until consumed=1 last_heartbeat_age=0s boot_id=boot-a " +
                "car_off=-",
            engine.serviceStartDumpLine(),
        )
        unit.advance(45_000L)
        unit.screenOn = false
        engine.onCarOff()
        assertEquals(
            "service_start: interactive=false window=spent consumed=1 last_heartbeat_age=45s boot_id=boot-a " +
                "car_off=pending",
            engine.serviceStartDumpLine(),
        )
    }

    @Test fun `turn signal edge fires once on off to left transition`() = runBlocking {
        val r = rule(1, listOf(paramTrigger("TurnSignal", "==", "2")))
        val (engine, ruleDao) = setup { listOf(r) }

        engine.evaluate(diParsData(turnSignal = 1), null)  // seed: off
        engine.evaluate(diParsData(turnSignal = 2), null)  // front: left
        engine.evaluate(diParsData(turnSignal = 2), null)  // held on — no second front

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `rule with playSound plays chime once on fire`() = runBlocking {
        mockkObject(OverlayNotificationManager)
        try {
            every { OverlayNotificationManager.playNotificationSound(any()) } just Runs
            val r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23"))).copy(playSound = true)
            val (engine, _) = setup { listOf(r) }
            engine.evaluate(diParsData(exteriorTemp = 10), null)  // seed false
            engine.evaluate(diParsData(exteriorTemp = 25), null)  // front -> fire
            verify(timeout = 2000, exactly = 1) { OverlayNotificationManager.playNotificationSound(any()) }
        } finally {
            unmockkObject(OverlayNotificationManager)
        }
    }

    @Test fun `rule without playSound does not play chime`() = runBlocking {
        mockkObject(OverlayNotificationManager)
        try {
            every { OverlayNotificationManager.playNotificationSound(any()) } just Runs
            val r = rule(1, listOf(paramTrigger("ExtTemp", ">", "23")))
            val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
            val ruleDao = mockk<RuleDao>(relaxed = true) {
                coEvery { getEnabled() } answers { listOf(r) }
            }
            val engine = AutomationEngine(
                ruleDao = ruleDao,
                ruleLogDao = ruleLogDao,
                actionDispatcher = mockk(relaxed = true),
                placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
                networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                    every { lastAvailableAt } returns 0L
                    every { probePending } returns false
                },
                context = ApplicationProvider.getApplicationContext<Context>(),
                appStrings = com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()),
            )
            engine.evaluate(diParsData(exteriorTemp = 10), null)
            engine.evaluate(diParsData(exteriorTemp = 25), null)
            // Wait for the async executeAndLog to complete (its last step is the log insert),
            // then assert the chime never played.
            coVerify(timeout = 2000, exactly = 1) { ruleLogDao.insert(any()) }
            verify(exactly = 0) { OverlayNotificationManager.playNotificationSound(any()) }
        } finally {
            unmockkObject(OverlayNotificationManager)
        }
    }
}
