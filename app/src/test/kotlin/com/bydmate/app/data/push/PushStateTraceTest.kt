package com.bydmate.app.data.push

import android.os.IBinder
import android.os.Parcel
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.FidCatalog
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.ResolvedFidTable
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.helper.push.FID_PUSH_NOT_IN_FEATURE_MAP
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushEvent
import com.bydmate.app.helper.push.FidPushResult
import com.bydmate.app.helper.push.FidPushSub
import com.bydmate.app.helper.push.MAX_PUSH_FIDS
import com.bydmate.app.helper.push.writePushEvents
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The car states the push channel carries for the trace alone: the turn signal and seven ADAS
 * states around the lane change by turn signal. Only a change is a line; the FidMap wave, its
 * consumers and its dump rows stay exactly as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PushStateTraceTest {

    @get:Rule val trace = TraceRecorder()

    private val helper: HelperClient = mockk(relaxed = true)
    private val subs = slot<List<FidPushSub>>()
    private val binder = slot<IBinder>()
    private lateinit var channel: FidPushChannel

    /** Every fid accepted except the ones listed. */
    private var refused: Set<Int> = emptySet()

    @Before fun setUp() {
        FidAddresses.resetToConstants()
        channel = FidPushChannel(helper)
        coEvery { helper.pushSubscribe(capture(binder), capture(subs)) } answers {
            subs.captured.map {
                FidPushResult(it.fid, it.device, if (it.fid in refused) FID_PUSH_NOT_IN_FEATURE_MAP else FID_PUSH_OK)
            }
        }
    }

    @After fun tearDown() {
        FidAddresses.resetToConstants()
    }

    private fun events() = trace.events().map { it.replace(ID, "").replace(SPACES, " ") }

    /** The state changes alone, without the subscription line. */
    private fun changes() = events().filterNot { it.startsWith("car adas-subscribe") }

    /** One packet from the daemon, the way the callback receives it. */
    private fun push(vararg events: Pair<Int, Int>) {
        val data = Parcel.obtain()
        data.writeInterfaceToken(HelperBinderProtocol.PUSH_CALLBACK_DESCRIPTOR)
        writePushEvents(data, events.map { (fid, value) -> FidPushEvent(fid, value, 0.0, 0L) })
        binder.captured.transact(HelperBinderProtocol.TX_PUSH_EVENT, data, null, IBinder.FLAG_ONEWAY)
        data.recycle()
    }

    // --- subscription ---

    @Test fun `the ADAS states the catalog confirms ride after the FidMap wave, on the ADAS device`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        val wave = FidPushApplier.PUSH_FIELDS.map { FidPushSub(FidAddresses.fid(it), FidAddresses.device(it)) }
        assertEquals(wave, subs.captured.take(wave.size))
        assertEquals(ADAS.keys.map { FidPushSub(it, 1038) }, subs.captured.drop(wave.size))
        assertEquals(wave.size + 7, subs.captured.size)
        assertTrue(subs.captured.size <= MAX_PUSH_FIDS)
        assertEquals(listOf("car adas-subscribe confirmed=7 skipped=0"), events())
    }

    @Test fun `a symbol this firmware numbers differently is skipped, and never traced by name`() = runTest {
        // DiLink 4 catalog (alimzhan, 2026-09-11): ADAS_LKS_FAULT=309 there.
        channel.resubscribe("fid catalog resolved", catalogWith(LKS_SYMBOL to 309))

        val wave = FidPushApplier.PUSH_FIELDS.size
        assertEquals(ADAS.keys.filter { it != LKS_FAULT }.map { FidPushSub(it, 1038) }, subs.captured.drop(wave))
        assertTrue(subs.captured.none { it.fid == LKS_FAULT || it.fid == 309 })

        push(LKS_FAULT to 1, 309 to 1, LANE_GRAY to 1)

        assertEquals(
            listOf("car adas-subscribe confirmed=6 skipped=1", "car adas name=lane-change-gray to=1"),
            events(),
        )
    }

    @Test fun `a symbol missing from this firmware's catalog is skipped`() = runTest {
        // Song Plus DiLink 3 catalog (crazyhack, 2026-09-10): only ADAS_LKS_FAULT is there.
        channel.resubscribe("fid catalog resolved", FidCatalog(mapOf(LKS_SYMBOL to LKS_FAULT), emptyMap()))

        assertEquals(listOf(FidPushSub(LKS_FAULT, 1038)), subs.captured.drop(FidPushApplier.PUSH_FIELDS.size))
        assertEquals(listOf("car adas-subscribe confirmed=1 skipped=6"), events())
    }

    @Test fun `without a catalog no ADAS state is subscribed or traced`() = runTest {
        channel.resubscribe("binder accepted", catalog = null)

        val wave = FidPushApplier.PUSH_FIELDS.map { FidPushSub(FidAddresses.fid(it), FidAddresses.device(it)) }
        assertEquals(wave, subs.captured)
        assertEquals(122, subs.captured.size)

        push(*ADAS.keys.map { it to 1 }.toTypedArray())

        assertEquals(listOf("car adas-subscribe reason=no-catalog"), events())
    }

    @Test fun `a later catalog brings the ADAS states in`() = runTest {
        channel.resubscribe("binder accepted", catalog = null)
        channel.resubscribe("fid catalog resolved", CATALOG)

        push(LANE_GRAY to 1)

        assertEquals(
            listOf(
                "car adas-subscribe reason=no-catalog",
                "car adas-subscribe confirmed=7 skipped=0",
                "car adas name=lane-change-gray to=1",
            ),
            events(),
        )
    }

    @Test fun `no ADAS fid shares a number with a FidMap address`() {
        val taken = FidMap.all.map { it.fid }.toSet()
        assertEquals(emptyList<Int>(), ADAS.keys.filter { it in taken })
    }

    @Test fun `an ADAS fid a FidMap field already holds on this car is left to that field`() = runTest {
        val lane = ADAS.keys.first()
        FidAddresses.install(ResolvedFidTable(
            FidMap.all.associate { it.field to FidAddress(it.device, if (it.field == "bsdLeft") lane else it.fid) },
            emptyList(), "test",
        ))

        channel.resubscribe("fid catalog resolved", CATALOG)

        assertEquals(1, subs.captured.count { it.fid == lane })
        assertEquals("bsdLeft", channel.fieldFor(lane))
        assertEquals(listOf("car adas-subscribe confirmed=6 skipped=1"), events())
    }

    @Test fun `ADAS fids are not FidMap fields, so nothing patches the snapshot with them`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        ADAS.keys.forEach { assertNull(channel.fieldFor(it)) }
        assertEquals("turnSignal", channel.fieldFor(FidAddresses.fid("turnSignal")))
    }

    @Test fun `ADAS fids this car refuses cost nothing`() = runTest {
        refused = ADAS.keys
        channel.resubscribe("binder accepted", CATALOG)
        val received = mutableListOf<FidPushEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { channel.events.toList(received) }

        push(TURN to 2)

        val wave = FidPushApplier.PUSH_FIELDS.size
        val lines = channel.diagnosticsSnapshot()
        assertEquals("subscribed=${wave + ADAS.size} ok=$wave failed=${ADAS.size}", lines.first())
        assertTrue(lines.any { it.startsWith("turnSignal $TURN dev=1004 OK") })
        assertTrue(lines.any { it.startsWith("lane-change-gray $LANE_GRAY dev=1038 $FID_PUSH_NOT_IN_FEATURE_MAP") })
        assertEquals(listOf(FidPushEvent(TURN, 2, 0.0, 0L, receivedAtMs = received.single().receivedAtMs)), received)
        assertEquals(listOf("car turn to=left"), changes())
    }

    // --- trace ---

    @Test fun `ADAS changes are traced old to new, repeats are not`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        push(LANE_GRAY to 0, ILCA to 1)
        push(LANE_GRAY to 0, ILCA to 1)
        push(LANE_GRAY to 1)
        push(LANE_GRAY to 1)
        push(LANE_GRAY to 0)

        assertEquals(
            listOf(
                "car adas name=lane-change-gray to=0",
                "car adas name=ilca-switch to=1",
                "car adas name=lane-change-gray from=0 to=1",
                "car adas name=lane-change-gray from=1 to=0",
            ),
            changes(),
        )
    }

    @Test fun `every ADAS state has its own short name`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        push(*ADAS.keys.map { it to 0 }.toTypedArray())

        assertEquals(ADAS.values.map { "car adas name=$it to=0" }, changes())
    }

    @Test fun `a sentinel is traced as none, not as a number`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        push(TOR_FAULT to 65535)
        push(TOR_FAULT to 3)
        push(TOR_FAULT to 1048575)
        push(TOR_FAULT to 65535)

        assertEquals(
            listOf(
                "car adas name=tor-fault to=none",
                "car adas name=tor-fault from=none to=3",
                "car adas name=tor-fault from=3 to=none",
            ),
            changes(),
        )
    }

    @Test fun `turn signal transitions are traced by side, the held mask is not`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        push(TURN to 1)
        push(TURN to 2)
        push(TURN to 2)
        push(TURN to 4)
        push(TURN to 6)
        push(TURN to 9)
        push(TURN to 1)

        assertEquals(
            listOf(
                "car turn to=off",
                "car turn from=off to=left",
                "car turn from=left to=right",
                "car turn from=right to=hazard",
                "car turn from=hazard to=9",
                "car turn from=9 to=off",
            ),
            changes(),
        )
    }

    @Test fun `other push fields are not traced`() = runTest {
        channel.resubscribe("binder accepted", CATALOG)

        push(FidAddresses.fid("gear") to 4, FidAddresses.fid("bsdLeft") to 2)

        assertEquals(emptyList<String>(), changes())
    }

    private companion object {
        val ID = Regex(" #\\d+")
        val SPACES = Regex(" +")
        val TURN = FidMap.byField.getValue("turnSignal").fid
        const val LANE_GRAY = 535826452
        const val ILCA = 700448776
        const val TOR_FAULT = 230686760
        const val LKS_FAULT = 828375060
        const val LKS_SYMBOL = "Adas.ADAS_LKS_FAULT"
        val ADAS = linkedMapOf(
            LANE_GRAY to "lane-change-gray",
            ILCA to "ilca-switch",
            -1728052359 to "domain-disconnect",
            TOR_FAULT to "tor-fault",
            535826458 to "noa-gray",
            535830556 to "noa-quit",
            LKS_FAULT to "lks-fault",
        )

        /** The seven symbols as the Leopard 3 catalog numbers them (fid dump 2026-07-29). */
        val SYMBOLS = mapOf(
            "Adas.ADAS_INTERACTIVE_LANE_CHANGE_ASSIST_GRAY" to LANE_GRAY,
            "Adas.ADAS_ILCA_SWITCH_STATE" to ILCA,
            "Adas.ADAS_DOMAIN_CONTROL_DISCONNECTION_STATUS" to -1728052359,
            "Adas.ADAS_TOR_FAULT_CODE_ID" to TOR_FAULT,
            "Adas.ADAS_NOA_GRAY_STATE" to 535826458,
            "Adas.ADAS_NOA_QUIT_PROMPT" to 535830556,
            LKS_SYMBOL to LKS_FAULT,
        )
        val CATALOG = FidCatalog(SYMBOLS, emptyMap())

        fun catalogWith(vararg moved: Pair<String, Int>) = FidCatalog(SYMBOLS + moved, emptyMap())
    }
}
