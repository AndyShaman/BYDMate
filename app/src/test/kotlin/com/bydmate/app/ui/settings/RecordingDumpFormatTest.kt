package com.bydmate.app.ui.settings

import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class RecordingDumpFormatTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Test fun `app versions name the installed build, a variant, or absent`() {
        val installed = mapOf(
            "ru.yandex.yandexnavi" to ("24.5.1" to 1234L),
            "ru.yandex.yandexmaps.rustore" to (null to 7L),
        )

        assertEquals(
            "apps: yandexnavi=24.5.1/1234 yandexmaps=?/7(.rustore) music=absent dublgis=absent spotify=absent",
            RecordingDumpFormat.appVersionsLine { installed[it] },
        )
    }

    @Test fun `vehicle writes are oldest first with status and readback`() {
        val rows = listOf(
            VehicleWriteLogEntity(2, fmt.parse("2026-10-06 10:00:05")!!.time, "ac_on", 1000, 7, 1, null, -1, "timeout", true),
            VehicleWriteLogEntity(1, fmt.parse("2026-10-06 10:00:01")!!.time, "doors_lock", 1001, 9, 2, 2, 0, null, false),
        )

        assertEquals(
            listOf(
                "2026-10-06 10:00:01 doors_lock dev=1001 fid=9 req=2 rb=2 ok unvalidated",
                "2026-10-06 10:00:05 ac_on dev=1000 fid=7 req=1 rb=- fail err=timeout",
            ),
            RecordingDumpFormat.vehicleWriteLines(rows),
        )
        assertEquals(listOf("(none)"), RecordingDumpFormat.vehicleWriteLines(emptyList()))
    }

    @Test fun `hub snapshot keeps byte counts and the road's script, not the data`() {
        val s = NavGuidanceHub.Snapshot(
            active = true, road = "Ленина ул", maneuverPng = ByteArray(300) { 7 }, cameraIconPng = ByteArray(5),
        )

        val text = RecordingDumpFormat.hubSnapshot(s)

        assertTrue(text, text.contains("png=<300 bytes>"))
        assertTrue(text, text.contains("cameraIconPng=<5 bytes>"))
        assertTrue(text, text.contains("road_script=cyrillic road_len=9,"))
        assertFalse(text, text.contains("Ленина"))
        assertFalse(text, text.contains("7, 7"))
        assertTrue(text, text.contains("active=true"))
    }

    @Test fun `since keeps journal lines from the recording's start second on`() {
        val start = fmt.parse("2026-10-06 10:00:05")!!.time + 400
        val lines = listOf(
            "2026-10-06 10:00:04 old",
            "2026-10-06 10:00:05 same second",
            "2026-10-06 10:01:00 later",
            "garbage",
        )

        assertEquals(listOf("2026-10-06 10:00:05 same second", "2026-10-06 10:01:00 later"), RecordingDumpFormat.since(lines, start))
    }

    @Test fun `rule lines lose the name and the trigger snapshot`() {
        assertEquals(
            "2026-10-06 10:00:05 rule=4 ok=false steps=[param:fail(timeout)]",
            RecordingDumpFormat.ruleLineById(
                "2026-10-06 10:00:05 rule=4 \"Домой к маме\" ok=false steps=[param:fail(timeout)] trig=place:Дом",
            ),
        )
        assertEquals("(journal empty)", RecordingDumpFormat.ruleLineById("(journal empty)"))
    }

    @Test fun `fid push keeps the totals only`() {
        val lines = listOf(
            "subscribed=3 ok=3 failed=0",
            "Speed 123 dev=1 OK events=4 last=5 age=1s",
            "callback: alive deliver errors=0",
            "delivery: packets=1 events=2 coalesced=0",
            "resubscribes=0",
        )

        assertEquals(lines - lines[1], RecordingDumpFormat.fidPushTotals(lines))
    }
}
