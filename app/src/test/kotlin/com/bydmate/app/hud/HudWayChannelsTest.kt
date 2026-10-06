package com.bydmate.app.hud

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.data.vehicle.HudSdkCall
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavManeuverCodes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.TimeZone
import kotlin.random.Random
import kotlinx.coroutines.test.runCurrent
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
@Suppress("LargeClass") // ways 2 and 3 tick by tick on one harness
@RunWith(RobolectricTestRunner::class)
class HudWayChannelsTest {

    private companion object {
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

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
    /** What the daemon answers a way 3 SDK call; called and accepted by default. */
    private var sdkReply: (HudSdkCall) -> HudNaviReply? = { HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0) }
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
        coEvery { helper.hudSdk(any()) } answers {
            val call = firstArg<HudSdkCall>()
            calls += when (call) {
                is HudSdkCall.Guidance -> "sdk guidance ${call.turnKind}/${call.distanceM}"
                is HudSdkCall.PathName -> "sdk road ${call.name}"
                is HudSdkCall.RestRoute -> "sdk rest ${call.hours}/${call.minutes}/${call.mileageM}"
            }
            sdkReply(call)
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
        nowMs = { 1_700_000_000_000L }   // 22:13:20 UTC
        zone = { UTC }
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
    private val mileage = HudCanChannel.FID_REST_MILEAGE_M
    private val hours = HudCanChannel.FID_REST_HOURS
    private val minutes = HudCanChannel.FID_REST_MINUTES
    private val seconds = HudCanChannel.FID_REST_SECONDS
    private val arrive = HudCanChannel.FID_ARRIVE_MINUTE
    /** Way 3's rest of route: OpenBYD's five raw writes, then its SDK call. */
    private fun restCalls(h: Int, m: Int, mil: Long, arr: Int) = listOf(
        "set $mileage=$mil", "set $hours=$h", "set $minutes=$m", "set $seconds=0", "set $arrive=$arr", "sdk rest $h/$m/$mil",
    )
    /** ... and how OpenBYD's turnOffNavi blanks it. */
    private val restClear get() = listOf("set $mileage=-1", "set $hours=0", "set $minutes=0", "set $seconds=0", "set $arrive=0")
    private val canAndSdk get() = calls.filter { it.startsWith("set") || it.startsWith("buf") || it.startsWith("sdk") }

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

    /** Way 2 must not change by a single write or its order: start, icon change, distance change,
     *  street change and end, with the remaining time and distance known all along. */
    @Test fun `way 2 pins its whole write sequence for a route`() = runTest {
        val c = channels(2)
        route(gaode = 2, dist = 300, road = "Main St", total = 12_000, eta = 800)
        c.tick(active = true)
        route(gaode = 1, dist = 300, road = "Main St", total = 11_900, eta = 790)
        c.tick(active = true)
        route(gaode = 1, dist = 200, road = "Main St", total = 11_800, eta = 780)
        c.tick(active = true)
        route(gaode = 1, dist = 200, road = "Side Rd", total = 11_700, eta = 700)
        c.tick(active = true)
        c.tick(active = true)
        c.tick(active = false)
        assertEquals(
            listOf(
                "set $icon=2", "set $ahead=2", "set $dist=300", "buf $road=Main St",
                "set $icon=1", "set $ahead=1", "set $dist=300",
                "set $icon=1", "set $ahead=1", "set $dist=200",
                "buf $road=Side Rd",
            ) + clearCalls,
            calls.filterNot { it.startsWith("read") },
        )
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        val end = lines.single { it.startsWith("hud way: route end") }
        assertEquals("hud way: route end way=2 can accepted=11 refused=0 clear=ok", end)
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

    @Test fun `slight right goes out as OpenBYD's 5, every other code as is`() {
        // OpenBYD and the stock adapter: 5 = slight right; their 4 is a slight left.
        assertEquals(5, HudWayChannels.turnKind(NavManeuverCodes.GAODE_SLIGHT_RIGHT))
        (0..102).filter { it != NavManeuverCodes.GAODE_SLIGHT_RIGHT }.forEach {
            assertEquals(it, HudWayChannels.turnKind(it))
        }
    }

    @Test fun `way 2 writes slight right as icon 5 into both icon fids`() = runTest {
        val c = channels(2)
        route(gaode = NavManeuverCodes.GAODE_SLIGHT_RIGHT)
        c.tick(active = true)
        assertEquals(listOf("set $icon=5", "set $ahead=5", "set $dist=300", "buf $road=Main St"), calls)
    }

    @Test fun `way 3 sends slight right as icon 5 with OpenBYD's main action for it`() = runTest {
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        every { gateway.fireEvent(any(), any()) } answers { sent += firstArg<Long>() to secondArg<ByteArray>(); 0 }
        val c = channels(3)
        route(gaode = NavManeuverCodes.GAODE_SLIGHT_RIGHT)
        c.tick(active = true)
        val routeId = HudLauncherMapCnFrames.newRouteId(Random(3))
        val expected = HudLauncherMapCnFrames.update(
            5, 300, 12_000, 800, HudLauncherMapCnFrames.Position(53.9, 27.56), routeId, 0, 1_700_000_000_000L,
        )
        assertEquals(expected.map { it.topic }, sent.map { it.first })
        expected.zip(sent).forEach { (e, a) -> assertTrue(e.payload.contentEquals(a.second)) }
        assertEquals(5, HudLauncherMapCnFrames.mainAction(5))
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

    @Test fun `way 2 lifts 1 to 10 m to 11 m like the SOME IP frame and keeps the unknown 0 (#294)`() = runTest {
        val c = channels(2)
        route(dist = 3)
        c.tick(active = true)
        assertTrue(calls.toString(), calls.contains("set $dist=11"))
        calls.clear()
        route(dist = 0)
        c.tick(active = true)
        assertTrue(calls.toString(), calls.contains("set $dist=0"))
        calls.clear()
        route(dist = 12)
        c.tick(active = true)
        assertTrue(calls.toString(), calls.contains("set $dist=12"))
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

    @Test fun `way 3 lifts 1 to 10 m to 11 m in its maneuver frame and keeps 0 as it is (#294)`() = runTest {
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        every { gateway.fireEvent(any(), any()) } answers { sent += firstArg<Long>() to secondArg<ByteArray>(); 0 }
        val c = channels(3)
        val routeId = HudLauncherMapCnFrames.newRouteId(Random(3))
        fun frames(dist: Int, counter: Int) =
            HudLauncherMapCnFrames.update(2, dist, 12_000, 800, HudLauncherMapCnFrames.Position(53.9, 27.56), routeId, counter, 1_700_000_000_000L)
        route(dist = 4)
        c.tick(active = true)
        route(dist = 0)
        c.tick(active = true)
        val expected = frames(11, 0) + frames(0, 1)
        assertEquals(expected.map { it.topic }, sent.map { it.first })
        expected.zip(sent).forEach { (e, a) -> assertTrue(e.payload.contentEquals(a.second)) }
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
            clearCalls + restClear + listOf("fire 0x4000d000d8001", "fire 0x4000d000d8005", "fire 0x4000e000e8001") + stops,
            calls,
        )
        assertFalse(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    // --- way 3: OpenBYD's SDK calls and the rest of route on top of the CAN fields ---

    @Test fun `way 3 follows the three guidance writes with the SDK call on every icon or distance change`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        assertEquals(
            listOf("set $icon=2", "set $ahead=2", "set $dist=300", "sdk guidance 2/300", "buf $road=Main St", "sdk road Main St") +
                restCalls(0, 13, 12_000, 26),
            canAndSdk,
        )
        calls.clear()
        c.tick(active = true)
        assertTrue(calls.toString(), canAndSdk.isEmpty())
        route(dist = 250)
        c.tick(active = true)
        assertEquals(listOf("set $icon=2", "set $ahead=2", "set $dist=250", "sdk guidance 2/250"), canAndSdk)
        calls.clear()
        route(gaode = 1, dist = 250)
        c.tick(active = true)
        assertEquals(listOf("set $icon=1", "set $ahead=1", "set $dist=250", "sdk guidance 1/250"), canAndSdk)
    }

    @Test fun `way 3 hands the SDK the clamped icon and distance`() = runTest {
        val c = channels(3)
        route(gaode = 150, dist = 20_000_000)
        c.tick(active = true)
        assertTrue(calls.toString(), canAndSdk.take(4) == listOf("set $icon=0", "set $ahead=0", "set $dist=16777214", "sdk guidance 0/16777214"))
        calls.clear()
        route(gaode = NavManeuverCodes.GAODE_SLIGHT_RIGHT, dist = -5)
        c.tick(active = true)
        assertEquals(listOf("set $icon=5", "set $ahead=5", "set $dist=0", "sdk guidance 5/0"), canAndSdk)
    }

    @Test fun `way 3 follows the street write with the SDK street call, the same Latin string`() = runTest {
        val c = channels(3)
        route(road = "Проспект Независимости")
        c.tick(active = true)
        calls.clear()
        route(road = "")
        c.tick(active = true)
        assertEquals(listOf("buf $road= ", "sdk road  "), canAndSdk)
        calls.clear()
        route(road = "Щукина")
        c.tick(active = true)
        assertEquals(listOf("buf $road=Shchukina", "sdk road Shchukina"), canAndSdk)
    }

    @Test fun `way 3 writes the rest of route on change only and not while the time or the distance is unknown`() = runTest {
        val c = channels(3)
        route(total = 0, eta = 800)
        c.tick(active = true)
        route(total = 12_000, eta = 0)
        c.tick(active = true)
        assertTrue(calls.toString(), calls.none { it.startsWith("sdk rest") || it.startsWith("set $mileage") })
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
        route(total = 12_000, eta = 800)
        c.tick(active = true)
        assertTrue(prefs.getBoolean(HudWayChannels.KEY_REST_LEFT, false))
        calls.clear()
        // 790 s is still 13 min: nothing changed for the instrument.
        route(total = 12_000, eta = 790)
        c.tick(active = true)
        assertTrue(calls.toString(), canAndSdk.isEmpty())
        route(total = 11_950, eta = 790)
        c.tick(active = true)
        assertEquals(restCalls(0, 13, 11_950, 26), canAndSdk)
        calls.clear()
        route(total = 11_950, eta = 700)
        c.tick(active = true)
        assertEquals(restCalls(0, 11, 11_950, 24), canAndSdk)
    }

    @Test fun `a route without the rest of route ends without its clear`() = runTest {
        val c = channels(3)
        route(total = 0, eta = 0)
        c.tick(active = true)
        calls.clear()
        c.tick(active = false)
        assertEquals(clearCalls, canAndSdk)
    }

    @Test fun `a refused rest write is tried again in 5 s, not every tick`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        calls.clear()
        writeRc = -1
        route(total = 11_000)
        c.tick(active = true)
        assertEquals(restCalls(0, 13, 11_000, 26), canAndSdk)
        calls.clear()
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = true) }
        assertTrue(calls.toString(), canAndSdk.isEmpty())
        writeRc = 0
        c.tick(active = true)
        assertEquals(restCalls(0, 13, 11_000, 26), canAndSdk)
    }

    @Test fun `rest of route hours, minutes and arrival minute follow OpenBYD's formulas`() {
        val now = 1_700_000_000_000L   // 22:13:20 UTC
        fun rest(eta: Int, total: Int, zone: TimeZone = UTC) = HudWayChannels.restRoute(eta, total, now, zone)
        assertEquals(HudCanChannel.Rest(0, 0, 500L, 13), rest(59, 500))
        assertEquals(HudCanChannel.Rest(1, 0, 1_000L, 13), rest(3_600, 1_000))
        assertEquals(HudCanChannel.Rest(5, 59, 1_000L, 12), rest(5 * 3_600 + 59 * 60, 1_000))
        // 22:13 + 50 min crosses the hour: 23:03.
        assertEquals(HudCanChannel.Rest(0, 50, 1_000L, 3), rest(3_000, 1_000))
        assertEquals(HudCanChannel.Rest(0, 2, 0L, 15), rest(150, 0))
        assertEquals(HudCanChannel.Rest(0, 2, Int.MAX_VALUE.toLong(), 15), rest(150, Int.MAX_VALUE))
        assertEquals(HudCanChannel.Rest(0, 2, 0L, 15), rest(150, -7))
        // The instrument takes at most 254 hours; the minutes stay the remainder.
        assertEquals(HudCanChannel.Rest(254, 30, 1_000L, 43), rest(300 * 3_600 + 30 * 60, 1_000))
        // The arrival minute is the car's local clock: 03:43 in India.
        assertEquals(HudCanChannel.Rest(0, 10, 1_000L, 53), rest(600, 1_000, TimeZone.getTimeZone("Asia/Kolkata")))
    }

    @Test fun `way 3 ends the route by blanking the rest of route after the CAN fields`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        calls.clear()
        c.tick(active = false)
        assertEquals(clearCalls + restClear, canAndSdk)
        val end = lines.single { it.startsWith("hud way: route end") }
        assertTrue(end, end.contains("can accepted=9 refused=0 clear=ok sdk accepted=3 refused=0 absent=0"))
    }

    @Test fun `a refused rest clear keeps only its own leftover and is retried in 5 s without the CAN fields`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        var mileageRc = -1
        coEvery { helper.writeStatus(any(), HudCanChannel.FID_REST_MILEAGE_M, any(), any()) } answers {
            calls += "set $mileage=${arg<Int>(2)}"
            mileageRc
        }
        calls.clear()
        c.tick(active = false)
        // -1 refused: 0 in the same clear, refused too.
        assertEquals(clearCalls + "set $mileage=-1" + "set $mileage=0" + restClear.drop(1), canAndSdk)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        assertTrue(prefs.contains(HudWayChannels.KEY_REST_LEFT))
        assertTrue(lines.single { it.startsWith("hud way: route end") }.contains("clear=refused"))
        calls.clear()
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = false) }
        assertTrue(calls.toString(), calls.isEmpty())
        mileageRc = 0
        c.tick(active = false)
        assertEquals(restClear, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `a refused CAN clear keeps only its own leftover, the accepted rest clear is not repeated`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        bufRc = { -1 }
        calls.clear()
        c.tick(active = false)
        assertEquals(clearCalls + restClear, canAndSdk)
        assertTrue(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
        calls.clear()
        repeat((HudWayChannels.RETRY_MS / HudWayChannels.PERIOD_MS).toInt() - 1) { c.tick(active = false) }
        assertTrue(calls.toString(), calls.isEmpty())
        bufRc = { 0 }
        c.tick(active = false)
        assertEquals(clearCalls, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
    }

    @Test fun `a car that refuses the mileage -1 gets 0 in the same clear and the leftover goes`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        coEvery { helper.writeStatus(any(), HudCanChannel.FID_REST_MILEAGE_M, any(), any()) } answers {
            calls += "set $mileage=${arg<Int>(2)}"
            if (arg<Int>(2) == -1) -2 else 0
        }
        assertTrue(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(listOf("set $mileage=-1", "set $mileage=0") + restClear.drop(1), calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `no answer to the mileage -1 is not a refusal, no 0 and the leftover stays`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        coEvery { helper.writeStatus(any(), HudCanChannel.FID_REST_MILEAGE_M, any(), any()) } answers {
            calls += "set $mileage=${arg<Int>(2)}"
            null
        }
        assertFalse(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(restClear, calls)
        assertTrue(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `a rest leftover is blanked with the CAN fields and forgotten, a refused one kept`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_CAN_LEFT, true).putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        writeRc = -2
        assertFalse(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertTrue(prefs.contains(HudWayChannels.KEY_CAN_LEFT) && prefs.contains(HudWayChannels.KEY_REST_LEFT))
        writeRc = 0
        calls.clear()
        assertTrue(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(clearCalls + restClear, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT) || prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `a rest leftover alone, its CAN key gone with the HUD check's clear, is blanked without the CAN fields`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        assertTrue(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(restClear, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
        // The loop takes it as its own leftover too.
        prefs.edit().putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        calls.clear()
        channels(3).tick(active = false)
        assertEquals(restClear, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `a leftover whose CAN clear is accepted and rest clear refused keeps only the rest`() = runTest {
        prefs.edit().putBoolean(HudWayChannels.KEY_CAN_LEFT, true).putBoolean(HudWayChannels.KEY_REST_LEFT, true).commit()
        coEvery { helper.writeStatus(any(), HudCanChannel.FID_ARRIVE_MINUTE, any(), any()) } answers {
            calls += "set $arrive=${arg<Int>(2)}"
            -1
        }
        assertFalse(HudWayChannels.clearCanLeftover(HudCanChannel(helper), prefs))
        assertEquals(clearCalls + restClear, calls)
        assertFalse(prefs.contains(HudWayChannels.KEY_CAN_LEFT))
        assertTrue(prefs.contains(HudWayChannels.KEY_REST_LEFT))
    }

    @Test fun `a firmware without the SDK method keeps the raw writes, says so once per route and does not retry`() = runTest {
        sdkReply = { HudNaviReply(HelperBinderProtocol.HUD_NAVI_ABSENT, 0) }
        val c = channels(3)
        sdkRouteOf(c)
        assertEquals(sdkRouteRaw, canAndSdk.filterNot { it.startsWith("sdk") })
        // Each method was tried once.
        assertEquals(listOf("sdk guidance 2/300", "sdk road Main St", "sdk rest 0/13/12000"), calls.filter { it.startsWith("sdk") })
        assertEquals(1, lines.count { it.startsWith("hud way: sdk unavailable") })
        assertTrue(lines.single { it.startsWith("hud way: sdk unavailable") }.contains("reason=absent"))
        assertTrue(lines.single { it.startsWith("hud way: route end") }.contains("sdk accepted=0 refused=0 absent=3"))
        // The next route tries once again.
        calls.clear()
        sdkRouteOf(c)
        assertEquals(3, calls.count { it.startsWith("sdk") })
        assertEquals(2, lines.count { it.startsWith("hud way: sdk unavailable") })
    }

    @Test fun `an SDK method that throws keeps the raw writes, says so once per route and does not retry`() = runTest {
        sdkReply = { if (it is HudSdkCall.Guidance) HudNaviReply(HelperBinderProtocol.HUD_NAVI_THREW, 0) else HudNaviReply(0, 0) }
        val c = channels(3)
        sdkRouteOf(c)
        assertEquals(sdkRouteRaw, canAndSdk.filterNot { it.startsWith("sdk") })
        assertEquals(1, calls.count { it.startsWith("sdk guidance") })
        assertEquals(2, calls.count { it.startsWith("sdk road") })
        assertTrue(lines.single { it.startsWith("hud way: sdk unavailable") }.contains("call=sendSimpleGuidanceInfo reason=threw"))
        assertTrue(lines.single { it.startsWith("hud way: route end") }.contains("sdk accepted=4 refused=1 absent=0"))
    }

    @Test fun `a daemon that does not know the SDK call keeps the raw writes and is asked once per route`() = runTest {
        sdkReply = { null }
        val c = channels(3)
        sdkRouteOf(c)
        assertEquals(sdkRouteRaw, canAndSdk.filterNot { it.startsWith("sdk") })
        assertEquals(listOf("sdk guidance 2/300"), calls.filter { it.startsWith("sdk") })
        assertTrue(lines.single { it.startsWith("hud way: sdk unavailable") }.contains("reason=daemon"))
        assertTrue(lines.single { it.startsWith("hud way: route end") }.contains("sdk accepted=0 refused=0 absent=1"))
    }

    @Test fun `an SDK refusal is counted, not retried, and the raw writes go on`() = runTest {
        sdkReply = { HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, -2147482645) }
        val c = channels(3)
        sdkRouteOf(c)
        assertEquals(sdkRouteRaw, canAndSdk.filterNot { it.startsWith("sdk") })
        assertEquals(6, calls.count { it.startsWith("sdk") })
        assertTrue(lines.none { it.startsWith("hud way: sdk unavailable") })
        assertTrue(lines.last { it.startsWith("hud way: route end") }.contains("sdk accepted=0 refused=6 absent=0"))
    }

    /** A route with a distance change, a road change and a rest change, then its end. */
    private suspend fun sdkRouteOf(c: HudWayChannels) {
        route()
        c.tick(active = true)
        route(dist = 250, road = "Side Rd")
        c.tick(active = true)
        route(dist = 250, road = "Side Rd", total = 11_000)
        c.tick(active = true)
        c.tick(active = false)
    }

    private val sdkRouteRaw get() = listOf(
        "set $icon=2", "set $ahead=2", "set $dist=300", "buf $road=Main St",
        "set $mileage=12000", "set $hours=0", "set $minutes=13", "set $seconds=0", "set $arrive=26",
        "set $icon=2", "set $ahead=2", "set $dist=250", "buf $road=Side Rd",
        "set $mileage=11000", "set $hours=0", "set $minutes=13", "set $seconds=0", "set $arrive=26",
    ) + clearCalls + restClear

    /** The #269 Sea Lion 06 route shape: arrow 2 held while the distance falls, then changing. */
    @Test fun `way 3 on the 269 route writes OpenBYD's whole set`() = runTest {
        val rows = requireNotNull(javaClass.classLoader?.getResource("hud/way3-route-269.txt")).readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
        val c = channels(3)
        rows.forEach { row ->
            route(gaode = row[0].toInt(), dist = row[1].toInt(), road = row[2], total = row[3].toInt(), eta = row[4].toInt())
            c.tick(active = true)
        }
        c.tick(active = false)
        fun g(k: Int, m: Int) = listOf("set $icon=$k", "set $ahead=$k", "set $dist=$m", "sdk guidance $k/$m")
        fun rd(name: String) = listOf("buf $road=$name", "sdk road $name")
        assertEquals(
            g(2, 40) + rd("Road One Street") + restCalls(0, 10, 5_200, 23) +
                g(2, 30) + restCalls(0, 10, 5_190, 23) +
                g(2, 20) + restCalls(0, 10, 5_180, 23) +
                g(2, 11) + restCalls(0, 10, 5_170, 23) +   // 10 m read, lifted to the glass floor (#294)
                g(2, 1_500) + rd("Second Avenue") + restCalls(0, 10, 5_160, 23) +
                g(2, 1_400) + restCalls(0, 9, 5_060, 22) +
                g(1, 200) + rd("Third Lane") + restCalls(0, 7, 3_600, 20) +
                clearCalls + restClear,
            canAndSdk,
        )
        assertTrue(lines.single { it.startsWith("hud way: route end") }.contains("can accepted=59 refused=0 clear=ok sdk accepted=17 refused=0 absent=0"))
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

    @Test fun `an off event that threw keeps the family leftover though every stop answered`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        every { gateway.fireEvent(0x4000d000d8001L, any()) } answers { calls += "fire 0x4000d000d8001"; -2 }
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

    @Test fun `a family leftover whose off event threw stays kept`() {
        prefs.edit().putLong(HudWayChannels.KEY_LMCN_LEFT, 1_234_567_890L).commit()
        every { gateway.fireEvent(0x4000e000e8001L, any()) } returns -2
        HudWayChannels.stopLmcnLeftover(gateway, prefs, nowMs = 1_700_000_000_000L)
        assertEquals(stops, calls.filter { it.startsWith("stop") })
        assertTrue(prefs.contains(HudWayChannels.KEY_LMCN_LEFT))
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
        // Two guidance writes of three fids, one road write and the rest of route's five: twelve accepted.
        assertTrue(end, end.contains("can accepted=12 refused=0") && end.contains("clear=ok") && end.contains("fire={"))
        val events = trace.events()
        assertTrue(events.any { it.contains("way-route ") && it.contains("way=3") && it.contains("started=6/6") })
        assertTrue(events.any { it.contains("way-route-end") && it.contains("can-ok=12") && it.contains("can-refused=0") })
        assertEquals(12, c.canAccepted)
    }

    @Test fun `coordinates never reach a line or a trace event`() = runTest {
        val c = channels(3)
        route()
        c.tick(active = true)
        c.tick(active = false)
        (lines + trace.events()).forEach { assertFalse(it, it.contains("53.9") || it.contains("27.56")) }
    }

    // --- trace audit: the readback and the road's script ---

    @Test fun `one CAN readback per route, after its first turn kind other than 0, off the tick`() = runTest {
        coEvery { helper.readBatch(any()) } answers { calls += "read"; listOf(0 to 2, 0 to 300) }
        val c = channels(2).apply { readbackScope = this@runTest }
        route(gaode = 0)
        c.tick(active = true)
        runCurrent()
        assertEquals(0, calls.count { it == "read" })
        route(gaode = 2, dist = 300)
        c.tick(active = true)
        assertEquals("the tick does not wait for the read", 0, calls.count { it == "read" })
        runCurrent()
        assertEquals(1, calls.count { it == "read" })
        route(gaode = 5, dist = 200)
        c.tick(active = true)
        runCurrent()
        assertEquals(1, calls.count { it == "read" })
        val readback = trace.events().single { it.contains("can-readback") }
        assertTrue(readback, readback.contains("fid=TURN_KIND wrote=2 read=2 dist_wrote=300 dist_read=300"))
        c.tick(active = false)                      // the route ends (its clear reads back on its own)
        route(gaode = 3, dist = 100)
        c.tick(active = true)
        runCurrent()
        assertEquals(2, trace.events().count { it.contains("can-readback") })
    }

    @Test fun `the CAN road's script class is traced when it changes, never the name`() = runTest {
        val c = channels(2)
        route(road = "Main St")
        c.tick(active = true)
        route(road = "Second St")                   // same class: no line
        c.tick(active = true)
        route(road = "")
        c.tick(active = true)
        val roads = trace.events().filter { it.contains(" road ") }
        assertEquals(2, roads.size)
        assertTrue(roads[0], roads[0].contains("road chan=can script=latin len=7"))
        assertTrue(roads[1], roads[1].contains("road chan=can script=empty len=0"))
        assertFalse(roads.any { it.contains("Main") || it.contains("Second") })
    }
}
