package com.bydmate.app.helper.offreport

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OffReportWireTest {

    @Test fun `an arm request survives the parcel`() {
        val p = Parcel.obtain()
        writeOffReportArm(p, "a1", "123:tok", -100500L, "<b>x {{time}}</b>")
        p.setDataPosition(0)
        val r = readOffReportArm(p)!!
        assertEquals("a1", r.id)
        assertEquals("123:tok", r.token)
        assertEquals(-100500L, r.chatId)
        assertEquals("<b>x {{time}}</b>", r.text)
        p.recycle()
    }

    @Test fun `an arm without a token is refused`() {
        val p = Parcel.obtain()
        writeOffReportArm(p, "a1", "", 1L, "t")
        p.setDataPosition(0)
        assertNull(readOffReportArm(p))
        p.recycle()
    }

    @Test fun `a status survives the parcel, with and without a last power-off`() {
        val last = OffReportOutcome("a0", OffReportState.FAILED, 1_000L, 0L, 13, "io:UnknownHostException")
        val full = OffReportStatus(OffReportOutcome("a1", OffReportState.ARMED), "a1", 5_000L, 2, last)
        val empty = OffReportStatus(OffReportOutcome("x", OffReportState.UNKNOWN), "", 0L, -1, null)
        for (status in listOf(full, empty)) {
            val p = Parcel.obtain()
            writeOffReportStatus(p, status)
            p.setDataPosition(0)
            assertEquals(status, readOffReportStatus(p))
            p.recycle()
        }
    }
}
