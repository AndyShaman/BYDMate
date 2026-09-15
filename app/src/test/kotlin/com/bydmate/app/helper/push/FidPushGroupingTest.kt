package com.bydmate.app.helper.push

import org.junit.Assert.assertEquals
import org.junit.Test

class FidPushGroupingTest {

    @Test fun `fids are grouped by their device in request order`() {
        val grouping = groupFidsByDevice(
            listOf(
                FidPushSub(950009900, 1004),
                FidPushSub(692060168, 1001),
                FidPushSub(555745336, 1011),
                FidPushSub(692060170, 1001),
            )
        )
        assertEquals(listOf(1004, 1001, 1011), grouping.byDevice.keys.toList())
        assertEquals(listOf(692060168, 692060170), grouping.byDevice.getValue(1001))
        assertEquals(emptyList<Int>(), grouping.unsupported)
    }

    // 1018 (VIDEO) is a device constant with no BYDAuto*Device class behind it, so no listener
    // can ever be registered on it.
    @Test fun `a fid on a device without a listener class is unsupported`() {
        val grouping = groupFidsByDevice(
            listOf(FidPushSub(1246777400, 1018), FidPushSub(1098907664, 1038))
        )
        assertEquals(listOf(1246777400), grouping.unsupported)
        assertEquals(mapOf(1038 to listOf(1098907664)), grouping.byDevice)
    }

    @Test fun `a repeated fid is registered once`() {
        val grouping = groupFidsByDevice(
            listOf(FidPushSub(950009900, 1004), FidPushSub(950009900, 1004))
        )
        assertEquals(mapOf(1004 to listOf(950009900)), grouping.byDevice)
    }
}
