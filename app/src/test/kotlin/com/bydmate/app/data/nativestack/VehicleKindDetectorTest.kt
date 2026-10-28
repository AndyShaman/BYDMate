package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleKindDetectorTest {

    private data class Sample(val label: String, val hev: Int, val ev: Int, val fuel: Int, val expected: VehicleKind)

    /** Raw words measured in diagnostic dumps (resources/vehicle-kind/samples.tsv). */
    private fun samples(): List<Sample> =
        requireNotNull(javaClass.classLoader?.getResource("vehicle-kind/samples.tsv")).readText()
            .lines().drop(1).filter { it.isNotBlank() }
            .map { line ->
                val c = line.split('\t')
                Sample(c[0], c[1].toInt(), c[2].toInt(), c[3].toInt(), VehicleKind.valueOf(c[4]))
            }

    @Test fun `every measured dump classifies as its car`() {
        val all = samples()
        assertEquals(35, all.size)
        all.forEach { s ->
            assertEquals(s.label, s.expected, VehicleKindDetector.classify(s.hev, s.ev, s.fuel))
        }
    }

    @Test fun `one sentinel next to a live second value is not an EV`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(1048575, 9240, 54))
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(3812, 9240, 255))
    }

    @Test fun `a zero HEV odometer is a hybrid only with a real fuel level`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(0, 9240, 255))
        assertEquals(VehicleKind.HYBRID, VehicleKindDetector.classify(0, 9240, 40))
    }

    @Test fun `any failed read is UNKNOWN`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(null, 9240, 54))
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(3812, null, 54))
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(3812, 9240, null))
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(null, null, null))
    }

    @Test fun `a fuel level above 100 is UNKNOWN`() {
        assertEquals(VehicleKind.UNKNOWN, VehicleKindDetector.classify(3812, 9240, 101))
    }

    @Test fun `error sentinels and failed statuses read as null, the EV marker stays`() {
        assertEquals(1048575, VehicleKindDetector.word(0, 1048575))
        assertEquals(3812, VehicleKindDetector.word(0, 3812))
        assertNull(VehicleKindDetector.word(0, 65535))
        assertNull(VehicleKindDetector.word(0, -10011))
        assertNull(VehicleKindDetector.word(0, -10013))
        assertNull(VehicleKindDetector.word(-998, 3812))
    }

    @Test fun `a second identical result confirms only 10 s after the first`() {
        val c = VehicleKindConfirmation()
        assertNull(c.offer(VehicleKind.HYBRID, 0L))
        assertNull(c.offer(VehicleKind.HYBRID, 5_000L))
        assertEquals(VehicleKind.HYBRID, c.offer(VehicleKind.HYBRID, 10_000L))
    }

    @Test fun `a different result restarts the count`() {
        val c = VehicleKindConfirmation()
        assertNull(c.offer(VehicleKind.HYBRID, 0L))
        assertNull(c.offer(VehicleKind.EV, 10_000L))
        assertNull(c.offer(VehicleKind.EV, 15_000L))
        assertEquals(VehicleKind.EV, c.offer(VehicleKind.EV, 20_000L))
    }

    @Test fun `UNKNOWN never confirms`() {
        val c = VehicleKindConfirmation()
        repeat(20) { i -> assertNull(c.offer(VehicleKind.UNKNOWN, i * 10_000L)) }
    }

    @Test fun `UNKNOWN between two identical results does not break the count`() {
        val c = VehicleKindConfirmation()
        assertNull(c.offer(VehicleKind.HYBRID, 0L))
        assertNull(c.offer(VehicleKind.UNKNOWN, 5_000L))
        assertEquals(VehicleKind.HYBRID, c.offer(VehicleKind.HYBRID, 10_000L))
    }

    @Test fun `dump line of a confirmed kind`() {
        assertEquals(
            "vehicle_kind: HYBRID hev=3812 ev=9240 fuel=54 confirmed=2026-10-11T07:15:00Z",
            VehicleKindDetector.dumpLine(VehicleKind.HYBRID, "hev=3812;ev=9240;fuel=54", 1_791_702_900_000L, null),
        )
    }

    @Test fun `dump line of UNKNOWN shows the last read`() {
        assertEquals(
            "vehicle_kind: UNKNOWN last=hev=?;ev=?;fuel=?",
            VehicleKindDetector.dumpLine(VehicleKind.UNKNOWN, null, null, VehicleKindDetector.evidence(null, null, null)),
        )
        assertEquals("vehicle_kind: UNKNOWN last=(none)", VehicleKindDetector.dumpLine(VehicleKind.UNKNOWN, null, null, null))
    }

    // --- VehicleKindMonitor: the read rounds and the cache ---

    private class Store {
        val values = mutableMapOf<String, String>()
        val settings: SettingsRepository = mockk<SettingsRepository>().also { s ->
            val key = slot<String>()
            val default = slot<String>()
            coEvery { s.getString(capture(key), capture(default)) } answers { values[key.captured] ?: default.captured }
            val map = slot<Map<String, String>>()
            coEvery { s.setStrings(capture(map)) } answers { values.putAll(map.captured) }
        }
    }

    private fun helperAnswering(vararg replies: List<Pair<Int, Int>>?): HelperClient {
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(VehicleKindMonitor.ITEMS) } returnsMany replies.toList()
        return helper
    }

    private val hybridReply = listOf(0 to 3812, 0 to 9240, 0 to 54)

    @Test fun `two hybrid reads 10 s apart persist the kind and its evidence`() = runTest {
        val store = Store()
        val monitor = VehicleKindMonitor(store.settings, helperAnswering(hybridReply, hybridReply))

        monitor.onServiceStart(0L)
        assertEquals(VehicleKind.UNKNOWN, monitor.kind.value)
        assertFalse("a read 5 s later is not due", monitor.claimProbe(5_000L))
        assertTrue(monitor.claimProbe(10_000L))
        monitor.probe(10_000L)

        assertEquals(VehicleKind.HYBRID, monitor.kind.value)
        assertEquals("HYBRID", store.values[SettingsRepository.KEY_VEHICLE_KIND])
        assertEquals("hev=3812;ev=9240;fuel=54", store.values[SettingsRepository.KEY_VEHICLE_KIND_EVIDENCE])
        assertEquals("10000", store.values[SettingsRepository.KEY_VEHICLE_KIND_CONFIRMED_AT])
        assertFalse("confirmed: no reads until the daily recheck", monitor.claimProbe(20_000L))
        assertTrue(monitor.claimProbe(VehicleKindMonitor.RECHECK_MS))
    }

    @Test fun `awaitCache publishes the cached kind without a read`() = runTest {
        val store = Store()
        store.values[SettingsRepository.KEY_VEHICLE_KIND] = "HYBRID"
        val helper = mockk<HelperClient>()
        val monitor = VehicleKindMonitor(store.settings, helper)

        monitor.awaitCache()

        assertEquals(VehicleKind.HYBRID, monitor.kind.value)
        coVerify(exactly = 0) { helper.readBatch(any()) }
    }

    @Test fun `failed reads after a confirmation keep the cached kind`() = runTest {
        val store = Store()
        store.values[SettingsRepository.KEY_VEHICLE_KIND] = "HYBRID"
        val monitor = VehicleKindMonitor(store.settings, helperAnswering(null, listOf(-998 to 0, -998 to 0, -998 to 0)))

        monitor.onServiceStart(0L)
        assertTrue(monitor.claimProbe(10_000L))
        monitor.probe(10_000L)

        assertEquals(VehicleKind.HYBRID, monitor.kind.value)
        assertEquals("HYBRID", store.values[SettingsRepository.KEY_VEHICLE_KIND])
        // The cache seeded here holds no evidence or time, the failed reads add none.
        assertEquals("vehicle_kind: HYBRID ? confirmed=?", monitor.dumpLine())
    }

    @Test fun `ten failed reads end the round until the daily recheck`() = runTest {
        val store = Store()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns null
        val monitor = VehicleKindMonitor(store.settings, helper)

        monitor.onServiceStart(0L)
        for (i in 1 until VehicleKindMonitor.MAX_ATTEMPTS) {
            val at = i * 10_000L
            assertTrue("attempt ${i + 1}", monitor.claimProbe(at))
            monitor.probe(at)
        }
        assertFalse(monitor.claimProbe(200_000L))
        coVerify(exactly = VehicleKindMonitor.MAX_ATTEMPTS) { helper.readBatch(any()) }
        assertEquals(VehicleKind.UNKNOWN, monitor.kind.value)
        assertTrue(store.values.isEmpty())
        assertEquals("vehicle_kind: UNKNOWN last=hev=?;ev=?;fuel=?", monitor.dumpLine())
    }

    /** Stage 1 must not grow the EV poll batch; the type probe runs as its own batch. */
    @Test fun `the live poll batch keeps its length`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 72.9
        val gate = mockk<BatchReadGate>()
        coEvery { gate.mode() } returns BatchMode.ACTIVE
        every { gate.recordBatchUnavailable() } just Runs
        val items = slot<List<BatchReadItem>>()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(capture(items)) } returns null
        coEvery { auto.isAvailable() } returns false

        NativeParsReader(auto, settings, helper, gate, kindMonitor()).fetch()

        assertEquals(EV_POLL_BATCH_SIZE, items.captured.size)
    }

    private companion object {
        const val EV_POLL_BATCH_SIZE = 98
    }
}
