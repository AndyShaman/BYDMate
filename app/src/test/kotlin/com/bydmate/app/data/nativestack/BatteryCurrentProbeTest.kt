package com.bydmate.app.data.nativestack

import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.BatchReadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429: on some DiLink 5 cars the float read of the battery current answers -1.0 while its push
 * carries an int. The probe reads that fid as an int and three candidate fids both ways, into one
 * «Запись логов» line, so the next log shows which one carries the current.
 */
class BatteryCurrentProbeTest {

    private val hvCurrent = FidAddress(1009, 1145045016)

    private fun floatBits(v: Float) = java.lang.Float.floatToRawIntBits(v)

    @Test fun `items read hvCurrent as int plus each candidate both ways`() {
        assertEquals(
            listOf(
                BatchReadItem(5, 1009, 1145045016),
                BatchReadItem(5, 1009, -1002438632), BatchReadItem(7, 1009, -1002438632),
                BatchReadItem(5, 1039, 677380120), BatchReadItem(7, 1039, 677380120),
                BatchReadItem(5, 1007, 1298153504), BatchReadItem(7, 1007, 1298153504),
            ),
            BatteryCurrentProbe().items(null, hvCurrent),
        )
    }

    @Test fun `the catalog number wins where it knows the symbol`() {
        val catalog = FidCatalog(mapOf("Gb.GB_BATTERY_CCURRENT" to 111), emptyMap(), 1)
        val items = BatteryCurrentProbe().items(catalog, hvCurrent)
        assertEquals(listOf(BatchReadItem(5, 1039, 111), BatchReadItem(7, 1039, 111)), items.subList(3, 5))
        assertEquals(-1002438632, items[1].fid)
        assertEquals(1298153504, items[5].fid)
    }

    @Test fun `hvCurrent row follows the resolved address`() {
        val first = BatteryCurrentProbe().items(null, FidAddress(1009, 1145047064)).first()
        assertEquals(BatchReadItem(5, 1009, 1145047064), first)
    }

    @Test fun `line renders words, float bits and failures`() {
        val data = diParsData(speed = 60).copy(hvVoltage = 531, hvCurrent = null, motorCurrentFront = -34.7f)
        val readings = listOf(
            0 to 4810, 0 to -10013, 0 to floatBits(-1f), 1 to 0, 0 to floatBits(0f), 0 to 65535, 0 to floatBits(12.5f),
        )
        assertEquals(
            "battery current probe: V=531 I=? cF=-34.7 spd=60 chgInt=4810 notConv5=-10013 notConv7=-1.0 " +
                "gb5=? gb7=0.0 pspk5=65535 pspk7=12.5",
            BatteryCurrentProbe().line(data, readings),
        )
    }

    @Test fun `no answer is one unavailable line`() {
        val data = diParsData()
        assertEquals("battery current probe: (unavailable)", BatteryCurrentProbe().line(data, null))
        assertEquals("battery current probe: (unavailable)", BatteryCurrentProbe().line(data, listOf(0 to 1)))
    }

    @Test fun `reads at most once per 10 s`() {
        val probe = BatteryCurrentProbe()
        assertTrue(probe.shouldRead(0L))
        assertFalse(probe.shouldRead(5_000L))
        assertTrue(probe.shouldRead(10_000L))
    }
}
