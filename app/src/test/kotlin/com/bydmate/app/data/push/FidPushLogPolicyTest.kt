package com.bydmate.app.data.push

import com.bydmate.app.data.nativestack.FidMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FidPushLogPolicyTest {

    @Test fun `every analog field is a real FidMap field`() {
        assertEquals(emptyList<String>(), FidPushLogPolicy.ANALOG_FIELDS.filter { it !in FidMap.byField })
    }

    @Test fun `states someone asks about stay discrete`() {
        listOf(
            "gear", "doorFL", "lockFL", "trunk", "lightLow", "turnSignal", "seatbeltFL", "occupancyFL",
            "seatHeatDriver", "windowFL", "acStatus", "acTemp", "powerState", "bsdLeft", "chargeGunState",
        ).forEach { assertFalse(it, FidPushLogPolicy.isAnalog(it)) }
    }
}
