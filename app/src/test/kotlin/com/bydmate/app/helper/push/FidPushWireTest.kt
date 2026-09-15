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

    @Test fun `subscribe request survives the round trip`() {
        val subs = listOf(FidPushSub(950009900, 1004), FidPushSub(555745336, 1011))
        assertEquals(subs, roundTrip({ writeSubscribeRequest(it, subs) }, ::readSubscribeRequest))
    }

    @Test fun `a subscribe request above the cap is refused`() {
        val subs = List(MAX_PUSH_FIDS + 1) { FidPushSub(it, 1001) }
        assertEquals(emptyList<FidPushSub>(), roundTrip({ writeSubscribeRequest(it, subs) }, ::readSubscribeRequest))
    }

    @Test fun `result table survives the round trip`() {
        val table = listOf(
            FidPushResult(950009900, 1004, FID_PUSH_OK),
            FidPushResult(1246777400, 1014, FID_PUSH_UNSUPPORTED),
            FidPushResult(555745336, 1011, "SecurityException: enableDevice"),
        )
        assertEquals(table, roundTrip({ writeResultTable(it, table) }, ::readResultTable))
    }

    @Test fun `status table survives the round trip`() {
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
        assertEquals(status, roundTrip({ writeStatusTable(it, status) }, ::readStatusTable))
    }

    @Test fun `an empty flush survives the round trip`() {
        assertEquals(
            emptyList<FidPushEvent>(),
            roundTrip({ writePushEvents(it, emptyList()) }, { readPushEvents(it, receivedAtMs = 5L) }),
        )
    }

    @Test fun `a single event survives the round trip`() {
        val events = listOf(FidPushEvent(950009900, 4, 2.5, 99_000L, 5L))
        assertEquals(events, roundTrip({ writePushEvents(it, events) }, { readPushEvents(it, receivedAtMs = 5L) }))
    }

    @Test fun `a full flush survives the round trip`() {
        val events = List(MAX_PUSH_FIDS) { FidPushEvent(it, it, it.toDouble(), it.toLong(), 5L) }
        assertEquals(events, roundTrip({ writePushEvents(it, events) }, { readPushEvents(it, receivedAtMs = 5L) }))
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
