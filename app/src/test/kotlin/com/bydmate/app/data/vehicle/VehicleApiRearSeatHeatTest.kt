package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.FidCatalog
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** dispatch() routes the rear seat heat commands (#327) through the gate, the write and the readback. */
class VehicleApiRearSeatHeatTest {
    private val helper: HelperClient = mockk()
    private val allowlist = WriteAllowlist.loadProduction { "{}" }
    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) = Unit
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }
    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) = Unit
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) = Unit
    }
    private val audit = mutableListOf<VehicleWriteLogEntity>()
    private val dao = mockk<VehicleWriteLogDao> { coEvery { insert(capture(audit)) } returns Unit }

    private fun api(catalog: FidCatalog? = RearSeatCatalogs.dl4) = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        dao, seatStore, windowStore, rearCatalog = { catalog },
    )

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    @Test fun `left level 1 writes 2 and succeeds when the state follows`() = runTest {
        coEvery { helper.read(1023, -933232629, any()) } returns 1L
        coEvery { helper.read(1023, 1214251019, any()) } returnsMany listOf(1L, 2L)
        coEvery { helper.writeStatus(1001, 1125122091, 2) } returns 1
        assertTrue(api().dispatch("后左座椅加热1档").isSuccess)
        val row = verdictRow()
        assertEquals("verdict=OK", row.error)
        assertEquals(2, row.readback)
    }

    @Test fun `a car without rear heat fails with NotEquipped and writes nothing`() = runTest {
        coEvery { helper.read(1023, -933232629, any()) } returns 0L
        val err = api().dispatch("后左座椅加热1档").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `without a catalog the command fails and writes nothing`() = runTest {
        val err = api(catalog = null).dispatch("后左座椅加热1档").exceptionOrNull()
        assertTrue("got $err", err != null)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `the default constructor has no catalog and writes nothing`() = runTest {
        val plain = VehicleApiImpl(
            mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
            dao, seatStore, windowStore,
        )
        assertTrue(plain.dispatch("后左座椅加热1档").isFailure)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a front seat command never reads the rear fids`() = runTest {
        coEvery { helper.writeStatus(any(), any(), any()) } returns 1
        coEvery { helper.read(any(), any(), any()) } returns 1L
        api().dispatch("主驾座椅加热1档")
        coVerify(exactly = 0) { helper.read(1023, -933232629, any()) }
        coVerify(exactly = 0) { helper.read(1023, 1214251019, any()) }
    }

    @Test fun `only the sides whose has reads 1 are offered`() = runTest {
        coEvery { helper.read(1023, -933232629, any()) } returns 1L
        coEvery { helper.read(1023, -933232621, any()) } returns 0L
        assertEquals(setOf(RearSeat.LEFT), api().rearSeatHeatSides())
    }
}
