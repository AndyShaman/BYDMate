package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Telegram report text, built with the real strings of each interface language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportBuilderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun strings(lang: String): ReportStrings {
        val lc = ctx.localizedContext(lang)
        return ReportStrings { id, args -> lc.getString(id, *args) }
    }

    private val now = 1_759_000_000_000L

    private val fullData = diParsData(
        soc = 64, exteriorTemp = 12, insideTemp = 19,
        doorFL = 0, doorFR = 0, doorRL = 0, doorRR = 0,
        windowFL = 30, windowFR = 0, windowRL = 0, windowRR = 0,
        sunroof = 0, trunk = 2, hood = 0,
        tirePressFL = 240, tirePressFR = 240, tirePressRL = 230, tirePressRR = 240,
    )

    private fun inputs(
        data: com.bydmate.app.data.remote.DiParsData? = fullData,
        rangeKm: Double? = 312.4,
        location: Pair<Double, Double>? = 53.9 to 27.56,
        live: LiveTrip? = null,
        last: TripEntity? = null,
    ) = ReportInputs(data, rangeKm, location?.first, location?.second, live, last)

    private fun build(
        fields: Set<ReportField> = ReportField.entries.toSet(),
        inputs: ReportInputs = inputs(),
        lang: String = "ru",
        header: String = TelegramReportBuilder.ruleHeader("Где машина"),
        custom: String = "",
    ) = TelegramReportBuilder.build(header, custom, fields, inputs, lang, strings(lang), now)

    @Test fun `every item present gives the mock layout with the map link last`() {
        val report = build(inputs = inputs(live = LiveTrip(23.4, 3.79, now - 34 * 60_000L)))
        val lines = report.text.lines()
        assertEquals("<b>BYDMate: Где машина</b>", lines[0])
        assertEquals("Заряд 64%, запас 312 км", lines[1])
        assertEquals("Поездка: 23 км, 16,2 кВт·ч/100 км, 34 мин", lines[2])
        assertEquals("Снаружи +12°, в салоне +19°", lines[3])
        assertEquals("Открыто: окно водителя", lines[4])
        assertEquals("Шины: 2,4 / 2,4 / 2,3 / 2,4 бар", lines[5])
        assertTrue(lines[6], lines[6].startsWith("<a href=\"https://yandex.ru/maps/"))
        assertTrue(lines[6], lines[6].endsWith(">Открыть на карте</a>"))
        assertEquals(ReportField.entries.toList(), report.taken)
        assertTrue(report.skipped.isEmpty())
    }

    @Test fun `an item the car does not report is left out, never faked`() {
        val data = diParsData(soc = 51)
        val report = build(inputs = inputs(data = data, rangeKm = null, location = null))
        assertEquals(listOf("<b>BYDMate: Где машина</b>", "Заряд 51%"), report.text.lines())
        assertEquals(listOf(ReportField.SOC), report.taken)
        assertEquals(
            listOf(ReportField.LOCATION, ReportField.RANGE, ReportField.TRIP, ReportField.TEMPS, ReportField.OPENINGS, ReportField.TIRES),
            report.skipped,
        )
    }

    @Test fun `only the checked items go in, and all closed is said when nothing is open`() {
        val closed = fullData.copy(windowFL = 0)
        val report = build(fields = setOf(ReportField.OPENINGS, ReportField.RANGE), inputs = inputs(data = closed))
        assertEquals(listOf("<b>BYDMate: Где машина</b>", "Запас 312 км", "Всё закрыто"), report.text.lines())
    }

    @Test fun `a trunk mid-motion counts as open, not closed`() {
        val data = fullData.copy(windowFL = 0, trunk = 3)
        val report = build(fields = setOf(ReportField.OPENINGS), inputs = inputs(data = data))
        assertEquals("Открыто: багажник", report.text.lines()[1])
    }

    @Test fun `a panel with a value the car never explained is left out of both lists`() {
        val data = fullData.copy(windowFL = 0, doorFL = 5)
        val report = build(fields = setOf(ReportField.OPENINGS), inputs = inputs(data = data))
        assertEquals(1, report.text.lines().size)
        assertEquals(listOf(ReportField.OPENINGS), report.skipped)
    }

    @Test fun `a car without a sunroof still gets all closed once its doors and windows are`() {
        val data = fullData.copy(windowFL = 0, sunroof = null)
        val report = build(fields = setOf(ReportField.OPENINGS), inputs = inputs(data = data))
        assertEquals("Всё закрыто", report.text.lines()[1])
    }

    @Test fun `a window the car does not report blocks all closed, but nothing is said to be open`() {
        val data = fullData.copy(windowFL = null)
        val report = build(fields = setOf(ReportField.OPENINGS), inputs = inputs(data = data))
        assertEquals(1, report.text.lines().size)
        assertEquals(listOf(ReportField.OPENINGS), report.skipped)
    }

    @Test fun `a wheel without a reading keeps its place as a dash`() {
        val data = diParsData(tirePressFL = 250, tirePressRR = 0)
        val report = build(fields = setOf(ReportField.TIRES), inputs = inputs(data = data))
        assertEquals("Шины: 2,5 / - / - / - бар", report.text.lines()[1])
    }

    @Test fun `rule name and own text are escaped for Telegram HTML`() {
        val report = build(
            fields = setOf(ReportField.SOC),
            header = TelegramReportBuilder.ruleHeader("A & <b>B</b>"),
            custom = "  ключ у Лены <3  ",
        )
        val lines = report.text.lines()
        assertEquals("<b>BYDMate: A &amp; &lt;b&gt;B&lt;/b&gt;</b>", lines[0])
        assertEquals("ключ у Лены &lt;3", lines[1])
        assertEquals("Заряд 64%", lines[2])
    }

    @Test fun `ru and be open Yandex Maps, other languages Google Maps, with the ampersand escaped`() {
        for (lang in listOf("ru", "be")) {
            val link = build(fields = setOf(ReportField.LOCATION), lang = lang).text.lines()[1]
            assertTrue(link, link.contains("https://yandex.ru/maps/?pt=27.560000,53.900000&amp;z=16&amp;l=map"))
        }
        for (lang in listOf("en", "pl", "pt", "zh")) {
            val link = build(fields = setOf(ReportField.LOCATION), lang = lang).text.lines()[1]
            assertTrue(link, link.contains("https://www.google.com/maps/search/?api=1&amp;query=53.900000,27.560000"))
        }
    }

    @Test fun `a trip under way shows its own counters, consumption only from 2 km`() {
        val short = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = LiveTrip(1.2, 0.4, now - 5 * 60_000L)))
        assertEquals("Поездка: 1,2 км, 5 мин", short.text.lines()[1])
    }

    @Test fun `below 0,1 km the last recorded trip is shown with its date`() {
        val start = now - 3 * 3_600_000L
        val last = TripEntity(startTs = start, endTs = start + 80 * 60_000L, distanceKm = 41.6, kwhPer100km = 15.44)
        val report = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = LiveTrip(0.05, null, now), last = last))
        val stamp = TelegramReportBuilder.formatDateTime(start)
        assertEquals("Последняя поездка $stamp: 42 км, 15,4 кВт·ч/100 км, 1 ч 20 мин", report.text.lines()[1])
    }

    @Test fun `no trip at all is skipped`() {
        val report = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = null, last = null))
        assertEquals(listOf(ReportField.TRIP), report.skipped)
        assertEquals(1, report.text.lines().size)
    }

    @Test fun `every interface language builds the whole report`() {
        for (lang in listOf("ru", "be", "en", "pl", "pt", "zh")) {
            val report = build(lang = lang, inputs = inputs(live = LiveTrip(23.4, 3.79, now - 34 * 60_000L)))
            assertEquals(lang, 7, report.text.lines().size)
            assertFalse(lang, report.text.contains("%1"))
        }
    }

    @Test fun `power-off header carries the time placeholder the daemon fills`() {
        val header = TelegramReportBuilder.powerOffHeader(strings("ru"))
        assertEquals("BYDMate: машина выключена в ${TelegramReportBuilder.TIME_PLACEHOLDER}", header)
        val text = build(fields = setOf(ReportField.SOC), header = header).text
        val filled = TelegramReportBuilder.fillTime(text, now)
        assertTrue(filled, filled.startsWith("<b>BYDMate: машина выключена в ${TelegramReportBuilder.formatTime(now)}</b>"))
        assertFalse(filled.contains(TelegramReportBuilder.TIME_PLACEHOLDER))
    }
}
