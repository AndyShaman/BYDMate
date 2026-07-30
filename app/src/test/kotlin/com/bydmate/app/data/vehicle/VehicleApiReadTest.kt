package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleApiReadTest {

    private val parsReader: ParsReader = mockk()
    private val autoservice: AutoserviceClient = mockk()
    private val helper: HelperClient = mockk(relaxed = true)
    private val allowlist = WriteAllowlist.EMPTY
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)
    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) {}
    }

    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) {}
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) {}
    }
    private val api: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, windowStore)

    @Test fun `readSnapshot delegates to ParsReader fetch`() = runTest {
        val expected = diParsData(soc = 73, speed = 0)
        coEvery { parsReader.fetch() } returns expected
        assertEquals(expected, api.readSnapshot())
    }

    @Test fun `readSnapshot returns null when reader returns null`() = runTest {
        coEvery { parsReader.fetch() } returns null
        assertNull(api.readSnapshot())
    }

    @Test fun `readBatterySnapshot delegates to AutoserviceClient`() = runTest {
        val br = BatteryReading(
            sohPercent = 100f, socPercent = 73.5f,
            lifetimeKwh = 1234f, lifetimeMileageKm = 5678f,
            voltage12v = 12.7f, readAtMs = 1L
        )
        coEvery { autoservice.readBatterySnapshot() } returns br
        assertEquals(br, api.readBatterySnapshot())
    }

    @Test fun `isAvailable delegates to AutoserviceClient`() = runTest {
        coEvery { autoservice.isAvailable() } returns true
        assertTrue(api.isAvailable())
    }

    @Test fun `readAcTemp delegates to AutoserviceClient getInt dev=1000 fid=1077936168`() = runTest {
        coEvery { autoservice.getInt(1000, 1077936168) } returns 23
        assertEquals(23, api.readAcTemp())
    }

    @Test fun `readSoc delegates to AutoserviceClient getFloat dev=1014 fid=1246777400`() = runTest {
        coEvery { autoservice.getFloat(1014, 1246777400) } returns 73.5f
        assertEquals(73.5f, api.readSoc())
    }

    // ── #79: rear-right window read across generations ────────────────────────
    @Test fun `readWindowRearRight returns the DiLink 5 fid value`() = runTest {
        coEvery { autoservice.getIntRaw(1001, 947912752) } returns 40

        assertEquals(40, api.readWindowRearRight())
    }

    @Test fun `readWindowRearRight falls back to the alternative fid on a link error`() = runTest {
        coEvery { autoservice.getIntRaw(1001, 947912752) } returns 65535
        coEvery { autoservice.getInt(1001, 1267728408) } returns 80

        assertEquals(80, api.readWindowRearRight())
    }

    /** Any other sentinel is a transient fault, not a missing fid — no cross-fid answer. */
    @Test fun `readWindowRearRight reports null on a transient sentinel`() = runTest {
        coEvery { autoservice.getIntRaw(1001, 947912752) } returns -10013

        assertNull(api.readWindowRearRight())
    }

    @Test fun `readWindowRearRight reports null when the read itself fails`() = runTest {
        coEvery { autoservice.getIntRaw(1001, 947912752) } returns null

        assertNull(api.readWindowRearRight())
    }
}
