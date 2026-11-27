package com.bydmate.app.data.vehicle

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.FidCatalog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #441: two-level seat heat on the fallback channel (Song Plus EV 2025 and similar),
 * tested through the existing [VehicleApiImpl.dispatch] entry point with [FidCatalog].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VehicleApiSeatFallbackTwoLevelTest {

    private class FakeStore(var w: SeatChannel) : SeatChannelStore {
        private var spent = false
        override fun winner() = w
        override fun setWinner(channel: SeatChannel) {
            if (channel == SeatChannel.UNKNOWN) spent = false
            w = channel
        }
        override fun reprobeExhausted() = spent
        override fun claimReprobe(): Boolean = if (spent) false else { spent = true; true }
    }

    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) = Unit
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) = Unit
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val allowlist = WriteAllowlist.loadProduction { "{}" }
    private val dao: VehicleWriteLogDao = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private lateinit var helper: HelperClient
    private lateinit var writes: MutableList<Triple<Int, Int, Int>>

    private val canFdCatalog: FidCatalog = FidCatalog.parse(
        """
        Setting.SET_DRIVER_SEAT_HEATING_STATE=$DRIVER_STATE_CANFD
        Setting.SET_PASSENGER_SEAT_HEATING_STATE=$PASSENGER_STATE_CANFD
        """.trimIndent()
    )

    private val nonCanFdCatalog: FidCatalog = FidCatalog.parse(
        """
        Setting.SET_DRIVER_SEAT_HEATING_STATE=$DRIVER_STATE_NON_CANFD
        Setting.SET_PASSENGER_SEAT_HEATING_STATE=$PASSENGER_STATE_NON_CANFD
        """.trimIndent()
    )

    private val toyotaCatalog: FidCatalog = FidCatalog.parse(
        """
        Setting.SET_DRIVER_SEAT_HEATING_STATE=1221591056
        Setting.SET_PASSENGER_SEAT_HEATING_STATE=880803851
        """.trimIndent()
    )

    @Before fun setUp() {
        app.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        writes = mutableListOf()
        helper = mockk(relaxed = true)
        coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
            writes += Triple(firstArg(), secondArg(), thirdArg())
            1
        }
    }

    private fun journal() = SeatCommandJournal(
        app.getSharedPreferences(JOURNAL_PREFS, Context.MODE_PRIVATE)
    )

    private fun api(
        winner: SeatChannel = SeatChannel.FALLBACK,
        catalog: FidCatalog? = canFdCatalog,
        withJournal: SeatCommandJournal? = null,
    ): VehicleApiImpl = VehicleApiImpl(
        autoservice = autoservice,
        helper = helper,
        allowlist = allowlist,
        writeLogDao = dao,
        seatStore = FakeStore(winner),
        windowStore = windowStore,
        seatJournal = withJournal,
        rearCatalog = { catalog },
    )

    private fun fallbackValues(fid: Int): List<Int> =
        writes.filter { it.first == DEV_FALLBACK && it.second == fid }.map { it.third }

    // Case 1: Fallback CanFD, driver level 3, readback 1 -> writes [6, 3]; readback 2 -> writes [6, 3].
    @Test fun `fallback CanFD driver level 3 clamps to 3 when state reads 1 or 2`() = runTest {
        for (state in listOf(1L, 2L)) {
            writes.clear()
            coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns state

            assertTrue(api().dispatch("主驾座椅加热3档").isSuccess)

            assertEquals("state=$state", listOf(6, 3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
        }
    }

    // Case 2: Same for the passenger: [4, 3].
    @Test fun `fallback CanFD passenger level 3 clamps to 3 when state reads 1 or 2`() = runTest {
        for (state in listOf(1L, 2L)) {
            writes.clear()
            coEvery { helper.read(DEV_SETTING, PASSENGER_STATE_CANFD, any()) } returns state

            assertTrue(api().dispatch("副驾座椅加热3档").isSuccess)

            assertEquals("state=$state", listOf(4, 3), fallbackValues(PASSENGER_HEAT_FALLBACK_FID))
        }
    }

    // Case 3: Fallback, driver level 3, readback 3, 4 or 6 -> writes [6] only.
    @Test fun `fallback driver level 3 writes 6 only when readback shows 3, 4 or 6`() = runTest {
        for (state in listOf(3L, 4L, 6L)) {
            writes.clear()
            io.mockk.clearMocks(helper, answers = false, recordedCalls = true)
            coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns state

            assertTrue(api().dispatch("主驾座椅加热3档").isSuccess)

            assertEquals("state=$state", listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
            coVerify(atLeast = 1) { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) }
        }
    }

    // Case 4: Fallback, driver level 3, readback null or error, or unknown platform -> writes [6] only.
    @Test fun `fallback driver level 3 writes 6 only when readback fails or returns error`() = runTest {
        for (readValue in listOf(null, -10011L, 65535L)) {
            writes.clear()
            io.mockk.clearMocks(helper, answers = false, recordedCalls = true)
            coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns readValue

            assertTrue(api().dispatch("主驾座椅加热3档").isSuccess)

            assertEquals("read=$readValue", listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
            coVerify(atLeast = 1) { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) }
        }

        writes.clear()
        io.mockk.clearMocks(helper, answers = false, recordedCalls = true)
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } throws IllegalStateException("binder dead")

        assertTrue(api().dispatch("主驾座椅加热3档").isSuccess)

        assertEquals(listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
        coVerify(atLeast = 1) { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) }
    }

    @Test fun `fallback driver level 3 writes 6 only on unknown platform without reading state`() = runTest {
        for (catalog in listOf(null, FidCatalog.EMPTY, toyotaCatalog)) {
            writes.clear()
            assertTrue(api(catalog = catalog).dispatch("主驾座椅加热3档").isSuccess)
            assertEquals("catalog=$catalog", listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
            coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }
        }
    }

    // Case 5: Fallback, driver level 4 and 5 -> exactly today's writes, no state read.
    @Test fun `fallback driver level 4 and 5 keep todays writes with no state read`() = runTest {
        assertTrue(api().dispatch("主驾座椅加热4档").isSuccess)
        assertEquals(listOf(5), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        writes.clear()
        assertTrue(api().dispatch("主驾座椅加热5档").isSuccess)
        assertEquals(listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }
    }

    // Case 6: Fallback non-CanFD platform, driver level 3, state fid 1335885851 reads 1 -> writes [6, 3].
    @Test fun `fallback non-CanFD platform resolves 1335885851 for driver level 3`() = runTest {
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_NON_CANFD, any()) } returns 1L

        assertTrue(api(catalog = nonCanFdCatalog).dispatch("主驾座椅加热3档").isSuccess)

        assertEquals(listOf(6, 3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
        coVerify(exactly = 0) { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) }
    }

    // Case 7: Fallback, level 1 and level 2 -> exactly today's writes, no extra write.
    @Test fun `fallback level 1 and 2 keep todays writes with no extra write`() = runTest {
        assertTrue(api().dispatch("主驾座椅加热1档").isSuccess)
        assertEquals(listOf(2), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        writes.clear()
        assertTrue(api().dispatch("主驾座椅加热2档").isSuccess)
        assertEquals(listOf(3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }
    }

    // Case 8: Fallback first use (the probe path that writes universal 2 first, then 6):
    // state reads 2 afterwards -> one extra write of 3.
    @Test fun `fallback probe path writes 2, 6 then 3 when state stays 2`() = runTest {
        val store = FakeStore(SeatChannel.UNKNOWN)
        coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
            val dev = firstArg<Int>()
            val fid = secondArg<Int>()
            val value = thirdArg<Int>()
            writes += Triple(dev, fid, value)
            if (dev == DEV_PRIMARY) -10011 else 1
        }
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 2L

        val subject = VehicleApiImpl(
            autoservice = autoservice,
            helper = helper,
            allowlist = allowlist,
            writeLogDao = dao,
            seatStore = store,
            windowStore = windowStore,
            rearCatalog = { canFdCatalog },
        )

        assertTrue(subject.dispatch("主驾座椅加热3档").isSuccess)
        assertEquals(SeatChannel.FALLBACK, store.winner())
        assertEquals(listOf(2, 6, 3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
    }

    // Case 9: Primary channel, any level -> exactly today's writes and journal, no state read for the clamp.
    @Test fun `primary channel keeps todays writes and journal with no dev 1023 state read`() = runTest {
        val j = journal()
        coEvery { helper.read(DEV_PRIMARY, DRIVER_HEAT_STATUS_FID, 5) } returns 1L
        val subject = api(winner = SeatChannel.PRIMARY, catalog = canFdCatalog, withJournal = j)

        for (lvl in 1..5) {
            writes.clear()
            assertTrue("lvl=$lvl", subject.dispatch("主驾座椅加热${lvl}档").isSuccess)
            assertEquals(
                listOf(
                    Triple(DEV_PRIMARY, DRIVER_HEAT_SWITCH_FID, 1),
                    Triple(DEV_PRIMARY, DRIVER_HEAT_LEVEL_FID, lvl),
                ),
                writes,
            )
        }

        writes.clear()
        coEvery { helper.read(DEV_PRIMARY, DRIVER_HEAT_STATUS_FID, 5) } returns 2L
        assertTrue(subject.dispatch("主驾座椅加热关闭").isSuccess)
        assertEquals(listOf(Triple(DEV_PRIMARY, DRIVER_HEAT_SWITCH_FID, 2)), writes)

        coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }

        val lines = j.lines()
        // 5 on-commands (3 lines each) + 1 off-command (2 lines) = 17 journal lines.
        assertEquals(17, lines.size)
        assertTrue(lines.none { it.contains("ch=fallback") })
        assertTrue(lines.any { it.contains("W driver_seat_heat_level ch=primary dev=1000 fid=1276252180 val=3 status=1 outcome=REAL") })
        assertTrue(lines.any { it.contains("A DRIVER_HEAT lvl=3 ch=primary outcome=REAL rb=expect=1 got=1") })
    }

    @Test fun `fallback driver level 3 clamps to 3 when 6 answers NOOP and state reads 1 or 2`() = runTest {
        for (state in listOf(1L, 2L)) {
            writes.clear()
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                val dev = firstArg<Int>()
                val fid = secondArg<Int>()
                val value = thirdArg<Int>()
                writes += Triple(dev, fid, value)
                if (value == 6) 0 else 1
            }
            coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns state

            assertTrue("state=$state", api().dispatch("主驾座椅加热3档").isSuccess)
            assertEquals("state=$state", listOf(6, 3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
        }
    }

    @Test fun `fallback passenger level 3 clamps to 3 when 4 answers NOOP and state reads 1 or 2`() = runTest {
        for (state in listOf(1L, 2L)) {
            writes.clear()
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                val dev = firstArg<Int>()
                val fid = secondArg<Int>()
                val value = thirdArg<Int>()
                writes += Triple(dev, fid, value)
                if (value == 4) 0 else 1
            }
            coEvery { helper.read(DEV_SETTING, PASSENGER_STATE_CANFD, any()) } returns state

            assertTrue("state=$state", api().dispatch("副驾座椅加热3档").isSuccess)
            assertEquals("state=$state", listOf(4, 3), fallbackValues(PASSENGER_HEAT_FALLBACK_FID))
        }
    }

    @Test fun `fallback driver level 3 writes 6 only and is not success when 6 answers NOOP and state reads 6`() = runTest {
        coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
            writes += Triple(firstArg(), secondArg(), thirdArg())
            0
        }
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 6L

        assertFalse(api().dispatch("主驾座椅加热3档").isSuccess)
        assertEquals(listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
    }

    @Test fun `fallback driver level 3 skips state read when first write is TRANSIENT`() = runTest {
        for (transientStatus in listOf(null, -1)) {
            writes.clear()
            io.mockk.clearMocks(helper, answers = false, recordedCalls = true)
            coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers {
                writes += Triple(firstArg(), secondArg(), thirdArg())
                transientStatus
            }

            assertFalse("status=$transientStatus", api().dispatch("主驾座椅加热3档").isSuccess)
            assertEquals("status=$transientStatus", listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
            coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }
        }
    }

    // Q1 (#441): a newer command for the same seat cancels a pending two-level clamp.
    @Test fun `newer command for the same driver seat cancels pending two-level clamp`() = runTest {
        val subject = api()
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 1L

        val first = async { subject.dispatch("主驾座椅加热3档") }
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        assertTrue(subject.dispatch("主驾座椅加热关闭").isSuccess)
        advanceUntilIdle()

        assertTrue(first.await().isSuccess)
        assertEquals(listOf(6, 1), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
    }

    @Test fun `newer command for the same passenger seat cancels pending two-level clamp`() = runTest {
        val subject = api()
        coEvery { helper.read(DEV_SETTING, PASSENGER_STATE_CANFD, any()) } returns 1L

        val first = async { subject.dispatch("副驾座椅加热3档") }
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf(4), fallbackValues(PASSENGER_HEAT_FALLBACK_FID))

        assertTrue(subject.dispatch("副驾座椅加热关闭").isSuccess)
        advanceUntilIdle()

        assertTrue(first.await().isSuccess)
        assertEquals(listOf(4, 1), fallbackValues(PASSENGER_HEAT_FALLBACK_FID))
    }

    @Test fun `command for the other seat does not cancel pending two-level clamp`() = runTest {
        val subject = api()
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 1L

        val first = async { subject.dispatch("主驾座椅加热3档") }
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf(6), fallbackValues(DRIVER_HEAT_FALLBACK_FID))

        assertTrue(subject.dispatch("副驾座椅加热关闭").isSuccess)
        advanceUntilIdle()

        assertTrue(first.await().isSuccess)
        assertEquals(listOf(6, 3), fallbackValues(DRIVER_HEAT_FALLBACK_FID))
        assertEquals(listOf(1), fallbackValues(PASSENGER_HEAT_FALLBACK_FID))
    }

    private companion object {
        const val JOURNAL_PREFS = "seat_journal_two_level_test"
        const val DEV_PRIMARY = 1000
        const val DEV_FALLBACK = 1001
        const val DEV_SETTING = 1023
        const val DRIVER_HEAT_SWITCH_FID = 1276248084
        const val DRIVER_HEAT_LEVEL_FID = 1276252180
        const val DRIVER_HEAT_STATUS_FID = 702545932
        const val DRIVER_HEAT_FALLBACK_FID = 1125122068
        const val PASSENGER_HEAT_FALLBACK_FID = 1125122076
        const val DRIVER_STATE_CANFD = 1335885835
        const val DRIVER_STATE_NON_CANFD = 1335885851
        const val PASSENGER_STATE_CANFD = 1335885843
        const val PASSENGER_STATE_NON_CANFD = 1335885859
    }
}
