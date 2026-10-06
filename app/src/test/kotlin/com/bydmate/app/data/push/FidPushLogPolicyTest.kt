package com.bydmate.app.data.push

import com.bydmate.app.data.autoservice.LogThrottle
import com.bydmate.app.data.nativestack.FidMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FidPushLogPolicyTest {

    @Test fun `every analog field is a real FidMap field`() {
        assertEquals(emptyList<String>(), FidPushLogPolicy.ANALOG_FIELDS.filter { it !in FidMap.byField })
    }

    @Test fun `the busy continuous readings are analog`() {
        listOf("speed", "soc", "motorRpmFront", "maxCellVoltage", "hvCurrent", "power").forEach {
            assertTrue(it, FidPushLogPolicy.isAnalog(it))
        }
    }

    @Test fun `states someone asks about stay discrete`() {
        listOf(
            "gear", "doorFL", "lockFL", "trunk", "lightLow", "turnSignal", "seatbeltFL", "occupancyFL",
            "seatHeatDriver", "windowFL", "acStatus", "acTemp", "powerState", "bsdLeft", "chargeGunState",
        ).forEach { assertFalse(it, FidPushLogPolicy.isAnalog(it)) }
    }

    @Test fun `an analog field writes one line a minute, a discrete one a line a second`() {
        val discrete = LogThrottle(FidPushLogPolicy.DISCRETE_WINDOW_MS)
        val analog = LogThrottle(FidPushLogPolicy.ANALOG_WINDOW_MS)
        // A field pushed every 200 ms for two minutes.
        val times = (0L until 120_000L step 200L)

        assertEquals(120, times.count { discrete.shouldLog("gear", it) })
        assertEquals(2, times.count { analog.shouldLog("speed", it) })
    }
}
