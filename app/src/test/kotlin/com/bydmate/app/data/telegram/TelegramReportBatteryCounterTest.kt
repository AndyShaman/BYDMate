package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportBatteryCounterTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_759_000_000_000L

    private fun build(
        fields: Set<ReportField>,
        totalElecConsumption: Double?,
        lang: String = "en",
    ): BuiltReport {
        val lc = ctx.localizedContext(lang)
        val data = diParsData(soc = 64, totalElecConsumption = totalElecConsumption)
        val inputs = ReportInputs(data, 312.4, null, null, null, null)
        return TelegramReportBuilder.build(
            "BYDMate", "", fields, inputs, lang, ReportStrings { id, args -> lc.getString(id, *args) }, now,
        )
    }

    @Test
    fun `battery_counter is appended at the end of ReportField and DEFAULT stays unchanged`() {
        assertEquals("battery_counter", ReportField.BATTERY_COUNTER.id)
        assertEquals(ReportField.BATTERY_COUNTER, ReportField.fromId("battery_counter"))
        assertFalse(ReportField.BATTERY_COUNTER in ReportField.DEFAULT)
        assertEquals(
            setOf(ReportField.LOCATION, ReportField.SOC, ReportField.RANGE, ReportField.ODOMETER, ReportField.TRIP),
            ReportField.DEFAULT,
        )
        val existingIds = listOf(
            "location", "soc", "range", "odometer", "trip", "trip_time", "trip_kwh", "trip_cost",
            "trip1", "trip2", "temps", "battery_temp", "openings", "tires",
        )
        assertEquals(existingIds + "battery_counter", ReportField.entries.map { it.id })
        val existingFields = ReportField.entries.filter { it != ReportField.BATTERY_COUNTER }.toSet()
        assertEquals(existingIds.joinToString(","), ReportField.toCsv(existingFields))
        assertEquals("soc,battery_counter", ReportField.toCsv(setOf(ReportField.SOC, ReportField.BATTERY_COUNTER)))
    }

    @Test
    fun `selected battery counter with positive value adds one line with formatted kWh and unit`() {
        val without = build(setOf(ReportField.SOC), totalElecConsumption = 12345.6, lang = "en")
        val with = build(setOf(ReportField.SOC, ReportField.BATTERY_COUNTER), totalElecConsumption = 12345.6, lang = "en")

        val addedLines = with.text.lines() - without.text.lines().toSet()
        assertEquals(1, addedLines.size)
        val line = addedLines.single()
        val kwhUnit = ctx.localizedContext("en").getString(R.string.dashboard_unit_kwh)
        assertTrue(line, line.contains("12345.6"))
        assertTrue(line, line.contains(kwhUnit))
        assertTrue(ReportField.BATTERY_COUNTER in with.taken)
        assertFalse(ReportField.BATTERY_COUNTER in with.skipped)
    }

    @Test
    fun `selected battery counter with null or zero value is skipped and leaves report identical`() {
        for (value in listOf(null, 0.0)) {
            val without = build(setOf(ReportField.SOC), totalElecConsumption = value)
            val with = build(setOf(ReportField.SOC, ReportField.BATTERY_COUNTER), totalElecConsumption = value)
            assertEquals("value=$value", without.text, with.text)
            assertTrue("value=$value", ReportField.BATTERY_COUNTER in with.skipped)
            assertFalse("value=$value", ReportField.BATTERY_COUNTER in with.taken)
        }
    }

    @Test
    fun `report without battery_counter stays byte-identical regardless of totalElecConsumption`() {
        val withValue = build(setOf(ReportField.SOC), totalElecConsumption = 12345.6)
        val withoutValue = build(setOf(ReportField.SOC), totalElecConsumption = null)
        assertEquals(withoutValue.text, withValue.text)
    }
}
