package com.bydmate.app.data.push

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.nativestack.BatchMode
import com.bydmate.app.data.nativestack.BatchReadGate
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.FidEntry
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.NativeParsReader
import com.bydmate.app.data.nativestack.kindMonitor
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks that a value read by the poll and the same value pushed for the same fid land in the
 * snapshot identically. The poll (NativeParsReader.assembleSnapshot) and the push
 * (FidPushApplier setters) hold the same field table twice; a merge or split of either must not
 * change what a field ends up as.
 *
 * The poll returns no snapshot when no liveness fid (soc, mileage, voltage12v) answers, so every
 * poll here keeps one anchor fid answering: mileage, or soc when mileage itself is under test.
 * The baseline B is the poll snapshot with the anchor alone answering.
 */
class PollPushParityTest {

    private val tx5Samples = listOf(0, 1, 2, 7, 37, 50, 100, 120, 255, 400, 3700, 12345, 65535, -1, -40)
    private val tx7Samples = listOf(0.0f, 1.0f, 3.7f, 12.5f, 43.0f, 400.0f, -1.0f, -12.5f)

    /** Anchor answers: the liveness fid that keeps fetch() from returning null. */
    private fun anchorFor(field: String): Map<String, Number> =
        if (field == "mileage") mapOf("soc" to 50.0f) else mapOf("mileage" to 1000)

    // One set of mocks for the whole class, with the recorded calls dropped after every poll:
    // thousands of polls on fresh mocks keep enough call history to run the suite out of heap.
    private val byAddress = mutableMapOf<Pair<Int, Int>, Number>()
    private val auto = mockk<AutoserviceClient>().also { auto ->
        coEvery { auto.isAvailable() } returns true
        coEvery { auto.getInt(any(), any()) } coAnswers { byAddress[firstArg<Int>() to secondArg<Int>()] as? Int }
        coEvery { auto.getIntRaw(any(), any()) } coAnswers { byAddress[firstArg<Int>() to secondArg<Int>()] as? Int }
        coEvery { auto.getFloat(any(), any()) } coAnswers { byAddress[firstArg<Int>() to secondArg<Int>()] as? Float }
    }
    private val settings = mockk<SettingsRepository>().also { coEvery { it.getBatteryCapacity() } returns 72.9 }
    private val gate = mockk<BatchReadGate>().also { coEvery { it.mode() } returns BatchMode.OFF }
    private val helper = mockk<HelperClient>()

    /** The poll snapshot when exactly [answers] (field to raw sample) answer, nothing else. */
    private suspend fun poll(answers: Map<String, Number>): DiParsData {
        byAddress.clear()
        for ((field, raw) in answers) {
            val a = FidAddresses.of(field)
            byAddress[a.device to a.fid] = raw
        }
        // A fresh reader per poll: driveMode is held between polls, which would leak samples.
        val reader = NativeParsReader(auto, settings, helper, gate, kindMonitor())
        return checkNotNull(reader.fetch()) { "fetch() returned null for $answers" }
            .also { clearMocks(auto, settings, gate, helper, answers = false) }
    }

    private fun samplesOf(entry: FidEntry): List<Number> =
        if (entry.transact == 7) tx7Samples else tx5Samples

    /** Push of one sample over the baseline; tx 7 carries the float as a double, tx 5 the int. */
    private fun push(b: DiParsData, entry: FidEntry, sample: Number): DiParsData? =
        if (entry.transact == 7) {
            FidPushApplier.apply(b, entry.field, 0, (sample as Float).toDouble())
        } else {
            FidPushApplier.apply(b, entry.field, sample as Int, 0.0)
        }

    private data class Result(val p: DiParsData, val q: DiParsData?)

    /** Per field, per sample: the poll and push results over that field's own baseline. */
    private suspend fun runAll(): Map<String, Map<Number, Result>> =
        FidMap.all.associate { entry ->
            val anchor = anchorFor(entry.field)
            val b = poll(anchor)
            entry.field to samplesOf(entry).associateWith { sample ->
                Result(poll(anchor + (entry.field to sample)), push(b, entry, sample))
            }
        }

    @Test
    fun `a polled value and a pushed value of the same fid give the same snapshot`() = runTest {
        val results = runAll()
        val mismatches = mutableListOf<String>()
        for ((field, bySample) in results) {
            val patched = bySample.values.any { it.q != null }
            val b = poll(anchorFor(field))
            for ((sample, r) in bySample) {
                val msg = "$field sample $sample"
                if (r.q != null) {
                    if (r.p != r.q) mismatches += "$msg: poll=${r.p} push=${r.q}"
                } else if (patched) {
                    assertEquals("$msg: push rejects, so the poll must reject too", b, r.p)
                }
            }
        }
        assertEquals("poll and push differ", emptyList<String>(), mismatches)
    }

    @Test
    fun `the fields push never patches are exactly the listed ones`() = runTest {
        val unpatched = runAll().filterValues { bySample -> bySample.values.all { it.q == null } }.keys
        assertEquals(EXPECTED_UNPATCHED.sorted(), unpatched.sorted())
    }

    private companion object {
        /**
         * Every FidMap field push does not patch, by reason. A new FidMap field fails the test
         * until it is placed here or given a push setter.
         */
        val EXPECTED_UNPATCHED: Set<String> = setOf(
            // The poll derives something else from them (they are also stored as read);
            // push leaves the poll as the only writer.
            "driveMode", // held through a mode switch (stickyDriveMode)
            "chargeGunState", "bmsState", // chargingStatus
            "wiperRelay", "autoWipers", // rain
            "maxBatTemp", "minBatTemp", // avgBatTemp
            "windowRRGen3", // generation fallback for windowRR
            // No snapshot property: they reach consumers through the event flow and the dump.
            "bsdLeft", "bsdRight",
            "soh", "lifetimeAvgPhm", "chargingType", "chargeBatteryVolt", "batteryType", "chargingCapacity",
            "seatHasDriverVent", "seatHasDriverHeat",
            "seatCandPassengerVentStatus", "seatCandPassengerHeatStatus",
            "seatCand3ceVentStatusDriver", "seatCand3ceHeatStatusDriver",
            "seatCand3ceVentLevelDriver", "seatCand3ceHeatLevelDriver",
            "seatCandLrseVentStatusRl", "seatCandLrseHeatStatusRl",
            "seatCandLrseVentStatusRr", "seatCandLrseHeatStatusRr",
            "seatCandLrseLevel1", "seatCandLrseLevel2", "seatCandLrseLevel3", "seatCandLrseLevel4",
            "seatVentStatusDriver", "seatHeatStatusDriver", "seatConfigVentLf", "seatConfigHeatLf",
        )
        // Hybrid-only fields (FidMap.hybrid) are not in FidMap.all and are never pushed.
    }
}
