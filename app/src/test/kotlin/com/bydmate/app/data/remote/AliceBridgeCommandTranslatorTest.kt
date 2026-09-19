package com.bydmate.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliceBridgeCommandTranslatorTest {
    @Test fun `bounded climate values translate`() {
        assertEquals(
            "设置温度22",
            AliceBridgeCommandTranslator.resolve(
                JSONObject("""{"action":"climate.temperature","value":22}""")
            )?.vehicleCommand,
        )
        assertNull(
            AliceBridgeCommandTranslator.resolve(
                JSONObject("""{"action":"climate.temperature","value":31}""")
            )
        )
    }

    @Test fun `seat level zero means off`() {
        assertEquals(
            "副驾座椅通风关闭",
            AliceBridgeCommandTranslator.resolve(
                JSONObject("""{"action":"seat.passenger.vent","value":0}""")
            )?.vehicleCommand,
        )
    }

    @Test fun `airflow and roof actions stay semantic`() {
        assertEquals(
            "吹面吹脚除霜",
            AliceBridgeCommandTranslator.resolve(
                JSONObject("""{"action":"climate.airflow_face_feet_windshield"}""")
            )?.vehicleCommand,
        )
        assertEquals(
            "天窗通风",
            AliceBridgeCommandTranslator.resolve(
                JSONObject("""{"action":"sunroof.vent"}""")
            )?.vehicleCommand,
        )
    }

    @Test fun `raw command injection is rejected`() {
        assertNull(AliceBridgeCommandTranslator.resolve(JSONObject("""{"command":"车门解锁"}""")))
        assertNull(AliceBridgeCommandTranslator.resolve(JSONObject("""{"action":"raw.fid.write","value":1}""")))
    }

    @Test fun `unadvertised legacy vehicle actions are rejected`() {
        assertNull(AliceBridgeCommandTranslator.resolve(JSONObject("""{"action":"doors.unlock"}""")))
        assertNull(AliceBridgeCommandTranslator.resolve(JSONObject("""{"action":"fridge.cool"}""")))
        assertNull(AliceBridgeCommandTranslator.resolve(JSONObject("""{"action":"light.hazard_on"}""")))
    }
}
