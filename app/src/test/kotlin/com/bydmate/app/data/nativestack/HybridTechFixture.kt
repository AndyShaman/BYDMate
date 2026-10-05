package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

/**
 * The hybrid dump of 2026-10-04 (native-stack-fixtures/tech-hybrid-placeholders-20261004.txt):
 * its first raw `tech:` line replayed through the reader's batch path, so a test sees the very
 * snapshot that car produced.
 */
object HybridTechFixture {

    const val FIXTURE = "native-stack-fixtures/tech-hybrid-placeholders-20261004.txt"

    /** `tech:` log key → FidMap field, for the keys that are plain fid words. */
    private val FIELDS = mapOf(
        "soc" to "soc", "remain" to "batteryRemainKwh", "ins" to "insulationKohm",
        "mF" to "motorTempFront", "mR" to "motorTempRear",
        "iF" to "inverterTempFront", "iR" to "inverterTempRear",
        "V" to "hvVoltage", "I" to "hvCurrent",
        "chg" to "bmsMaxChargeKw", "dis" to "bmsMaxDischargeKw",
        "tmax" to "maxBatTemp", "tmin" to "minBatTemp",
        "rF" to "motorRpmFront", "rR" to "motorRpmRear",
        "comp" to "compressorW", "ac" to "acStatus",
        "acc" to "pedalAccel", "brk" to "pedalBrake",
        "cF" to "motorCurrentFront", "cR" to "motorCurrentRear",
        "gun" to "chargeGunState", "bms" to "bmsState",
        "chgConn" to "chargerConnectState", "chgInd" to "chargeConnectIndicator",
    )

    /** key → raw text of the first `tech:` line. */
    fun firstTechLine(): Map<String, String> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) { FIXTURE }
            .bufferedReader().use { it.readText() }
        val line = text.lineSequence().first { "tech: " in it }.substringAfter("tech: ")
        return line.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** The batch words the daemon returned for that line; every fid the line does not carry is -10011. */
    private fun batchPairs(): List<Pair<Int, Int>> {
        val words = firstTechLine().mapNotNull { (key, raw) ->
            val field = FIELDS[key] ?: return@mapNotNull null
            val entry = FidMap.entries.first { it.field == field }
            field to if (entry.transact == 7) java.lang.Float.floatToRawIntBits(raw.toFloat()) else raw.toInt()
        }.toMap()
        return FidMap.entries.map { 0 to (words[it.field] ?: -10011) }
    }

    /** The snapshot the reader assembles from that line, battery capacity as in the dump. */
    suspend fun snapshot(): DiParsData {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 31.8
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns batchPairs()
        val gate = mockk<BatchReadGate>()
        coEvery { gate.mode() } returns BatchMode.ACTIVE
        coEvery { gate.recordComparison(any(), any()) } just Runs
        every { gate.recordBatchUnavailable() } just Runs
        return checkNotNull(NativeParsReader(mockk<AutoserviceClient>(), settings, helper, gate).fetch())
    }
}
