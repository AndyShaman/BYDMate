package com.bydmate.app.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: the Android JSONObject keeps insertion order, as on the car.
@RunWith(RobolectricTestRunner::class)
class TriggerDefPressTest {

    @Test fun `press null omits press key and matches baseline json`() {
        val baseline = TriggerDef(
            param = "steering_key",
            chineseName = "",
            operator = "==",
            value = "305",
            displayName = "Key 305",
            kind = "steering_key",
        )
        val explicitNull = baseline.copy(press = null)
        val json = explicitNull.toJson()

        assertFalse(json.has("press"))
        assertEquals(baseline.toJson().toString(), json.toString())
        assertEquals(
            """{"param":"steering_key","chineseName":"","operator":"==","value":"305","displayName":"Key 305","kind":"steering_key"}""",
            json.toString(),
        )
    }

    @Test fun `press double round-trips through toJson and listFromJson`() {
        val doublePress = TriggerDef(
            param = "steering_key",
            chineseName = "",
            operator = "==",
            value = "305",
            displayName = "Key 305",
            kind = "steering_key",
            press = "double",
        )

        val roundTripped = TriggerDef.listFromJson(TriggerDef.listToJson(listOf(doublePress))).single()

        assertEquals("double", roundTripped.press)
        assertEquals(doublePress, roundTripped)
    }
}
