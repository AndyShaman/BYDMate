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
        writeOffReportArm(p, "a1", "123:tok", -100500L, "<b>x {{time}}</b>", "<i>(at {{time}})</i>")
        p.setDataPosition(0)
        val r = readOffReportArm(p)!!
        assertEquals("a1", r.id)
        assertEquals("123:tok", r.token)
        assertEquals(-100500L, r.chatId)
        assertEquals("<b>x {{time}}</b>", r.text)
        assertEquals("<i>(at {{time}})</i>", r.lateMark)
        p.recycle()
    }

    @Test fun `an arm without a token is refused`() {
        val p = Parcel.obtain()
        writeOffReportArm(p, "a1", "", 1L, "t", "")
        p.setDataPosition(0)
        assertNull(readOffReportArm(p))
        p.recycle()
    }

    @Test fun `a status survives the parcel, with and without a last power-off`() {
        val last = OffReportOutcome("a0", OffReportState.FAILED, 1_000L, 0L, 13, "io:UnknownHostException")
        val fids = listOf(OffReportFid(1001, 315621418, "OK"), OffReportFid(1023, 315621408, "pending"))
        val full = OffReportStatus(OffReportOutcome("a1", OffReportState.ARMED), "a1", 5_000L, 1, last, fids, pending = 3)
        val empty = OffReportStatus(OffReportOutcome("x", OffReportState.UNKNOWN), "", 0L, -1, null)
        for (status in listOf(full, empty)) {
            val p = Parcel.obtain()
            writeOffReportStatus(p, status)
            p.setDataPosition(0)
            assertEquals(status, readOffReportStatus(p))
            p.recycle()
        }
    }

    /** The status reply field by field, in wire order, so a test can stop after any of them. */
    private val statusFields: List<(Parcel) -> Unit> = listOf(
        { it.writeString("a1") }, { it.writeInt(OffReportState.ARMED) }, { it.writeLong(0L) }, { it.writeLong(0L) },
        { it.writeInt(0) }, { it.writeString("-") },
        { it.writeString("a1") }, { it.writeLong(5_000L) }, { it.writeInt(2) }, { it.writeInt(1) },
        { it.writeString("a0") }, { it.writeInt(OffReportState.SENT) }, { it.writeLong(1_000L) }, { it.writeLong(2_000L) },
        { it.writeInt(1) }, { it.writeString("200") },
        { it.writeInt(2) },
        { it.writeInt(1001) }, { it.writeInt(315621418) }, { it.writeString("OK") },
        { it.writeInt(1023) }, { it.writeInt(315621408) }, { it.writeString("OK") },
        { it.writeInt(1) },
    )

    @Test fun `a status cut short anywhere is refused, never half read`() {
        for (cut in 0 until statusFields.size) {
            val p = Parcel.obtain()
            statusFields.take(cut).forEach { it(p) }
            p.setDataPosition(0)
            assertNull("cut after $cut fields", readOffReportStatus(p))
            p.recycle()
        }
        val whole = Parcel.obtain()
        statusFields.forEach { it(whole) }
        whole.setDataPosition(0)
        assertEquals(2, readOffReportStatus(whole)!!.fids.size)
        whole.recycle()
    }

    @Test fun `a status with a bad flag, an absurd fid or pending count is refused`() {
        val tails = listOf(
            listOf(2), listOf(0, -1), listOf(0, OFF_REPORT_MAX_FIDS + 1), listOf(0, 1_000_000),
            listOf(0, 0, -1), listOf(0, 0, OFF_REPORT_MAX_PENDING + 1),
        )
        for (tail in tails) {
            val p = Parcel.obtain()
            p.writeString("a1"); p.writeInt(1); p.writeLong(0L); p.writeLong(0L); p.writeInt(0); p.writeString("-")
            p.writeString(""); p.writeLong(0L); p.writeInt(-1)
            tail.forEach { p.writeInt(it) }
            p.setDataPosition(0)
            assertNull("tail $tail", readOffReportStatus(p))
            p.recycle()
        }
    }

    @Test fun `an arm cut short or oversized is refused`() {
        val armFields: List<(Parcel) -> Unit> =
            listOf(
                { it.writeString("a1") }, { it.writeString("123:tok") }, { it.writeLong(7L) }, { it.writeString("text") },
                { it.writeString("late") },
            )
        for (cut in 0 until armFields.size) {
            val c = Parcel.obtain()
            armFields.take(cut).forEach { it(c) }
            c.setDataPosition(0)
            assertNull("cut after $cut fields", readOffReportArm(c))
            c.recycle()
        }
        val long = Parcel.obtain()
        writeOffReportArm(long, "a1", "123:tok", 7L, "x".repeat(OFF_REPORT_MAX_TEXT + 1), "")
        long.setDataPosition(0)
        assertNull(readOffReportArm(long))
        long.recycle()
        val longId = Parcel.obtain()
        writeOffReportArm(longId, "a".repeat(OFF_REPORT_MAX_ID + 1), "123:tok", 7L, "t", "")
        longId.setDataPosition(0)
        assertNull(readOffReportArm(longId))
        longId.recycle()
        val longMark = Parcel.obtain()
        writeOffReportArm(longMark, "a1", "123:tok", 7L, "t", "x".repeat(OFF_REPORT_MAX_LATE_MARK + 1))
        longMark.setDataPosition(0)
        assertNull(readOffReportArm(longMark))
        longMark.recycle()
    }
}
