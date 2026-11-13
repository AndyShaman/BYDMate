package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression guard for #414: without the battery-temperature field the report text does not change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportBatteryOffTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_759_000_000_000L

    private fun build(fields: Set<ReportField>, avgBatTemp: Int?): String {
        val lc = ctx.localizedContext("ru")
        val data = diParsData(
            soc = 64, mileage = 23_456.7, exteriorTemp = 12, insideTemp = 19,
            avgBatTemp = avgBatTemp, minBatTemp = avgBatTemp?.minus(1), maxBatTemp = avgBatTemp?.plus(2),
        )
        val inputs = ReportInputs(data, 312.4, null, null, null, null)
        return TelegramReportBuilder.build(
            "BYDMate", "", fields, inputs, "ru", ReportStrings { id, args -> lc.getString(id, *args) }, now,
        ).text
    }

    @Test fun `the temps line is the same with and without battery data, report is unchanged`() {
        val fields = setOf(ReportField.SOC, ReportField.TEMPS)
        val expected = "<b>BYDMate</b>\n\n🔋 Заряд <b>64%</b>\n🌡 Снаружи +12°, в салоне +19°"
        assertEquals(expected, build(fields, avgBatTemp = null))
        assertEquals(expected, build(fields, avgBatTemp = 23))
    }
}
