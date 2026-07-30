package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fleet-safety invariant for the DiLink 3.0 window channel (#79): a percent-capable
 * unit (PERCENT) and an undecided probe (UNKNOWN) must produce EXACTLY the writes they
 * produced before the router existed — same dev, same fid, same value. Only a fixed
 * CTRL channel changes the target.
 */
class VehicleApiWindowRoutingTest {

    private companion object {
        const val DEV = 1001
        const val DRIVER_POS_FID = 1276219408
        const val REAR_RIGHT_POS_FID = 1276219432
        const val DRIVER_CTRL_FID = 1125122104
        const val REAR_RIGHT_CTRL_FID = 1125122115
        /** The four percent READ fids the generation probe consults. */
        val PROBE_FIDS = listOf(947912728, 1267728400, 947912736, 947912752)
    }

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)

    private val allowlist = WriteAllowlist(
        (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED)
            .associateBy { it.actionName.lowercase() }
    )

    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) {}
    }

    private fun fixedStore(channel: WindowChannel) = object : WindowChannelStore {
        override fun winner() = channel
        override fun setWinner(channel: WindowChannel) {}
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) {}
    }

    private fun api(helper: HelperClient, channel: WindowChannel): VehicleApi =
        VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, fixedStore(channel))

    private fun writingHelper(): HelperClient {
        val helper = mockk<HelperClient>()
        coEvery { helper.write(any(), any(), any()) } returns true
        return helper
    }

    @Test fun `PERCENT channel writes the percent fid byte for byte`() = runTest {
        val helper = writingHelper()

        val result = api(helper, WindowChannel.PERCENT).writeWindowDriver(50)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 50) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_CTRL_FID, any()) }
    }

    @Test fun `UNKNOWN channel with an undecided probe writes the percent fid byte for byte`() = runTest {
        val helper = writingHelper()
        PROBE_FIDS.forEach { coEvery { helper.read(DEV, it) } returns null }

        val result = api(helper, WindowChannel.UNKNOWN).writeWindowDriver(50)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 50) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_CTRL_FID, any()) }
    }

    @Test fun `CTRL channel writes the ctrl fid with the mapped detent`() = runTest {
        val helper = writingHelper()

        val result = api(helper, WindowChannel.CTRL).writeWindowDriver(50)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_CTRL_FID, 4) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
    }

    @Test fun `CTRL channel routes the rear right window too`() = runTest {
        val helper = writingHelper()

        val result = api(helper, WindowChannel.CTRL).writeWindowRearRight(0)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, REAR_RIGHT_CTRL_FID, 2) }
        coVerify(exactly = 0) { helper.write(DEV, REAR_RIGHT_POS_FID, any()) }
    }

    @Test fun `composite command fans out on the percent fids under PERCENT`() = runTest {
        val helper = writingHelper()

        api(helper, WindowChannel.PERCENT).dispatch("车窗全开")

        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_RIGHT_POS_FID, 100) }
    }

    @Test fun `composite command fans out on the ctrl fids under CTRL`() = runTest {
        val helper = writingHelper()

        api(helper, WindowChannel.CTRL).dispatch("车窗全开")

        coVerify(exactly = 1) { helper.write(DEV, DRIVER_CTRL_FID, 1) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_RIGHT_CTRL_FID, 1) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
    }

    /** Vent commands resolve to VENT_PCT on the percent path; on CTRL they must land on
     *  the vent detent (5), not on a half/open detent. */
    @Test fun `vent command maps to the ctrl vent detent`() = runTest {
        val helper = writingHelper()

        api(helper, WindowChannel.CTRL).dispatch("主驾通风")

        coVerify(exactly = 1) { helper.write(DEV, DRIVER_CTRL_FID, 5) }
    }

    /** Non-window writes must not be touched by the router (and must not probe). */
    @Test fun `sunroof write is unaffected by the CTRL channel`() = runTest {
        val helper = writingHelper()

        api(helper, WindowChannel.CTRL).writeSunroof(SunroofMode.OPEN)

        coVerify(exactly = 1) { helper.write(DEV, 1125122056, 1) }
        coVerify(exactly = 0) { helper.read(any(), any()) }
    }
}
