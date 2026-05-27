package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixture-driven test for NativeParsReader.
 *
 * Loads S2 (parked, IGN ON, AC off) snapshot, stubs AutoserviceClient with
 * raw_int values from the fixture, and asserts that fetch() decodes each
 * FidMap field to a value consistent with the DiPlus side.
 *
 * Tolerances:
 *   floats (soc, speed, totalElecConsumption): ± 0.5
 *   kwh (totalElecConsumption):               ± 0.05
 *   volts (voltage12v, cell voltages):        ± 0.01
 */
class NativeParsReaderTest {

    private fun loadFixture(): JSONObject {
        val stream = checkNotNull(
            NativeParsReaderTest::class.java.classLoader
                ?.getResourceAsStream("native-stack-fixtures/s2-parked-on.json")
        ) { "s2-parked-on.json fixture not found in test resources" }
        return JSONObject(stream.bufferedReader().readText())
    }

    /**
     * Build a map (device, fid) → raw_int from the fixture's autoservice block,
     * skipping entries marked skipped=true.
     */
    private fun buildFixtureAutoserviceMap(fixture: JSONObject): Map<Pair<Int, Int>, Int> {
        val result = mutableMapOf<Pair<Int, Int>, Int>()
        val autoservice = fixture.getJSONObject("autoservice")
        for (param in autoservice.keys()) {
            val entry = autoservice.getJSONObject(param)
            if (entry.optBoolean("skipped", false)) continue
            val dev = entry.getInt("device")
            val fid = entry.getInt("fid")
            val raw = entry.getInt("raw_int")
            result[dev to fid] = raw
        }
        return result
    }

    @Test
    fun `s2 fixture decodes to values that match D+ side after equivalent unit transform`() = runTest {
        val fixture = loadFixture()
        val fixtureMap = buildFixtureAutoserviceMap(fixture)
        val diplus = fixture.getJSONObject("diplus")

        // ── Mock AutoserviceClient ────────────────────────────────────────────
        val auto = mockk<AutoserviceClient>()
        coEvery { auto.isAvailable() } returns true
        // Default: all fids return null (no data / sentinel)
        coEvery { auto.getInt(any(), any()) } returns null
        coEvery { auto.getFloat(any(), any()) } returns null

        // Override per FidMap entry where the fixture has a real raw_int.
        // NOTE: The fixture was captured with some older fids (Gear=562036736,
        // DRL=985661476). FidMap has since been updated to validated fids.
        // Those entries will get null from the mock — that's correct behaviour.
        for (entry in FidMap.entries) {
            val raw = fixtureMap[entry.device to entry.fid] ?: continue
            when (entry.transact) {
                5 -> coEvery { auto.getInt(entry.device, entry.fid) } returns raw
                7 -> coEvery { auto.getFloat(entry.device, entry.fid) } returns
                        java.lang.Float.intBitsToFloat(raw)
            }
        }

        // Mock SettingsRepository
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 72.9

        // ── Run ───────────────────────────────────────────────────────────────
        val reader = NativeParsReader(auto, settings)
        val data = reader.fetch()
        assertNotNull("fetch() returned null", data)
        checkNotNull(data)

        // ── Assert each covered FidMap field ─────────────────────────────────

        // soc: float 100.0 → Int 100; D+ SOC="100"
        val diplusSoc = diplus.getString("SOC").toIntOrNull()
        assertEquals("soc", diplusSoc, data.soc)

        // speed: float 0.0 → Int 0; D+ Speed="0"
        val diplusSpeed = diplus.getString("Speed").toIntOrNull()
        assertEquals("speed", diplusSpeed, data.speed)

        // mileage: raw 37998 × 0.1 = 3799.8; D+ Mileage=37998 → 37998/10=3799.8
        val diplusMileage = diplus.getString("Mileage").toDoubleOrNull()?.let { it / 10.0 }
        if (diplusMileage != null && data.mileage != null) {
            assertEquals("mileage", diplusMileage, data.mileage!!, 0.1)
        }

        // power: raw 0 → Int 0; D+ Power="0"
        val diplusPower = diplus.getString("Power").toDoubleOrNull()
        if (diplusPower != null) {
            assertEquals("power", diplusPower, data.power ?: 0.0, 0.5)
        }

        // totalElecConsumption: float from bits 1149052519 ≈ 1012.6; D+ TotalElecCon="1012.6"
        val diplusTec = diplus.getString("TotalElecCon").toDoubleOrNull()
        if (diplusTec != null && data.totalElecConsumption != null) {
            assertEquals("totalElecConsumption", diplusTec, data.totalElecConsumption!!, 0.05)
        }

        // voltage12v: float 14.0; D+ Voltage12V="14" (already volts)
        val diplusV12 = diplus.getString("Voltage12V").toDoubleOrNull()
        if (diplusV12 != null && diplusV12 > 0.0 && data.voltage12v != null) {
            // D+ may return millivolts (>100) or volts; 14 is already volts
            val expected = if (diplusV12 > 100.0) diplusV12 / 1000.0 else diplusV12
            assertEquals("voltage12v", expected, data.voltage12v!!, 0.01)
        }

        // maxCellVoltage: 3426 × 0.001 = 3.426; D+ MaxCellV="3.427" (±0.01 tolerance)
        val diplusMaxCell = diplus.getString("MaxCellV").toDoubleOrNull()
        if (diplusMaxCell != null && diplusMaxCell > 0.5 && data.maxCellVoltage != null) {
            assertEquals("maxCellVoltage", diplusMaxCell, data.maxCellVoltage!!, 0.01)
        }

        // minCellVoltage: 3352 × 0.001 = 3.352; D+ MinCellV="3.353" (±0.01 tolerance)
        val diplusMinCell = diplus.getString("MinCellV").toDoubleOrNull()
        if (diplusMinCell != null && diplusMinCell > 0.5 && data.minCellVoltage != null) {
            assertEquals("minCellVoltage", diplusMinCell, data.minCellVoltage!!, 0.01)
        }

        // chargeGunState: raw 1; D+ ChargeGun="1"
        val diplusGun = diplus.getString("ChargeGun").toIntOrNull()
        assertEquals("chargeGunState", diplusGun, data.chargeGunState)

        // acStatus: raw 0; D+ ACStatus="0"
        val diplusAcStatus = diplus.getString("ACStatus").toIntOrNull()
        assertEquals("acStatus", diplusAcStatus, data.acStatus)

        // insideTemp: raw 18; D+ InsideTemp="18"
        val diplusInside = diplus.getString("InsideTemp").toIntOrNull()
        if (diplusInside != null && diplusInside in -50..80) {
            assertEquals("insideTemp", diplusInside, data.insideTemp)
        }

        // exteriorTemp: raw 15; D+ ExtTemp="15"
        val diplusExt = diplus.getString("ExtTemp").toIntOrNull()
        if (diplusExt != null && diplusExt in -50..80) {
            assertEquals("exteriorTemp", diplusExt, data.exteriorTemp)
        }

        // hood: raw 0; D+ Hood="0"
        val diplusHood = diplus.getString("Hood").toIntOrNull()
        assertEquals("hood", diplusHood, data.hood)

        // seatbeltFL: raw 0; D+ SeatbeltFL="0"
        val diplusBelt = diplus.getString("SeatbeltFL").toIntOrNull()
        assertEquals("seatbeltFL", diplusBelt, data.seatbeltFL)

        // tire pressures: direct int comparison
        assertEquals("tirePressFL", diplus.getString("TirePressFL").toIntOrNull(), data.tirePressFL)
        assertEquals("tirePressFR", diplus.getString("TirePressFR").toIntOrNull(), data.tirePressFR)
        assertEquals("tirePressRL", diplus.getString("TirePressRL").toIntOrNull(), data.tirePressRL)
        assertEquals("tirePressRR", diplus.getString("TirePressRR").toIntOrNull(), data.tirePressRR)

        // batteryCapacityKwh: from settings mock = 72.9
        assertEquals("batteryCapacityKwh", 72.9, data.batteryCapacityKwh!!, 0.01)

        // ── Fields not in FidMap (or no fixture raw for validated fid) ────────
        // Gear: FidMap uses validated fid=555745336; fixture used old fid=562036736 — no match → null
        assertNull("gear should be null (no fixture data for validated fid)", data.gear)
        // DRL: FidMap uses validated fid=1231040528; fixture used old fid=985661476 — no match → null
        assertNull("drl should be null (no fixture data for validated fid)", data.drl)
        // lightLow: no fixture data for fid=950009866 → null
        assertNull("lightLow should be null (no fixture data)", data.lightLow)
    }

    /**
     * Liveness gate: when autoservice is available but all primary signals (soc,
     * mileage, voltage12v) return null, fetch() must return null so the caller
     * treats it as an unreachable / sentinel-spamming autoservice and retries.
     */
    @Test
    fun `fetch returns null when all primary signals are null`() = runTest {
        val auto = mockk<AutoserviceClient>()
        coEvery { auto.isAvailable() } returns true
        // All reads return null — simulates sentinel-spam or disconnected autoservice.
        coEvery { auto.getInt(any(), any()) } returns null
        coEvery { auto.getFloat(any(), any()) } returns null

        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 72.9

        val reader = NativeParsReader(auto, settings)
        val result = reader.fetch()
        assertNull("fetch() must return null when soc + mileage + voltage12v are all null", result)
    }
}
