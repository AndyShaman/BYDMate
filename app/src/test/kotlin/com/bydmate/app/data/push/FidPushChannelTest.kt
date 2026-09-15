package com.bydmate.app.data.push

import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushResult
import com.bydmate.app.helper.push.FidPushStatus
import com.bydmate.app.helper.push.FidPushStatusRow
import com.bydmate.app.helper.push.FidPushSub
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidPushChannelTest {

    private val helper: HelperClient = mockk(relaxed = true)
    private lateinit var channel: FidPushChannel

    private fun okTable(subs: List<FidPushSub>) =
        subs.map { FidPushResult(it.fid, it.device, FID_PUSH_OK) }

    @Before fun setUp() {
        FidAddresses.resetToConstants()
        channel = FidPushChannel(helper)
    }

    @Test fun `a fresh daemon is subscribed with the whole wave`() = runTest {
        val captured = slot<List<FidPushSub>>()
        coEvery { helper.pushSubscribe(any(), capture(captured)) } answers { okTable(captured.captured) }

        channel.resubscribe("binder accepted")

        assertEquals(FidPushApplier.PUSH_FIELDS.size, captured.captured.size)
        val turnSignal = captured.captured[FidPushApplier.PUSH_FIELDS.indexOf("turnSignal")]
        assertEquals(FidAddresses.fid("turnSignal"), turnSignal.fid)
        assertEquals(FidAddresses.device("turnSignal"), turnSignal.device)
        assertEquals(FidPushApplier.PUSH_FIELDS.size, channel.results.size)
        assertEquals(1, channel.resubscribes)
    }

    @Test fun `every acceptance of a daemon reinstalls the subscription`() = runTest {
        val captured = slot<List<FidPushSub>>()
        coEvery { helper.pushSubscribe(any(), capture(captured)) } answers { okTable(captured.captured) }

        channel.resubscribe("binder accepted")
        channel.resubscribe("fid catalog resolved")

        assertEquals(2, channel.resubscribes)
    }

    @Test fun `an unreachable daemon leaves no subscription`() = runTest {
        coEvery { helper.pushSubscribe(any(), any()) } returns null

        channel.resubscribe("binder accepted")

        assertEquals(emptyList<FidPushResult>(), channel.results)
        assertEquals(0, channel.resubscribes)
        assertEquals(listOf("no subscription"), channel.diagnosticsSnapshot())
    }

    @Test fun `a dead callback is named in the dump`() = runTest {
        val captured = slot<List<FidPushSub>>()
        coEvery { helper.pushSubscribe(any(), capture(captured)) } answers { okTable(captured.captured) }
        coEvery { helper.pushStatus() } answers {
            FidPushStatus(
                rows = captured.captured.map {
                    FidPushStatusRow(it.fid, it.device, FID_PUSH_OK, 0, 0, 0.0, 0L)
                },
                callbackAlive = false,
                deliverErrors = 3,
            )
        }

        channel.resubscribe("binder accepted")
        val lines = channel.diagnosticsSnapshot()

        assertEquals("subscribed=${FidPushApplier.PUSH_FIELDS.size} ok=${FidPushApplier.PUSH_FIELDS.size} failed=0", lines.first())
        assertTrue(lines.any { it.startsWith("turnSignal ${FidAddresses.fid("turnSignal")} dev=1004 OK") })
        assertTrue(lines.contains("callback: dead deliver errors=3"))
        assertTrue(lines.contains("delivery: packets=0 events=0 coalesced=0"))
        assertTrue(lines.contains("resubscribes=1"))
    }
}
