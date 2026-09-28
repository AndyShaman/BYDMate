package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.ParamDecoder
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.ui.automation.newTelegramReportAction
import com.bydmate.app.ui.automation.reportFields
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** The indent of the trip's number lines: two EM SPACEs. */
    private val em = "\u2003\u2003"

    private val fullData = diParsData(
        soc = 64, mileage = 23_456.7, exteriorTemp = 12, insideTemp = 19,
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

    /** Blocks split by one empty line: never two empty lines, never one at an end. */
    private fun assertWellFormed(text: String) {
        assertFalse(text, text.contains("\n\n\n"))
        assertFalse(text, text.startsWith("\n") || text.endsWith("\n"))
    }

    @Test fun `every item present gives the approved layout with the map link last`() {
        val report = build(inputs = inputs(live = LiveTrip(23.4, 3.79, now - 34 * 60_000L)))
        assertEquals(
            listOf(
                "<b>BYDMate: Где машина</b>",
                "",
                "🔋 Заряд <b>64%</b>, запас <b>312 км</b>",
                "🧭 Пробег 23\u00A0456 км",
                "🌡 Снаружи +12°, в салоне +19°",
                "🛞 Шины: ПЛ 2,4 · ПП 2,4 · ЗЛ 2,3 · ЗП 2,4 бар",
                "",
                "🚗 <b>Поездка</b>",
                "${em}23 км за 34 мин",
                "${em}Расход 16,2 кВт·ч/100 км",
                "",
                "⚠️ <b>Открыто: окно водителя</b>",
                "",
                "📍 <a href=\"https://yandex.ru/maps/?pt=27.560000,53.900000&amp;z=16&amp;l=map\">Открыть на карте</a>",
            ),
            report.text.lines(),
        )
        assertEquals(ReportField.entries.toList(), report.taken)
        assertTrue(report.skipped.isEmpty())
    }

    @Test fun `the last trip title carries its date and time, the numbers are indented under it`() {
        val start = now - 3 * 3_600_000L
        val last = TripEntity(startTs = start, endTs = start + 80 * 60_000L, distanceKm = 41.6, kwhPer100km = 15.44)
        val report = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = LiveTrip(0.05, null, now), last = last))
        val stamp = TelegramReportBuilder.formatDateTime(start).split(' ')
        assertEquals(
            listOf(
                "<b>BYDMate: Где машина</b>",
                "",
                "🚗 <b>Последняя поездка</b> ${stamp[0]} в ${stamp[1]}",
                "${em}42 км за 1 ч 20 мин",
                "${em}Расход 15,4 кВт·ч/100 км",
            ),
            report.text.lines(),
        )
    }

    @Test fun `a trip part that is missing drops only its line`() {
        val start = now - 3 * 3_600_000L
        val noConsumption = TripEntity(startTs = start, endTs = start + 80 * 60_000L, distanceKm = 41.6)
        val a = build(fields = setOf(ReportField.TRIP), inputs = inputs(last = noConsumption)).text.lines()
        assertEquals(4, a.size)
        assertEquals("${em}42 км за 1 ч 20 мин", a[3])

        val noEnd = TripEntity(startTs = start, distanceKm = 41.6, kwhPer100km = 15.44)
        val b = build(fields = setOf(ReportField.TRIP), inputs = inputs(last = noEnd)).text.lines()
        assertEquals(listOf("${em}42 км", "${em}Расход 15,4 кВт·ч/100 км"), b.drop(3))
    }

    @Test fun `a trip under way shows its own counters, consumption only from 2 km`() {
        val short = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = LiveTrip(1.2, 0.4, now - 5 * 60_000L)))
        assertEquals(listOf("🚗 <b>Поездка</b>", "${em}1,2 км за 5 мин"), short.text.lines().drop(2))
    }

    @Test fun `an item the car does not report is left out, never faked, and no block is left empty`() {
        val data = diParsData(soc = 51)
        val report = build(inputs = inputs(data = data, rangeKm = null, location = null))
        assertEquals(listOf("<b>BYDMate: Где машина</b>", "", "🔋 Заряд <b>51%</b>"), report.text.lines())
        assertEquals(listOf(ReportField.SOC), report.taken)
        assertEquals(
            listOf(
                ReportField.LOCATION, ReportField.RANGE, ReportField.ODOMETER, ReportField.TRIP,
                ReportField.TEMPS, ReportField.OPENINGS, ReportField.TIRES,
            ),
            report.skipped,
        )
    }

    @Test fun `missing blocks collapse without double empty lines`() {
        val combos = listOf(
            setOf(ReportField.LOCATION),
            setOf(ReportField.TEMPS, ReportField.LOCATION),
            setOf(ReportField.OPENINGS, ReportField.LOCATION),
            setOf(ReportField.RANGE, ReportField.OPENINGS),
            setOf(ReportField.TRIP, ReportField.OPENINGS),
            emptySet(),
        )
        for (fields in combos) {
            val text = build(fields = fields, inputs = inputs(live = LiveTrip(5.0, null, now)), custom = "ключ").text
            assertWellFormed(text)
        }
        val text = build(fields = setOf(ReportField.OPENINGS, ReportField.LOCATION)).text
        assertEquals(
            listOf("<b>BYDMate: Где машина</b>", "", "⚠️ <b>Открыто: окно водителя</b>", ""),
            text.lines().dropLast(1),
        )
        assertEquals(listOf("<b>BYDMate: Где машина</b>"), build(fields = emptySet()).text.lines())
    }

    @Test fun `only the checked items go in, and all closed sits with the car`() {
        val closed = fullData.copy(windowFL = 0)
        val report = build(fields = setOf(ReportField.OPENINGS, ReportField.RANGE), inputs = inputs(data = closed))
        assertEquals(listOf("<b>BYDMate: Где машина</b>", "", "🔋 Запас <b>312 км</b>", "🔒 Всё закрыто"), report.text.lines())
    }

    @Test fun `a trunk mid-motion counts as open, not closed`() {
        val data = fullData.copy(windowFL = 0, trunk = 3)
        val report = build(fields = setOf(ReportField.OPENINGS), inputs = inputs(data = data))
        assertEquals("⚠️ <b>Открыто: багажник</b>", report.text.lines()[2])
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
        assertEquals("🔒 Всё закрыто", report.text.lines()[2])
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
        assertEquals("🛞 Шины: ПЛ 2,5 · ПП - · ЗЛ - · ЗП - бар", report.text.lines()[2])
    }

    @Test fun `each tire label goes with its own wheel`() {
        val data = diParsData(tirePressFL = 210, tirePressFR = 220, tirePressRL = 230, tirePressRR = 240)
        val ru = build(fields = setOf(ReportField.TIRES), inputs = inputs(data = data)).text.lines()[2]
        assertEquals("🛞 Шины: ПЛ 2,1 · ПП 2,2 · ЗЛ 2,3 · ЗП 2,4 бар", ru)
        val en = build(fields = setOf(ReportField.TIRES), inputs = inputs(data = data), lang = "en").text.lines()[2]
        assertEquals("🛞 Tires: FL 2.1 · FR 2.2 · RL 2.3 · RR 2.4 bar", en)
    }

    @Test fun `rule name and own text are escaped for Telegram HTML, own text is its own block`() {
        val report = build(
            fields = setOf(ReportField.SOC),
            header = TelegramReportBuilder.ruleHeader("A & <b>B</b>"),
            custom = "  ключ у Лены <3 & >_<  ",
        )
        assertEquals(
            listOf(
                "<b>BYDMate: A &amp; &lt;b&gt;B&lt;/b&gt;</b>",
                "",
                "ключ у Лены &lt;3 &amp; &gt;_&lt;",
                "",
                "🔋 Заряд <b>64%</b>",
            ),
            report.text.lines(),
        )
    }

    @Test fun `a translation with markup characters is escaped, our own tags stay`() {
        val raw = strings("ru")
        val tricky = ReportStrings { id, args -> raw.get(id, args).replace("заряд", "заряд <&>") }
        val text = TelegramReportBuilder.build("h", "", setOf(ReportField.SOC), inputs(), "ru", tricky, now).text
        assertEquals("🔋 Заряд &lt;&amp;&gt; <b>64%</b>", text.lines()[2])
    }

    @Test fun `ru and be open Yandex Maps, other languages Google Maps, with the ampersand escaped`() {
        for (lang in listOf("ru", "be")) {
            val link = build(fields = setOf(ReportField.LOCATION), lang = lang).text.lines().last()
            assertTrue(link, link.startsWith("📍 <a href=\"https://yandex.ru/maps/?pt=27.560000,53.900000&amp;z=16&amp;l=map\">"))
        }
        for (lang in listOf("en", "pl", "pt", "zh")) {
            val link = build(fields = setOf(ReportField.LOCATION), lang = lang).text.lines().last()
            assertTrue(link, link.startsWith("📍 <a href=\"https://www.google.com/maps/search/?api=1&amp;query=53.900000,27.560000\">"))
        }
    }

    @Test fun `no trip at all is skipped`() {
        val report = build(fields = setOf(ReportField.TRIP), inputs = inputs(live = null, last = null))
        assertEquals(listOf(ReportField.TRIP), report.skipped)
        assertEquals(1, report.text.lines().size)
    }

    @Test fun `every interface language builds the whole report`() {
        for (lang in listOf("ru", "be", "en", "pl", "pt", "zh")) {
            val report = build(lang = lang, inputs = inputs(live = LiveTrip(23.4, 3.79, now - 34 * 60_000L)))
            assertEquals(lang, 14, report.text.lines().size)
            assertFalse(lang, report.text.contains("%1"))
            assertFalse(lang, report.text.contains('\u0000'))
            assertTrue(lang, report.text.lines().last().startsWith("📍 <a href="))
            assertWellFormed(report.text)
        }
    }

    @Test fun `the late mark goes right above the map link, or last without one`() {
        val mark = "<i>(записано в 18:54)</i>"
        val withMap = TelegramReportBuilder.withLateMark(build(fields = setOf(ReportField.SOC, ReportField.LOCATION)).text, mark)
        val lines = withMap.lines()
        assertEquals(listOf(mark, ""), lines.subList(lines.size - 3, lines.size - 1))
        assertTrue(lines.last(), lines.last().startsWith("📍 <a href="))
        assertWellFormed(withMap)

        val noMap = TelegramReportBuilder.withLateMark(build(fields = setOf(ReportField.SOC)).text, mark)
        assertEquals(listOf("<b>BYDMate: Где машина</b>", "", "🔋 Заряд <b>64%</b>", "", mark), noMap.lines())
    }

    @Test fun `own text that looks like a map line never moves the late mark`() {
        val text = build(fields = setOf(ReportField.SOC), custom = "📍 <a href=\"x\">y</a>").text
        val marked = TelegramReportBuilder.withLateMark(text, "<i>m</i>")
        assertTrue(marked, marked.endsWith("\n\n<i>m</i>"))
    }

    // --- the odometer ---

    /** By its stored id: rule payloads and the power-off setting keep the item as `odometer`. */
    private val odometer: ReportField get() = ReportField.fromId("odometer")!!

    @Test fun `the odometer comes right after the range in the pickers, in every interface language`() {
        val ids = ReportField.entries.map { it.id }
        assertEquals(ids.indexOf("range") + 1, ids.indexOf("odometer"))
        val langs = listOf("ru", "be", "en", "pl", "pt", "zh")
        val labels = langs.map { ctx.localizedContext(it).getString(odometer.labelRes) }
        val descs = langs.map { ctx.localizedContext(it).getString(odometer.descRes) }
        assertEquals("Пробег", labels.first())
        assertEquals("each language has its own label: $labels", langs.size, labels.toSet().size)
        assertEquals("each language has its own description: $descs", langs.size, descs.toSet().size)
    }

    @Test fun `the odometer line follows the charge line, whole kilometers grouped as the language groups them`() {
        val data = fullData.copy(mileage = 23_456.7)
        val fields = setOf(ReportField.SOC, ReportField.RANGE, odometer, ReportField.TEMPS)
        assertEquals(
            listOf(
                "<b>BYDMate: Где машина</b>",
                "",
                "🔋 Заряд <b>64%</b>, запас <b>312 км</b>",
                "🧭 Пробег 23\u00A0456 км",
                "🌡 Снаружи +12°, в салоне +19°",
            ),
            build(fields = fields, inputs = inputs(data = data)).text.lines(),
        )
        val en = build(fields = setOf(odometer), inputs = inputs(data = data), lang = "en")
        assertEquals("🧭 Odometer 23,456 km", en.text.lines()[2])
        assertEquals(listOf(odometer), en.taken)
    }

    @Test fun `without charge and range the odometer opens the car block`() {
        val data = fullData.copy(mileage = 23_456.7)
        val report = build(fields = setOf(odometer, ReportField.TIRES), inputs = inputs(data = data))
        assertEquals(
            listOf("<b>BYDMate: Где машина</b>", "", "🧭 Пробег 23\u00A0456 км", "🛞 Шины: ПЛ 2,4 · ПП 2,4 · ЗЛ 2,3 · ЗП 2,4 бар"),
            report.text.lines(),
        )
    }

    @Test fun `an odometer the car does not report is left out, never shown as zero`() {
        val sentinel = ParamDecoder.decodeScaled(SentinelDecoder.FEATURE_LINK_ERROR, 0.1)
        for (km in listOf(null, sentinel, 0.0, 0.4, -12.0)) {
            val report = build(fields = setOf(ReportField.SOC, odometer), inputs = inputs(data = fullData.copy(mileage = km)))
            assertEquals("$km", listOf("<b>BYDMate: Где машина</b>", "", "🔋 Заряд <b>64%</b>"), report.text.lines())
            assertEquals("$km", listOf(odometer), report.skipped)
        }
    }

    @Test fun `a new report action and a fresh install start with the odometer on`() {
        assertTrue(odometer in ReportField.DEFAULT)
        assertTrue(odometer in ReportField.parseCsv(null))
        assertTrue(odometer in newTelegramReportAction(ctx).reportFields())
    }

    @Test fun `a rule saved before the odometer existed keeps exactly its own items`() {
        val saved = ActionDef(
            command = "", displayName = "x", kind = TELEGRAM_REPORT_KIND,
            payload = """{"fields":["location","soc","range","trip"],"text":""}""",
        )
        assertEquals(setOf(ReportField.LOCATION, ReportField.SOC, ReportField.RANGE, ReportField.TRIP), saved.reportFields())
    }

    // --- the point under the report ---

    @Test fun `the point comes back from the report's own map link in every language, late mark or not`() {
        for (lang in listOf("ru", "be", "en", "pl", "pt", "zh")) {
            for ((lat, lon) in listOf(53.9 to 27.56, -22.906847 to -43.172897)) {
                val text = build(fields = setOf(ReportField.SOC, ReportField.LOCATION), inputs = inputs(location = lat to lon), lang = lang).text
                assertEquals(lang, MapPoint(lat, lon), TelegramReportBuilder.mapPoint(text))
                assertEquals(lang, MapPoint(lat, lon), TelegramReportBuilder.mapPoint(TelegramReportBuilder.withLateMark(text, "<i>m</i>")))
            }
        }
    }

    @Test fun `no map link, no point, and own text that looks like one never gives a point`() {
        assertNull(TelegramReportBuilder.mapPoint(build(fields = setOf(ReportField.SOC)).text))
        assertNull(TelegramReportBuilder.mapPoint(build(inputs = inputs(location = null)).text))
        val forged = "📍 <a href=\"https://yandex.ru/maps/?pt=1.000000,2.000000&amp;z=16\">x</a>"
        assertNull(TelegramReportBuilder.mapPoint(build(fields = setOf(ReportField.SOC), custom = forged).text))
        assertNull(TelegramReportBuilder.mapPoint("<b>h</b>\n\n📍 <a href=\"https://yandex.ru/maps/?pt=1&amp;z=16\">x</a>"))
        assertFalse(MapPoint(53.9, 27.56).toString().contains("53"))
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
