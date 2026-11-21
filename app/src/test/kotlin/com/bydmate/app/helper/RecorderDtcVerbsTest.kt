package com.bydmate.app.helper

import android.os.IBinder
import android.os.Parcel
import com.bydmate.app.helper.dtc.DtcClock
import com.bydmate.app.helper.dtc.DtcDaemon
import com.bydmate.app.helper.dtc.DtcDevice
import com.bydmate.app.helper.dtc.DtcScanner
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsWire
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The start verbs of the diagnostic jobs and the speed read behind them. The daemon's scanner is
 * swapped for one whose jobs only queue, so a start stays «running» and the next start is busy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecorderDtcVerbsTest {

    private val iface = "test.autoservice"
    private val tasks = mutableListOf<Runnable>()

    /** What the autoservice answers to a speed read (tx 7) per device: the reply's ints after the interface token. */
    private var speedReply: (dev: Int) -> List<Int> = { emptyList() }

    private val svc: IBinder = mockk()

    private val s1 = 1001
    private val s2 = 1002
    private val plan = listOf(TpmsPlanEntry(TpmsWire.DID_FL, 0x11111111, 0x22222222))

    @Before fun setUp() {
        tasks.clear()
        val clock = object : DtcClock {
            override fun nowMs() = 0L
            override fun sleep(ms: Long) = Unit
        }
        val device = object : DtcDevice {
            override fun register(): String? = null
            override fun unregister() = Unit
            override fun send(payload: ByteArray) = 0
            override fun poll(timeoutMs: Long): ByteArray? = null
            override fun drain() = Unit
        }
        install(DtcScanner(device, clock, { DtcWire.GUARD_BASELINE }, runner = { tasks += it }, readSpeed = { DtcDaemon.speed?.invoke() }))
        DtcDaemon.speed = null
        speedReply = { dev -> if (dev == s1) listOf(0, 0) else listOf(0, java.lang.Float.floatToIntBits(5f)) }
        every { svc.transact(any(), any(), any(), any()) } answers {
            val data = secondArg<Parcel>()
            data.setDataPosition(0)
            data.readInt() // strict-mode policy
            data.readString() // interface token
            val dev = data.readInt()
            val reply = thirdArg<Parcel>()
            speedReply(dev).forEach { reply.writeInt(it) }
            reply.setDataPosition(0)
            true
        }
    }

    /** The lazy `scanner` of [DtcDaemon] holds the value in its `_value` field; the swap needs no production change. */
    private fun install(scanner: DtcScanner) {
        val holder = DtcDaemon::class.java.getDeclaredField("scanner\$delegate").apply { isAccessible = true }.get(DtcDaemon)
        holder.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(holder, scanner)
    }

    private fun writeData(speedDev: Int, n: Int, entries: List<TpmsPlanEntry>): Parcel = Parcel.obtain().apply {
        writeInt(1); writeInt(2); writeInt(speedDev); writeInt(3); writeInt(n)
        entries.forEach { writeInt(it.did); writeInt(it.expectedOld); writeInt(it.newId) }
        setDataPosition(0)
    }

    private fun eraseData(speedDev: Int, addrs: IntArray): Parcel = Parcel.obtain().apply {
        writeInt(1); writeInt(2); writeInt(speedDev); writeInt(3); writeIntArray(addrs)
        setDataPosition(0)
    }

    private fun call(code: Int, data: Parcel): Int {
        val reply = Parcel.obtain()
        handleDtcScanTransact(code, data, reply, svc, iface)
        reply.setDataPosition(0)
        return reply.readInt()
    }

    private fun startWrite(speedDev: Int) = call(HelperBinderProtocol.TX_TPMS_WRITE_START, writeData(speedDev, plan.size, plan))
    private fun startErase(speedDev: Int, addrs: IntArray = intArrayOf(0x712)) =
        call(HelperBinderProtocol.TX_DTC_ERASE_START, eraseData(speedDev, addrs))

    /** What the running job's next gate would read now. */
    private fun speedNow(): Int? = DtcDaemon.speed?.invoke()

    // C1

    @Test fun `C1 a write start answered busy leaves the speed source of the running write`() {
        assertEquals(DtcScanner.START_OK, startWrite(s1))
        assertEquals(0, speedNow())
        assertEquals(DtcScanner.START_BUSY, startWrite(s2))
        assertEquals(0, speedNow())
    }

    @Test fun `C1 an erase start answered busy leaves the speed source of the running write`() {
        assertEquals(DtcScanner.START_OK, startWrite(s1))
        assertEquals(DtcScanner.START_BUSY, startErase(s2))
        assertEquals(0, speedNow())
    }

    @Test fun `C1 a write start answered busy leaves the speed source of the running erase`() {
        assertEquals(DtcScanner.START_OK, startErase(s1))
        assertEquals(DtcScanner.START_BUSY, startWrite(s2))
        assertEquals(0, speedNow())
    }

    @Test fun `C1 a refused write start sets no speed source`() {
        // A plan of 9 entries is refused unread; an invalid plan by the scanner.
        assertEquals(DtcScanner.START_REFUSED, call(HelperBinderProtocol.TX_TPMS_WRITE_START, writeData(s2, 9, emptyList())))
        assertNull(DtcDaemon.speed)
        val same = listOf(TpmsPlanEntry(TpmsWire.DID_FL, 5, 5))
        assertEquals(DtcScanner.START_REFUSED, call(HelperBinderProtocol.TX_TPMS_WRITE_START, writeData(s2, 1, same)))
        assertNull(DtcDaemon.speed)
    }

    @Test fun `C1 a refused erase start sets no speed source`() {
        assertEquals(DtcScanner.START_REFUSED, startErase(s2, IntArray(0)))
        assertNull(DtcDaemon.speed)
        assertEquals(DtcScanner.START_REFUSED, startErase(s2, intArrayOf(0x700)))
        assertNull(DtcDaemon.speed)
    }

    @Test fun `C1 an accepted start sets its own speed source`() {
        assertEquals(DtcScanner.START_OK, startWrite(s2))
        assertEquals(5, speedNow())
    }

    // C2

    @Test fun `C2 a speed reply with status 0 and no value word is unknown, not 0`() {
        speedReply = { listOf(0) }
        assertEquals(DtcScanner.START_OK, startWrite(s1))
        assertNull(speedNow())
    }

    @Test fun `C2 a speed reply with status 0 and a value word of 0 is speed 0`() {
        speedReply = { listOf(0, 0) }
        assertEquals(DtcScanner.START_OK, startWrite(s1))
        assertEquals(0, speedNow())
    }

    @Test fun `C2 a speed reply with a value rounds away from zero`() {
        speedReply = { listOf(0, java.lang.Float.floatToIntBits(1.2f)) }
        assertEquals(DtcScanner.START_OK, startErase(s1))
        assertEquals(2, speedNow())
    }

    @Test fun `C2 an empty speed reply or a non-zero status is unknown`() {
        speedReply = { emptyList() }
        assertEquals(DtcScanner.START_OK, startWrite(s1))
        assertNull(speedNow())
        speedReply = { listOf(-1, 0) }
        assertNull(speedNow())
    }

    @Test fun `C2 other callers of autoserviceTransact keep status 0 with value 0 for a reply without a value word`() {
        speedReply = { listOf(0) }
        assertEquals(0 to 0, autoserviceTransact(svc, iface, 7, s1, 3, 0, writeValue = false))
        speedReply = { listOf(0, 42) }
        assertEquals(0 to 42, autoserviceTransact(svc, iface, 5, s1, 3, 0, writeValue = false))
    }
}
