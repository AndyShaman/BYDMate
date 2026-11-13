package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.util.localizedContext
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The separate «Температура батареи» report field (#414). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportBatteryTempTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_759_000_000_000L

    private fun build(fields: Set<ReportField>, avgBatTemp: Int?, minBatTemp: Int? = null, maxBatTemp: Int? = null): BuiltReport {
        val lc = ctx.localizedContext("ru")
        val data = diParsData(
            soc = 64, exteriorTemp = 12, insideTemp = 19,
            avgBatTemp = avgBatTemp, minBatTemp = minBatTemp, maxBatTemp = maxBatTemp,
        )
        val inputs = ReportInputs(data, 312.4, null, null, null, null)
        return TelegramReportBuilder.build(
            "BYDMate", "", fields, inputs, "ru", ReportStrings { id, args -> lc.getString(id, *args) }, now,
        )
    }

    @Test fun `battery_temp is a new field right after temps and is not a default`() {
        assertEquals("battery_temp", ReportField.BATTERY_TEMP.id)
        val entries = ReportField.entries
        assertEquals(entries.indexOf(ReportField.TEMPS) + 1, entries.indexOf(ReportField.BATTERY_TEMP))
        assertFalse(ReportField.BATTERY_TEMP in ReportField.DEFAULT)
        // The existing entries keep their ids and their order.
        assertEquals(
            listOf(
                "location", "soc", "range", "odometer", "trip", "trip_time", "trip_kwh", "trip_cost",
                "trip1", "trip2", "temps", "battery_temp", "openings", "tires",
            ),
            entries.map { it.id },
        )
    }

    @Test fun `battery_temp round-trips through csv and json`() {
        val fields = setOf(ReportField.SOC, ReportField.BATTERY_TEMP)
        assertEquals("soc,battery_temp", ReportField.toCsv(fields))
        assertEquals(fields, ReportField.parseCsv("soc,battery_temp"))
        val json = ReportField.toJson(fields)
        assertEquals("""["soc","battery_temp"]""", json.toString())
        assertEquals(fields, ReportField.fromJson(JSONArray(json.toString())))
    }

    @Test fun `a selected battery temperature has its own line and is taken`() {
        val report = build(setOf(ReportField.SOC, ReportField.TEMPS, ReportField.BATTERY_TEMP), avgBatTemp = 23)
        val line = report.text.lines().single { it.contains("Батарея") }
        assertTrue(line, line.contains("+23°"))
        assertTrue(ReportField.BATTERY_TEMP in report.taken)
        assertFalse(ReportField.BATTERY_TEMP in report.skipped)
    }

    @Test fun `a selected battery temperature without data is skipped and the rest stays the same`() {
        val without = build(setOf(ReportField.SOC, ReportField.TEMPS), avgBatTemp = null)
        val with = build(setOf(ReportField.SOC, ReportField.TEMPS, ReportField.BATTERY_TEMP), avgBatTemp = null)
        assertEquals(without.text, with.text)
        assertTrue(ReportField.BATTERY_TEMP in with.skipped)
        assertFalse(ReportField.BATTERY_TEMP in with.taken)
    }
}
