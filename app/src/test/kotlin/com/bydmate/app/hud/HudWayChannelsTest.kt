package com.bydmate.app.hud

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.navdata.NavGuidanceHub
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ways 2 and 3 on top of the frames and the raised status: the CAN guidance fields written on
 * change during a route and blanked with distance 0 at its end; in way 3 also the LAUNCHER_MAP_CN
 * family on its six services. Driven tick by tick.
 */
@RunWith(RobolectricTestRunner::class)
class HudWayChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)

    @get:Rule val trace = TraceRecorder()

    private val calls = mutableListOf<String>()
    private val helper: HelperClient = mockk(relaxed = true)
    private val gateway: HudSomeIpBridge = mockk(relaxed = true)
    private var writeRc: Int? = 0
    /** The road buffer's rc; the set writes' by default. */
    private var bufRc: () -> Int? = { writeRc }
    private var stopRc: (Long) -> Int = { 0 }
    /** [flaky] prefs: a commit keeps the value in memory but reports the disk write failed. */
    private var saveFails = true
    private var snapshot = NavGuidanceHub.Snapshot()
    private val lines = mutableListOf<String>()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        coEvery { helper.writeStatus(any(), any(), any(), any()) } answers {
            calls += "set ${arg<Int>(1)}=${arg<Int>(2)}"
            writeRc
        }
        coEvery { helper.writeBufferStatus(any(), any(), any()) } answers {
            calls += "buf ${arg<Int>(1)}=${String(arg<ByteArray>(2), Charsets.UTF_16LE)}"
            bufRc()
        }
        every { gateway.startService(any()) } answers { calls += "start 0x${firstArg<Long>().toString(16)}"; 0 }
        every { gateway.stopService(any()) } answers { calls += "stop 0x${firstArg<Long>().toString(16)}"; stopRc(firstArg()) }
        every { gateway.fireEvent(any(), any()) } answers { calls += "fire 0x${firstArg<Long>().toString(16)}"; 0 }
    }

    /** Prefs whose commit fails while [saveFails], as a full disk does: the value is in memory only. */
    private val flaky: SharedPreferences = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val real = prefs.edit()
            return object : SharedPreferences.Editor by real {
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { real.putBoolean(key, value); return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { real.putLong(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                override fun commit(): Boolean = if (saveFails) { real.apply(); false } else real.commit()
            }
        }
    }

    private fun channels(way: Int, prefs: SharedPreferences = this.prefs) = HudWayChannels(
        way = way, can = HudCanChannel(helper), gateway = gateway, prefs = prefs,
        position = { HudLauncherMapCnFrames.Position(53.9, 27.56) },
    ).apply {
        snapshot = { this@HudWayChannelsTest.snapshot }
        random = Random(3)
        nowMs = { 1_700_000_000_000L }
        log = { lines += it }
    }

    private fun route(gaode: Int = 2, dist: Int = 300, road: String = "Main St", total: Int = 12_000, eta: Int = 800) {
        snapshot = NavGuidanceHub.Snapshot(
            active = true, maneuverGaode = gaode, distanceMeters = dist, road = road, totalDistMeters = total, etaSeconds = eta,
        )
    }

    private val icon = HudCanChannel.FID_TURN_KIND
    private val ahead = HudCanChannel.FID_GUIDE_INFO_ROAD_AHEAD
    private val dist = HudCanChannel.FID_TURN_DISTANCE_M
    private val road = HudCanChannel.FID_NEXT_PATHNAME
    private val clearCalls get() = listOf("set $icon=0", "set $ahead=0", "set $dist=0", "buf $road= ")

    // --- way 2: the CAN fields ---

    @Test fun `way 2 writes the maneuver, distance and road on the first tick and nothing while they hold`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=300", "buf $road=Main St"), calls)
        calls.clear()
        c.tick(active = true)
        c.tick(active = true)
        assertTrue(calls.isEmpty())
        assertTrue(prefs.getBoolean(HudWayChannels.KEY_CAN_LEFT, false))
    }

    @Test fun `way 2 rewrites the guidance fields on a distance change and the road only when it changes`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        calls.clear()
        route(dist = 250)
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=250"), calls)
        calls.clear()
        route(dist = 250, road = "Side Rd")
        c.tick(active = true)
        assertEquals(listOf("buf $road=Side Rd"), calls)
        calls.clear()
        route(gaode = 1, dist = 250, road = "Side Rd")
        c.tick(active = true)
        assertEquals(listOf("set $icon=1", "set $ahead=1", "set $dist=250"), calls)
    }

    @Test fun `way 2 starts nothing on the gateway`() = runTest {
        val c = channels(2)
        route()
        repeat(5) { c.tick(active = true) }
        c.tick(active = false)
        assertTrue(calls.none { it.startsWith("start") || it.startsWith("fire") || it.startsWith("stop") })
    }

    @Test fun `the route end blanks the CAN fields with distance 0 once and forgets the leftover`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        calls.clear()
        c.tick(active = false)
        assertEquals(clearCalls, calls.filterNot { it.startsWith("read") })
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        calls.clear()
        c.tick(active = false)
        c.close()
        assertTrue(calls.isEmpty())
    }

    @Test fun `after a close the loop writes and starts nothing until the next arm reopens it`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        c.close()
        calls.clear()
        // The route is back while the disarm after the close still waits on the helper.
        route(dist = 250)
        repeat(3) { c.tick(active = true) }
        assertTrue(calls.toString(), calls.isEmpty())
        c.reopen()
        c.tick(active = true)
        assertTrue(calls.toString(), calls.contains("set $dist=250") && calls.containsAll(starts))
    }

    @Test fun `a held loop leaves the CAN leftover of the HUD check alone`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_CAN_LEFT, true).commit()
        var held = true
        val c = channels(2)
        c.start(backgroundScope, held = { held }) { false }
        testScheduler.advanceTimeBy(HudWayChannels.RETRY_MS * 2)
        testScheduler.runCurrent()
        assertTrue(calls.toString(), calls.isEmpty())
        assertTrue(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        held = false
        testScheduler.advanceTimeBy(HudWayChannels.PERIOD_MS * 2)
        testScheduler.runCurrent()
        assertEquals(clearCalls, calls)
    }

    @Test fun `close before any write writes nothing`() = runTest {
        val c = channels(3)
        c.tick(active = false)
        c.close()
        assertTrue(calls.isEmpty())
    }

    @Test fun `a refused clear keeps the leftover and is retried every 5 s, not every tick`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        writeRc = -1
        calls.clear()
        c.tick(active = false)
        assertEquals(4, calls.size)
        assertTrue(prefs.getBoolean(HudWayChannels.KEY_CAN_LEFT, false))
        calls.clear()
        repeat((HudArming.CHECK_PERIOD_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = false) }
        assertTrue(calls.isEmpty())
        writeRc = 0
        c.tick(active = false)
        assertEquals(clearCalls, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
    }

    @Test fun `a new route after the end writes again from scratch`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        c.tick(active = false)
        calls.clear()
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=300", "buf $road=Main St"), calls)
    }

    @Test fun `turn kind is the icon id as OpenBYD writes it, out of range blank`() {
        assertEquals(1, HudWayChannels.turnKind(1))
        assertEquals(49, HudWayChannels.turnKind(49))
        assertEquals(102, HudWayChannels.turnKind(102))
        assertEquals(0, HudWayChannels.turnKind(103))
        assertEquals(0, HudWayChannels.turnKind(-1))
    }

    @Test fun `the road name fits the instrument's 255 bytes and is never empty`() {
        assertEquals(" ", HudWayChannels.roadName(""))
        assertEquals(" ", HudWayChannels.roadName("   "))
        assertEquals("Main St", HudWayChannels.roadName(" Main St "))
        val long = HudWayChannels.roadName("x".repeat(400))
        assertEquals(127, long.length)
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= 255)
    }

    @Test fun `the road name goes to the instrument in Latin, cut after the transliteration`() {
        assertEquals("Prospekt Nezavisimosti", HudWayChannels.roadName("Проспект Независимости"))
        assertEquals("M1 Minsk-Brest 42", HudWayChannels.roadName("M1 Minsk-Brest 42"))
        // 100 letters grow to 200 (ß is ss): the cap applies to what goes out.
        assertEquals("ss".repeat(100).take(127), HudWayChannels.roadName("ß".repeat(100)))
        // Cyrillic reads as a driver would spell it: Щ is Shch, not ICU's per-letter S.
        assertEquals("Shchukina", HudWayChannels.roadName("Щукина"))
        assertEquals("shch".repeat(100).take(127), HudWayChannels.roadName("щ".repeat(100)))
    }

    @Test fun `way 2 writes a Cyrillic road in Latin`() = runTest {
        val c = channels(2)
        route(road = "Проспект Независимости")
        c.tick(active = true)
        assertTrue(calls.toString(), calls.contains("buf $road=Prospekt Nezavisimosti"))
    }

    @Test fun `the distance is kept in the instrument's range`() = runTest {
        val c = channels(2)
        route(dist = 20_000_000)
        c.tick(active = true)
        assertTrue(calls.contains("set $dist=16777214"))
    }

    // --- way 3: the LAUNCHER_MAP_CN family on top ---

    private val starts = HudLauncherMapCnFrames.SERVICE_IDS.map { "start 0x${it.toString(16)}" }
    private val stops = HudLauncherMapCnFrames.SERVICE_IDS.map { "stop 0x${it.toString(16)}" }

    @Test fun `way 3 starts the six services at the route start and sends the update set every tick`() = runTest {
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        every { gateway.fireEvent(any(), any()) } answers { sent += firstArg<Long>() to secondArg<ByteArray>(); 0 }
        val c = channels(3)
        route()
        c.tick(active = true)
        c.tick(active = true)
        c.tick(active = true)
        assertEquals(starts, calls.filter { it.startsWith("start") })
        val routeId = HudLauncherMapCnFrames.newRouteId(Random(3))
        assertEquals(routeId, prefs.getLong(HudWayChannels.KEY_LMCN_LEFT, 0L))
        val expected = (0 until 3).flatMap { counter ->
            HudLauncherMapCnFrames.update(2, 300, 12_000, 800, HudLauncherMapCnFrames.Position(53.9, 27.56), routeId, counter, 1_700_000_000_000L)
        }
        assertEquals(expected.map { it.topic }, sent.map { it.first })
        expected.zip(sent).forEach { (e, a) -> assertTrue(e.payload.contentEquals(a.second)) }
        // CAN too, as in way 2.
        assertTrue(calls.contains("set $dist=300"))
    }

    @Test fun `way 3 looks up the position at the route start and then every 5 s, not every tick`() = runTest {
        var lookups = 0
        val c = HudWayChannels(3, HudCanChannel(helper), gateway, prefs) {
            lookups++
            HudLauncherMapCnFrames.Position.DEFAULT
        }.apply { snapshot = { this@HudWayChannelsTest.snapshot } }
        route()
        c.tick(active = true)
        assertEquals(1, lookups)
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = true) }
        assertEquals(1, lookups)
        c.tick(active = true)
        assertEquals(2, lookups)
    }

    @Test fun `way 3 at the route end blanks the CAN fields, then sends the off events and stops the six services`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        calls.clear()
        c.tick(active = false)
        assertEquals(
            clearCalls + listOf("fire 0x4000d000d8001", "fire 0x4000d000d8005", "fire 0x4000e000e8001") + stops,
            calls,
        )
        assertFalse(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
    }

    @Test fun `way 3 stops on an unbound gateway keep the family leftover for the next start`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        stopRc = { -1 }
        c.close()
        assertTrue(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    @Test fun `a stop that threw in the binder keeps the family leftover`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        // -2: the transact threw (DeadObjectException); the five before it answered.
        stopRc = { if (it == HudLauncherMapCnFrames.SERVICE_IDS.last()) -2 else 0 }
        c.close()
        assertEquals(stops, calls.filter { it.startsWith("stop") })
        assertTrue(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    @Test fun `a family marker that does not reach the disk starts no family for the route and says so`() = runTest {
        val c = channels(3, flaky)
        route()
        repeat(3) { c.tick(active = true) }
        assertTrue(calls.toString(), calls.none { it.startsWith("start") || it.startsWith("fire") })
        assertFalse(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
        assertTrue(lines.toString(), lines.any { it.contains("marker not saved what=lmcn") })
        c.tick(active = false)
        assertTrue(calls.none { it.startsWith("stop") })
    }

    // --- writes the car refused ---

    @Test fun `a refused road write is tried again in 5 s, not every tick`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        calls.clear()
        bufRc = { -1 }
        route(road = "Side Rd")
        c.tick(active = true)
        assertEquals(listOf("buf $road=Side Rd"), calls)
        calls.clear()
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = true) }
        assertTrue(calls.toString(), calls.isEmpty())
        bufRc = { 0 }
        c.tick(active = true)
        assertEquals(listOf("buf $road=Side Rd"), calls)
        calls.clear()
        c.tick(active = true)
        assertTrue(calls.isEmpty())
    }

    @Test fun `a refused guidance write is tried again in 5 s`() = runTest {
        val c = channels(2)
        route()
        c.tick(active = true)
        calls.clear()
        writeRc = -1
        bufRc = { 0 }
        route(dist = 250)
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=250"), calls)
        calls.clear()
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = true) }
        assertTrue(calls.toString(), calls.isEmpty())
        writeRc = 0
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=250"), calls)
    }

    @Test fun `a road the car keeps refusing does not slow the distance down`() = runTest {
        val c = channels(2)
        bufRc = { -1 }
        route(dist = 300)
        c.tick(active = true)
        calls.clear()
        route(dist = 290)
        c.tick(active = true)
        route(dist = 280)
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=290", "set $icon=2", "set $ahead=2", "set $dist=280"), calls)
    }

    @Test fun `a CAN marker that does not reach the disk holds the writes, says so and is tried again in 5 s`() = runTest {
        val c = channels(2, flaky)
        route()
        c.tick(active = true)
        assertTrue(calls.toString(), calls.isEmpty())
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        assertTrue(lines.toString(), lines.any { it.contains("marker not saved what=can") })
        saveFails = false
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = true) }
        assertTrue(calls.toString(), calls.isEmpty())
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=300", "buf $road=Main St"), calls)
        assertTrue(prefs.getBoolean(HudWayChannels.KEY_CAN_LEFT, false))
    }

    // --- what a process death left ---

    @Test fun `a CAN leftover is blanked and forgotten, a refused one kept`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_CAN_LEFT, true).commit()
        writeRc = -2
        assertFalse(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertTrue(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        writeRc = 0
        calls.clear()
        assertTrue(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(clearCalls, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
    }

    @Test fun `no CAN leftover writes nothing`() = runTest {
        assertTrue(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertTrue(calls.isEmpty())
    }

    @Test fun `a family leftover gets the off events of its route and the six stops`() {
        prefs.edit().putLong(HudWayChannels.KEY_LMCN_LEFT, 1_234_567_890L).commit()
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        every { gateway.fireEvent(any(), any()) } answers { sent += firstArg<Long>() to secondArg<ByteArray>(); 0 }
        HudWayChannels.stopLmcnLeftover(gateway, prefs, nowMs = 1_700_000_000_000L)
        val expected = HudLauncherMapCnFrames.stop(1_234_567_890L, 1_700_000_000_000L)
        assertEquals(expected.map { it.topic }, sent.map { it.first })
        expected.zip(sent).forEach { (e, a) -> assertTrue(e.payload.contentEquals(a.second)) }
        assertEquals(stops, calls.filter { it.startsWith("stop") })
        assertFalse(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    @Test fun `a family leftover whose last stop threw stays kept`() {
        prefs.edit().putLong(HudWayChannels.KEY_LMCN_LEFT, 1_234_567_890L).commit()
        stopRc = { if (it == HudLauncherMapCnFrames.SERVICE_IDS.last()) -2 else 0 }
        HudWayChannels.stopLmcnLeftover(gateway, prefs, nowMs = 1_700_000_000_000L)
        assertTrue(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
    }

    // --- diagnostics ---

    @Test fun `each route leaves one start and one end line with the channels and the CAN counts`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        route(dist = 200)
        c.tick(active = true)
        c.tick(active = false)
        val start = lines.single { it.startsWith("hud way: route start") }
        assertTrue(start, start.contains("way=3") && start.contains("channels=can,someip-lmcn") && start.contains("services={"))
        val end = lines.single { it.startsWith("hud way: route end") }
        // Two guidance writes of three fids and one road write: seven accepted.
        assertTrue(end, end.contains("can accepted=7 refused=0") && end.contains("clear=ok") && end.contains("fire={"))
        val events = trace.events()
        assertTrue(events.any { it.contains("way-route ") && it.contains("way=3") && it.contains("started=6/6") })
        assertTrue(events.any { it.contains("way-route-end") && it.contains("can-ok=7") && it.contains("can-refused=0") })
        assertEquals(7, c.canAccepted)
    }

    @Test fun `coordinates never reach a line or a trace event`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        c.tick(active = false)
        (lines + trace.events()).forEach { assertFalse(it, it.contains("53.9") || it.contains("27.56")) }
    }
}
