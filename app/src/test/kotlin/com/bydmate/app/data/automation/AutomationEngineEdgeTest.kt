package com.bydmate.app.data.automation

import android.content.Context
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun setup(rulesProvider: () -> List<RuleEntity>): Pair<AutomationEngine, RuleDao> {
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
            context = ApplicationProvider.getApplicationContext<Context>(),
        )
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

    // #177: WorkManager restarts a killed process on the same DiLink boot; the new
    // AutomationEngine must not reopen the service_start window mid-session. A real suspend
    // (elapsedRealtime ran ahead of uptimeMillis) or a reboot (new boot id, elapsed going
    // backwards) is a real car start and must fire, also when the process itself survived
    // the sleep. A telemetry pause with the car on (both clocks run) is not. Wall-clock
    // time takes no part.
    private val t0 = 1_700_000_000_000L
    private val bootId = "boot-a"
    private val e0 = 3_600_000L  // elapsedRealtime of the stored heartbeat
    private val u0 = 3_000_000L  // uptimeMillis of the stored heartbeat

    private fun storeServiceStartState(bootId: String, lastSeenElapsed: Long, lastSeenUptime: Long = u0) {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("automation", Context.MODE_PRIVATE).edit()
            .putString("service_start_boot_id", bootId)
            .putLong("service_start_last_seen_elapsed", lastSeenElapsed)
            .putLong("service_start_last_seen_uptime", lastSeenUptime)
            .commit()
    }

    private class Clock(var elapsed: Long, var uptime: Long, var wall: Long) {
        fun awake(ms: Long) { elapsed += ms; uptime += ms; wall += ms }
        fun asleep(ms: Long) { elapsed += ms; wall += ms }
    }

    private fun serviceStartEngine(bootId: String, clock: Clock): Pair<AutomationEngine, RuleDao> {
        val r = rule(1, listOf(serviceStartTrigger()))
        val (engine, dao) = setup { listOf(r) }
        engine.bootIdProvider = { bootId }
        engine.elapsedMs = { clock.elapsed }
        engine.uptimeMs = { clock.uptime }
        engine.nowMs = { clock.wall }
        return engine to dao
    }

    private suspend fun startProcess(
        bootId: String,
        elapsed: Long,
        uptime: Long = u0 + (elapsed - e0),
        wall: Long = t0,
    ): RuleDao {
        val (engine, dao) = serviceStartEngine(bootId, Clock(elapsed, uptime, wall))
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
    }

    @Test fun `service_start fires after a sleep on the same boot`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        // 10 min by elapsed, 20 s of it awake: slept ~9.7 min.
        val dao = startProcess(bootId, e0 + 600_000L, uptime = u0 + 20_000L)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not fire after a restart that follows a telemetry pause`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        // 10 min without evaluate() but the head unit stayed awake the whole time.
        val dao = startProcess(bootId, e0 + 600_000L, uptime = u0 + 600_000L)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start fires on a new boot even with a fresh heartbeat`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        val dao = startProcess("boot-b", e0 + 20_000L)                      // quick reboot
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires on a reboot without boot id when elapsed goes backwards`() = runBlocking {
        storeServiceStartState("", lastSeenElapsed = e0)
        val dao = startProcess("", 40_000L, uptime = 40_000L)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not fire on a restart without boot id and a fresh heartbeat`() = runBlocking {
        storeServiceStartState("", lastSeenElapsed = e0)
        val dao = startProcess("", e0 + 20_000L, uptime = u0 + 20_000L)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
    }

    @Test fun `service_start re-arms when the same process survives a sleep`() = runBlocking {
        val clock = Clock(e0, u0, t0)
        val (engine, dao) = serviceStartEngine(bootId, clock)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        clock.awake(60_000L)
        engine.evaluate(diParsData(soc = 50), null)                         // window over
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }

        clock.asleep(3_600_000L)                                            // head unit slept 1 h
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start does not re-arm when telemetry pauses with the car on`() = runBlocking {
        val clock = Clock(e0, u0, t0)
        val (engine, dao) = serviceStartEngine(bootId, clock)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        clock.awake(600_000L)                                               // 10 min without data
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start re-arms in the same process after a 9 minute sleep`() = runBlocking {
        val clock = Clock(e0, u0, t0)
        val (engine, dao) = serviceStartEngine(bootId, clock)
        engine.evaluate(diParsData(soc = 50), null)                         // fires, rule consumed
        clock.awake(60_000L)
        clock.asleep(540_000L)                                              // elapsed +10 min, uptime +1 min
        engine.evaluate(diParsData(soc = 50), null)
        coVerify(exactly = 2) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start fires once over five minutes of 30 s ticks`() = runBlocking {
        val clock = Clock(e0, u0, t0)
        val (engine, dao) = serviceStartEngine(bootId, clock)
        repeat(11) {
            engine.evaluate(diParsData(soc = 50), null)
            clock.awake(30_000L)
        }
        coVerify(exactly = 1) { dao.updateLastTriggered(1, any()) }
    }

    @Test fun `service_start ignores a wall clock correction between processes`() = runBlocking {
        storeServiceStartState(bootId, lastSeenElapsed = e0)
        // NTP/GPS moved the wall clock a minute; same boot, heartbeat 20 s old by elapsed.
        val dao = startProcess(bootId, e0 + 20_000L, wall = t0 + 60_000L)
        coVerify(exactly = 0) { dao.updateLastTriggered(any(), any()) }
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
