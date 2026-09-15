package com.bydmate.app.helper.push

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recorder's wire and the catalog grouping it registers from: the grouping is the only place
 * that knows which fid belongs to which device, so a wrong prefix match would silently record the
 * wrong device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidRecorderWireTest {

    private val dump = """
        BYDAutoConstants.BYDAUTO_DEVICE_AC=1000
        BYDAutoConstants.BYDAUTO_DEVICE_BODYWORK=1001
        BYDAutoConstants.BYDAUTO_DEVICE_DOOR_LOCK=1041
        BYDAutoConstants.BYDAUTO_DEVICE_REAR_VIEW_MIRROR=1047
        BYDAutoConstants.BYDAUTO_MODE_SOMETHING=7
        Ac.AC_TEMP_MAIN=1000001
        Bodywork.BODYWORK_WINDOW_FL=2000001
        Bodywork.BODYWORK_WINDOW_FR=2000002
        Bodywork.BODYWORK_WINDOW_FR_ALIAS=2000002
        Door.DOOR_LOCK_COMMAND_AREA_LEFT_FRONT=4000001
        Rear.REAR_VIEW_MIRROR_ANTIGLARE_STATE=4700001
        Nosuch.NOSUCH_FIELD=9000001
        BYDAutoFeatureIds.AC_TEMP_MAIN=1000001
        Broken line=12
        Huge.HUGE_VALUE=99999999999
    """.trimIndent()

    private fun <T> roundTrip(write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val p = Parcel.obtain()
        try {
            write(p)
            p.setDataPosition(0)
            return read(p)
        } finally {
            p.recycle()
        }
    }

    @Test fun `catalog symbols land on the device their prefix names`() {
        val grouped = groupCatalogByDevice(dump)

        assertEquals(listOf(FidRecSymbol(1000001, "Ac.AC_TEMP_MAIN")), grouped[1000])
        assertEquals(
            listOf("Bodywork.BODYWORK_WINDOW_FL", "Bodywork.BODYWORK_WINDOW_FR"),
            grouped.getValue(1001).map { it.symbol },
        )
        // Prefix and device constant are spelled differently on these two.
        assertEquals(listOf(4000001), grouped.getValue(1041).map { it.fid })
        assertEquals(listOf(4700001), grouped.getValue(1047).map { it.fid })
    }

    @Test fun `unknown prefixes, flat duplicates and non-fid lines are dropped`() {
        val grouped = groupCatalogByDevice(dump)
        val symbols = grouped.values.flatten().map { it.symbol }

        assertTrue(symbols.none { it.startsWith("Nosuch.") })
        assertTrue(symbols.none { it.startsWith("BYDAutoFeatureIds.") })
        assertTrue(symbols.none { it.startsWith("Huge.") })
        assertTrue(symbols.none { it.contains(' ') })
    }

    @Test fun `a start request survives the round trip`() {
        val devices = intArrayOf(1001, 1004, 1041)
        val back = roundTrip({ writeRecStartRequest(it, devices) }, ::readRecStartRequest)
        assertEquals(devices.toList(), back.toList())
    }

    @Test fun `a start request above the cap is refused`() {
        val devices = IntArray(MAX_REC_DEVICES + 1) { 1000 + it }
        val back = roundTrip({ writeRecStartRequest(it, devices) }, ::readRecStartRequest)
        assertEquals(emptyList<Int>(), back.toList())
    }

    @Test fun `status survives the round trip`() {
        val status = FidRecStatus(
            running = true,
            devices = listOf(
                FidRecDeviceRow(1001, 120, 140, 20, 380, FID_REC_NO_ERROR),
                FidRecDeviceRow(1041, 0, 12, 0, 0, "SecurityException: enableDevice"),
            ),
            totalEvents = 380,
            top = listOf(FidRecTopRow(2000001, "Bodywork.BODYWORK_WINDOW_FL", 196, 43, 43.0)),
            filePath = "/sdcard/Download/bydmate_fidrec_20260915_120000.txt",
            fileBytes = 1_234_567L,
        )
        assertEquals(status, roundTrip({ writeRecStatus(it, status) }, ::readRecStatus))
    }

    @Test fun `an empty status still reads as not running`() {
        val status = FidRecStatus(false, emptyList(), 0, emptyList())
        assertEquals(status, roundTrip({ writeRecStatus(it, status) }, ::readRecStatus))
    }
}
