package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The report on a DM-i: the car's own ranges, and the fuel line under a TRIP counter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportHybridTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_759_000_000_000L
    private val em = "  "

    private fun strings(lang: String): ReportStrings {
        val lc = ctx.localizedContext(lang)
        return ReportStrings { id, args -> lc.getString(id, *args) }
    }

    private val hybridData = diParsData(soc = 64).copy(evRangeKm = 46, fuelRangeKm = 512)
    private val counter = TripCounterUi(
        km = 412.3, kwh = 88.2, drivingKwh = 88.2, idleKwh = 0.0, cost = 0.0, tripCount = 3,
        drivingMs = (9 * 60 + 20) * 60_000L, resetTs = 0L,
    )

    private fun build(inputs: ReportInputs, fields: Set<ReportField>, lang: String = "ru") =
        TelegramReportBuilder.build("BYDMate", "", fields, inputs, lang, strings(lang), now).text

    @Test fun `a hybrid range line uses the car's battery and fuel ranges, not the estimate`() {
        val text = build(
            ReportInputs(hybridData, 312.4, null, null, null, null, hybrid = true),
            setOf(ReportField.SOC, ReportField.RANGE),
        )
        assertTrue(text, text.contains("🔋 Заряд <b>64%</b>, запас <b>46 км</b> на батарее, <b>512 км</b> на топливе"))
        assertFalse(text, text.contains("312"))
    }

    @Test fun `a hybrid range with one half shows that half, with none no range`() {
        val evOnly = build(
            ReportInputs(hybridData.copy(fuelRangeKm = null), 312.4, null, null, null, null, hybrid = true),
            setOf(ReportField.RANGE),
        )
        assertTrue(evOnly, evOnly.contains("🔋 Запас <b>46 км</b> на батарее"))
        val none = TelegramReportBuilder.build(
            "BYDMate", "", setOf(ReportField.RANGE),
            ReportInputs(hybridData.copy(fuelRangeKm = null, evRangeKm = null), 312.4, null, null, null, null, hybrid = true),
            "ru", strings("ru"), now,
        )
        assertEquals(listOf(ReportField.RANGE), none.skipped)
    }

    @Test fun `a hybrid counter gets the fuel line over all km of its trips`() {
        val text = build(
            ReportInputs(hybridData, null, null, null, null, null, trip1 = counter, hybrid = true,
                trip1Fuel = FuelTotals(liters = 7.6, km = 420.0)),
            setOf(ReportField.TRIP1),
        )
        assertEquals("${em}Топливо: 7,6 л за период, 1,8 л/100 на все км", text.lines().last())
    }

    @Test fun `a hybrid counter without litres has no fuel line`() {
        val text = build(
            ReportInputs(hybridData, null, null, null, null, null, trip1 = counter, hybrid = true),
            setOf(ReportField.TRIP1),
        )
        assertFalse(text, text.contains("Топливо"))
    }

    private val lastTrip = com.bydmate.app.data.local.entity.TripEntity(
        startTs = now - 3_600_000L, endTs = now - 1_500_000L, distanceKm = 34.0, kwhPer100km = 14.2, fuelLiters = 2.8,
    )

    @Test fun `a hybrid last trip carries its own litres`() {
        val text = build(
            ReportInputs(hybridData, null, null, null, null, lastTrip, hybrid = true),
            setOf(ReportField.TRIP),
        )
        assertEquals("${em}Топливо: 2,8 л", text.lines().last())
    }

    @Test fun `a hybrid last trip without litres has no fuel line`() {
        val text = build(
            ReportInputs(hybridData, null, null, null, null, lastTrip.copy(fuelLiters = null), hybrid = true),
            setOf(ReportField.TRIP),
        )
        assertFalse(text, text.contains("Топливо"))
    }

    @Test fun `an EV report ignores fuel fields and fuel totals`() {
        val fields = setOf(ReportField.SOC, ReportField.RANGE, ReportField.TRIP, ReportField.TRIP1)
        val plain = ReportInputs(diParsData(soc = 64), 312.4, null, null, null, lastTrip.copy(fuelLiters = null), trip1 = counter)
        val withFuel = plain.copy(data = hybridData, lastTrip = lastTrip, trip1Fuel = FuelTotals(7.6, 420.0))
        assertEquals(build(plain, fields), build(withFuel, fields))
        assertTrue(build(plain, fields).contains("запас <b>312 км</b>"))
    }

    @Test fun `every interface language builds the hybrid lines`() {
        for (lang in listOf("ru", "be", "en", "zh", "pl", "pt")) {
            val text = build(
                ReportInputs(hybridData, null, null, null, null, null, trip1 = counter, hybrid = true,
                    trip1Fuel = FuelTotals(7.6, 420.0)),
                setOf(ReportField.RANGE, ReportField.TRIP1), lang,
            )
            assertTrue("$lang: $text", text.contains("46") && text.contains("512") && text.lines().size == 8)
        }
    }
}
