package com.bydmate.app.data.tpms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsSlotResult
import com.bydmate.app.helper.dtc.TpmsStatus
import com.bydmate.app.helper.dtc.TpmsVerdict
import com.bydmate.app.helper.dtc.TpmsWire
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The repository runs on the scope it is given, so the test scheduler decides when a poll happens. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TpmsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("tpms", Context.MODE_PRIVATE)

    private val helper = mockk<HelperClient>()
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private var readReply: Int? = 0
    private var writeReply: Int? = 0
    private var statuses = ArrayDeque<TpmsStatus?>()
    private var fallbackStatus: TpmsStatus? = running(DtcWire.MODE_TPMS_READ)

    private var gear: Int? = 1
    private var sampleAgeMs = 1_000L
    private var wall = 1_000_000L

    // ids on the car: FL..RR
    private val a = 0x9A7BB4C2.toInt()
    private val b = 0x9A1561AF.toInt()
    private val c = 0x9A4B4AC0.toInt()
    private val d = 0x9A367593.toInt()
    private val e = 0x9A111111.toInt()
    private val carIds = listOf(a, b, c, d)
    private val dids = TpmsWire.DIDS

    @Before fun setUp() {
        prefs.edit().clear().commit()
        coEvery { helper.tpmsReadStart(any(), any()) } answers { readReply }
        coEvery { helper.tpmsWriteStart(any(), any(), any(), any(), any()) } answers { writeReply }
        coEvery { helper.tpmsStatus() } answers { statuses.removeFirstOrNull() ?: fallbackStatus }
    }

    private fun slotAnswer(did: Int, id: Int) =
        hex("62%04X%08X".format(did, id) + "000001011C0200")

    private fun running(mode: Int) = TpmsStatus(DtcWire.STATE_RUNNING, mode, "", null, null, null, emptyList(), "")

    private val f194 = hex("62F1944845001201F00056E0")

    private fun readDone(ids: List<Int> = carIds, identity: ByteArray? = f194) = TpmsStatus(
        DtcWire.STATE_DONE, DtcWire.MODE_TPMS_READ, "", identity, null, null,
        dids.indices.map { TpmsSlotResult(dids[it], slotAnswer(dids[it], ids[it]), null, null) }, "",
    )

    /** A finished write: [before] -> [after] on the car, [verdicts] per DID index (null = not planned). */
    private fun writeDone(before: List<Int>, after: List<Int?>, verdicts: List<TpmsVerdict?>) = TpmsStatus(
        DtcWire.STATE_DONE, DtcWire.MODE_TPMS_WRITE, "", f194, null, null,
        dids.indices.map {
            TpmsSlotResult(dids[it], slotAnswer(dids[it], before[it]), after[it]?.let { id -> slotAnswer(dids[it], id) }, verdicts[it])
        }, "",
    )

    private fun repo(scope: TestScope) = TpmsRepository(
        helper = helper,
        prefs = prefs,
        settings = settings,
        scope = scope.backgroundScope,
        liveSample = { gear?.let { TimedSnapshot(diParsData().copy(gear = it), 100_000L - sampleAgeMs) } },
        serviceRunning = { true },
        elapsedNow = { 100_000L },
        wallNow = { wall },
    )

    private suspend fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private suspend fun TestScope.openAndRead(r: TpmsRepository, status: TpmsStatus = readDone()) {
        statuses.addLast(status)
        r.onWindowOpened()
        runCurrent()
        settle()
    }

    private fun hx(id: Int) = "%08X".format(id)
    private fun idsOf(st: TpmsStatus?) = st!!.slots.map { TpmsWire.parseSlot(it.did, it.beforeUds)!!.id }

    private val allWritten = listOf(TpmsVerdict.WRITTEN, TpmsVerdict.WRITTEN, null, null)
    private val swapped = listOf(b, a, c, d)

    // read

    @Test fun `a fresh P opens the window with one read and the result is kept`() = runTest {
        val r = repo(this)
        openAndRead(r)
        val g = FidAddresses.of("gear")
        coVerify(exactly = 1) { helper.tpmsReadStart(g.device, g.fid) }
        assertEquals(TpmsPhase.IDLE, r.state.value.phase)
        assertEquals(carIds, idsOf(r.state.value.lastRead))
        assertNotNull(r.state.value.lastReadAt)
    }

    @Test fun `the last read survives a new repository on the same prefs`() = runTest {
        openAndRead(repo(this))
        val r2 = repo(this)
        assertEquals(carIds, idsOf(r2.state.value.lastRead))
    }

    @Test fun `a gear outside P or a stale sample sends nothing and keeps the last read`() = runTest {
        val r = repo(this)
        openAndRead(r)
        val kept = r.state.value.lastRead
        gear = 4
        r.onWindowOpened()
        runCurrent()
        sampleAgeMs = 11_000
        gear = 1
        r.onWindowOpened()
        runCurrent()
        coVerify(exactly = 1) { helper.tpmsReadStart(any(), any()) }
        assertSame(kept, r.state.value.lastRead)
    }

    @Test fun `canWrite is true only for the Leopard 3 shape`() = runTest {
        val ok = repo(this)
        openAndRead(ok)
        assertTrue(ok.state.value.canWrite)

        prefs.edit().clear().commit()
        val other = repo(this)
        openAndRead(other, readDone(identity = hex("62F1944846001201F00056E0")))
        assertFalse(other.state.value.canWrite)
    }

    // write

    @Test fun `a write with every target equal to the read never reaches the daemon`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.write(carIds.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    @Test fun `a write outside P is refused with NOT_PARK`() = runTest {
        val r = repo(this)
        openAndRead(r)
        gear = 4
        r.write(listOf(hx(e), null, null, null), null, 0, carIds)
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
        assertEquals(TpmsError.NOT_PARK, r.state.value.error)
    }

    @Test fun `a write with a duplicate ID never reaches the daemon`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.write(listOf(hx(d), null, null, null), null, 0, carIds)
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    @Test fun `a valid write sends exactly the changed entries once`() = runTest {
        val r = repo(this)
        openAndRead(r)
        statuses.addLast(writeDone(carIds, carIds, listOf(null, null, null, null)))
        r.write(listOf(hx(a), hx(b), hx(e), null), null, 2, carIds)
        runCurrent()
        val g = FidAddresses.of("gear")
        val s = FidAddresses.of("speed")
        coVerify(exactly = 1) {
            helper.tpmsWriteStart(g.device, g.fid, s.device, s.fid, listOf(TpmsPlanEntry(TpmsWire.DID_RL, c, e)))
        }
    }

    @Test fun `a complete write of winter in a swapped order makes winter the swapped order`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        statuses.addLast(writeDone(carIds, swapped, allWritten))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(swapped.map(::hx)), r.state.value.sets.winter)
        assertNotNull(r.state.value.lastWrite)
    }

    @Test fun `a complete single wheel write updates the set that is on the car`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        statuses.addLast(writeDone(carIds, listOf(a, b, e, d), listOf(null, null, TpmsVerdict.WRITTEN, null)))
        r.write(listOf(null, null, hx(e), null), null, 2, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(listOf(a, b, e, d).map(::hx)), r.state.value.sets.winter)
    }

    @Test fun `a partial write result leaves the sets as they were`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        statuses.addLast(writeDone(carIds, listOf(b, b, c, d), listOf(TpmsVerdict.WRITTEN, TpmsVerdict.REFUSED, null, null)))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(carIds.map(::hx)), r.state.value.sets.winter)
    }

    @Test fun `undo writes the before IDs expecting the read back IDs`() = runTest {
        val r = repo(this)
        openAndRead(r)
        statuses.addLast(writeDone(carIds, swapped, allWritten))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        r.undoLastWrite(swapped)
        runCurrent()
        coVerify(exactly = 1) {
            helper.tpmsWriteStart(any(), any(), any(), any(), listOf(
                TpmsPlanEntry(TpmsWire.DID_FL, b, a),
                TpmsPlanEntry(TpmsWire.DID_FR, a, b),
            ))
        }
    }

    // sets and concurrency

    @Test fun `takeFromCar copies the last read into the set without a helper call`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.SUMMER)
        assertEquals(TyreSet(carIds.map(::hx)), r.state.value.sets.summer)
        coVerify(exactly = 1) { helper.tpmsReadStart(any(), any()) }
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    @Test fun `a write is ignored while a read runs`() = runTest {
        val r = repo(this)
        openAndRead(r)
        fallbackStatus = running(DtcWire.MODE_TPMS_READ)
        r.onWindowOpened()
        runCurrent()
        assertEquals(TpmsPhase.READING, r.state.value.phase)
        r.write(listOf(hx(e), null, null, null), null, 0, carIds)
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    @Test fun `a second write is ignored while a write runs`() = runTest {
        val r = repo(this)
        openAndRead(r)
        fallbackStatus = running(DtcWire.MODE_TPMS_WRITE)
        r.write(listOf(hx(e), null, null, null), null, 0, carIds)
        runCurrent()
        assertEquals(TpmsPhase.WRITING, r.state.value.phase)
        r.write(listOf(null, hx(e), null, null), null, 1, carIds)
        runCurrent()
        coVerify(exactly = 1) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    // Review round: the shown «было» (C7)

    @Test fun `C7 a write whose shown list differs from the last read never reaches the daemon`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.write(listOf(hx(e), null, null, null), null, 0, listOf(b, a, c, d))
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
        assertEquals(TpmsError.REFUSED, r.state.value.error)
        assertEquals(TpmsPhase.IDLE, r.state.value.phase)
    }

    @Test fun `C7 a shown list with an unread slot never matches a full read`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.write(listOf(hx(e), null, null, null), null, 0, listOf(a, b, c, null))
        runCurrent()
        coVerify(exactly = 0) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
        assertEquals(TpmsError.REFUSED, r.state.value.error)
    }

    @Test fun `C7 a write whose shown list equals the last read goes to the daemon`() = runTest {
        val r = repo(this)
        openAndRead(r)
        statuses.addLast(writeDone(carIds, carIds, listOf(null, null, null, null)))
        r.write(listOf(hx(e), null, null, null), null, 0, carIds)
        runCurrent()
        coVerify(exactly = 1) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
    }

    @Test fun `C7 an undo whose shown list differs from the IDs now in the block never reaches the daemon`() = runTest {
        val r = repo(this)
        openAndRead(r)
        statuses.addLast(writeDone(carIds, swapped, allWritten))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        r.undoLastWrite(carIds)
        runCurrent()
        coVerify(exactly = 1) { helper.tpmsWriteStart(any(), any(), any(), any(), any()) }
        assertEquals(TpmsError.REFUSED, r.state.value.error)
    }

    // C10: when a write is complete

    @Test fun `C10 a planned entry found already in place with the others written still completes the write`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        statuses.addLast(writeDone(carIds, swapped, listOf(TpmsVerdict.WRITTEN, TpmsVerdict.ALREADY, null, null)))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(swapped.map(::hx)), r.state.value.sets.winter)
    }

    @Test fun `C10 every planned entry written but a read-back that is not the target leaves the sets as they were`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        // The car holds e where the target says a.
        statuses.addLast(writeDone(carIds, listOf(b, e, c, d), allWritten))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(carIds.map(::hx)), r.state.value.sets.winter)
    }

    @Test fun `C10 a write with a slot not read back leaves the sets as they were`() = runTest {
        val r = repo(this)
        openAndRead(r)
        r.takeFromCar(TyreSetName.WINTER)
        statuses.addLast(writeDone(carIds, listOf(b, a, c, null), allWritten))
        r.write(swapped.map(::hx), TyreSetName.WINTER, null, carIds)
        runCurrent()
        settle()
        assertEquals(TyreSet(carIds.map(::hx)), r.state.value.sets.winter)
    }

    // C11: a read that parsed nothing

    private fun unparsedRead() = TpmsStatus(
        DtcWire.STATE_DONE, DtcWire.MODE_TPMS_READ, "", f194, null, null,
        dids.map { TpmsSlotResult(it, hex("7F2231"), null, null) }, "",
    )

    @Test fun `C11 a read with no parsed slot keeps the previous read and reports the failure`() = runTest {
        val r = repo(this)
        openAndRead(r)
        val kept = r.state.value.lastRead
        val keptAt = r.state.value.lastReadAt
        wall += 60_000
        openAndRead(r, unparsedRead())
        assertSame(kept, r.state.value.lastRead)
        assertEquals(keptAt, r.state.value.lastReadAt)
        assertNotNull(r.state.value.error)
        assertEquals(TpmsPhase.IDLE, r.state.value.phase)
        // The saved read survives a restart too.
        assertEquals(carIds, idsOf(repo(this).state.value.lastRead))
    }

    @Test fun `C11 a first read with no parsed slot saves nothing`() = runTest {
        val r = repo(this)
        openAndRead(r, unparsedRead())
        assertNull(r.state.value.lastRead)
        assertNull(r.state.value.lastReadAt)
        assertNotNull(r.state.value.error)
        assertFalse(r.state.value.canWrite)
    }
}
