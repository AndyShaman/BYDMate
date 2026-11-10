package com.bydmate.app.helper.push

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidPushWireTest {

    private fun <T> roundTrip(write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val p = Parcel.obtain()
        try {
            write(p)
            p.setDataPosition(0)
            return read(p)
        } finally {
            p.recycle()
        }
    }

    /** Every wire table the app and the daemon exchange comes back equal to what was written. */
    @Test fun `wire tables survive the round trip`() {
        val subs = listOf(FidPushSub(950009900, 1004), FidPushSub(555745336, 1011))
        val table = listOf(
            FidPushResult(950009900, 1004, FID_PUSH_OK),
            FidPushResult(1246777400, 1014, FID_PUSH_UNSUPPORTED),
            FidPushResult(555745336, 1011, "SecurityException: enableDevice"),
        )
        val status = FidPushStatus(
            rows = listOf(
                FidPushStatusRow(950009900, 1004, FID_PUSH_OK, 7, 2, 0.0, 123_456L),
                FidPushStatusRow(555745336, 1011, FID_PUSH_OK, 0, 0, 1.5, 0L),
            ),
            callbackAlive = true,
            deliverErrors = 2,
            packets = 11,
            events = 42,
            coalesced = 31,
        )
        val single = listOf(FidPushEvent(950009900, 4, 2.5, 99_000L, 5L))
        val full = List(MAX_PUSH_FIDS) { FidPushEvent(it, it, it.toDouble(), it.toLong(), 5L) }
        fun events(e: List<FidPushEvent>) =
            roundTrip({ writePushEvents(it, e) }, { readPushEvents(it, receivedAtMs = 5L) })

        val cases = listOf(
            Triple("subscribe request", subs, roundTrip({ writeSubscribeRequest(it, subs) }, ::readSubscribeRequest)),
            Triple("result table", table, roundTrip({ writeResultTable(it, table) }, ::readResultTable)),
            Triple("status table", status, roundTrip({ writeStatusTable(it, status) }, ::readStatusTable)),
            Triple("empty flush", emptyList<FidPushEvent>(), events(emptyList())),
            Triple("single event", single, events(single)),
            Triple("full flush", full, events(full)),
        )
        for ((label, expected, actual) in cases) assertEquals("case: $label", expected, actual)
    }

    @Test fun `a subscribe request above the cap is refused`() {
        val subs = List(MAX_PUSH_FIDS + 1) { FidPushSub(it, 1001) }
        assertEquals(emptyList<FidPushSub>(), roundTrip({ writeSubscribeRequest(it, subs) }, ::readSubscribeRequest))
    }

    @Test fun `a flush above the cap is refused`() {
        val events = List(MAX_PUSH_FIDS + 1) { FidPushEvent(it, it, 0.0, 0L, 5L) }
        assertEquals(
            emptyList<FidPushEvent>(),
            roundTrip({ writePushEvents(it, events) }, { readPushEvents(it, receivedAtMs = 5L) }),
        )
    }

    @Test fun `a truncated flush parcel yields nothing`() {
        assertEquals(
            emptyList<FidPushEvent>(),
            roundTrip({ it.writeInt(2); it.writeInt(950009900) }, { readPushEvents(it, receivedAtMs = 5L) }),
        )
    }
}
